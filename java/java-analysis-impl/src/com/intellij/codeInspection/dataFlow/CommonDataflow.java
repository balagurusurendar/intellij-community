// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInspection.dataFlow.java.JavaDfaInstructionVisitor;
import com.intellij.codeInspection.dataFlow.jvm.JvmSpecialField;
import com.intellij.codeInspection.dataFlow.jvm.descriptors.AssertionDisabledDescriptor;
import com.intellij.codeInspection.dataFlow.lang.DfaInterceptor;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.codeInspection.dataFlow.types.*;
import com.intellij.codeInspection.dataFlow.value.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.util.JavaPsiConstructorUtil;
import com.intellij.util.ThreeState;
import com.siyeh.ig.psiutils.ExpressionUtils;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;

import static com.intellij.codeInspection.dataFlow.DfaUtil.hasImplicitImpureSuperCall;

public final class CommonDataflow {
  private static class DataflowPoint {
    @NotNull DfType myDfType = DfType.BOTTOM;
    // empty = top; null = bottom
    @Nullable Set<Object> myPossibleValues = Collections.emptySet();
    boolean myMayFailByContract = false;

    DataflowPoint() {}

    DataflowPoint(DataflowPoint other) {
      myDfType = other.myDfType;
      myPossibleValues = other.myPossibleValues;
      myMayFailByContract = other.myMayFailByContract;
    }

    void addValue(DfaMemoryState memState, DfaValue value) {
      if (myPossibleValues == null) return;
      DfType dfType = memState.getDfType(value);
      if (!(dfType instanceof DfConstantType)) {
        myPossibleValues = null;
        return;
      }
      Object newValue = ((DfConstantType<?>)dfType).getValue();
      if (myPossibleValues.contains(newValue)) return;
      if (myPossibleValues.isEmpty()) {
        myPossibleValues = Collections.singleton(newValue);
      }
      else {
        myPossibleValues = new HashSet<>(myPossibleValues);
        myPossibleValues.add(newValue);
      }
    }

    void addFacts(DfaMemoryState memState, DfaValue value) {
      if (myDfType == DfType.TOP) return;
      DfType newType = memState.getDfType(value);
      if (value instanceof DfaVariableValue) {
        SpecialField field = JvmSpecialField.fromQualifier(value);
        if (field != null && newType instanceof DfReferenceType) {
          DfaValue specialField = field.createValue(value.getFactory(), value);
          DfType withSpecialField = field.asDfType(memState.getDfType(specialField));
          newType = newType
            .meet(withSpecialField instanceof DfReferenceType ? ((DfReferenceType)withSpecialField).dropNullability() : withSpecialField);
        }
      }
      if (value instanceof DfaWrappedValue) {
        SpecialField field = ((DfaWrappedValue)value).getSpecialField();
        DfaVariableValue var = ((DfaWrappedValue)value).getWrappedValue();
        newType = newType.meet(field.asDfType(memState.getDfType(var)));
      }
      myDfType = myDfType.join(newType);
    }
  }

  /**
   * Represents the result of dataflow applied to some code fragment (usually a method)
   */
  public static final class DataflowResult {
    private final @NotNull Map<PsiExpression, DataflowPoint> myData = new HashMap<>();
    private @NotNull Map<PsiExpression, DataflowPoint> myDataAssertionsDisabled = myData;
    private final RunnerResult myResult;

    public DataflowResult(RunnerResult result) {
      myResult = result;
    }

    @NotNull
    DataflowResult copy() {
      DataflowResult copy = new DataflowResult(myResult);
      myData.forEach((expression, point) -> copy.myData.put(expression, new DataflowPoint(point)));
      return copy;
    }

    void add(PsiExpression expression, DfaMemoryState memState, DfaValue value) {
      DfaVariableValue assertionDisabled = AssertionDisabledDescriptor.getAssertionsDisabledVar(value.getFactory());
      if (assertionDisabled == null) {
        assert myData == myDataAssertionsDisabled;
        updateDataPoint(myData, expression, memState, value);
      } else {
        DfType type = memState.getDfType(assertionDisabled);
        if (type == DfTypes.TRUE || type == DfTypes.FALSE) {
          if (myData == myDataAssertionsDisabled) {
            myDataAssertionsDisabled = new HashMap<>(myData);
          }
          updateDataPoint(type == DfTypes.TRUE ? myDataAssertionsDisabled : myData, expression, memState, value);
        } else {
          updateDataPoint(myData, expression, memState, value);
          if (myData != myDataAssertionsDisabled) {
            updateDataPoint(myDataAssertionsDisabled, expression, memState, value);
          }
        }
      }
    }

    private void updateDataPoint(Map<PsiExpression, DataflowPoint> data,
                                 PsiExpression expression,
                                 DfaMemoryState memState,
                                 DfaValue value) {
      DataflowPoint point = data.computeIfAbsent(expression, e -> new DataflowPoint());
      if (DfaTypeValue.isContractFail(value)) {
        point.myMayFailByContract = true;
        return;
      }
      if (point.myDfType != DfType.TOP) {
        PsiElement parent = PsiUtil.skipParenthesizedExprUp(expression.getParent());
        if (parent instanceof PsiConditionalExpression &&
            !PsiTreeUtil.isAncestor(((PsiConditionalExpression)parent).getCondition(), expression, false)) {
          add((PsiExpression)parent, memState, value);
        }
      }
      point.addFacts(memState, value);
      point.addValue(memState, value);
    }

    /**
     * Returns true if given expression was visited by dataflow. Note that dataflow usually tracks deparenthesized expressions only,
     * so you should deparenthesize it in advance if necessary.
     *
     * @param expression expression to check, not parenthesized
     * @return true if given expression was visited by dataflow.
     * If false is returned, it's possible that the expression exists in unreachable branch or this expression is not tracked due to
     * the dataflow implementation details.
     */
    public boolean expressionWasAnalyzed(PsiExpression expression) {
      if (expression instanceof PsiParenthesizedExpression) {
        throw new IllegalArgumentException("Should not pass parenthesized expression");
      }
      return myData.containsKey(expression);
    }

    /**
     * Returns true if given call cannot fail according to its contracts
     * (e.g. {@code Optional.get()} executed under {@code Optional.isPresent()}).
     *
     * @param call call to check
     * @return true if it cannot fail by contract; false if unknown or can fail
     */
    public boolean cannotFailByContract(PsiCallExpression call) {
      DataflowPoint point = myData.get(call);
      return point != null && !point.myMayFailByContract;
    }

    /**
     * Returns a set of expression values if known. If non-empty set is returned, then given expression
     * is guaranteed to have one of returned values.
     *
     * @param expression an expression to get its value
     * @return a set of possible values or empty set if not known
     */
    @NotNull
    public Set<Object> getExpressionValues(@Nullable PsiExpression expression) {
      DataflowPoint point = myData.get(expression);
      if (point == null) return Collections.emptySet();
      Set<Object> values = point.myPossibleValues;
      return values == null ? Collections.emptySet() : Collections.unmodifiableSet(values);
    }

    /**
     * @param expression an expression to infer the DfType, must be deparenthesized.
     * @return DfType for that expression, assuming assertions are disabled.
     * May return {@link DfType#TOP} if no information from dataflow is known about this expression
     * @see #getDfTypeNoAssertions(PsiExpression)
     */
    @NotNull
    public DfType getDfType(PsiExpression expression) {
      DataflowPoint point = myData.get(expression);
      return point == null ? DfType.TOP : point.myDfType;
    }

    /**
     * @param expression an expression to infer the DfType, must be deparenthesized.
     * @return DfType for that expression, assuming assertions are disabled.
     * May return {@link DfType#TOP} if no information from dataflow is known about this expression
     * @see #getDfType(PsiExpression)
     */
    @NotNull
    public DfType getDfTypeNoAssertions(PsiExpression expression) {
      DataflowPoint point = myDataAssertionsDisabled.get(expression);
      return point == null ? DfType.TOP : point.myDfType;
    }
  }

  @NotNull
  private static DataflowResult runDFA(@Nullable PsiElement block) {
    if (block == null) return new DataflowResult(RunnerResult.NOT_APPLICABLE);
    DataFlowRunner runner = new DataFlowRunner(block.getProject(), block, ThreeState.UNSURE);
    var interceptor = new CommonDataflowInterceptor();
    RunnerResult result = runner.analyzeMethodRecursively(block, new JavaDfaInstructionVisitor(interceptor));
    if (result != RunnerResult.OK) return new DataflowResult(result);
    if (!(block instanceof PsiClass)) return interceptor.myResult;
    DataflowResult dfr = interceptor.myResult.copy();
    List<DfaMemoryState> states = interceptor.myEndOfInitializerStates;
    for (PsiMethod method : ((PsiClass)block).getConstructors()) {
      List<DfaMemoryState> initialStates;
      PsiCodeBlock body = method.getBody();
      if (body == null) continue;
      PsiMethodCallExpression call = JavaPsiConstructorUtil.findThisOrSuperCallInConstructor(method);
      if (JavaPsiConstructorUtil.isChainedConstructorCall(call) || (call == null && hasImplicitImpureSuperCall((PsiClass)block, method))) {
        initialStates = Collections.singletonList(runner.createMemoryState());
      } else {
        initialStates = StreamEx.of(states).map(DfaMemoryState::createCopy).toList();
      }
      if(runner.analyzeBlockRecursively(body, initialStates, new JavaDfaInstructionVisitor(interceptor)) == RunnerResult.OK) {
        dfr = interceptor.myResult.copy();
      } else {
        interceptor.myResult = dfr;
      }
    }
    return dfr;
  }

  /**
   * Returns the dataflow result for code fragment which contains given context
   * @param context a context to get the dataflow result
   * @return the dataflow result or null if dataflow cannot be launched for this context (e.g. we are inside too complex method)
   */
  @Nullable
  public static DataflowResult getDataflowResult(PsiExpression context) {
    PsiElement body = DfaUtil.getDataflowContext(context);
    if (body == null) return null;
    ConcurrentHashMap<PsiElement, DataflowResult> fileMap =
      CachedValuesManager.getCachedValue(body.getContainingFile(), () ->
        CachedValueProvider.Result.create(new ConcurrentHashMap<>(), PsiModificationTracker.MODIFICATION_COUNT));
    class ManagedCompute implements ForkJoinPool.ManagedBlocker {
      DataflowResult myResult;

      @Override
      public boolean block() {
        myResult = fileMap.computeIfAbsent(body, CommonDataflow::runDFA);
        return true;
      }

      @Override
      public boolean isReleasable() {
        myResult = fileMap.get(body);
        return myResult != null;
      }

      DataflowResult getResult() {
        return myResult == null || myResult.myResult != RunnerResult.OK ? null : myResult;
      }
    }
    ManagedCompute managedCompute = new ManagedCompute();
    try {
      ForkJoinPool.managedBlock(managedCompute);
    }
    catch (RejectedExecutionException ex) {
      // Too many FJP threads: execute anyway in current thread
      managedCompute.block();
    }
    catch (InterruptedException ex) {
      // Should not happen
      throw new AssertionError(ex);
    }
    return managedCompute.getResult();
  }

  /**
   * @param expression an expression to infer the DfType
   * @return DfType for that expression. May return {@link DfType#TOP} if no information from dataflow is known about this expression
   */
  @NotNull
  public static DfType getDfType(PsiExpression expression) {
    DataflowResult result = getDataflowResult(expression);
    if (result == null) return DfType.TOP;
    return result.getDfType(PsiUtil.skipParenthesizedExprDown(expression));
  }

  /**
   * Returns long range set for expression or null if range is unknown.
   * This method first tries to compute expression using {@link com.intellij.psi.impl.ConstantExpressionEvaluator}
   * and only then calls {@link #getDfType(PsiExpression)}.
   *
   * @param expression expression to get its range
   * @return long range set
   */
  @Contract("null -> null")
  @Nullable
  public static LongRangeSet getExpressionRange(@Nullable PsiExpression expression) {
    if (expression == null) return null;
    Object value = ExpressionUtils.computeConstantExpression(expression);
    LongRangeSet rangeSet = LongRangeSet.fromConstant(value);
    if (rangeSet != null) return rangeSet;
    DfType dfType = getDfType(expression);
    return dfType instanceof DfIntegralType ? ((DfIntegralType)dfType).getRange() : null;
  }

  /**
   * Returns the value of given expression calculated via dataflow; or null if value is null or unknown.
   *
   * @param expression expression to analyze
   * @return expression value if known
   */
  @Contract("null -> null")
  public static Object computeValue(@Nullable PsiExpression expression) {
    PsiExpression expressionToAnalyze = PsiUtil.skipParenthesizedExprDown(expression);
    if (expressionToAnalyze == null) return null;
    Object computed = ExpressionUtils.computeConstantExpression(expressionToAnalyze);
    if (computed != null) return computed;
    return getDfType(expressionToAnalyze).getConstantOfType(Object.class);
  }

  private static class CommonDataflowInterceptor implements DfaInterceptor<PsiExpression> {
    private DataflowResult myResult = new DataflowResult(RunnerResult.OK);
    private final List<DfaMemoryState> myEndOfInitializerStates = new ArrayList<>();

    @Override
    public void beforeInitializerEnd(boolean isStatic, @NotNull DfaMemoryState state) {
      if (!isStatic) {
        myEndOfInitializerStates.add(state.createCopy());
      }
    }

    @Override
    public void beforeExpressionPush(@NotNull DfaValue value,
                                     @NotNull PsiExpression expression,
                                     @Nullable TextRange range,
                                     @NotNull DfaMemoryState state) {
      if (range == null) {
        // Do not track instructions which cover part of expression
        myResult.add(expression, state, value);
      }
    }
  }
}
