// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.java.refactoring

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.refactoring.actions.*
import com.intellij.refactoring.wrapreturnvalue.WrapReturnValueAction
import com.intellij.testFramework.LightJavaCodeInsightTestCase
import org.jetbrains.annotations.NonNls

class RefactorThisTest: LightJavaCodeInsightTestCase() {
  private val BASE_PATH: @NonNls String = "/refactoring/refactorThis"

  fun testPullMembersUpWithExtends() {
    assertTrue(doActionExists<PullUpAction>())
  }

  fun testPullMembersUpWithImplements() {
    assertTrue(doActionExists<PullUpAction>())
  }

  fun testPullMembersUpFromAnonymousClass() {
    assertTrue(doActionExists<PullUpAction>())
  }

  fun testPullMembersUpFiltered() {
    assertFalse(doActionExists<PullUpAction>())
  }

  fun testInheritanceToDelegationWithExtends() {
    assertTrue(doActionExists<InheritanceToDelegationAction>())
  }

  fun testInheritanceToDelegationWithImplements() {
    assertTrue(doActionExists<InheritanceToDelegationAction>())
  }

  fun testInheritanceToDelegationNoSuperClass() {
    assertFalse(doActionExists<InheritanceToDelegationAction>())
  }

  fun testInheritanceToDelegationOutsideDeclaration() {
    assertFalse(doActionExists<InheritanceToDelegationAction>())
  }

  fun testReplaceMethodWithMethodObjectIsFiltered() {
    assertFalse(doActionExists<ReplaceMethodWithMethodObjectAction>())
  }

  fun testFindAndReplaceDuplicatesIsFiltered() {
    assertFalse(doActionExists<MethodDuplicatesAction>())
  }

  fun testFindAndReplaceDuplicatesOnMethodDeclaration() {
    assertTrue(doActionExists<MethodDuplicatesAction>())
  }

  fun testFindAndReplaceDuplicatesOnFieldDeclaration() {
    assertTrue(doActionExists<MethodDuplicatesAction>())
  }

  fun testGenerifyIsFiltered() {
    assertFalse(doActionExists<TypeCookAction>())
  }

  fun testReplaceConstructorWithFactoryFilteredOnParameters() {
    assertFalse(doActionExists<ReplaceConstructorWithFactoryAction>())
  }

  fun testReplaceConstructorWithFactoryFilteredOnStatement() {
    assertFalse(doActionExists<ReplaceConstructorWithFactoryAction>())
  }

  fun testReplaceConstructorWithFactoryOnCall() {
    assertFalse(doActionExists<ReplaceConstructorWithFactoryAction>())
  }

  fun testReplaceConstructorWithFactoryOnDeclaration() {
    assertTrue(doActionExists<ReplaceConstructorWithFactoryAction>())
  }

  fun testUseInterfaceWherePossibleOnDeclaration() {
    assertTrue(doActionExists<TurnRefsToSuperAction>())
  }

  fun testUseInterfaceWherePossibleFilteredOnReference() {
    assertFalse(doActionExists<TurnRefsToSuperAction>())
  }

  fun testUseInterfaceWherePossibleIsFiltered() {
    assertFalse(doActionExists<TurnRefsToSuperAction>())
  }

  fun testSafeDeleteIsFilteredOnClassReference() {
    assertFalse(doActionExists<SafeDeleteAction>())
  }

  fun testSafeDeleteIsFilteredOnMethodReference() {
    assertFalse(doActionExists<SafeDeleteAction>())
  }

  fun testSafeDeleteIsFilteredOnVariableReference() {
    assertFalse(doActionExists<SafeDeleteAction>())
  }

  fun testSafeDeleteOnClassDeclaration() {
    assertTrue(doActionExists<SafeDeleteAction>())
  }

  fun testSafeDeleteOnMethodDeclaration() {
    assertTrue(doActionExists<SafeDeleteAction>())
  }

  fun testSafeDeleteOnVariableDeclaration() {
    assertTrue(doActionExists<SafeDeleteAction>())
  }

  fun testMoveIsFilteredOnStatement() {
    assertFalse(doActionExists<MoveAction>())
  }

  fun testMoveIsFilteredOnMethodReference() {
    assertFalse(doActionExists<MoveAction>())
  }

  fun testMoveIsFilteredOnConstructor() {
    assertFalse(doActionExists<MoveAction>())
  }

  fun testMoveOnMethodDeclaration() {
    assertTrue(doActionExists<MoveAction>())
  }

  fun testMoveOnClassDeclaration() {
    assertTrue(doActionExists<MoveAction>())
  }

  fun testIntroduceParameterObject() {
    assertTrue(doActionExists<IntroduceParameterObjectAction>())
  }

  fun testIntroduceParameterObjectFiltered() {
    assertFalse(doActionExists<IntroduceParameterObjectAction>())
  }

  fun testWrapReturnValue() {
    assertTrue(doActionExists<WrapReturnValueAction>())
  }

  fun testWrapReturnValueFiltered() {
    assertFalse(doActionExists<WrapReturnValueAction>())
  }

  fun testMakeStatic() {
    assertTrue(doActionExists<MakeStaticAction>())
  }

  fun testMakeStaticFiltered() {
    assertFalse(doActionExists<MakeStaticAction>())
  }

  fun testConvertToInstanceMethod() {
    assertTrue(doActionExists<ConvertToInstanceMethodAction>())
  }

  fun testConvertToInstanceMethodFiltered() {
    assertFalse(doActionExists<ConvertToInstanceMethodAction>())
  }

  fun testPushDownOnMethod() {
    assertTrue(doActionExists<PushDownAction>())
  }

  fun testPushDownOnClass() {
    assertTrue(doActionExists<PushDownAction>())
  }

  fun testPushDownFiltered() {
    assertFalse(doActionExists<PushDownAction>())
  }

  fun testIntroduceFunctionalVariableFromExpression() {
    assertTrue(doActionExists<IntroduceFunctionalVariableAction>())
  }

  fun testIntroduceFunctionalVariableFromStatement() {
    assertTrue(doActionExists<IntroduceFunctionalVariableAction>())
  }

  fun testIntroduceFunctionalVariableFiltered() {
    assertFalse(doActionExists<IntroduceFunctionalVariableAction>())
  }

  private inline fun <reified A> doActionExists(): Boolean {
    configureByFile("$BASE_PATH/${getTestName(false)}.java")
    val actions = findAvailableActions()
    return actions.any { action -> action is A }
  }

  private fun findAvailableActions(): List<AnAction> {
    val action = RefactoringQuickListPopupAction()
    val group = DefaultActionGroup()
    val dataContext = DataManager.getInstance().getDataContext(editor.component)
    action.fillActions(project, group, dataContext)
    return group.childActionsOrStubs.toList()
  }

}