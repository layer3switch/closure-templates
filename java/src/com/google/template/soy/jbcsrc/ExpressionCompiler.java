/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.template.soy.jbcsrc;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.template.soy.jbcsrc.BytecodeUtils.compare;
import static com.google.template.soy.jbcsrc.BytecodeUtils.constant;
import static com.google.template.soy.jbcsrc.BytecodeUtils.logicalNot;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import com.google.template.soy.data.SoyMap;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.exprtree.BooleanNode;
import com.google.template.soy.exprtree.DataAccessNode;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.FieldAccessNode;
import com.google.template.soy.exprtree.FloatNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.IntegerNode;
import com.google.template.soy.exprtree.ItemAccessNode;
import com.google.template.soy.exprtree.ListLiteralNode;
import com.google.template.soy.exprtree.MapLiteralNode;
import com.google.template.soy.exprtree.NullNode;
import com.google.template.soy.exprtree.OperatorNodes.AndOpNode;
import com.google.template.soy.exprtree.OperatorNodes.ConditionalOpNode;
import com.google.template.soy.exprtree.OperatorNodes.DivideByOpNode;
import com.google.template.soy.exprtree.OperatorNodes.EqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.GreaterThanOpNode;
import com.google.template.soy.exprtree.OperatorNodes.GreaterThanOrEqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.LessThanOpNode;
import com.google.template.soy.exprtree.OperatorNodes.LessThanOrEqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.MinusOpNode;
import com.google.template.soy.exprtree.OperatorNodes.ModOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NegativeOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NotEqualOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NotOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NullCoalescingOpNode;
import com.google.template.soy.exprtree.OperatorNodes.OrOpNode;
import com.google.template.soy.exprtree.OperatorNodes.PlusOpNode;
import com.google.template.soy.exprtree.OperatorNodes.TimesOpNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.jbcsrc.Expression.SimpleExpression;
import com.google.template.soy.jbcsrc.ExpressionDetacher.BasicDetacher;
import com.google.template.soy.soytree.defn.LocalVar;
import com.google.template.soy.soytree.defn.TemplateParam;
import com.google.template.soy.types.SoyType.Kind;
import com.google.template.soy.types.SoyTypes;
import com.google.template.soy.types.aggregate.ListType;
import com.google.template.soy.types.aggregate.MapType;
import com.google.template.soy.types.aggregate.RecordType;
import com.google.template.soy.types.primitive.AnyType;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a {@link ExprNode} to a {@link SoyExpression}.
 */
final class ExpressionCompiler {

  static final class BasicExpressionCompiler {
    private final VariableLookup variables;
    private final ErrorReporter reporter;
    private BasicExpressionCompiler(VariableLookup variables, ErrorReporter errorReporter) {
      this.reporter = errorReporter;
      this.variables = variables;
    }

    /**
     * Compile an expression.
     */
    SoyExpression compile(ExprNode expr) {
      return new CompilerVisitor(reporter, variables, Suppliers.ofInstance(BasicDetacher.INSTANCE))
          .exec(expr);
    }
  }

  /**
   * Create an expression compiler that can implement complex detaching logic with the given
   * {@link ExpressionDetacher.Factory}
   */
  static ExpressionCompiler create(
      ExpressionDetacher.Factory detacherFactory,
      VariableLookup variables,
      ErrorReporter errorReporter) {
    return new ExpressionCompiler(detacherFactory, variables, errorReporter);
  }

  /**
   * Create a basic compiler with trivial detaching logic.
   *
   * <p>All generated detach points are implemented as {@code return} statements so it is only valid
   * for use by the {@link LazyClosureCompiler}.
   */
  static BasicExpressionCompiler createBasicCompiler(VariableLookup variables,
      ErrorReporter reporter) {
    return new BasicExpressionCompiler(variables, reporter);
  }

  private final VariableLookup variables;
  private final ErrorReporter reporter;
  private final ExpressionDetacher.Factory detacherFactory;

  private ExpressionCompiler(
      ExpressionDetacher.Factory detacherFactory,
      VariableLookup variables,
      ErrorReporter errorReporter) {
    this.detacherFactory = detacherFactory;
    this.reporter = errorReporter;
    this.variables = variables;
  }

  /**
   * Compiles the given expression tree to a sequence of bytecode in the current method visitor.
   *
   * <p>The reattachPoint should be {@link CodeBuilder#mark(Label) marked} by the caller at a
   * location where the stack depth is 0 and will be used to 'reattach' execution if the compiled
   * expression needs to perform a detach operation.
   */
  SoyExpression compile(ExprNode node, final Label reattachPoint) {
    return new CompilerVisitor(reporter, variables,
        // Use a lazy supplier to allocate the expression detacher on demand.  Allocating the
        // detacher eagerly creates detach points so we want to delay until definitely neccesary.
        Suppliers.memoize(new Supplier<ExpressionDetacher>() {
          @Override public ExpressionDetacher get() {
            return detacherFactory.createExpressionDetacher(reattachPoint);
          }
        }))
      .exec(checkNotNull(node));
  }

  /**
   * Compiles the given expression tree to a sequence of bytecode in the current method visitor.
   *
   * <p>The generated bytecode expects that the evaluation stack is empty when this method is
   * called and it will generate code such that the stack contains a single SoyValue when it
   * returns.  The SoyValue object will have a runtime type equal to
   * {@code node.getType().javaType()}.
   */
  SoyExpression compile(ExprNode node) {
    Label reattachPoint = new Label();
    final SoyExpression exec = compile(node, reattachPoint);
    return exec.withSource(exec.labelStart(reattachPoint));
  }

  private static final class CompilerVisitor
      extends EnhancedAbstractExprNodeVisitor<SoyExpression> {
    final Supplier<? extends ExpressionDetacher> detacher;
    final VariableLookup variables;

    CompilerVisitor(ErrorReporter errorReporter, VariableLookup variables,
        Supplier<? extends ExpressionDetacher> detacher) {
      super(errorReporter);
      this.detacher = detacher;
      this.variables = variables;
    }

    @Override protected final SoyExpression visitExprRootNode(ExprRootNode node) {
      return visit(node.getChild(0));
    }

  // Primitive value constants

    @Override protected final SoyExpression visitNullNode(NullNode node) {
      return SoyExpression.NULL;
    }

    @Override protected final SoyExpression visitFloatNode(FloatNode node) {
      return SoyExpression.forFloat(constant(node.getValue()));
    }

    @Override protected final SoyExpression visitStringNode(StringNode node) {
      return SoyExpression.forString(constant(node.getValue()));
    }

    @Override protected final SoyExpression visitBooleanNode(BooleanNode node) {
      return node.getValue() ? SoyExpression.TRUE : SoyExpression.FALSE;
    }

    @Override protected final SoyExpression visitIntegerNode(IntegerNode node) {
      return SoyExpression.forInt(BytecodeUtils.constant((long) node.getValue()));
    }

  // Collection literals

    @Override protected final SoyExpression visitListLiteralNode(ListLiteralNode node) {
      // TODO(lukes): this should really box the children as SoyValueProviders, we are boxing them
      // anyway and could additionally delay detach generation.  Ditto for MapLiteralNode.
      return SoyExpression.forList((ListType) node.getType(), childrenAsList(node.getChildren()));
    }

    @Override protected final SoyExpression visitMapLiteralNode(MapLiteralNode node) {
      // map literals are either records (if all the strings are literals) or maps if they aren't
      // constants.
      final int numItems = node.numChildren() / 2;
      if (numItems == 0) {
        MapType mapType = (MapType) node.getType();
        return SoyExpression.forMap(mapType, MethodRef.IMMUTABLE_MAP_OF.invoke().asConstant());
      }
      boolean isRecord = node.getType().getKind() == Kind.RECORD;
      final int hashMapCapacity = hashMapCapacity(numItems);
      Expression dupExpr = BytecodeUtils.dupExpr(Type.getType(LinkedHashMap.class));
      List<Statement> puts = new ArrayList<>(numItems);
      boolean isConstant = true;
      for (int i = 0; i < numItems; i++) {
        // Keys are strings and values are boxed SoyValues
        // Note: The soy grammar and type system both allow for maps to have arbitrary keys for
        // types but none of the implementations support this.  So we don't support it either.
        // b/20468013
        SoyExpression key = visit(node.getChild(2 * i)).convert(String.class);
        SoyExpression value = visit(node.getChild(2 * i + 1)).box();
        isConstant = isConstant && key.isConstant() && value.isConstant();
        // TODO(user): Assert that the return value of put() is null? The current impl doesn't
        // care, but perhaps we should
        puts.add(MethodRef.LINKED_HASH_MAP_PUT.invoke(dupExpr, key, value).toStatement());
      }
      final Expression construct = ConstructorRef.LINKED_HASH_MAP_SIZE
          .construct(BytecodeUtils.constant(hashMapCapacity));
      final Statement putAll = Statement.concat(puts);
      Expression mapExpr = new SimpleExpression(Type.getType(Map.class), isConstant) {
        @Override void doGen(CodeBuilder mv) {
          // create a linkedhashmap with the expected size.
          construct.gen(mv);
          // call put for each key value pair.
          putAll.gen(mv);
        }
      };
      if (isRecord) {
        return SoyExpression.forRecord((RecordType) node.getType(), mapExpr);
      }
      return SoyExpression.forMap((MapType) node.getType(), mapExpr);
    }

  // Comparison operators.

    @Override protected final SoyExpression visitEqualOpNode(EqualOpNode node) {
      return SoyExpression.forBool(
          BytecodeUtils.compareSoyEquals(visit(node.getChild(0)), visit(node.getChild(1))));
    }

    @Override protected final SoyExpression visitNotEqualOpNode(NotEqualOpNode node) {
      return SoyExpression.forBool(
          logicalNot(
              BytecodeUtils.compareSoyEquals(visit(node.getChild(0)), visit(node.getChild(1)))));
    }

    @Override protected final SoyExpression visitLessThanOpNode(LessThanOpNode node) {
      SoyExpression left = visit(node.getChild(0));
      SoyExpression right = visit(node.getChild(1));
      if (left.isKnownInt() && right.isKnownInt()) {
        return SoyExpression.forBool(
            compare(Opcodes.IFLT, left.convert(long.class), right.convert(long.class)));
      }
      if (left.isKnownNumber() && right.isKnownNumber()) {
        return SoyExpression.forBool(
            compare(Opcodes.IFLT, left.convert(double.class), right.convert(double.class)));
      }
      return SoyExpression.forBool(MethodRef.RUNTIME_LESS_THAN.invoke(left.box(), right.box()));
    }

    @Override protected final SoyExpression visitGreaterThanOpNode(GreaterThanOpNode node) {
      SoyExpression left = visit(node.getChild(0));
      SoyExpression right = visit(node.getChild(1));
      if (left.isKnownInt() && right.isKnownInt()) {
        return SoyExpression.forBool(
            compare(Opcodes.IFGT, left.convert(long.class), right.convert(long.class)));
      }
      if (left.isKnownNumber() && right.isKnownNumber()) {
        return SoyExpression.forBool(
            compare(Opcodes.IFGT, left.convert(double.class), right.convert(double.class)));
      }
      // Note the argument reversal
      return SoyExpression.forBool(
          MethodRef.RUNTIME_LESS_THAN.invoke(right.box(), left.box()));
    }

    @Override protected final SoyExpression visitLessThanOrEqualOpNode(LessThanOrEqualOpNode node) {
      SoyExpression left = visit(node.getChild(0));
      SoyExpression right = visit(node.getChild(1));
      if (left.isKnownInt() && right.isKnownInt()) {
        return SoyExpression.forBool(
            compare(Opcodes.IFLE, left.convert(long.class), right.convert(long.class)));
      }
      if (left.isKnownNumber() && right.isKnownNumber()) {
        return SoyExpression.forBool(
            compare(Opcodes.IFLE, left.convert(double.class), right.convert(double.class)));
      }
      return SoyExpression.forBool(
          MethodRef.RUNTIME_LESS_THAN_OR_EQUAL.invoke(left.box(), right.box()));
    }

    @Override protected final SoyExpression visitGreaterThanOrEqualOpNode(
        GreaterThanOrEqualOpNode node) {
      SoyExpression left = visit(node.getChild(0));
      SoyExpression right = visit(node.getChild(1));
      if (left.isKnownInt() && right.isKnownInt()) {
        return SoyExpression.forBool(
            compare(Opcodes.IFGE, left.convert(long.class), right.convert(long.class)));
      }
      if (left.isKnownNumber() && right.isKnownNumber()) {
        return SoyExpression.forBool(
            compare(Opcodes.IFGE, left.convert(double.class), right.convert(double.class)));
      }
      // Note the reversal of the arguments.
      return SoyExpression.forBool(
          MethodRef.RUNTIME_LESS_THAN_OR_EQUAL.invoke(right.box(), left.box()));
    }

  // Binary operators

    @Override protected final SoyExpression visitPlusOpNode(PlusOpNode node) {
      SoyExpression left = visit(node.getChild(0));
      SoyExpression right = visit(node.getChild(1));
      if (left.isKnownInt() && right.isKnownInt()) {
        return applyBinaryIntOperator(Opcodes.LADD, left, right);
      }
      if (left.isKnownNumber() && right.isKnownNumber()) {
        return applyBinaryFloatOperator(Opcodes.DADD, left, right);
      }
      // '+' is overloaded for string arguments to mean concatenation.
      if (left.isKnownString() || right.isKnownString()) {
        SoyExpression leftString = left.convert(String.class);
        SoyExpression rightString = right.convert(String.class);
        return SoyExpression.forString(
            MethodRef.STRING_CONCAT.invoke(leftString, rightString));
      }
      return SoyExpression.forSoyValue(node.getType(),
          MethodRef.RUNTIME_PLUS.invoke(left.box(), right.box()));
    }

    @Override protected final SoyExpression visitMinusOpNode(MinusOpNode node) {
      final SoyExpression left = visit(node.getChild(0));
      final SoyExpression right = visit(node.getChild(1));
      if (left.isKnownInt() && right.isKnownInt()) {
        return applyBinaryIntOperator(Opcodes.LSUB, left, right);
      }
      if (left.isKnownNumber() && right.isKnownNumber()) {
        return applyBinaryFloatOperator(Opcodes.DSUB, left, right);
      }
      return SoyExpression.forSoyValue(node.getType(),
          MethodRef.RUNTIME_MINUS.invoke(left.box(), right.box()));
    }

    @Override protected final SoyExpression visitTimesOpNode(TimesOpNode node) {
      final SoyExpression left = visit(node.getChild(0));
      final SoyExpression right = visit(node.getChild(1));
      if (left.isKnownInt() && right.isKnownInt()) {
        return applyBinaryIntOperator(Opcodes.LMUL, left, right);
      }
      if (left.isKnownNumber() && right.isKnownNumber()) {
        return applyBinaryFloatOperator(Opcodes.DMUL, left, right);
      }

      return SoyExpression.forSoyValue(node.getType(),
          MethodRef.RUNTIME_TIMES.invoke(left.box(), right.box()));
    }

    @Override protected final SoyExpression visitDivideByOpNode(DivideByOpNode node) {
      // Note: Soy always performs floating-point division, even on two integers (like JavaScript).
      // Note that this *will* lose precision for longs.
      return applyBinaryFloatOperator(
          Opcodes.DDIV, visit(node.getChild(0)), visit(node.getChild(1)));
    }

    @Override protected final SoyExpression visitModOpNode(ModOpNode node) {
      // If the underlying expression is not an int, then this will throw a SoyDataExpression at
      // runtime.  This is how the current tofu works.
      // If the expression is known not to be an int, then this will throw an exception at compile
      // time.  This should generally be handled by the type checker. See b/19833234
      return applyBinaryIntOperator(Opcodes.LREM, visit(node.getChild(0)), visit(node.getChild(1)));
    }

    private SoyExpression applyBinaryIntOperator(final int operator, SoyExpression left,
        SoyExpression right) {
      final SoyExpression leftInt = left.convert(long.class);
      final SoyExpression rightInt = right.convert(long.class);
      return SoyExpression.forInt(
          new SimpleExpression(Type.LONG_TYPE, leftInt.isConstant() && rightInt.isConstant()) {
            @Override void doGen(CodeBuilder mv) {
              leftInt.gen(mv);
              rightInt.gen(mv);
              mv.visitInsn(operator);
            }
          });
    }

    private SoyExpression applyBinaryFloatOperator(final int operator, SoyExpression left,
        SoyExpression right) {
      final SoyExpression leftFloat = left.convert(double.class);
      final SoyExpression rightFloat = right.convert(double.class);
      boolean constant = leftFloat.isConstant() && rightFloat.isConstant();
      return SoyExpression.forFloat(
          new SimpleExpression(Type.DOUBLE_TYPE, constant) {
            @Override void doGen(CodeBuilder mv) {
              leftFloat.gen(mv);
              rightFloat.gen(mv);
              mv.visitInsn(operator);
            }
          });
    }

  // Unary negation

    @Override protected final SoyExpression visitNegativeOpNode(NegativeOpNode node) {
      final SoyExpression child = visit(node.getChild(0));
      if (child.isKnownInt()) {
        final SoyExpression intExpr = child.convert(long.class);
        return SoyExpression.forInt(new SimpleExpression(Type.LONG_TYPE, child.isConstant()) {
          @Override void doGen(CodeBuilder mv) {
            intExpr.gen(mv);
            mv.visitInsn(Opcodes.LNEG);
          }
        });
      }
      if (child.isKnownNumber()) {
        final SoyExpression floatExpr = child.convert(double.class);
        return SoyExpression.forFloat(new SimpleExpression(Type.DOUBLE_TYPE, child.isConstant()) {
          @Override void doGen(CodeBuilder mv) {
            floatExpr.gen(mv);
            mv.visitInsn(Opcodes.DNEG);
          }
        });
      }
      return SoyExpression.forSoyValue(node.getType(),
          MethodRef.RUNTIME_NEGATIVE.invoke(child.box()));
    }

  // Boolean operators

    @Override protected final SoyExpression visitNotOpNode(NotOpNode node) {
      // All values are convertible to boolean
      return SoyExpression.forBool(
          logicalNot(visit(node.getChild(0)).convert(boolean.class)));
    }

    @Override protected final SoyExpression visitAndOpNode(AndOpNode node) {
      SoyExpression left = visit(node.getChild(0)).convert(boolean.class);
      SoyExpression right = visit(node.getChild(1)).convert(boolean.class);
      return SoyExpression.forBool(BytecodeUtils.logicalAnd(left, right));
    }

    @Override protected final SoyExpression visitOrOpNode(OrOpNode node) {
      SoyExpression left = visit(node.getChild(0)).convert(boolean.class);
      SoyExpression right = visit(node.getChild(1)).convert(boolean.class);
      return SoyExpression.forBool(BytecodeUtils.logicalOr(left, right));
    }

    @Override protected SoyExpression visitNullCoalescingOpNode(NullCoalescingOpNode node) {
      // TODO(lukes): we should be able to avoid boxing the right hand side at least some of the
      // time but it is tricky.  Consider adding specific primitive optimizations ($foo ?: 0 is not
      // uncommon).
      final SoyExpression left = visit(node.getLeftChild()).box();
      final SoyExpression right = visit(node.getRightChild()).box();
      return SoyExpression.forSoyValue(node.getType(),
          new SimpleExpression(Type.getType(node.getType().javaType()),
              left.isConstant() && right.isConstant()) {
            @Override void doGen(CodeBuilder cb) {
              left.gen(cb);
              cb.dup();
              Label success = new Label();
              cb.ifNonNull(success);
              cb.pop();  // pop the extra copy of left
              right.gen(cb);
              cb.mark(success);
            }
          });
    }

    @Override protected final SoyExpression visitConditionalOpNode(ConditionalOpNode node) {
      final SoyExpression condition = visit(node.getChild(0)).convert(boolean.class);
      SoyExpression trueBranch = visit(node.getChild(1));
      SoyExpression falseBranch = visit(node.getChild(2));
      boolean constant =
          condition.isConstant() && trueBranch.isConstant() && falseBranch.isConstant();
      if (trueBranch.isKnownInt() && falseBranch.isKnownInt()) {
        final SoyExpression trueAsLong = trueBranch.convert(long.class);
        final SoyExpression falseAsLong = falseBranch.convert(long.class);
        return SoyExpression.forInt(
            new SimpleExpression(Type.LONG_TYPE, constant) {
              @Override void doGen(CodeBuilder adapter) {
                doCondition(adapter, condition, trueAsLong, falseAsLong);
              }
            });
      }
      if (trueBranch.isKnownNumber() && falseBranch.isKnownNumber()) {
        final SoyExpression trueAsFloat = trueBranch.convert(double.class);
        final SoyExpression falseAsFloat = falseBranch.convert(double.class);
        return SoyExpression.forFloat(new SimpleExpression(Type.DOUBLE_TYPE, constant) {
          @Override void doGen(CodeBuilder adapter) {
            doCondition(adapter, condition, trueAsFloat, falseAsFloat);
          }
        });
      }
      if (trueBranch.isKnownString() && falseBranch.isKnownString()) {
        final SoyExpression trueAsString = trueBranch.convert(String.class);
        final SoyExpression falseAsString = falseBranch.convert(String.class);
        return SoyExpression.forString(new SimpleExpression(Type.getType(String.class), constant) {
          @Override void doGen(CodeBuilder adapter) {
            doCondition(adapter, condition, trueAsString, falseAsString);
          }
        });
      }
      // Fallback to boxing and use the type from the type checker as our node type.
      // TODO(lukes): can we do better here? other types? if the types match can we generically
      // unbox? in the common case the runtime type will just be SoyValue, which is not very useful.
      final SoyExpression trueBoxed = trueBranch.box();
      final SoyExpression falseBoxed = falseBranch.box();
      final Type type = Type.getType(node.getType().javaType());
      return SoyExpression.forSoyValue(node.getType(),
          new SimpleExpression(type, constant) {
            @Override void doGen(CodeBuilder mv) {
              doCondition(mv, condition, trueBoxed, falseBoxed);
            }
          });
    }

    private void doCondition(CodeBuilder mv, Expression condition,
        SoyExpression trueBranch, SoyExpression falseBranch) {
      condition.gen(mv);
      Label ifFalse = new Label();
      Label end = new Label();
      mv.visitJumpInsn(Opcodes.IFEQ, ifFalse);  // if 0 goto ifFalse
      trueBranch.gen(mv);  // eval true branch
      mv.visitJumpInsn(Opcodes.GOTO, end);  // jump to the end
      mv.visitLabel(ifFalse);
      falseBranch.gen(mv);  // eval false branch
      mv.visitLabel(end);
    }

    @Override SoyExpression visitForLoopIndex(VarRefNode varRef, LocalVar local) {
      // an index variable in a {for $index in range(...)} statement
      // These are special because they do not need any attaching/detaching logic and are
      // always unboxed ints
      return SoyExpression.forInt(
          BytecodeUtils.numericConversion(variables.getLocal(local), Type.LONG_TYPE));
    }

    @Override SoyExpression visitForeachLoopVar(VarRefNode varRef, LocalVar local) {
      Expression expression = variables.getLocal(local);
      expression = detacher.get().resolveSoyValueProvider(expression);
      return SoyExpression.forSoyValue(varRef.getType(),
          expression.cast(Type.getType(varRef.getType().javaType())));
    }

    @Override SoyExpression visitParam(VarRefNode varRef, TemplateParam param) {
      // TODO(lukes): It would be nice not to generate a detach for every param access, since
      // after the first successful 'resolve()' we know that all later ones will also resolve
      // successfully. This means that we will generate a potentially large amount of dead
      // branches/states/calls to SoyValueProvider.status(). We could eliminate these by doing
      // some kind of definite assignment analysis to know whether or not a particular varref is
      // _not_ the first one. This would be super awesome and would save bytecode/branches/states
      // and technically be useful for all varrefs. For the time being we do the naive thing and
      // just assume that the jit can handle all the dead branches effectively.
      Expression paramExpr = detacher.get().resolveSoyValueProvider(variables.getParam(param));
      // This inserts a CHECKCAST instruction (aka runtime type checking).  However, it is limited
      // since we do not have good checking for unions (or nullability)
      // TODO(lukes): Where/how should we implement type checking.  For the time being type errors
      // will show up here, and in the unboxing conversions performed during expression
      // manipulation. And, presumably, in NullPointerExceptions.
      return SoyExpression.forSoyValue(varRef.getType(),
          paramExpr.cast(Type.getType(varRef.getType().javaType())));
    }

    @Override SoyExpression visitLetNodeVar(VarRefNode varRef, LocalVar local) {
      Expression expression = variables.getLocal(local);
      expression = detacher.get().resolveSoyValueProvider(expression);
      return SoyExpression.forSoyValue(varRef.getType(),
          expression.cast(Type.getType(varRef.getType().javaType())));
    }

    @Override protected SoyExpression visitDataAccessNode(DataAccessNode node) {
      return new NullSafeAccessVisitor().visit(node);
    }

    @Override protected SoyExpression visitFieldAccessNode(FieldAccessNode node) {
      return new NullSafeAccessVisitor().visit(node);
    }

    @Override SoyExpression visitIsFirstFunction(FunctionNode node, SyntheticVarName indexVar) {
      final Expression expr = variables.getLocal(indexVar);

      return SoyExpression.forBool(new SimpleExpression(Type.BOOLEAN_TYPE, false) {
        @Override void doGen(CodeBuilder adapter) {
          // implements index == 0 ? true : false
          expr.gen(adapter);
          Label ifFirst = new Label();
          adapter.ifZCmp(Opcodes.IFEQ, ifFirst);
          adapter.pushBoolean(false);
          Label end = new Label();
          adapter.goTo(end);
          adapter.mark(ifFirst);
          adapter.pushBoolean(true);
          adapter.mark(end);
        }
      });
    }

    @Override SoyExpression visitIsLastFunction(
        FunctionNode node, SyntheticVarName indexVar, SyntheticVarName lengthVar) {
      final Expression index = variables.getLocal(indexVar);
      final Expression length = variables.getLocal(lengthVar);
      // basically 'index + 1 == length'
      return SoyExpression.forBool(new SimpleExpression(Type.BOOLEAN_TYPE, false) {
        @Override void doGen(CodeBuilder adapter) {
          // 'index + 1 == length ? true : false'
          index.gen(adapter);
          adapter.pushInt(1);
          adapter.visitInsn(Opcodes.IADD);
          length.gen(adapter);
          Label ifLast = new Label();
          adapter.ifICmp(Opcodes.IFEQ, ifLast);
          adapter.pushBoolean(false);
          Label end = new Label();
          adapter.goTo(end);
          adapter.mark(ifLast);
          adapter.pushBoolean(true);
          adapter.mark(end);
        }
      });
    }

    @Override SoyExpression visitIndexFunction(FunctionNode node, SyntheticVarName indexVar) {
      // '(long) index'
      return SoyExpression.forInt(
          BytecodeUtils.numericConversion(variables.getLocal(indexVar), Type.LONG_TYPE));
    }

    @Override SoyExpression visitCheckNotNullFunction(FunctionNode node) {
      // there is only ever a single child
      final ExprNode childNode = Iterables.getOnlyElement(node.getChildren());
      final SoyExpression childExpr = visit(childNode);
      return SoyExpression.forSoyValue(node.getType(),
          new SimpleExpression(Type.getType(node.getType().javaType()), childExpr.isConstant()) {
            @Override void doGen(CodeBuilder adapter) {
              childExpr.gen(adapter);
              adapter.dup();
              Label end = new Label();
              adapter.ifNonNull(end);
              adapter.throwException(Type.getType(NullPointerException.class),
                  "'" + childNode.toSourceString() + "' evaluates to null");
              adapter.mark(end);
            }
          });
    }

    // TODO(lukes): For plugins we simply add the Map<String, SoyJavaFunction> map to RenderContext
    // and pull it out of there.  However, it seems like we should be able to turn some of those
    // calls into static method calls (maybe be stashing instances in static fields in our
    // template). We would probably need to introduce a new mechanism for registering functions.
    // Or we should just 'intrinsify' a number of extra function (isNonnull for example)
    @Override SoyExpression visitPluginFunction(FunctionNode node) {
      Expression soyJavaFunctionExpr =
          MethodRef.RENDER_CONTEXT_GET_FUNCTION
              .invoke(variables.getRenderContext(), constant(node.getFunctionName()));
      Expression list = childrenAsList(node.getChildren());
      return SoyExpression.forSoyValue(AnyType.getInstance(),
          MethodRef.RUNTIME_CALL_SOY_FUNCTION.invoke(soyJavaFunctionExpr, list));
    }

    @Override protected final SoyExpression visitExprNode(ExprNode node) {
      throw new UnsupportedOperationException(
          "Support for " + node.getKind() + " has node been added yet");
    }

    private int hashMapCapacity(int expectedSize) {
      if (expectedSize < 3) {
        return expectedSize + 1;
      }
      if (expectedSize < Ints.MAX_POWER_OF_TWO) {
        // This is the calculation used in JDK8 to resize when a putAll
        // happens; it seems to be the most conservative calculation we
        // can make.  0.75 is the default load factor.
        return (int) (expectedSize / 0.75F + 1.0F);
      }
      return Integer.MAX_VALUE; // any large value
    }


    /**
     * Returns an Expression that evaluates to a list containing all the child expressions.
     */
    private Expression childrenAsList(List<ExprNode> children) {
      int numChildren = children.size();
      if (numChildren == 0) {
        return MethodRef.IMMUTABLE_LIST_OF.invoke().asConstant();
      }
      final List<Expression> childExprs = new ArrayList<>(numChildren);
      boolean isConstant = true;
      for (ExprNode child : children) {
        // All children must be soy values
        SoyExpression childExpr = visit(child).box();
        isConstant = isConstant && childExpr.isConstant();
        childExprs.add(childExpr);
      }
      final Expression construct = ConstructorRef.ARRAY_LIST_SIZE
          .construct(BytecodeUtils.constant(numChildren));
      return new SimpleExpression(Type.getType(List.class), isConstant) {
        @Override void doGen(CodeBuilder mv) {
          construct.gen(mv);
          for (Expression child : childExprs) {
            mv.dup();
            child.gen(mv);
            MethodRef.ARRAY_LIST_ADD.invokeUnchecked(mv);
            mv.pop();  // pop the bool result of arraylist.add
          }
        }
      };
    }

    /**
     * A helper for generating code for null safe access expressions.
     *
     * <p>A null safe access {@code $foo?.bar?.baz} is syntactic sugar for
     * {@code $foo == null ? null : ($foo.bar == null ? null : $foo.bar.baz)}.  So to generate code
     * for it we need to have a way to 'exit' the full access chain as soon as we observe a failed
     * null safety check.
     */
    private final class NullSafeAccessVisitor {
      Label nullSafeExit;

      Label getNullSafeExit() {
        Label local = nullSafeExit;
        return local == null ? nullSafeExit = new Label() : local;
      }

      SoyExpression visit(DataAccessNode node) {
        final SoyExpression dataAccess = visitNullSafeNodeRecurse(node);
        if (nullSafeExit == null) {
          return dataAccess;
        }
        return dataAccess.withSource(
            new SimpleExpression(dataAccess.resultType(), dataAccess.isConstant()) {
              @Override void doGen(CodeBuilder adapter) {
                dataAccess.gen(adapter);
                // At this point either 'orig' will be on the top of stack, or it will be a null
                // value (if a null safety check failed).
                adapter.mark(nullSafeExit);
              }
            });
      }

      SoyExpression addNullSafetyCheck(SoyExpression baseExpr) {
        // need to check if baseExpr == null
        final SoyExpression orig = baseExpr;
        final Label nullSafeExit = getNullSafeExit();
        return SoyExpression.forSoyValue(SoyTypes.removeNull(orig.soyType()),
            new SimpleExpression(orig.resultType(), false) {
          @Override void doGen(CodeBuilder adapter) {
            orig.gen(adapter);                                       // S
            adapter.dup();                                           // S, S
            adapter.ifNull(nullSafeExit);                            // S
            // Note. When we jump to nullSafeExit there is still an instance of 'orig' on the
            // stack but we know it is == null.
          }
        });
      }

      SoyExpression visitNullSafeNodeRecurse(ExprNode node) {
        switch (node.getKind()) {
          // Note: unlike the other backends we don't support nullsafe injected data (i.e. $ij?.foo)
          // because we generally don't support $ij!
          case FIELD_ACCESS_NODE:
            return visitNullSafeFieldAccess((FieldAccessNode) node);
          case ITEM_ACCESS_NODE:
            return visitNullSafeItemAccess((ItemAccessNode) node);

          default:
            return CompilerVisitor.this.visit(node);
        }
      }

      SoyExpression visitNullSafeFieldAccess(FieldAccessNode node) {
        throw new UnsupportedOperationException(
            "Support for " + node.getKind() + " has node been added yet");
      }

      SoyExpression visitNullSafeItemAccess(ItemAccessNode node) {
        SoyExpression baseExpr = visitNullSafeNodeRecurse(node.getBaseExprChild());
        if (node.isNullSafe()) {
          baseExpr = addNullSafetyCheck(baseExpr);
        }
        // KeyExprs never participate in the current null access chain.
        SoyExpression keyExpr = CompilerVisitor.this.visit(node.getKeyExprChild());

        Expression soyValueProvider;
        // Special case index lookups on lists to avoid boxing the int key.  Maps cannot be
        // optimized the same way because there is no real way to 'unbox' a SoyMap.
        if (baseExpr.isKnownList()) {
          soyValueProvider = MethodRef.RUNTIME_GET_LIST_ITEM.invoke(
              baseExpr.convert(List.class),
              keyExpr.convert(long.class));
        } else {
          // Box and do a map style lookup.
          soyValueProvider = MethodRef.RUNTIME_GET_MAP_ITEM.invoke(
              baseExpr.box().cast(SoyMap.class),
              keyExpr.box());
        }
        Expression soyValue = detacher.get().resolveSoyValueProvider(soyValueProvider)
            // Just like javac, we insert cast operations when removing from a collection.
            .cast(node.getType().javaType());
        return SoyExpression.forSoyValue(node.getType(), soyValue);
      }
    }
  }

}
