// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.refactoring.introduce.introduceParameter

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.introduce.inplace.AbstractInplaceIntroducer
import com.intellij.util.SmartList
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggestionProvider
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNameSuggester
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNewDeclarationNameValidator
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.base.psi.unifier.toRange
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.caches.resolve.unsafeResolveToDescriptor
import org.jetbrains.kotlin.idea.core.CollectingNameValidator
import org.jetbrains.kotlin.idea.core.moveInsideParenthesesAndReplaceWith
import org.jetbrains.kotlin.idea.core.util.runSynchronouslyWithProgress
import org.jetbrains.kotlin.idea.refactoring.CompositeRefactoringRunner
import org.jetbrains.kotlin.idea.refactoring.changeSignature.*
import org.jetbrains.kotlin.idea.refactoring.introduce.*
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.*
import org.jetbrains.kotlin.idea.refactoring.showWithTransaction
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.idea.util.application.executeCommand
import org.jetbrains.kotlin.idea.util.application.isUnitTestMode
import org.jetbrains.kotlin.idea.util.approximateWithResolvableType
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.idea.util.psi.patternMatching.KotlinPsiUnifier
import org.jetbrains.kotlin.idea.util.psi.patternMatching.match
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.types.typeUtil.isNothing
import org.jetbrains.kotlin.types.typeUtil.isUnit
import org.jetbrains.kotlin.types.typeUtil.supertypes
import org.jetbrains.kotlin.utils.addIfNotNull
import java.util.*


fun IntroduceParameterDescriptor<FunctionDescriptor>.performRefactoring(editor: Editor? = null, onExit: (() -> Unit)? = null) {
    val config = object : KotlinChangeSignatureConfiguration {
        override fun configure(originalDescriptor: KotlinMethodDescriptor): KotlinMethodDescriptor {
            return originalDescriptor.modify { methodDescriptor ->
                if (!withDefaultValue) {
                    val parameters = callable.getValueParameters()
                    val withReceiver = methodDescriptor.receiver != null
                    parametersToRemove
                        .map {
                            if (it is KtParameter) {
                                parameters.indexOf(it) + if (withReceiver) 1 else 0
                            } else 0
                        }
                        .sortedDescending()
                        .forEach { methodDescriptor.removeParameter(it) }
                }

                val defaultValue = (if (newArgumentValue is KtProperty) (newArgumentValue as KtProperty).initializer else newArgumentValue)?.let { KtPsiUtil.safeDeparenthesize(it) }
                val parameterInfo = KotlinParameterInfo(
                    callableDescriptor = callableDescriptor,
                    name = newParameterName,
                    defaultValueForCall = defaultValue,
                    defaultValueAsDefaultParameter = withDefaultValue,
                    valOrVar = valVar
                )

                parameterInfo.currentTypeInfo = KotlinTypeInfo(false, null, newParameterTypeText)
                methodDescriptor.addParameter(parameterInfo)
            }
        }

        override fun isPerformSilently(affectedFunctions: Collection<PsiElement>): Boolean = true
    }

    val project = callable.project
    object : CompositeRefactoringRunner(project, "refactoring.changeSignature") {
        override fun runRefactoring() {
            runChangeSignature(project, editor, callableDescriptor, config, callable, INTRODUCE_PARAMETER)
        }

        override fun onRefactoringDone() {
            occurrencesToReplace.forEach { occurrenceReplacer(it) }
        }

        override fun onExit() {
            onExit?.invoke()
        }
    }.run()
}

open class KotlinIntroduceParameterHandler(
    val helper: KotlinIntroduceParameterHelper<FunctionDescriptor> = KotlinIntroduceParameterHelper.Default()
) : RefactoringActionHandler {
    open operator fun invoke(project: Project, editor: Editor, expression: KtExpression, targetParent: KtNamedDeclaration) {
        val physicalExpression = expression.substringContextOrThis
        if (physicalExpression is KtProperty && physicalExpression.isLocal && physicalExpression.nameIdentifier == null) {
            showErrorHintByKey(project, editor, "cannot.refactor.no.expression", INTRODUCE_PARAMETER)
            return
        }

        val context = physicalExpression.analyze()

        val expressionType = if (physicalExpression is KtProperty && physicalExpression.isLocal) {
            context[BindingContext.VARIABLE, physicalExpression]?.type
        } else {
            (expression.extractableSubstringInfo as? K1ExtractableSubstringInfo)?.type ?: context.getType(physicalExpression)
        }

        if (expressionType == null) {
            showErrorHint(project, editor, KotlinBundle.message("error.text.expression.has.no.type"), INTRODUCE_PARAMETER)
            return
        }

        if (expressionType.isUnit() || expressionType.isNothing()) {
            val message = KotlinBundle.message(
                "cannot.introduce.parameter.of.0.type",
                IdeDescriptorRenderers.SOURCE_CODE_SHORT_NAMES_NO_ANNOTATIONS.renderType(expressionType),
            )

            showErrorHint(project, editor, message, INTRODUCE_PARAMETER)
            return
        }

        val descriptor = context[BindingContext.DECLARATION_TO_DESCRIPTOR, targetParent]
        val functionDescriptor = descriptor.toFunctionDescriptor(targetParent)
        val replacementType = expressionType.approximateWithResolvableType(
            targetParent.getResolutionScope(context, targetParent.getResolutionFacade()),
            false
        )

        val body = when (targetParent) {
            is KtFunction -> targetParent.bodyExpression
            is KtClass -> targetParent.body
            else -> null
        }
        val bodyValidator: ((String) -> Boolean)? =
            body?.let { Fe10KotlinNewDeclarationNameValidator(it, sequenceOf(it), KotlinNameSuggestionProvider.ValidatorTarget.PARAMETER) }
        val nameValidator = CollectingNameValidator(targetParent.getValueParameters().mapNotNull { it.name }, bodyValidator ?: { true })

        val suggestedNames = SmartList<String>().apply {
            if (physicalExpression is KtProperty && !isUnitTestMode()) {
                addIfNotNull(physicalExpression.name)
            }
            addAll(Fe10KotlinNameSuggester.suggestNamesByType(replacementType, nameValidator, "p"))
        }

        val parametersUsages = findInternalUsagesOfParametersAndReceiver(targetParent, functionDescriptor) ?: return

        val forbiddenRanges = (targetParent as? KtClass)?.declarations?.asSequence()
            ?.filter(::isObjectOrNonInnerClass)
            ?.map { it.textRange }
            ?.toList()
            ?: Collections.emptyList()

        val occurrencesToReplace = if (expression is KtProperty) {
            ReferencesSearch.search(expression).asIterable().mapNotNullTo(SmartList(expression.toRange())) { it.element.toRange() }
        } else {
            expression.toRange()
                .match(targetParent, KotlinPsiUnifier.DEFAULT)
                .asSequence()
                .filterNot {
                    val textRange = it.range.getPhysicalTextRange()
                    forbiddenRanges.any { range -> range.intersects(textRange) }
                }
                .mapNotNull {
                    val matchedExpr = when (val matchedElement = it.range.elements.singleOrNull()) {
                        is KtExpression -> matchedElement
                        is KtStringTemplateEntryWithExpression -> matchedElement.expression
                        else -> null
                    }
                    matchedExpr?.toRange()
                }
                .toList()
        }

        project.executeCommand(
            INTRODUCE_PARAMETER,
            null,
            fun() {
                val isTestMode = isUnitTestMode()
                val haveLambdaArgumentsToReplace = occurrencesToReplace.any { range ->
                    range.elements.any { it is KtLambdaExpression && it.parent is KtLambdaArgument }
                }
                val inplaceIsAvailable = editor.settings.isVariableInplaceRenameEnabled
                        && !isTestMode
                        && !haveLambdaArgumentsToReplace
                        && expression.extractableSubstringInfo == null
                        && !expression.mustBeParenthesizedInInitializerPosition()

                val originalExpression = expression
                val psiFactory = KtPsiFactory(project)
                val introduceParameterDescriptor =
                    helper.configure(
                        IntroduceParameterDescriptor<FunctionDescriptor>(
                            originalRange = originalExpression.toRange(),
                            callable = targetParent,
                            callableDescriptor = functionDescriptor,
                            newParameterName = suggestedNames.first().quoteIfNeeded(),
                            newParameterTypeText = IdeDescriptorRenderers.SOURCE_CODE_SHORT_NAMES_NO_ANNOTATIONS
                                .renderType(replacementType),
                            argumentValue = originalExpression,
                            withDefaultValue = false,
                            parametersUsages = parametersUsages,
                            occurrencesToReplace = occurrencesToReplace,
                            occurrenceReplacer = replacer@{
                                val expressionToReplace = it.elements.single() as KtExpression
                                val replacingExpression = psiFactory.createExpression(newParameterName)
                                val substringInfo = expressionToReplace.extractableSubstringInfo
                                val result = when {
                                    expressionToReplace is KtProperty -> return@replacer expressionToReplace.delete()
                                    expressionToReplace.isLambdaOutsideParentheses() -> {
                                        expressionToReplace
                                            .getStrictParentOfType<KtLambdaArgument>()!!
                                            .moveInsideParenthesesAndReplaceWith(replacingExpression, context)
                                    }
                                    substringInfo != null -> substringInfo.replaceWith(replacingExpression)
                                    else -> expressionToReplace.replaced(replacingExpression)
                                }
                                result.removeTemplateEntryBracesIfPossible()
                            }
                        )
                    )
                if (isTestMode) {
                    introduceParameterDescriptor.performRefactoring(editor)
                    return
                }

                if (inplaceIsAvailable) {
                    with(PsiDocumentManager.getInstance(project)) {
                        commitDocument(editor.document)
                        doPostponedOperationsAndUnblockDocument(editor.document)
                    }

                    val introducer = KotlinInplaceParameterIntroducer(
                        introduceParameterDescriptor,
                        replacementType,
                        suggestedNames.toTypedArray(),
                        project,
                        editor
                    )
                    if (introducer.startInplaceIntroduceTemplate()) return
                }

                val dialog = KotlinIntroduceParameterDialog(
                    project,
                    editor,
                    introduceParameterDescriptor,
                    suggestedNames.toTypedArray(),
                    listOf(replacementType) + replacementType.supertypes(),
                    helper
                )
                dialog.showWithTransaction()
            }
        )
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        (AbstractInplaceIntroducer.getActiveIntroducer(editor) as? KotlinInplaceParameterIntroducer)?.let {
            it.switchToDialogUI()
            return
        }

        if (file !is KtFile) return

        val elementAtCaret = file.findElementAt(editor.caretModel.offset) ?: return
        if (elementAtCaret.getNonStrictParentOfType<KtAnnotationEntry>() != null) {
            showErrorHint(
                project,
                editor,
                KotlinBundle.message("error.text.introduce.parameter.is.not.available.inside.of.annotation.entries"),
                INTRODUCE_PARAMETER
            )
            return
        }
        if (elementAtCaret.getNonStrictParentOfType<KtParameter>() != null) {
            showErrorHint(
                project,
                editor,
                KotlinBundle.message("error.text.introduce.parameter.is.not.available.for.default.value"),
                INTRODUCE_PARAMETER
            )
            return
        }

        selectNewParameterContext(editor, file) { elements, targetParent ->
            val expression = ((elements.singleOrNull() as? KtBlockExpression)?.statements ?: elements).singleOrNull()
            if (expression is KtExpression) {
                invoke(project, editor, expression, targetParent as KtNamedDeclaration)
            } else {
                showErrorHintByKey(project, editor, "cannot.refactor.no.expression", INTRODUCE_PARAMETER)
            }
        }
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        throw AssertionError("$INTRODUCE_PARAMETER can only be invoked from editor")
    }
}

private fun DeclarationDescriptor?.toFunctionDescriptor(targetParent: KtNamedDeclaration): FunctionDescriptor {
    val functionDescriptor: FunctionDescriptor? =
        when (this) {
            is FunctionDescriptor -> this
            is ClassDescriptor -> this.unsubstitutedPrimaryConstructor
            else -> null
        }
    return functionDescriptor ?: throw AssertionError("Unexpected element type: ${targetParent.getElementTextWithContext()}")
}

private fun findInternalUsagesOfParametersAndReceiver(
    targetParent: KtNamedDeclaration,
    targetDescriptor: FunctionDescriptor
): MultiMap<KtElement, KtElement>? {
    val usages = MultiMap<KtElement, KtElement>()
    val searchComplete = targetParent.project.runSynchronouslyWithProgress(
        KotlinBundle.message("searching.usages.of.0.parameter", targetParent.name.toString()),
        true
    ) {
        runReadAction {
            targetParent.getValueParameters()
                .filter { !it.hasValOrVar() }
                .forEach {
                    val paramUsages = ReferencesSearch.search(it).asIterable().map { reference -> reference.element as KtElement }
                    if (paramUsages.isNotEmpty()) {
                        usages.put(it, paramUsages)
                    }
                }
        }
    } != null
    if (!searchComplete) return null
    val receiverTypeRef = (targetParent as? KtFunction)?.receiverTypeReference
    if (receiverTypeRef != null) {
        targetParent.acceptChildren(
            object : KtTreeVisitorVoid() {
                override fun visitThisExpression(expression: KtThisExpression) {
                    super.visitThisExpression(expression)

                    if (expression.instanceReference.mainReference.resolve() == targetDescriptor) {
                        usages.putValue(receiverTypeRef, expression)
                    }
                }

                override fun visitKtElement(element: KtElement) {
                    super.visitKtElement(element)

                    val resolvedCall = element.resolveToCall() ?: return

                    if ((resolvedCall.extensionReceiver as? ImplicitReceiver)?.declarationDescriptor == targetDescriptor ||
                        (resolvedCall.dispatchReceiver as? ImplicitReceiver)?.declarationDescriptor == targetDescriptor
                    ) {
                        usages.putValue(receiverTypeRef, resolvedCall.call.callElement)
                    }
                }
            }
        )
    }
    return usages
}

interface KotlinIntroduceLambdaParameterHelper<Descriptor> : KotlinIntroduceParameterHelper<Descriptor> {
    class Default<D> : KotlinIntroduceLambdaParameterHelper<D>{
        override fun configure(descriptor: IntroduceParameterDescriptor<D>): IntroduceParameterDescriptor<D> = descriptor
        override fun configureExtractLambda(descriptor: ExtractableCodeDescriptor): ExtractableCodeDescriptor = descriptor
    }

    fun configureExtractLambda(descriptor: ExtractableCodeDescriptor): ExtractableCodeDescriptor
}

open class KotlinIntroduceLambdaParameterHandler(
    helper: KotlinIntroduceLambdaParameterHelper<FunctionDescriptor> = KotlinIntroduceLambdaParameterHelper.Default()
) : KotlinIntroduceParameterHandler(helper) {
    private val extractLambdaHelper = object : ExtractionEngineHelper(INTRODUCE_LAMBDA_PARAMETER) {
        private fun createDialog(
            project: Project,
            editor: Editor,
            lambdaExtractionDescriptor: ExtractableCodeDescriptor
        ): KotlinIntroduceParameterDialog? {
            val callable = lambdaExtractionDescriptor.extractionData.targetSibling as KtNamedDeclaration
            val descriptor = callable.unsafeResolveToDescriptor()
            val callableDescriptor = descriptor.toFunctionDescriptor(callable)
            val originalRange = lambdaExtractionDescriptor.extractionData.originalRange
            val parametersUsages = findInternalUsagesOfParametersAndReceiver(callable, callableDescriptor) ?: return null
            val introduceParameterDescriptor = IntroduceParameterDescriptor(
                originalRange = originalRange,
                callable = callable,
                callableDescriptor = callableDescriptor,
                newParameterName = "", // to be chosen in the dialog
                newParameterTypeText = "", // to be chosen in the dialog
                argumentValue = KtPsiFactory(project).createExpression("{}"), // substituted later
                withDefaultValue = false,
                parametersUsages = parametersUsages,
                occurrencesToReplace = listOf(originalRange),
                parametersToRemove = listOf()
            )

            return KotlinIntroduceParameterDialog(project, editor, introduceParameterDescriptor, lambdaExtractionDescriptor, helper)
        }

        override fun configureAndRun(
            project: Project,
            editor: Editor,
            descriptorWithConflicts: ExtractableCodeDescriptorWithConflicts,
            onFinish: (ExtractionResult) -> Unit
        ) {
            val lambdaExtractionDescriptor = helper.configureExtractLambda(descriptorWithConflicts.descriptor)
            if (!ExtractionTarget.FAKE_LAMBDALIKE_FUNCTION.isAvailable(lambdaExtractionDescriptor)) {
                showErrorHint(
                    project,
                    editor,
                    KotlinBundle.message("error.text.can.t.introduce.lambda.parameter.for.this.expression"),
                    INTRODUCE_LAMBDA_PARAMETER
                )
                return
            }

            val dialog = createDialog(project, editor, lambdaExtractionDescriptor) ?: return
            if (isUnitTestMode()) {
                dialog.performRefactoring()
            } else {
                dialog.showWithTransaction()
            }
        }
    }

    override fun invoke(project: Project, editor: Editor, expression: KtExpression, targetParent: KtNamedDeclaration) {
        val duplicateContainer =
            when (targetParent) {
                is KtFunction -> targetParent.bodyExpression
                is KtClass -> targetParent.body
                else -> null
            } ?: throw AssertionError("Body element is not found: ${targetParent.getElementTextWithContext()}")
        val extractionData = ExtractionData(
            targetParent.containingKtFile,
            expression.toRange(),
            targetParent,
            duplicateContainer,
            ExtractionOptions.DEFAULT
        )
        ExtractionEngine(extractLambdaHelper).run(editor, extractionData)
    }
}