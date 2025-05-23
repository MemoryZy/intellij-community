// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.bytecodeAnalysis;

import com.intellij.codeInspection.bytecodeAnalysis.asm.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringHash;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.util.CachedValueImpl;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.gist.GistManager;
import com.intellij.util.gist.VirtualFileGist;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.io.UnsyncByteArrayOutputStream;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode;
import org.jetbrains.org.objectweb.asm.tree.InsnList;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.VarInsnNode;
import org.jetbrains.org.objectweb.asm.tree.analysis.AnalyzerException;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.intellij.codeInspection.bytecodeAnalysis.Direction.*;
import static com.intellij.codeInspection.bytecodeAnalysis.Effects.VOLATILE_EFFECTS;
import static com.intellij.codeInspection.bytecodeAnalysis.ProjectBytecodeAnalysis.LOG;

/**
 * Here's <a href="https://github.com/ilya-klyuchnikov/faba">Scala code</a> (same algorithm, but easier to read).
 * <p>
 * Based on <a href="http://meta2014.pereslavl.ru/papers/2014_Klyuchnikov__Nullness_Analysis_of_Java_Bytecode_via_Supercompilation_over_Abstract_Values.pdf">"Nullness Analysis of Java Bytecode via Supercompilation over Abstract Values"</a> 
 * by Ilya Klyuchnikov.
 */
public final class ClassDataIndexer implements VirtualFileGist.GistCalculator<Map<HMember, Equations>> {
  static final String STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";

  public static final Consumer<Map<HMember, Equations>> ourIndexSizeStatistics =
    ApplicationManager.getApplication().isUnitTestMode() ? new ClassDataIndexerStatistics() : map -> {};

  // Hash collision is possible: resolve it just flushing all the equations for colliding methods (unless equations are the same)
  static final BinaryOperator<Equations> MERGER =
    (eq1, eq2) -> eq1.equals(eq2) ? eq1 : new Equations(Collections.emptyList(), false);

  private static final int VERSION = 19; // change when inference algorithm changes
  private static final int VERSION_MODIFIER = HardCodedPurity.AGGRESSIVE_HARDCODED_PURITY ? 1 : 0;
  private static final int FINAL_VERSION = VERSION * 2 + VERSION_MODIFIER + StringHash.murmur(
    BytecodeAnalysisSuppressor.EP_NAME.getExtensionList().stream().map(ep -> String.valueOf(ep.getVersion())).collect(Collectors.joining("-")),
    31);
  private static final VirtualFileGist<Map<HMember, Equations>> ourGist = GistManager.getInstance().newVirtualFileGist(
    "BytecodeAnalysisIndex", FINAL_VERSION, new BytecodeAnalysisIndex.EquationsExternalizer(), new ClassDataIndexer());

  @Override
  public @Nullable Map<HMember, Equations> calcData(Project project, @NotNull VirtualFile file) {
    HashMap<HMember, Equations> map = new HashMap<>();
    if (isFileExcluded(file)) {
      return map;
    }
    try {
      ClassReader reader = new ClassReader(file.contentsToByteArray(false));
      Map<EKey, Equations> allEquations = processClass(reader, file.getPresentableUrl());
      solvePartially(reader.getClassName(), allEquations);
      allEquations.forEach((methodKey, equations) -> map.merge(methodKey.member.hashed(), hash(equations), MERGER));
    }
    catch (ProcessCanceledException e) {
      throw e;
    }
    catch (Throwable e) {
      // incorrect bytecode may result in Runtime exceptions during analysis
      // so here we suppose that exception is due to incorrect bytecode
      LOG.debug("Unexpected Error during indexing of bytecode", e);
    }
    ourIndexSizeStatistics.accept(map);
    return map;
  }

  /**
   * Returns true if file must be excluded from the analysis for some reason (e.g. it's known stub
   * jar which will be replaced in runtime).
   *
   * @param file file to check
   * @return true if this file must be excluded
   */
  static boolean isFileExcluded(VirtualFile file) {
    String path = file.getPath();
    return isInsideDummyAndroidJar(path) ||
           // Methods of GenericModel.class in Play framework throw UnsupportedOperationException
           // However, it looks like they are replaced with something meaningful during compilation/runtime
           // See IDEA-285334.
           path.endsWith("!/play/db/jpa/GenericModel.class") ||
           ContainerUtil.exists(BytecodeAnalysisSuppressor.EP_NAME.getExtensionList(), ep -> ep.shouldSuppress(file));
  }
  
  private static final Pattern ANDROID_JAR_PATH = Pattern.compile(
    "(platforms/android-.+/android.jar!/|com/google/android/android/[\\d.]+/android-[\\d.]+.jar!/android)");

  /**
   * Ignore inside android.jar because all class files there are dummy and contain no code at all.
   * Rely on the fact that it's always located at .../platforms/android-.../android.jar!/
   */
  private static boolean isInsideDummyAndroidJar(String path) {
    return path.contains("android") && ANDROID_JAR_PATH.matcher(path).find();
  }

  @Contract(mutates = "param2")
  private static void solvePartially(String className, Map<EKey, Equations> map) {
    PuritySolver solver = new PuritySolver();
    for (Map.Entry<EKey, Equations> entry : map.entrySet()) {
      EKey key = entry.getKey();
      Equations equations = entry.getValue();
      for (DirectionResultPair drp : equations.results) {
        Result result = drp.result;
        if (result instanceof Effects effects) {
          key = new EKey(key.member, fromInt(drp.directionKey), equations.stable, false);
          solver.addEquation(key, effects);
        }
      }
    }
    solver.addPlainFieldEquations(md -> md instanceof Member member && member.internalClassName.equals(className));
    solver.solve();
    map.replaceAll((key, eqs) -> updatePurity(key, eqs, solver));
  }

  private static @NotNull Equations updatePurity(EKey key, Equations eqs, PuritySolver solver) {
    for (int i = 0; i < eqs.results.size(); i++) {
      DirectionResultPair drp = eqs.results.get(i);
      if (drp.directionKey == Pure.asInt() || drp.directionKey == Volatile.asInt()) {
        EKey newKey = new EKey(key.member, fromInt(drp.directionKey), eqs.stable, false);
        Effects effects = solver.pending.get(newKey);
        if (effects == null || effects.isTop()) {
          effects = solver.solved.get(newKey);
        }
        if (effects == drp.result) {
          return eqs;
        }
        List<DirectionResultPair> newPairs;
        if (effects == null || effects.isTop()) {
          newPairs = new ArrayList<>(eqs.results.size() - 1);
          newPairs.addAll(eqs.results.subList(0, i));
          newPairs.addAll(eqs.results.subList(i + 1, eqs.results.size()));
        } else {
          newPairs = new ArrayList<>(eqs.results);
          newPairs.set(i, new DirectionResultPair(Pure.asInt(), effects));
        }
        return new Equations(newPairs, eqs.stable);
      }
    }
    return eqs;
  }

  private static Equations hash(Equations equations) {
    return new Equations(ContainerUtil.map(equations.results, ClassDataIndexer::hash), equations.stable);
  }

  private static DirectionResultPair hash(DirectionResultPair drp) {
    return new DirectionResultPair(drp.directionKey, hash(drp.result));
  }

  private static Result hash(Result result) {
    if (result instanceof Effects effects) {
      return new Effects(effects.returnValue, StreamEx.of(effects.effects).map(ClassDataIndexer::hash).toSet());
    }
    else if (result instanceof Pending) {
      return new Pending(ContainerUtil.map(((Pending)result).delta, ClassDataIndexer::hash));
    }
    return result;
  }

  private static Component hash(Component component) {
    EKey[] ids = component.ids;
    EKey[] hashedKeys = new EKey[ids.length];
    for (int i = 0; i < ids.length; i++) {
      hashedKeys[i] = ids[i].hashed();
    }
    return new Component(component.value, hashedKeys);
  }

  private static EffectQuantum hash(EffectQuantum effect) {
    if (effect instanceof EffectQuantum.CallQuantum call) {
      return new EffectQuantum.CallQuantum(call.key.hashed(), call.data, call.isStatic);
    }
    return effect;
  }

  private static @NotNull Equations convertEquations(EKey methodKey, List<Equation> rawMethodEquations) {
    List<DirectionResultPair> compressedMethodEquations =
      ContainerUtil.map(rawMethodEquations, equation -> new DirectionResultPair(equation.key.dirKey, equation.result));
    return new Equations(compressedMethodEquations, methodKey.stable);
  }

  static Map<EKey, Equations> processClass(final ClassReader classReader, final String presentableUrl) {

    // It is OK to share pending states, actions and results for analyses.
    // Analyses are designed in such a way that they first write to states/actions/results and then read only those portion
    // of states/actions/results which were written by the current pass of the analysis.
    // Since states/actions/results are quite expensive to create (32K array) for each analysis, we create them once per class analysis.
    final ExpandableArray<State> sharedPendingStates = new ExpandableArray<>();
    final ExpandableArray<PendingAction> sharedPendingActions = new ExpandableArray<>();
    final ExpandableArray<PResults.PResult> sharedResults = new ExpandableArray<>();
    final Map<EKey, Equations> equations = new HashMap<>();

    FieldData data = FieldData.read(classReader);
    data.registerVolatileFields(equations);
    Set<Member> staticFinalFields = data.staticFinalFields();

    if ((classReader.getAccess() & Opcodes.ACC_ENUM) != 0) {
      // ordinal() method is final in java.lang.Enum, but for some reason referred on call sites using specific enum class
      // it's used on every enum switch statement, so forcing its purity is important
      EKey ordinalKey = new EKey(new Member(classReader.getClassName(), "ordinal", "()I"), Out, true);
      equations.put(ordinalKey, new Equations(
        Collections.singletonList(new DirectionResultPair(Pure.asInt(), new Effects(DataValue.LocalDataValue, Collections.emptySet()))),
        true));
    }

    classReader.accept(
      new MethodAnalysisVisitor(equations, presentableUrl, sharedPendingStates, sharedPendingActions, sharedResults, staticFinalFields), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    return equations;
  }
  
  private record FieldData(Set<Member> staticFinalFields, Set<Member> volatileFields) {
    static @NotNull FieldData read(@NotNull ClassReader classReader) {
      Set<Member> staticFields = new HashSet<>();
      Set<Member> volatileFields = new HashSet<>();
      classReader.accept(new ClassVisitor(Opcodes.API_VERSION) {
        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
          int modifiers = Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
          if ((access & modifiers) == modifiers && (access & (Opcodes.ACC_ENUM | Opcodes.ACC_SYNTHETIC)) == 0 &&
              (desc.startsWith("L") || desc.startsWith("["))) {
            staticFields.add(new Member(classReader.getClassName(), name, desc));
          }
          if ((access & Opcodes.ACC_VOLATILE) != 0) {
            volatileFields.add(new Member(classReader.getClassName(), name, desc));
          }
          return null;
        }
      }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE);
      return new FieldData(staticFields, volatileFields);
    }
    
    void registerVolatileFields(Map<EKey, Equations> equations) {
      for (Member field : volatileFields) {
        EKey fieldKey = new EKey(field, Out, true);
        equations.put(fieldKey, new Equations(Collections.singletonList(new DirectionResultPair(Volatile.asInt(), VOLATILE_EFFECTS)), true));
      }
    }
  }

  private static final Key<CachedValue<Map<HMember, Equations>>> EQUATIONS =
    Key.create("com.intellij.codeInspection.bytecodeAnalysis.ClassDataIndexer.Equations");

  static @NotNull @Unmodifiable List<Equations> getEquations(GlobalSearchScope scope, HMember key) {
    return ContainerUtil.mapNotNull(
      FileBasedIndex.getInstance().getContainingFiles(BytecodeAnalysisIndex.NAME, key, scope),
      file -> {
        CachedValue<Map<HMember, Equations>> equations = ConcurrencyUtil.computeIfAbsent(file, EQUATIONS, () ->
          new CachedValueImpl<>(() -> CachedValueProvider.Result.create(ourGist.getFileData(null, file), file)));
        return equations.getValue().get(key);
      });
  }

  private static class ClassDataIndexerStatistics implements Consumer<Map<HMember, Equations>> {
    private static final AtomicLong ourTotalSize = new AtomicLong(0);
    private static final AtomicLong ourTotalCount = new AtomicLong(0);

    @Override
    public void accept(Map<HMember, Equations> map) {
      try {
        UnsyncByteArrayOutputStream stream = new UnsyncByteArrayOutputStream();
        new BytecodeAnalysisIndex.EquationsExternalizer().save(new DataOutputStream(stream), map);
        ourTotalSize.addAndGet(stream.size());
        ourTotalCount.incrementAndGet();
      }
      catch (IOException ignored) {
      }
    }

    @Override
    public String toString() {
      if (ourTotalCount.get() == 0) {
        return "";
      }
      return String.format(Locale.ENGLISH, "Classes: %d\nBytes: %d\nBytes per class: %.2f%n", ourTotalCount.get(), ourTotalSize.get(),
                           ((double)ourTotalSize.get()) / ourTotalCount.get());
    }
  }

  private static final class MethodAnalysisVisitor extends KeyedMethodVisitor {
    private final Map<EKey, Equations> myEquations;
    private final String myPresentableUrl;
    private final ExpandableArray<State> mySharedPendingStates;
    private final ExpandableArray<PendingAction> mySharedPendingActions;
    private final ExpandableArray<PResults.PResult> mySharedResults;
    private final Set<Member> myStaticFinalFields;

    private MethodAnalysisVisitor(Map<EKey, Equations> equations,
                                  String presentableUrl,
                                  ExpandableArray<State> sharedPendingStates,
                                  ExpandableArray<PendingAction> sharedPendingActions,
                                  ExpandableArray<PResults.PResult> sharedResults, Set<Member> staticFinalFields) {
      myEquations = equations;
      myPresentableUrl = presentableUrl;
      mySharedPendingStates = sharedPendingStates;
      mySharedPendingActions = sharedPendingActions;
      mySharedResults = sharedResults;
      myStaticFinalFields = staticFinalFields;
    }

    @Override
    protected MethodVisitor visitMethod(final MethodNode node, Member method, final EKey key) {
      return new MethodVisitor(Opcodes.API_VERSION, node) {
        private boolean jsr;
        private boolean skip;

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
          if (descriptor.equals("Lio/quarkus/panache/common/impl/GenerateBridge;")) {
            skip = true;
          }
          return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
          if (opcode == Opcodes.JSR) {
            jsr = true;
          }
          super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitEnd() {
          super.visitEnd();
          if (!skip) {
            myEquations.put(key, convertEquations(key, processMethod(node, jsr, method, key.stable)));
          }
        }
      };
    }

    /**
     * Facade for analysis, it invokes specialized analyses for branching/non-branching methods.
     *
     * @param methodNode asm node for method
     * @param jsr whether a method has jsr instruction
     * @param method a method descriptor
     * @param stable whether a method is stable (final or declared in a final class)
     */
    private List<Equation> processMethod(final MethodNode methodNode, boolean jsr, Member method, boolean stable) {
      ProgressManager.checkCanceled();
      final Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
      final Type resultType = Type.getReturnType(methodNode.desc);
      final boolean isReferenceResult = ASMUtils.isReferenceType(resultType);
      final boolean isBooleanResult = ASMUtils.isBooleanType(resultType);
      final boolean isInterestingResult = isReferenceResult || isBooleanResult;

      List<Equation> equations = new ArrayList<>();
      ContainerUtil.addIfNotNull(equations, PurityAnalysis.analyze(method, methodNode, stable, jsr));

      try {
        final ControlFlowGraph graph = ControlFlowGraph.build(className, methodNode, jsr);
        if (graph.transitions.length > 0) {
          final DFSTree dfs = DFSTree.build(graph.transitions, graph.edgeCount);
          boolean branching = !dfs.isBackEmpty();
          if (!branching) {
            for (int[] transition : graph.transitions) {
              if (transition != null && transition.length > 1) {
                branching = true;
                break;
              }
            }
          }
          if (branching) {
            RichControlFlow richControlFlow = new RichControlFlow(graph, dfs);
            if (richControlFlow.reducible()) {
              NegationAnalysis negated = tryNegation(method, argumentTypes, graph, isBooleanResult, dfs, jsr);
              processBranchingMethod(method, methodNode, richControlFlow, argumentTypes, resultType, stable, jsr, equations, negated);
              return equations;
            }
            LOG.debug(method + ": CFG is not reducible");
          }
          // simple
          else {
            processNonBranchingMethod(method, argumentTypes, graph, resultType, stable, equations);
            return equations;
          }
        }
        // We can visit here if method body is absent (e.g. native method)
        // Make sure to preserve hardcoded purity, if any.
        equations.addAll(topEquations(method, argumentTypes, isReferenceResult, isInterestingResult, stable));
        return equations;
      }
      catch (ProcessCanceledException e) {
        throw e;
      }
      catch (TooComplexException e) {
        LOG.debug(method + " in " + myPresentableUrl + " is too complex for bytecode analysis");
        return topEquations(method, argumentTypes, isReferenceResult, isInterestingResult, stable);
      }
      catch (Throwable e) {
        // incorrect bytecode may result in Runtime exceptions during analysis
        // so here we suppose that exception is due to incorrect bytecode
        LOG.debug("Unexpected Error during processing of " + method + " in " + myPresentableUrl, e);
        return topEquations(method, argumentTypes, isReferenceResult, isInterestingResult, stable);
      }
    }

    private static NegationAnalysis tryNegation(final Member method,
                                                final Type[] argumentTypes,
                                                final ControlFlowGraph graph,
                                                final boolean isBooleanResult,
                                                final DFSTree dfs,
                                                final boolean jsr) throws AnalyzerException {

      class Util {
        boolean isMethodCall(int opCode) {
          return opCode == Opcodes.INVOKESTATIC ||
                 opCode == Opcodes.INVOKESPECIAL ||
                 opCode == Opcodes.INVOKEVIRTUAL ||
                 opCode == Opcodes.INVOKEINTERFACE;
        }

        boolean singleIfBranch() {
          int branch = 0;

          for (int i = 0; i < graph.transitions.length; i++) {
            int[] transition = graph.transitions[i];
            if (transition.length == 2) {
              branch++;
              int opCode = graph.methodNode.instructions.get(i).getOpcode();
              boolean isIfInsn = opCode == Opcodes.IFEQ || opCode == Opcodes.IFNE;
              if (!isIfInsn) {
                return false;
              }
            }
            if (branch > 1)
              return false;
          }
          return branch == 1;
        }

        boolean singleMethodCall() {
          int callCount = 0;
          for (int i = 0; i < graph.transitions.length; i++) {
            if (isMethodCall(graph.methodNode.instructions.get(i).getOpcode())) {
              callCount++;
              if (callCount > 1) {
                return false;
              }
            }
          }
          return callCount == 1;
        }

        public boolean booleanConstResult() {
          try {
            final boolean[] origins =
              OriginsAnalysis.resultOrigins(
                leakingParametersAndFrames(method, graph.methodNode, argumentTypes, jsr).frames,
                graph.methodNode.instructions,
                graph);

            for (int i = 0; i < origins.length; i++) {
              if (origins[i]) {
                int opCode = graph.methodNode.instructions.get(i).getOpcode();
                boolean isBooleanConst = opCode == Opcodes.ICONST_0 || opCode == Opcodes.ICONST_1;
                if (!isBooleanConst) {
                  return false;
                }
              }
            }

            return true;
          }
          catch (AnalyzerException ignore) {
          }
          return false;
        }
      }

      if (graph.methodNode.instructions.size() < 20 && isBooleanResult && dfs.isBackEmpty() && !jsr) {
        Util util = new Util();
        if (util.singleIfBranch() && util.singleMethodCall() && util.booleanConstResult()) {
          NegationAnalysis analyzer = new NegationAnalysis(method, graph);
          try {
            analyzer.analyze();
            return analyzer;
          }
          catch (NegationAnalysisFailedException ignore) {
            return null;
          }
        }
      }

      return null;
    }

    private void processBranchingMethod(final Member method,
                                        final MethodNode methodNode,
                                        final RichControlFlow richControlFlow,
                                        Type[] argumentTypes,
                                        Type resultType,
                                        final boolean stable,
                                        boolean jsr,
                                        List<? super Equation> result,
                                        NegationAnalysis negatedAnalysis) throws AnalyzerException {
      final boolean isReferenceResult = ASMUtils.isReferenceType(resultType);
      final boolean isBooleanResult = ASMUtils.isBooleanType(resultType);
      boolean isInterestingResult = isBooleanResult || isReferenceResult;

      final LeakingParameters leakingParametersAndFrames = leakingParametersAndFrames(method, methodNode, argumentTypes, jsr);

      boolean[] leakingParameters = leakingParametersAndFrames.parameters;
      boolean[] leakingNullableParameters = leakingParametersAndFrames.nullableParameters;

      final boolean[] origins =
        OriginsAnalysis.resultOrigins(leakingParametersAndFrames.frames, methodNode.instructions, richControlFlow.controlFlow);

      Equation outEquation =
        isInterestingResult ?
        new InOutAnalysis(richControlFlow, Out, origins, stable, mySharedPendingStates).analyze() :
        null;

      if (isReferenceResult) {
        result.add(outEquation);
        result.add(new Equation(new EKey(method, NullableOut, stable), NullableMethodAnalysis.analyze(methodNode, origins, jsr)));
      }
      final boolean shouldInferNonTrivialFailingContracts;
      final Equation throwEquation;
      if (methodNode.name.equals("<init>") || methodNode.instructions.size() > 64) {
        // Do not infer failing contracts for constructors or long methods
        shouldInferNonTrivialFailingContracts = false;
        throwEquation = new Equation(new EKey(method, Throw, stable), Value.Top);
      }
      else {
        final InThrowAnalysis inThrowAnalysis = new InThrowAnalysis(richControlFlow, Throw, origins, stable, mySharedPendingStates);
        throwEquation = inThrowAnalysis.analyze();
        if (!throwEquation.result.equals(Value.Top)) {
          result.add(throwEquation);
        }
        shouldInferNonTrivialFailingContracts = !inThrowAnalysis.myHasNonTrivialReturn && 
                                                richControlFlow.controlFlow.errorTransitions.isEmpty();
      }

      boolean withCycle = !richControlFlow.dfsTree.isBackEmpty();
      if (argumentTypes.length > 50 && withCycle) {
        // IDEA-137443 - do not analyze very complex methods
        return;
      }

      // arguments and contract clauses
      for (int i = 0; i < argumentTypes.length; i++) {
        boolean notNullParam = false;

        if (ASMUtils.isReferenceType(argumentTypes[i])) {
          boolean possibleNPE = false;
          if (leakingParameters[i]) {
            NonNullInAnalysis notNullInAnalysis =
              new NonNullInAnalysis(richControlFlow, new In(i, false), stable, mySharedPendingActions, mySharedResults);
            Equation notNullParamEquation = notNullInAnalysis.analyze();
            possibleNPE = notNullInAnalysis.possibleNPE;
            notNullParam = notNullParamEquation.result.equals(Value.NotNull);
            result.add(notNullParamEquation);
          }
          else {
            // parameter is not leaking, so it is definitely NOT @NotNull
            result.add(new Equation(new EKey(method, new In(i, false), stable), Value.Top));
          }

          if (leakingNullableParameters[i]) {
            if (notNullParam || possibleNPE) {
              result.add(new Equation(new EKey(method, new In(i, true), stable), Value.Top));
            }
            else {
              result.add(new NullableInAnalysis(richControlFlow, new In(i, true), stable, mySharedPendingStates).analyze());
            }
          }
          else {
            result.add(new Equation(new EKey(method, new In(i, true), stable), Value.Null));
          }

          if (isInterestingResult) {
            if (!leakingParameters[i]) {
              // parameter is not leaking, so a contract is the same as for the whole method
              result.add(new Equation(new EKey(method, new InOut(i, Value.Null), stable), outEquation.result));
              result.add(new Equation(new EKey(method, new InOut(i, Value.NotNull), stable), outEquation.result));
              continue;
            }
            if (notNullParam) {
              // @NotNull, like "null->fail"
              result.add(new Equation(new EKey(method, new InOut(i, Value.Null), stable), Value.Bot));
              result.add(new Equation(new EKey(method, new InOut(i, Value.NotNull), stable), outEquation.result));
              continue;
            }
          }
        }
        for (Value val : Value.typeValues(argumentTypes[i])) {
          if (isBooleanResult && negatedAnalysis != null) {
            result.add(negatedAnalysis.contractEquation(i, val, stable));
            continue;
          }
          try {
            if (isInterestingResult) {
              result.add(new InOutAnalysis(richControlFlow, new InOut(i, val), origins, stable, mySharedPendingStates).analyze());
            }
            if (shouldInferNonTrivialFailingContracts) {
              InThrow direction = new InThrow(i, val);
              Equation failEquation = throwEquation.result.equals(Value.Fail)
                                      ? new Equation(new EKey(method, direction, stable), Value.Fail)
                                      : new InThrowAnalysis(richControlFlow, direction, origins, stable, mySharedPendingStates).analyze();
              result.add(failEquation);
            }
          }
          catch (AnalyzerException e) {
            throw new RuntimeException("Analyzer error", e);
          }
        }
      }
    }

    private void processNonBranchingMethod(Member method,
                                           Type[] argumentTypes,
                                           ControlFlowGraph graph,
                                           Type returnType,
                                           boolean stable,
                                           List<? super Equation> result) throws AnalyzerException {
      Set<Member> fieldsToTrack = method.methodName.equals("<clinit>") ? myStaticFinalFields : Collections.emptySet();
      if (argumentTypes.length == 0 && !Type.VOID_TYPE.equals(returnType)) {
        ContainerUtil.addIfNotNull(result, getterEquation(method, graph, stable));
      }
      if (argumentTypes.length == 1 && Type.VOID_TYPE.equals(returnType)) {
        ContainerUtil.addIfNotNull(result, setterEquation(method, graph, stable));
      }
      CombinedAnalysis analyzer = new CombinedAnalysis(method, graph, fieldsToTrack);
      analyzer.analyze();
      ContainerUtil.addIfNotNull(result, analyzer.outContractEquation(stable));
      ContainerUtil.addIfNotNull(result, analyzer.failEquation(stable));
      storeStaticFieldEquations(analyzer);
      if (ASMUtils.isReferenceType(returnType)) {
        result.add(analyzer.nullableResultEquation(stable));
      }
      for (int i = 0; i < argumentTypes.length; i++) {
        Type argType = argumentTypes[i];
        if (ASMUtils.isReferenceType(argType)) {
          result.add(analyzer.notNullParamEquation(i, stable));
          result.add(analyzer.nullableParamEquation(i, stable));
          for (Value val : Value.OBJECT) {
            ContainerUtil.addIfNotNull(result, analyzer.contractEquation(i, val, stable));
            ContainerUtil.addIfNotNull(result, analyzer.failEquation(i, val, stable));
          }
        } else if (ASMUtils.isBooleanType(argType)) {
          for (Value val : Value.BOOLEAN) {
            ContainerUtil.addIfNotNull(result, analyzer.contractEquation(i, val, stable));
            ContainerUtil.addIfNotNull(result, analyzer.failEquation(i, val, stable));
          }
        }
      }
    }

    private @Nullable Equation setterEquation(@NotNull Member method, @NotNull ControlFlowGraph controlFlow, boolean stable) {
      MethodNode node = controlFlow.methodNode;
      boolean isStatic = (node.access & Opcodes.ACC_STATIC) != 0;
      InsnList instructions = node.instructions;
      int size = instructions.size();
      int shift = isStatic ? 0 : 1;
      if (size != 3 + shift) return null;
      if (instructions.get(2 + shift).getOpcode() != Opcodes.RETURN) return null;
      // isStatic -> xLOAD_0 + PUTSTATIC + RETURN
      // !isStatic -> ALOAD_0 + xLOAD_1 + PUTFIELD + RETURN
      if (!isStatic) {
        if (!(instructions.get(0) instanceof VarInsnNode varAccess) ||
            varAccess.getOpcode() != Opcodes.ALOAD ||
            varAccess.var != 0) {
          return null;
        }
      }
      if (!(instructions.get(shift) instanceof VarInsnNode argLoad)) return null;
      if (argLoad.var != shift) return null;
      int loadOpcode = argLoad.getOpcode();
      if (loadOpcode < Opcodes.ILOAD || loadOpcode > Opcodes.ALOAD) return null;

      if (!(instructions.get(1 + shift) instanceof FieldInsnNode fieldAccess) ||
          fieldAccess.getOpcode() != (isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD) ||
          !fieldAccess.owner.equals(className)) {
        return null;
      }
      String name = fieldAccess.name;
      return new Equation(new EKey(method, new In(0, true), stable), new FieldAccess(name));
    }
    
    private @Nullable Equation getterEquation(@NotNull Member method, @NotNull ControlFlowGraph controlFlow, boolean stable) {
      MethodNode node = controlFlow.methodNode;
      boolean isStatic = (node.access & Opcodes.ACC_STATIC) != 0;
      InsnList instructions = node.instructions;
      int size = instructions.size();
      int shift = isStatic ? 0 : 1;
      if (size != 2 + shift) return null;
      int returnOpcode = instructions.get(1 + shift).getOpcode();
      if (returnOpcode < Opcodes.IRETURN || returnOpcode > Opcodes.ARETURN) return null;
      // isStatic -> GETSTATIC + xRETURN
      // !isStatic -> ALOAD_0 + GETFIELD + xRETURN
      if (!isStatic) {
        if (!(instructions.get(0) instanceof VarInsnNode varAccess) ||
            varAccess.getOpcode() != Opcodes.ALOAD ||
            varAccess.var != 0) {
          return null;
        }
      }
      if (!(instructions.get(shift) instanceof FieldInsnNode fieldAccess) ||
          fieldAccess.getOpcode() != (isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD) ||
          !fieldAccess.owner.equals(className)) {
        return null;
      }
      String name = fieldAccess.name;
      return new Equation(new EKey(method, Out, stable), new FieldAccess(name));
    }

    private void storeStaticFieldEquations(CombinedAnalysis analyzer) {
      for (Equation equation : analyzer.staticFieldEquations()) {
        myEquations.put(equation.key,
                        new Equations(Collections.singletonList(new DirectionResultPair(equation.key.dirKey, equation.result)), true));
      }
    }

    private static List<Equation> topEquations(Member method,
                                               Type[] argumentTypes,
                                               boolean isReferenceResult,
                                               boolean isInterestingResult,
                                               boolean stable) {
      // 4 = @NotNull parameter, @Nullable parameter, null -> ..., !null -> ...
      List<Equation> result = new ArrayList<>(argumentTypes.length * 4 + 2);
      if (isReferenceResult) {
        result.add(new Equation(new EKey(method, Out, stable), Value.Top));
        result.add(new Equation(new EKey(method, NullableOut, stable), Value.Bot));
      }
      for (int i = 0; i < argumentTypes.length; i++) {
        if (ASMUtils.isReferenceType(argumentTypes[i])) {
          result.add(new Equation(new EKey(method, new In(i, false), stable), Value.Top));
          result.add(new Equation(new EKey(method, new In(i, true), stable), Value.Top));
          if (isInterestingResult) {
            result.add(new Equation(new EKey(method, new InOut(i, Value.Null), stable), Value.Top));
            result.add(new Equation(new EKey(method, new InOut(i, Value.NotNull), stable), Value.Top));
          }
        }
      }
      return result;
    }

    private static @NotNull LeakingParameters leakingParametersAndFrames(Member method, MethodNode methodNode, Type[] argumentTypes, boolean jsr)
      throws AnalyzerException {
      return argumentTypes.length < 32 ?
              LeakingParameters.buildFast(method.internalClassName, methodNode, jsr) :
              LeakingParameters.build(method.internalClassName, methodNode, jsr);
    }
  }
}
