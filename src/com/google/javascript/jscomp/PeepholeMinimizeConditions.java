/*
 * Copyright 2010 The Closure Compiler Authors.
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

package com.google.javascript.jscomp;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.jstype.TernaryValue;

import java.util.Collections;
import java.util.Comparator;

/**
 * A peephole optimization that minimizes conditional expressions
 * according to De Morgan's laws.
 * Also rewrites conditional statements as expressions by replacing them
 * with HOOKs and short-circuit binary operators.
 *
 * Based on PeepholeSubstituteAlternateSyntax by
 */
class PeepholeMinimizeConditions
  extends AbstractPeepholeOptimization {

  private static final int AND_PRECEDENCE = NodeUtil.precedence(Token.AND);
  private static final int OR_PRECEDENCE = NodeUtil.precedence(Token.OR);

  private final boolean late;

  static final Predicate<Node> DONT_TRAVERSE_FUNCTIONS_PREDICATE
      = new Predicate<Node>() {
    @Override
    public boolean apply(Node input) {
      return !input.isFunction();
    }
  };

  /**
   * @param late When late is false, this mean we are currently running before
   * most of the other optimizations. In this case we would avoid optimizations
   * that would make the code harder to analyze (such as using string splitting,
   * merging statements with commas, etc). When this is true, we would
   * do anything to minimize for size.
   */
  PeepholeMinimizeConditions(boolean late) {
    this.late = late;
  }

  /**
   * Tries apply our various peephole minimizations on the passed in node.
   */
  @Override
  @SuppressWarnings("fallthrough")
  public Node optimizeSubtree(Node node) {
    switch(node.getType()) {
      case Token.RETURN: {
        Node result = tryRemoveRedundantExit(node);
        if (result != node) {
          return result;
        }
        result = tryReplaceExitWithBreak(node);
        if (result != node) {
          return result;
        }
        return tryReduceReturn(node);
      }

      case Token.THROW: {
        Node result = tryRemoveRedundantExit(node);
        if (result != node) {
          return result;
        }
        return tryReplaceExitWithBreak(node);
      }

      // TODO(johnlenz): Maybe remove redundant BREAK and CONTINUE. Overlaps
      // with MinimizeExitPoints.

      case Token.NOT:
        tryMinimizeCondition(node.getFirstChild(), true);
        return tryMinimizeNot(node);

      case Token.IF:
        tryMinimizeCondition(node.getFirstChild(), false);
        return tryMinimizeIf(node);

      case Token.EXPR_RESULT:
        tryMinimizeCondition(node.getFirstChild(), true);
        return node;

      case Token.HOOK:
        tryMinimizeCondition(node.getFirstChild(), false);
        return tryMinimizeHook(node);

      case Token.WHILE:
      case Token.DO:
        tryMinimizeCondition(NodeUtil.getConditionExpression(node), true);
        return node;

      case Token.FOR:
        if (!NodeUtil.isForIn(node)) {
          tryJoinForCondition(node);
          tryMinimizeCondition(NodeUtil.getConditionExpression(node), true);
        }
        return node;

      case Token.BLOCK:
        return tryReplaceIf(node);

      default:
        return node; //Nothing changed
    }
  }

  private void tryJoinForCondition(Node n) {
    if (!late) {
      return;
    }

    Node block = n.getLastChild();
    Node maybeIf = block.getFirstChild();
    if (maybeIf != null && maybeIf.isIf()) {
      Node maybeBreak = maybeIf.getChildAtIndex(1).getFirstChild();
      if (maybeBreak != null && maybeBreak.isBreak()
          && !maybeBreak.hasChildren()) {

        // Preserve the IF ELSE expression is there is one.
        if (maybeIf.getChildCount() == 3) {
          block.replaceChild(maybeIf,
              maybeIf.getLastChild().detachFromParent());
        } else {
          block.removeFirstChild();
        }

        Node ifCondition = maybeIf.removeFirstChild();
        Node fixedIfCondition = IR.not(ifCondition)
            .srcref(ifCondition);

        // OK, join the IF expression with the FOR expression
        Node forCondition = NodeUtil.getConditionExpression(n);
        if (forCondition.isEmpty()) {
          n.replaceChild(forCondition, fixedIfCondition);
        } else {
          Node replacement = new Node(Token.AND);
          n.replaceChild(forCondition, replacement);
          replacement.addChildToBack(forCondition);
          replacement.addChildToBack(fixedIfCondition);
        }

        reportCodeChange();
      }
    }
  }

  /**
   * Use "return x?1:2;" in place of "if(x)return 1;return 2;"
   */
  private Node tryReplaceIf(Node n) {

    for (Node child = n.getFirstChild();
         child != null; child = child.getNext()){
      if (child.isIf()){
        Node cond = child.getFirstChild();
        Node thenBranch = cond.getNext();
        Node elseBranch = thenBranch.getNext();
        Node nextNode = child.getNext();

        if (nextNode != null && elseBranch == null
            && isReturnBlock(thenBranch)
            && nextNode.isIf()) {
          Node nextCond = nextNode.getFirstChild();
          Node nextThen = nextCond.getNext();
          Node nextElse = nextThen.getNext();
          if (thenBranch.isEquivalentToTyped(nextThen)) {
            // Transform
            //   if (x) return 1; if (y) return 1;
            // to
            //   if (x||y) return 1;
            child.detachFromParent();
            child.detachChildren();
            Node newCond = new Node(Token.OR, cond);
            nextNode.replaceChild(nextCond, newCond);
            newCond.addChildToBack(nextCond);
            reportCodeChange();
          } else if (nextElse != null
              && thenBranch.isEquivalentToTyped(nextElse)) {
            // Transform
            //   if (x) return 1; if (y) foo() else return 1;
            // to
            //   if (!x&&y) foo() else return 1;
            child.detachFromParent();
            child.detachChildren();
            Node newCond = new Node(Token.AND,
                IR.not(cond).srcref(cond));
            nextNode.replaceChild(nextCond, newCond);
            newCond.addChildToBack(nextCond);
            reportCodeChange();
          }
        } else if (nextNode != null && elseBranch == null &&
            isReturnBlock(thenBranch) && isReturnExpression(nextNode)) {
          Node thenExpr = null;
          // if(x)return; return 1 -> return x?void 0:1
          if (isReturnExpressBlock(thenBranch)) {
            thenExpr = getBlockReturnExpression(thenBranch);
            thenExpr.detachFromParent();
          } else {
            thenExpr = NodeUtil.newUndefinedNode(child);
          }

          Node elseExpr = nextNode.getFirstChild();

          cond.detachFromParent();
          elseExpr.detachFromParent();

          Node returnNode = IR.returnNode(
                                IR.hook(cond, thenExpr, elseExpr)
                                    .srcref(child));
          n.replaceChild(child, returnNode);
          n.removeChild(nextNode);
          reportCodeChange();
        } else if (elseBranch != null && statementMustExitParent(thenBranch)) {
          child.removeChild(elseBranch);
          n.addChildAfter(elseBranch, child);
          reportCodeChange();
        }
      }
    }
    return n;
  }

  private boolean statementMustExitParent(Node n) {
    switch (n.getType()) {
      case Token.THROW:
      case Token.RETURN:
        return true;
      case Token.BLOCK:
        if (n.hasChildren()) {
          Node child = n.getLastChild();
          return statementMustExitParent(child);
        }
        return false;
      // TODO(johnlenz): handle TRY/FINALLY
      case Token.FUNCTION:
      default:
        return false;
    }
  }

  /**
   * Reduce "return undefined" or "return void 0" to simply "return".
   *
   * @return The original node, maybe simplified.
   */
  private Node tryReduceReturn(Node n) {
    Node result = n.getFirstChild();

    if (result != null) {
      switch (result.getType()) {
        case Token.VOID:
          Node operand = result.getFirstChild();
          if (!mayHaveSideEffects(operand)) {
            n.removeFirstChild();
            reportCodeChange();
          }
          break;
        case Token.NAME:
          String name = result.getString();
          if (name.equals("undefined")) {
            n.removeFirstChild();
            reportCodeChange();
          }
          break;
      }
    }

    return n;
  }

  /**
   * Replace duplicate exits in control structures.  If the node following
   * the exit node expression has the same effect as exit node, the node can
   * be replaced or removed.
   * For example:
   *   "while (a) {return f()} return f();" ==> "while (a) {break} return f();"
   *   "while (a) {throw 'ow'} throw 'ow';" ==> "while (a) {break} throw 'ow';"
   *
   * @param n An follow control exit expression (a THROW or RETURN node)
   * @return The replacement for n, or the original if no change was made.
   */
  private Node tryReplaceExitWithBreak(Node n) {
    Node result = n.getFirstChild();

    // Find the enclosing control structure, if any, that a "break" would exit
    // from.
    Node breakTarget = n;
    for (; !ControlFlowAnalysis.isBreakTarget(breakTarget, null /* no label */);
        breakTarget = breakTarget.getParent()) {
      if (breakTarget.isFunction() || breakTarget.isScript()) {
        // No break target.
        return n;
      }
    }

    Node follow = ControlFlowAnalysis.computeFollowNode(breakTarget);

    // Skip pass all the finally blocks because both the break and return will
    // also trigger all the finally blocks. However, the order of execution is
    // slightly changed. Consider:
    //
    // return a() -> finally { b() } -> return a()
    //
    // which would call a() first. However, changing the first return to a
    // break will result in calling b().

    Node prefinallyFollows = follow;
    follow = skipFinallyNodes(follow);

    if (prefinallyFollows != follow) {
      // There were finally clauses
      if (!isPure(result)) {
        // Can't defer the exit
        return n;
      }
    }

    if (follow == null && (n.isThrow() || result != null)) {
      // Can't complete remove a throw here or a return with a result.
      return n;
    }

    // When follow is null, this mean the follow of a break target is the
    // end of a function. This means a break is same as return.
    if (follow == null || areMatchingExits(n, follow)) {
      Node replacement = IR.breakNode();
      n.getParent().replaceChild(n, replacement);
      this.reportCodeChange();
      return replacement;
    }

    return n;
  }

  /**
   * Remove duplicate exits.  If the node following the exit node expression
   * has the same effect as exit node, the node can be removed.
   * For example:
   *   "if (a) {return f()} return f();" ==> "if (a) {} return f();"
   *   "if (a) {throw 'ow'} throw 'ow';" ==> "if (a) {} throw 'ow';"
   *
   * @param n An follow control exit expression (a THROW or RETURN node)
   * @return The replacement for n, or the original if no change was made.
   */
  private Node tryRemoveRedundantExit(Node n) {
    Node exitExpr = n.getFirstChild();

    Node follow = ControlFlowAnalysis.computeFollowNode(n);

    // Skip pass all the finally blocks because both the fall through and return
    // will also trigger all the finally blocks.
    Node prefinallyFollows = follow;
    follow = skipFinallyNodes(follow);
    if (prefinallyFollows != follow) {
      // There were finally clauses
      if (!isPure(exitExpr)) {
        // Can't replace the return
        return n;
      }
    }

    if (follow == null && (n.isThrow() || exitExpr != null)) {
      // Can't complete remove a throw here or a return with a result.
      return n;
    }

    // When follow is null, this mean the follow of a break target is the
    // end of a function. This means a break is same as return.
    if (follow == null || areMatchingExits(n, follow)) {
      n.detachFromParent();
      reportCodeChange();
      return null;
    }

    return n;
  }

  /**
   * @return Whether the expression does not produces and can not be affected
   * by side-effects.
   */
  boolean isPure(Node n) {
    return n == null
        || (!NodeUtil.canBeSideEffected(n)
            && !mayHaveSideEffects(n));
  }

  /**
   * @return n or the node following any following finally nodes.
   */
  Node skipFinallyNodes(Node n) {
    while (n != null && NodeUtil.isTryFinallyNode(n.getParent(), n)) {
      n = ControlFlowAnalysis.computeFollowNode(n);
    }
    return n;
  }

  /**
   * Check whether one exit can be replaced with another. Verify:
   * 1) They are identical expressions
   * 2) If an exception is possible that the statements, the original
   * and the potential replacement are in the same exception handler.
   */
  boolean areMatchingExits(Node nodeThis, Node nodeThat) {
    return nodeThis.isEquivalentTo(nodeThat)
        && (!isExceptionPossible(nodeThis)
            || getExceptionHandler(nodeThis) == getExceptionHandler(nodeThat));
  }

  boolean isExceptionPossible(Node n) {
    // TODO(johnlenz): maybe use ControlFlowAnalysis.mayThrowException?
    Preconditions.checkState(n.isReturn()
        || n.isThrow());
    return n.isThrow()
        || (n.hasChildren()
            && !NodeUtil.isLiteralValue(n.getLastChild(), true));
  }

  Node getExceptionHandler(Node n) {
    return ControlFlowAnalysis.getExceptionHandler(n);
  }

  /**
   * Try to minimize NOT nodes such as !(x==y).
   *
   * Returns the replacement for n or the original if no change was made
   */
  private Node tryMinimizeNot(Node n) {
    Node parent = n.getParent();

    Node notChild = n.getFirstChild();
    // negative operator of the current one : == -> != for instance.
    int complementOperator;
    switch (notChild.getType()) {
      case Token.EQ:
        complementOperator = Token.NE;
        break;
      case Token.NE:
        complementOperator = Token.EQ;
        break;
      case Token.SHEQ:
        complementOperator = Token.SHNE;
        break;
      case Token.SHNE:
        complementOperator = Token.SHEQ;
        break;
      // GT, GE, LT, LE are not handled in this because !(x<NaN) != x>=NaN.
      default:
        return n;
    }
    Node newOperator = n.removeFirstChild();
    newOperator.setType(complementOperator);
    parent.replaceChild(n, newOperator);
    reportCodeChange();
    return newOperator;
  }

  /**
   * Try flipping HOOKs that have negated conditions.
   *
   * Returns the replacement for n or the original if no replacement was
   * necessary.
   */
  private Node tryMinimizeHook(Node n) {
    Node cond = n.getFirstChild();
    if (cond.isNot()) {
      Node thenBranch = cond.getNext();
      n.replaceChild(cond, cond.removeFirstChild());
      n.removeChild(thenBranch);
      n.addChildToBack(thenBranch);
      reportCodeChange();
    }
    return n;
  }

  /**
   * Try turning IF nodes into smaller HOOKs
   *
   * Returns the replacement for n or the original if no replacement was
   * necessary.
   */
  private Node tryMinimizeIf(Node n) {

    Node parent = n.getParent();

    Node cond = n.getFirstChild();

    /* If the condition is a literal, we'll let other
     * optimizations try to remove useless code.
     */
    if (NodeUtil.isLiteralValue(cond, true)) {
      return n;
    }

    Node thenBranch = cond.getNext();
    Node elseBranch = thenBranch.getNext();

    if (elseBranch == null) {
      if (isFoldableExpressBlock(thenBranch)) {
        Node expr = getBlockExpression(thenBranch);
        if (!late && isPropertyAssignmentInExpression(expr)) {
          // Keep opportunities for CollapseProperties such as
          // a.longIdentifier || a.longIdentifier = ... -> var a = ...;
          // until CollapseProperties has been run.
          return n;
        }

        if (cond.isNot()) {
          // if(!x)bar(); -> x||bar();
          if (isLowerPrecedence(cond.getFirstChild(), OR_PRECEDENCE) &&
              isLowerPrecedence(expr.getFirstChild(),
                  OR_PRECEDENCE)) {
            // It's not okay to add two sets of parentheses.
            return n;
          }

          Node or = IR.or(
              cond.removeFirstChild(),
              expr.removeFirstChild()).srcref(n);
          Node newExpr = NodeUtil.newExpr(or);
          parent.replaceChild(n, newExpr);
          reportCodeChange();

          return newExpr;
        }

        // if(x)foo(); -> x&&foo();
        if (isLowerPrecedence(cond, AND_PRECEDENCE) &&
            isLowerPrecedence(expr.getFirstChild(),
                AND_PRECEDENCE)) {
          // One additional set of parentheses is worth the change even if
          // there is no immediate code size win. However, two extra pair of
          // {}, we would have to think twice. (unless we know for sure the
          // we can further optimize its parent.
          return n;
        }

        n.removeChild(cond);
        Node and = IR.and(cond, expr.removeFirstChild()).srcref(n);
        Node newExpr = NodeUtil.newExpr(and);
        parent.replaceChild(n, newExpr);
        reportCodeChange();

        return newExpr;
      } else {

        // Try to combine two IF-ELSE
        if (NodeUtil.isStatementBlock(thenBranch) &&
            thenBranch.hasOneChild()) {
          Node innerIf = thenBranch.getFirstChild();

          if (innerIf.isIf()) {
            Node innerCond = innerIf.getFirstChild();
            Node innerThenBranch = innerCond.getNext();
            Node innerElseBranch = innerThenBranch.getNext();

            if (innerElseBranch == null &&
                 !(isLowerPrecedence(cond, AND_PRECEDENCE) &&
                   isLowerPrecedence(innerCond, AND_PRECEDENCE))) {
              n.detachChildren();
              n.addChildToBack(
                  IR.and(
                      cond,
                      innerCond.detachFromParent())
                      .srcref(cond));
              n.addChildrenToBack(innerThenBranch.detachFromParent());
              reportCodeChange();
              // Not worth trying to fold the current IF-ELSE into && because
              // the inner IF-ELSE wasn't able to be folded into && anyways.
              return n;
            }
          }
        }
      }

      return n;
    }

    /* TODO(dcc) This modifies the siblings of n, which is undesirable for a
     * peephole optimization. This should probably get moved to another pass.
     */
    tryRemoveRepeatedStatements(n);

    // if(!x)foo();else bar(); -> if(x)bar();else foo();
    // An additional set of curly braces isn't worth it.
    if (cond.isNot() && !consumesDanglingElse(elseBranch)) {
      n.replaceChild(cond, cond.removeFirstChild());
      n.removeChild(thenBranch);
      n.addChildToBack(thenBranch);
      reportCodeChange();
      return n;
    }

    // if(x)return 1;else return 2; -> return x?1:2;
    if (isReturnExpressBlock(thenBranch) && isReturnExpressBlock(elseBranch)) {
      Node thenExpr = getBlockReturnExpression(thenBranch);
      Node elseExpr = getBlockReturnExpression(elseBranch);
      n.removeChild(cond);
      thenExpr.detachFromParent();
      elseExpr.detachFromParent();

      // note - we ignore any cases with "return;", technically this
      // can be converted to "return undefined;" or some variant, but
      // that does not help code size.
      Node returnNode = IR.returnNode(
                            IR.hook(cond, thenExpr, elseExpr)
                                .srcref(n));
      parent.replaceChild(n, returnNode);
      reportCodeChange();
      return returnNode;
    }

    boolean thenBranchIsExpressionBlock = isFoldableExpressBlock(thenBranch);
    boolean elseBranchIsExpressionBlock = isFoldableExpressBlock(elseBranch);

    if (thenBranchIsExpressionBlock && elseBranchIsExpressionBlock) {
      Node thenOp = getBlockExpression(thenBranch).getFirstChild();
      Node elseOp = getBlockExpression(elseBranch).getFirstChild();
      if (thenOp.getType() == elseOp.getType()) {
        // if(x)a=1;else a=2; -> a=x?1:2;
        if (NodeUtil.isAssignmentOp(thenOp)) {
          Node lhs = thenOp.getFirstChild();
          if (areNodesEqualForInlining(lhs, elseOp.getFirstChild()) &&
              // if LHS has side effects, don't proceed [since the optimization
              // evaluates LHS before cond]
              // NOTE - there are some circumstances where we can
              // proceed even if there are side effects...
              !mayEffectMutableState(lhs) &&
              (!mayHaveSideEffects(cond) ||
                  (thenOp.isAssign() && thenOp.getFirstChild().isName()))) {

            n.removeChild(cond);
            Node assignName = thenOp.removeFirstChild();
            Node thenExpr = thenOp.removeFirstChild();
            Node elseExpr = elseOp.getLastChild();
            elseOp.removeChild(elseExpr);

            Node hookNode = IR.hook(cond, thenExpr, elseExpr).srcref(n);
            Node assign = new Node(thenOp.getType(), assignName, hookNode)
                              .srcref(thenOp);
            Node expr = NodeUtil.newExpr(assign);
            parent.replaceChild(n, expr);
            reportCodeChange();

            return expr;
          }
        }
      }
      // if(x)foo();else bar(); -> x?foo():bar()
      n.removeChild(cond);
      thenOp.detachFromParent();
      elseOp.detachFromParent();
      Node expr = IR.exprResult(
          IR.hook(cond, thenOp, elseOp).srcref(n));
      parent.replaceChild(n, expr);
      reportCodeChange();
      return expr;
    }

    boolean thenBranchIsVar = isVarBlock(thenBranch);
    boolean elseBranchIsVar = isVarBlock(elseBranch);

    // if(x)var y=1;else y=2  ->  var y=x?1:2
    if (thenBranchIsVar && elseBranchIsExpressionBlock &&
        getBlockExpression(elseBranch).getFirstChild().isAssign()) {

      Node var = getBlockVar(thenBranch);
      Node elseAssign = getBlockExpression(elseBranch).getFirstChild();

      Node name1 = var.getFirstChild();
      Node maybeName2 = elseAssign.getFirstChild();

      if (name1.hasChildren()
          && maybeName2.isName()
          && name1.getString().equals(maybeName2.getString())) {
        Node thenExpr = name1.removeChildren();
        Node elseExpr = elseAssign.getLastChild().detachFromParent();
        cond.detachFromParent();
        Node hookNode = IR.hook(cond, thenExpr, elseExpr)
                            .srcref(n);
        var.detachFromParent();
        name1.addChildrenToBack(hookNode);
        parent.replaceChild(n, var);
        reportCodeChange();
        return var;
      }

    // if(x)y=1;else var y=2  ->  var y=x?1:2
    } else if (elseBranchIsVar && thenBranchIsExpressionBlock &&
        getBlockExpression(thenBranch).getFirstChild().isAssign()) {

      Node var = getBlockVar(elseBranch);
      Node thenAssign = getBlockExpression(thenBranch).getFirstChild();

      Node maybeName1 = thenAssign.getFirstChild();
      Node name2 = var.getFirstChild();

      if (name2.hasChildren()
          && maybeName1.isName()
          && maybeName1.getString().equals(name2.getString())) {
        Node thenExpr = thenAssign.getLastChild().detachFromParent();
        Node elseExpr = name2.removeChildren();
        cond.detachFromParent();
        Node hookNode = IR.hook(cond, thenExpr, elseExpr)
                            .srcref(n);
        var.detachFromParent();
        name2.addChildrenToBack(hookNode);
        parent.replaceChild(n, var);
        reportCodeChange();

        return var;
      }
    }

    return n;
  }

  /**
   * Try to remove duplicate statements from IF blocks. For example:
   *
   * if (a) {
   *   x = 1;
   *   return true;
   * } else {
   *   x = 2;
   *   return true;
   * }
   *
   * becomes:
   *
   * if (a) {
   *   x = 1;
   * } else {
   *   x = 2;
   * }
   * return true;
   *
   * @param n The IF node to examine.
   */
  private void tryRemoveRepeatedStatements(Node n) {
    Preconditions.checkState(n.isIf());

    Node parent = n.getParent();
    if (!NodeUtil.isStatementBlock(parent)) {
      // If the immediate parent is something like a label, we
      // can't move the statement, so bail.
      return;
    }

    Node cond = n.getFirstChild();
    Node trueBranch = cond.getNext();
    Node falseBranch = trueBranch.getNext();
    Preconditions.checkNotNull(trueBranch);
    Preconditions.checkNotNull(falseBranch);

    while (true) {
      Node lastTrue = trueBranch.getLastChild();
      Node lastFalse = falseBranch.getLastChild();
      if (lastTrue == null || lastFalse == null
          || !areNodesEqualForInlining(lastTrue, lastFalse)) {
        break;
      }
      lastTrue.detachFromParent();
      lastFalse.detachFromParent();
      parent.addChildAfter(lastTrue, n);
      reportCodeChange();
    }
  }

  /**
   * @return Whether the node is a block with a single statement that is
   *     an expression.
   */
  private static boolean isFoldableExpressBlock(Node n) {
    if (n.isBlock()) {
      if (n.hasOneChild()) {
        Node maybeExpr = n.getFirstChild();
        if (maybeExpr.isExprResult()) {
          // IE has a bug where event handlers behave differently when
          // their return value is used vs. when their return value is in
          // an EXPR_RESULT. It's pretty freaking weird. See:
          // http://code.google.com/p/closure-compiler/issues/detail?id=291
          // We try to detect this case, and not fold EXPR_RESULTs
          // into other expressions.
          if (maybeExpr.getFirstChild().isCall()) {
            Node calledFn = maybeExpr.getFirstChild().getFirstChild();

            // We only have to worry about methods with an implicit 'this'
            // param, or this doesn't happen.
            if (calledFn.isGetElem()) {
              return false;
            } else if (calledFn.isGetProp() &&
                       calledFn.getLastChild().getString().startsWith("on")) {
              return false;
            }
          }

          return true;
        }
        return false;
      }
    }

    return false;
  }

  /**
   * @return The expression node.
   */
  private static Node getBlockExpression(Node n) {
    Preconditions.checkState(isFoldableExpressBlock(n));
    return n.getFirstChild();
  }

  /**
   * @return Whether the node is a block with a single statement that is
   *     an return with or without an expression.
   */
  private static boolean isReturnBlock(Node n) {
    if (n.isBlock()) {
      if (n.hasOneChild()) {
        Node first = n.getFirstChild();
        return first.isReturn();
      }
    }

    return false;
  }

  /**
   * @return Whether the node is a block with a single statement that is
   *     an return.
   */
  private static boolean isReturnExpressBlock(Node n) {
    if (n.isBlock()) {
      if (n.hasOneChild()) {
        Node first = n.getFirstChild();
        if (first.isReturn()) {
          return first.hasOneChild();
        }
      }
    }

    return false;
  }

  /**
   * @return Whether the node is a single return statement.
   */
  private static boolean isReturnExpression(Node n) {
    if (n.isReturn()) {
      return n.hasOneChild();
    }
    return false;
  }

  /**
   * @return The expression that is part of the return.
   */
  private static Node getBlockReturnExpression(Node n) {
    Preconditions.checkState(isReturnExpressBlock(n));
    return n.getFirstChild().getFirstChild();
  }

  /**
   * @return Whether the node is a block with a single statement that is
   *     a VAR declaration of a single variable.
   */
  private static boolean isVarBlock(Node n) {
    if (n.isBlock()) {
      if (n.hasOneChild()) {
        Node first = n.getFirstChild();
        if (first.isVar()) {
          return first.hasOneChild();
        }
      }
    }

    return false;
  }

  /**
   * @return The var node.
   */
  private static Node getBlockVar(Node n) {
    Preconditions.checkState(isVarBlock(n));
    return n.getFirstChild();
  }

  /**
   * Does a statement consume a 'dangling else'? A statement consumes
   * a 'dangling else' if an 'else' token following the statement
   * would be considered by the parser to be part of the statement.
   */
  private static boolean consumesDanglingElse(Node n) {
    while (true) {
      switch (n.getType()) {
        case Token.IF:
          if (n.getChildCount() < 3) {
            return true;
          }
          // This IF node has no else clause.
          n = n.getLastChild();
          continue;
        case Token.WITH:
        case Token.WHILE:
        case Token.FOR:
          n = n.getLastChild();
          continue;
        default:
          return false;
      }
    }
  }

  /**
   * Whether the node type has lower precedence than "precedence"
   */
  private static boolean isLowerPrecedence(Node n, final int precedence) {
    return NodeUtil.precedence(n.getType()) < precedence;
  }

  /**
   * Does the expression contain a property assignment?
   */
  private static boolean isPropertyAssignmentInExpression(Node n) {
    Predicate<Node> isPropertyAssignmentInExpressionPredicate =
        new Predicate<Node>() {
      @Override
      public boolean apply(Node input) {
        return (input.isGetProp() &&
            input.getParent().isAssign());
      }
    };

    return NodeUtil.has(n, isPropertyAssignmentInExpressionPredicate,
        DONT_TRAVERSE_FUNCTIONS_PREDICATE);
  }

  private Node tryMinimizeCondition(Node n, boolean countLeadingNot) {
    n = tryMinimizeConditionOld(n);
    return demorganMinimizeCondition(n, countLeadingNot);
  }

  /**
   *  Minimize the given conditional node according to De Morgan's Laws.
   *    !(x && !y)  ==> !x || y
   *    (!a || !b || !c || !d)  ==>  !(a && b && c && d)
   *  etc.
   *
   * @param countLeadingNot When this is false, do not count a leading
   *  NOT in doing the minimization. i.e. Allow minimizations such as:
   *    (!x || !y || z)  ==>  !(x && y && !z)
   *  This is useful in contexts such as IFs or HOOKs where subsequent
   *  optimizations can efficiently deal with leading NOTs.
   *
   * @param n The conditional node.
   * @return The minimized version of n, or n if no minimization was possible.
   */
  private Node demorganMinimizeCondition(Node n, boolean countLeadingNot) {
    MinimizedCondition minCond = MinimizedCondition.fromConditionNode(n);
    if (countLeadingNot ||
         minCond.getLength() <= minCond.getNegativeLength()) {
      return maybeReplaceNode(n, minCond.getNode());
    } else {
      return maybeReplaceNode(n,
          new Node(Token.NOT, minCond.getNegatedNode()));
    }
  }

  private Node maybeReplaceNode(Node lhs, Node rhs) {
    if (lhs.isEquivalentTo(rhs)) {
      return lhs;
    }
    Node parent = lhs.getParent();
    parent.replaceChild(lhs, rhs);
    reportCodeChange();
    return rhs;
  }

  /** A class that represents a minimized conditional expression.
   *  Depending on the context, either the original conditional, or the
   *  negation of the original conditional may be needed, so this class
   *  provides ways to access minimized versions of both of those ASTs.
   */
  static class MinimizedCondition {
    private final Node positive;
    private final Node negative;

    private MinimizedCondition(Node p, Node n) {
      Preconditions.checkArgument(p.getParent() == null);
      Preconditions.checkArgument(n.getParent() == null);
      positive = p;
      negative = n;
    }

    /** Minimize the condition at the given node.
     *
     *  @param n The conditional expression tree to minimize.
     *   This may be still connected to a tree and will be cloned as necessary.
     *  @return A MinimizedCondition object representing that tree.
     */
    static MinimizedCondition fromConditionNode(Node n) {
      switch (n.getType()) {
        case Token.NOT: {
          MinimizedCondition subtree = fromConditionNode(n.getFirstChild());
          ImmutableSet<Node> positiveAsts = ImmutableSet.of(
              negate(subtree.positive.cloneTree()),
              subtree.negative.cloneTree());
          ImmutableSet<Node> negativeAsts = ImmutableSet.of(
              negate(subtree.negative),
              subtree.positive);
          return new MinimizedCondition(
              Collections.min(positiveAsts, AST_LENGTH_COMPARATOR),
              Collections.min(negativeAsts, AST_LENGTH_COMPARATOR));
        }
        case Token.AND:
        case Token.OR: {
          int opType = n.getType();
          int complementType = opType == Token.AND ? Token.OR : Token.AND;
          MinimizedCondition leftSubtree = fromConditionNode(n.getFirstChild());
          MinimizedCondition rightSubtree = fromConditionNode(n.getLastChild());
          ImmutableSet<Node> positiveAsts = ImmutableSet.of(
              new Node(opType,
                  leftSubtree.positive.cloneTree(),
                  rightSubtree.positive.cloneTree()).srcref(n),
              negate(new Node(complementType,
                  leftSubtree.negative.cloneTree(),
                  rightSubtree.negative.cloneTree()).srcref(n)));
          ImmutableSet<Node> negativeAsts = ImmutableSet.of(
              negate(new Node(opType,
                  leftSubtree.positive,
                  rightSubtree.positive).srcref(n)),
              new Node(complementType,
                  leftSubtree.negative,
                  rightSubtree.negative).srcref(n));
          return new MinimizedCondition(
              Collections.min(positiveAsts, AST_LENGTH_COMPARATOR),
              Collections.min(negativeAsts, AST_LENGTH_COMPARATOR));
        }
        default:
          return new MinimizedCondition(n.cloneTree(), negate(n.cloneTree()));
      }
    }

    Node getNode() {
      return positive;
    }

    Node getNegatedNode() {
      return negative;
    }

    int getLength() {
      return length(positive);
    }

    int getNegativeLength() {
      return length(negative);
    }

    private static Node negate(Node node) {
      Preconditions.checkArgument(node.getParent() == null);
      int complementOperator;
      switch (node.getType()) {
        default:
          return new Node(Token.NOT, node).srcref(node);
        case Token.NOT:
          return node.removeFirstChild();
        // Otherwise a binary operator with a complement.
        case Token.EQ:
          complementOperator = Token.NE;
          break;
        case Token.NE:
          complementOperator = Token.EQ;
          break;
        case Token.SHEQ:
          complementOperator = Token.SHNE;
          break;
        case Token.SHNE:
          complementOperator = Token.SHEQ;
          break;
      }
      // Clone entire tree and just change operator.
      node.setType(complementOperator);
      return node;
    }

    private static final Comparator<Node> AST_LENGTH_COMPARATOR =
        new Comparator<Node>() {
      @Override
      public int compare(Node o1, Node o2) {
        return length(o1) - length(o2);
      }
    };

    /** Return the number of characters in the textual representation of
     *  the given tree that will be devoted to negation, or parentheses.
     *  Since these are the only characters that flipping a condition
     *  according to De Morgan's rule can affect, these are the only ones
     *  we count.
     *  @param node The tree whose length should be checked.
     *  @return The number of negations and parentheses in the tree.
     */
    static int length(Node node) {
      int result = 0;
      if (node.isNot()) {
        result++;  // One negation needed.
      }
      for (Node n = node.getFirstChild(); n != null; n = n.getNext()) {
        if (NodeUtil.precedenceWithDefault(n.getType())
            < NodeUtil.precedenceWithDefault(node.getType())) {
          result += 2;  // One pair of parentheses needed.
        }
        result += length(n);
      }
      return result;
    }

  }

  /**
   * Try to minimize conditions expressions, as there are additional
   * assumptions that can be made when it is known that the final result
   * is a boolean.
   *
   * The following transformations are done recursively:
   *   !(x||y) --> !x&&!y
   *   !(x&&y) --> !x||!y
   *   !!x     --> x
   * Thus:
   *   !(x&&!y) --> !x||!!y --> !x||y
   *
   *   Returns the replacement for n, or the original if no change was made
   */
  private Node tryMinimizeConditionOld(Node n) {
    Node parent = n.getParent();

    switch (n.getType()) {
      case Token.OR:
      case Token.AND: {
        Node left = n.getFirstChild();
        Node right = n.getLastChild();

        // Because the expression is in a boolean context minimize
        // the children, this can't be done in the general case.
        left = tryMinimizeConditionOld(left);
        right = tryMinimizeConditionOld(right);

        // Remove useless conditionals
        // Handle four cases:
        //   x || false --> x
        //   x || true  --> true
        //   x && true --> x
        //   x && false  --> false
        TernaryValue rightVal = NodeUtil.getPureBooleanValue(right);
        if (NodeUtil.getPureBooleanValue(right) != TernaryValue.UNKNOWN) {
          int type = n.getType();
          Node replacement = null;
          boolean rval = rightVal.toBoolean(true);

          // (x || FALSE) => x
          // (x && TRUE) => x
          if (type == Token.OR && !rval ||
              type == Token.AND && rval) {
            replacement = left;
          } else if (!mayHaveSideEffects(left)) {
            replacement = right;
          }

          if (replacement != null) {
            n.detachChildren();
            parent.replaceChild(n, replacement);
            reportCodeChange();
            return replacement;
          }
        }
        return n;
      }

      case Token.HOOK: {
        Node condition = n.getFirstChild();
        Node trueNode = n.getFirstChild().getNext();
        Node falseNode = n.getLastChild();

        // Because the expression is in a boolean context minimize
        // the result children, this can't be done in the general case.
        // The condition is handled in the general case in #optimizeSubtree
        trueNode = tryMinimizeConditionOld(trueNode);
        falseNode = tryMinimizeConditionOld(falseNode);

        // Handle four cases:
        //   x ? true : false --> x
        //   x ? false : true --> !x
        //   x ? true : y     --> x || y
        //   x ? y : false    --> x && y
        Node replacement = null;
        TernaryValue trueNodeVal = NodeUtil.getPureBooleanValue(trueNode);
        TernaryValue falseNodeVal = NodeUtil.getPureBooleanValue(falseNode);
        if (trueNodeVal == TernaryValue.TRUE
            && falseNodeVal == TernaryValue.FALSE) {
          // Remove useless conditionals, keep the condition
          condition.detachFromParent();
          replacement = condition;
        } else if (trueNodeVal == TernaryValue.FALSE
            && falseNodeVal == TernaryValue.TRUE) {
          // Remove useless conditionals, keep the condition
          condition.detachFromParent();
          replacement = IR.not(condition);
        } else if (trueNodeVal == TernaryValue.TRUE) {
          // Remove useless true case.
          n.detachChildren();
          replacement = IR.or(condition, falseNode);
        } else if (falseNodeVal == TernaryValue.FALSE) {
          // Remove useless false case
          n.detachChildren();
          replacement = IR.and(condition, trueNode);
        }

        if (replacement != null) {
          parent.replaceChild(n, replacement);
          n = replacement;
          reportCodeChange();
        }

        return n;
      }

      default:
        // while(true) --> while(1)
        TernaryValue nVal = NodeUtil.getPureBooleanValue(n);
        if (nVal != TernaryValue.UNKNOWN) {
          boolean result = nVal.toBoolean(true);
          int equivalentResult = result ? 1 : 0;
          return maybeReplaceChildWithNumber(n, parent, equivalentResult);
        }
        // We can't do anything else currently.
        return n;
    }
  }

  /**
   * Replaces a node with a number node if the new number node is not equivalent
   * to the current node.
   *
   * Returns the replacement for n if it was replaced, otherwise returns n.
   */
  private Node maybeReplaceChildWithNumber(Node n, Node parent, int num) {
    Node newNode = IR.number(num);
    if (!newNode.isEquivalentTo(n)) {
      parent.replaceChild(n, newNode);
      reportCodeChange();

      return newNode;
    }

    return n;
  }

}
