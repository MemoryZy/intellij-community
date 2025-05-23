// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.importing

import com.intellij.openapi.externalSystem.util.ExternalSystemBundle
import com.intellij.platform.testFramework.assertion.treeAssertion.SimpleTreeAssertion
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BuildViewTestFixture
import com.intellij.util.ThrowableRunnable
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.jvmcompat.GradleJvmSupportMatrix
import org.jetbrains.plugins.gradle.util.GradleConstants

abstract class BuildViewMessagesImportingTestCase : GradleImportingTestCase() {

  private lateinit var buildViewTestFixture: BuildViewTestFixture

  @Throws(Exception::class)
  override fun setUp() {
    super.setUp()
    currentExternalProjectSettings.delegatedBuild = true
    useProjectTaskManager = true
    buildViewTestFixture = BuildViewTestFixture(myProject)
    buildViewTestFixture.setUp()
  }

  override fun tearDown() = RunAll(
    ThrowableRunnable { if (::buildViewTestFixture.isInitialized) buildViewTestFixture.tearDown() },
    ThrowableRunnable { super.tearDown() }
  ).run()

  protected fun SimpleTreeAssertion.Node<Nothing?>.assertNodeWithDeprecatedGradleWarning() {
    assertNodeWithDeprecatedGradleWarning(currentGradleVersion)
  }

  protected fun assertSyncViewTree(assert: SimpleTreeAssertion.Node<Nothing?>.() -> Unit) {
    buildViewTestFixture.assertSyncViewTree(assert)
  }

  protected fun assertSyncViewTreeEquals(executionTreeText: String) {
    buildViewTestFixture.assertSyncViewTreeEquals(executionTreeText)
  }

  protected fun assertSyncViewTreeSame(executionTreeText: String) {
    buildViewTestFixture.assertSyncViewTreeSame(executionTreeText)
  }

  protected fun assertSyncViewTreeEquals(treeTestPresentationChecker: (String) -> Unit) {
    buildViewTestFixture.assertSyncViewTreeEquals(treeTestPresentationChecker)
  }

  protected fun assertBuildViewTree(assert: SimpleTreeAssertion.Node<Nothing?>.() -> Unit) {
    buildViewTestFixture.assertBuildViewTree(assert)
  }

  protected fun assertBuildViewTreeEquals(executionTree: String) {
    buildViewTestFixture.assertBuildViewTreeEquals(executionTree)
  }

  protected fun assertBuildViewTreeSame(executionTree: String) {
    buildViewTestFixture.assertBuildViewTreeSame(executionTree)
  }

  protected fun assertBuildViewTreeEquals(treeTestPresentationChecker: (String?) -> Unit) {
    buildViewTestFixture.assertBuildViewTreeEquals(treeTestPresentationChecker)
  }

  protected fun assertSyncViewSelectedNode(nodeText: String, consoleText: String) {
    buildViewTestFixture.assertSyncViewSelectedNode(nodeText, consoleText)
  }

  protected fun assertSyncViewNode(nodeText: String, consoleTextChecker: (String) -> Unit) {
    buildViewTestFixture.assertSyncViewNode(nodeText, consoleTextChecker)
  }

  protected fun assertSyncViewSelectedNode(nodeText: String, consoleTextChecker: (String) -> Unit) {
    buildViewTestFixture.assertSyncViewSelectedNode(nodeText, consoleTextChecker)
  }

  protected fun assertSyncViewRerunActions() {
    val rerunActions = buildViewTestFixture.getSyncViewRerunActions()
    assertSize(1, rerunActions)
    val reimportActionText = ExternalSystemBundle.message("action.refresh.project.text", GradleConstants.SYSTEM_ID.readableName)
    assertEquals(reimportActionText, rerunActions[0].templateText)
  }

  protected fun assertBuildViewSelectedNode(nodeText: String, consoleText: String) {
    buildViewTestFixture.assertBuildViewSelectedNode(nodeText, consoleText)
  }

  override fun handleImportFailure(errorMessage: String, errorDetails: String?) {
    // do not fail tests with failed builds
  }

  companion object {

    fun SimpleTreeAssertion.Node<Nothing?>.assertNodeWithDeprecatedGradleWarning(gradleVersion: GradleVersion) {
      if (GradleJvmSupportMatrix.isGradleDeprecatedByIdea(gradleVersion)) {
        assertNode("Deprecated Gradle Version")
      }
    }
  }
}
