/*
 * Copyright 2008 The Closure Compiler Authors.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.javascript.jscomp.AbstractCompiler.LifeCycleStage;
import com.google.javascript.jscomp.MakeDeclaredNamesUnique.BoilerplateRenamer;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The goal with this pass is to simplify the other passes, by making less complex statements.
 *
 * <p>Starting with statements like: {@code var a = 0, b = foo();}
 *
 * <p>Which become: {@code var a = 0; var b = foo();}
 *
 * <p>The key here is only to break down things that help the other passes and can be put back
 * together in a form that is at least as small when all is said and done.
 *
 * <p>This pass currently does the following:
 *
 * <ol>
 *   <li>Simplifies the AST by splitting var/let/const statements, moving initializers out of for
 *       loops, and converting whiles to fors.
 *   <li>Makes all variable names globally unique (extern or otherwise) so that no value is ever
 *       shadowed (note: "arguments" may require special handling).
 *   <li>Removes duplicate variable declarations.
 *   <li>Marks constants with the IS_CONSTANT_NAME annotation.
 *   <li>Rewrite body of arrow function as a block.
 *   <li>Take var statements out from for-loop initializer. This: for(var a = 0;a<0;a++) {} becomes:
 *       var a = 0; for(a;a<0;a++) {}
 * </ol>
 */
final class Normalize implements CompilerPass {

  private final AbstractCompiler compiler;
  private final AstFactory astFactory;
  private final boolean assertOnChange;

  Normalize(Builder builder) {
    this.compiler = builder.compiler;
    this.assertOnChange = builder.assertOnChange;
    this.astFactory = builder.compiler.createAstFactory();
  }

  static Normalize createNormalizeForOptimizations(AbstractCompiler compiler) {
    // The default option values are the right ones for optimizations
    return builder(compiler).build();
  }

  static Builder builder(AbstractCompiler compiler) {
    return new Builder(compiler);
  }

  /** Configures and builds a {@link Normalize} object. */
  static class Builder {
    private final AbstractCompiler compiler;
    private boolean assertOnChange = false;

    Builder(AbstractCompiler compiler) {
      this.compiler = compiler;
    }

    /**
     * If the Normalize pass finds work to do, it will throw an exception.
     *
     * <p>This is intended for use in validating that an AST is already normalized.
     *
     * <p>This option is {@code false} by default.
     */
    @CanIgnoreReturnValue
    Builder assertOnChange(boolean assertOnChange) {
      this.assertOnChange = assertOnChange;
      return this;
    }

    Normalize build() {
      return new Normalize(this);
    }
  }

  static void normalizeSyntheticCode(AbstractCompiler compiler, Node js, String prefix) {
    NodeTraversal.traverse(compiler, js, new Normalize.NormalizeStatements(compiler, false));
    NodeTraversal.traverse(
        compiler,
        js,
        MakeDeclaredNamesUnique.builder()
            .withRenamer(
                new BoilerplateRenamer(
                    compiler.getCodingConvention(), compiler.getUniqueNameIdSupplier(), prefix))
            .build());
  }

  static Node parseAndNormalizeTestCode(AbstractCompiler compiler, String code) {
    Node js = compiler.parseTestCode(code);
    NodeTraversal.traverse(compiler, js, new Normalize.NormalizeStatements(compiler, false));
    return js;
  }

  private void reportCodeChange(String changeDescription, Node n) {
    if (assertOnChange) {
      throw new IllegalStateException("Normalize constraints violated:\n" + changeDescription);
    }
    compiler.reportChangeToEnclosingScope(n);
  }

  /** Is this a name node of a function expression? */
  private static boolean isFunctionExpressionNameNode(Node n) {
    if (n == null || !n.isName()) {
      return false;
    }
    Node parent = n.getParent();
    if (parent == null) {
      return false;
    }
    return NodeUtil.isFunctionExpression(parent) && n.isFirstChildOf(parent);
  }

  @Override
  public void process(Node externs, Node root) {
    MakeDeclaredNamesUnique renamer =
        MakeDeclaredNamesUnique.builder().withAssertOnChange(assertOnChange).build();
    NodeTraversal.traverseRoots(compiler, renamer, externs, root);

    NodeTraversal.traverseRoots(
        compiler, new NormalizeStatements(compiler, assertOnChange), externs, root);

    NodeTraversal.builder()
        .setCompiler(compiler)
        .setCallback(new PropagateConstantPropertyOverVars(compiler, assertOnChange))
        .setScopeCreator(new SyntacticScopeCreator(compiler, new DuplicateDeclarationHandler()))
        .traverseRoots(externs, root);

    if (!compiler.getLifeCycleStage().isNormalized()) {
      compiler.setLifeCycleStage(LifeCycleStage.NORMALIZED);
    }
  }

  /**
   * Propagate constant annotations and IS_CONSTANT_NAME property over the Var graph.
   *
   * <p>Also invokes t.getScope() on every scope, for use with the {@link
   * DuplicateDeclarationHandler}.
   */
  private static class PropagateConstantPropertyOverVars implements NodeTraversal.ScopedCallback {
    private final AbstractCompiler compiler;
    private final boolean assertOnChange;

    PropagateConstantPropertyOverVars(AbstractCompiler compiler, boolean forbidChanges) {
      this.compiler = compiler;
      this.assertOnChange = forbidChanges;
    }

    @Override
    public void enterScope(NodeTraversal t) {
      // Cause the scope to be created, which will cause duplicate
      // to be found.
      t.getScope();
    }

    @Override
    public void exitScope(NodeTraversal t) {
      // Nothing to do.
    }

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
      return true;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      // Note: Constant properties annotations are not propagated.
      if (!n.isName() || n.getString().isEmpty()) {
        return;
      }

      // Find the JSDocInfo for a top-level variable
      Var var = t.getScope().getVar(n.getString());
      JSDocInfo info = (var != null) ? var.getJSDocInfo() : null;

      boolean shouldBeConstant =
          (var != null && (var.isConst() || isFunctionExpressionNameNode(var.getNode())))
              || (info != null && info.isConstant())
              || NodeUtil.isConstantByConvention(compiler.getCodingConvention(), n);
      boolean isMarkedConstant = n.getBooleanProp(Node.IS_CONSTANT_NAME);
      if (shouldBeConstant && !isMarkedConstant) {
        if (assertOnChange) {
          String name = n.getString();
          throw new IllegalStateException(
              "Should be const but not marked as const.\n"
                  + "  name: "
                  + name
                  + "\n"
                  + "  parent:"
                  + n.getParent().toStringTree());
        }
        n.putBooleanProp(Node.IS_CONSTANT_NAME, true);
      }
    }
  }

  /** Walk the AST tree and verify that constant names are used consistently. */
  static class VerifyConstants extends AbstractPostOrderCallback implements CompilerPass {

    private final AbstractCompiler compiler;
    private final boolean checkUserDeclarations;

    VerifyConstants(AbstractCompiler compiler, boolean checkUserDeclarations) {
      this.compiler = compiler;
      this.checkUserDeclarations = checkUserDeclarations;
    }

    @Override
    public void process(Node externs, Node root) {
      Node externsAndJs = root.getParent();
      checkState(externsAndJs != null);
      checkState(externsAndJs.hasChild(externs));
      NodeTraversal.traverseRoots(compiler, this, externs, root);
    }

    private final Map<String, Boolean> constantMap = new LinkedHashMap<>();

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isName()) {
        String name = n.getString();
        if (n.getString().isEmpty()) {
          return;
        }

        boolean isConst = n.getBooleanProp(Node.IS_CONSTANT_NAME);
        if (checkUserDeclarations) {
          boolean expectedConst = false;
          CodingConvention convention = compiler.getCodingConvention();
          if (NodeUtil.isConstantName(n) || NodeUtil.isConstantByConvention(convention, n)) {
            expectedConst = true;
          } else {
            expectedConst = false;

            JSDocInfo info = null;
            Var var = t.getScope().getVar(n.getString());
            if (var != null) {
              info = var.getJSDocInfo();
            }

            if (info != null && info.isConstant()) {
              expectedConst = true;
            } else {
              expectedConst = false;
            }
          }

          if (expectedConst) {
            Preconditions.checkState(
                expectedConst == isConst, "The name %s is not annotated as constant.", name);
          } else {
            Preconditions.checkState(
                expectedConst == isConst, "The name %s should not be annotated as constant.", name);
          }
        }

        Boolean value = constantMap.get(name);
        if (value == null) {
          constantMap.put(name, isConst);
        } else if (value.booleanValue() != isConst) {
          throw new IllegalStateException(
              "The name "
                  + name
                  + " is not consistently annotated as constant. Expected "
                  + ImmutableMap.copyOf(constantMap));
        }
      }
    }
  }

  /**
   * Simplify the AST:<br>
   * - VAR declarations split, so they represent exactly one child declaration. <br>
   * - WHILEs are converted to FORs. <br>
   * - FOR loop are initializers are moved out of the FOR structure. <br>
   * - LABEL node of children other than LABEL, BLOCK, WHILE, FOR, or DO are moved into a block.<br>
   * - Add constant annotations based on coding convention. <br>
   */
  static class NormalizeStatements implements NodeTraversal.Callback {
    private final AbstractCompiler compiler;
    private final AstFactory astFactory;
    private final boolean assertOnChange;
    private final RewriteLogicalAssignmentOperatorsHelper rewriteLogicalAssignmentOperatorsHelper;

    NormalizeStatements(AbstractCompiler compiler, boolean assertOnChange) {
      this.compiler = compiler;
      this.assertOnChange = assertOnChange;
      this.astFactory = compiler.createAstFactory();
      this.rewriteLogicalAssignmentOperatorsHelper =
          new RewriteLogicalAssignmentOperatorsHelper(
              compiler, this.astFactory, compiler.getUniqueIdSupplier());
    }

    private void reportCodeChange(String changeDescription, Node n) {
      if (assertOnChange) {
        throw new IllegalStateException("Normalize constraints violated:\n" + changeDescription);
      }
      compiler.reportChangeToEnclosingScope(n);
    }

    @Override
    public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
      doStatementNormalizations(n);
      return true;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      switch (n.getToken()) {
        case WHILE:
          Node expr = n.getFirstChild();
          n.setToken(Token.FOR);
          Node empty = IR.empty();
          empty.srcrefIfMissing(n);
          empty.insertBefore(expr);
          empty.cloneNode().insertAfter(expr);
          reportCodeChange("WHILE node", n);
          break;

        case FUNCTION:
          if (visitFunction(n)) {
            reportCodeChange("Function declaration", n);
          }
          break;

        case ARRAYLIT:
        case CALL:
        case PARAM_LIST:
        case NEW:
        case OBJECTLIT:
        case OPTCHAIN_CALL:
          n.setTrailingComma(false);
          break;

        case NAME:
          annotateConstantsByConvention(n);
          annotateFunctionExpressionNameAsConstant(n);
          break;

        case DESTRUCTURING_LHS:
          normalizeDestructuringLhs(n);
          break;

        case ASSIGN_OR:
        case ASSIGN_AND:
        case ASSIGN_COALESCE:
          rewriteLogicalAssignmentOperatorsHelper.visitLogicalAssignmentOperator(t, n);
          break;

        default:
          break;
      }
    }

    /** Mark names and properties that are constants by convention. */
    private void annotateConstantsByConvention(Node n) {
      checkState(n.isName());

      // Need to check that variables have not been renamed, to determine whether
      // coding conventions still apply.
      if (compiler.getLifeCycleStage().isNormalizedObfuscated()) {
        return;
      }

      if (!n.getBooleanProp(Node.IS_CONSTANT_NAME)
          && NodeUtil.isConstantByConvention(compiler.getCodingConvention(), n)) {
        checkState(!assertOnChange, "Not marked as constant when it should be: %s", n);
        n.putBooleanProp(Node.IS_CONSTANT_NAME, true);
      }
    }

    /** Annotate function names on function expressions to be unconditionally constant */
    private void annotateFunctionExpressionNameAsConstant(Node n) {
      checkState(n.isName(), "Expected NAME node but got %s", n.getToken().toString());
      if (isFunctionExpressionNameNode(n)) {
        n.putBooleanProp(Node.IS_CONSTANT_NAME, true);
      }
    }

    /** Splits ES6 export combined with a variable or function declaration. */
    private void splitExportDeclaration(Node n) {
      if (n.getBooleanProp(Node.EXPORT_DEFAULT)) {
        return;
      }
      List<Node> destructuringLhsNodes = new ArrayList<>();
      Node c = n.getFirstChild();
      if (NodeUtil.isDeclaration(c)) {
        c.detach();

        Node exportSpecs = new Node(Token.EXPORT_SPECS).srcref(n);
        n.addChildToFront(exportSpecs);
        if (c.isClass() || c.isFunction()) {
          Node name = c.getFirstChild();
          c.insertBefore(n);
          addNameNodeToExportSpecs(exportSpecs, name);
        } else {
          NodeUtil.visitLhsNodesInNode(c, (name) -> addNameNodeToExportSpecs(exportSpecs, name));
          // Split up var declarations onto separate lines.
          for (Node child = c.getFirstChild(); child != null; ) {
            final Node next = child.getNext();
            child.detach();
            Node newDeclaration = new Node(c.getToken(), child).srcref(n);
            newDeclaration.insertBefore(n);
            if (child.isDestructuringLhs()) {
              // `export {a, b} = ...` changed to `var {a,b} = ...; export {a, b};`
              // Hence we must normalize the destructuring declaration.
              destructuringLhsNodes.add(child);
            }
            child = next;
          }
        }

        // normalize the newly added destructuring var declarations
        for (Node destructuringLhsNode : destructuringLhsNodes) {
          normalizeDestructuringLhs(destructuringLhsNode);
        }

        reportCodeChange("combined export and declaration", n.getParent());
      }
    }

    private void addNameNodeToExportSpecs(Node exportSpecs, Node name) {
      Node exportSpec = new Node(Token.EXPORT_SPEC).srcref(name);
      exportSpec.addChildToFront(name.cloneNode());
      exportSpec.addChildToFront(name.cloneNode());
      exportSpecs.addChildToBack(exportSpec);
    }

    /**
     * Rewrite blockless arrow functions to have a block with a single return statement
     *
     * <p>For example: {@code (x) => x} becomes {@code (x) => { return x; }}.
     *
     * <p>This simplifies optimizations as they can now assume all functions have a BLOCK.
     */
    boolean visitFunction(Node n) {
      checkState(n.isFunction(), n);
      if (n.isFunction() && !NodeUtil.getFunctionBody(n).isBlock()) {
        Node returnValue = NodeUtil.getFunctionBody(n);
        Node body = IR.block(IR.returnNode(returnValue.detach()));
        body.srcrefTreeIfMissing(returnValue);
        n.addChildToBack(body);
        reportCodeChange("blockless arrow function", body);
      }
      return false;
    }

    /** Do normalizations that introduce new siblings or parents. */
    private void doStatementNormalizations(Node n) {
      if (n.isLabel()) {
        normalizeLabels(n);
      }

      // Only inspect the children of SCRIPTs, BLOCKs and LABELs, as all these
      // are the only legal place for VARs and FOR statements.
      if (NodeUtil.isStatementBlock(n) || n.isLabel()) {
        // makes sure that var [a,b] destructuring from for-loop initializer is also extracted
        extractForInitializer(n, null, null);
      }

      // Only inspect the children of SCRIPTs, BLOCKs, as all these
      // are the only legal place for VARs.
      if (NodeUtil.isStatementBlock(n)) {
        splitVarDeclarations(n);
      }

      if (n.isFunction()) {
        moveNamedFunctions(n.getLastChild());
      }

      if (NodeUtil.isClassStaticBlock(n)) {
        moveNamedFunctions(n);
      }

      if (n.isExport()) {
        // Perform the split in pre-order traversal to accurately normalize destructuring
        // declarations.
        splitExportDeclaration(n);
      }

      if (NodeUtil.isCompoundAssignmentOp(n) && !NodeUtil.isLogicalAssignmentOp(n)) {
        // Logical assignments should be handled in visit(), not here
        normalizeAssignShorthand(n);
      }
    }

    /**
     * Split a var destructuring declaration into stub declarations of individual LHS name nodes
     * following by the destructuring pattern assignment.
     */
    private void normalizeDestructuringLhs(Node n) {
      if (isVarDestructuringDeclaration(n)) {
        rewriteVarDestructuringDeclaration(n);
      }
    }

    /** Is this a var destructuring LHS like `[a,b]` in `var [a,b] = ...` */
    private boolean isVarDestructuringDeclaration(Node n) {
      return n.isDestructuringLhs() // `[a,b]`
          && (n.getFirstChild().isArrayPattern()
              || n.getFirstChild()
                  .isObjectPattern()) // `var [a,b] = [5,6];` or `var {a,b} = {a: 5, b: 6};`
          && n.getParent().isVar(); // `var [a,b] = [5,6];`
    }

    /**
     * Transforms a var destructuring array declarations into stub declarations of the individual
     * lhs names within the array. For example:
     *
     * <pre>
     *    var [a, b = 3] = ....
     * to:
     *    var a; var b; [a, b = 3] = ...
     * </pre>
     *
     * Same for object destructuring declarations. For example:
     *
     * <pre>
     *    var {a, b = 3} = ....
     * to:
     *    var a; var b; ({a, b = 3} = ...);
     * </pre>
     *
     * Before this function is used, {@code extractForInitializer} has already run during pre-order
     * traversal, so we need not worry about destructuring declarations in the for loop initializers
     * (e.g. `for (var [a,b] ...)` is already rewritten before this). Also, export declarations are
     * already handled in `splitExportDeclaration` during pre-order traversal.
     */
    private void rewriteVarDestructuringDeclaration(Node destructuringLhs) {
      checkState(
          !destructuringLhs.getGrandparent().isExport(),
          "Export destructuring declarations should be already handled in `splitExportDeclaration`"
              + " during pre-order traversal.");
      Node var = destructuringLhs.getParent();

      // create a stub declaration for each name in the destructuring pattern
      NodeUtil.visitLhsNodesInNode(
          destructuringLhs,
          (name) -> {
            // Add a declaration outside the destructuring pattern for the given name.
            checkState(
                name.isName(),
                "lhs in destructuring declaration should be a simple name. (%s)",
                name);
            Node newName = IR.name(name.getString()).srcref(name);
            if (name.getBooleanProp(Node.IS_CONSTANT_NAME)) {
              // if old name was a const, new name should be too
              // e.g. when rewriting `{VALUE} = ...` the `VALUE` is const by coding convention
              newName.putBooleanProp(Node.IS_CONSTANT_NAME, true);
            }
            Node newVar = IR.var(newName).srcref(name);
            newVar.insertBefore(var);
          });

      // Transform destructuring var declaration to assignment. That is, `var [a, b] = ...` to `[a,
      // b] = ...` and `var {a, b} = ...` to `({a, b} = ...);`
      Node destructuringPattern = destructuringLhs.removeFirstChild();
      checkState(destructuringPattern.isDestructuringPattern(), "Expected destructuring pattern.");

      Node rhs = destructuringLhs.removeFirstChild();
      Node assign = astFactory.createAssign(destructuringPattern, rhs);
      assign.srcref(var);
      Node expr = astFactory.exprResult(assign);
      expr.srcref(var);

      var.replaceWith(expr);
      destructuringLhs.detach();
      reportCodeChange("Var destructuring declaration rewritten", expr);
    }

    // TODO(johnlenz): Move this to NodeTypeNormalizer once the unit tests are
    // fixed.
    /**
     * Limit the number of special cases where LABELs need to be handled. Only BLOCK and loops are
     * allowed to be labeled. Loop labels must remain in place as the named continues are not
     * allowed for labeled blocks.
     */
    private void normalizeLabels(Node n) {
      checkArgument(n.isLabel());

      Node last = n.getLastChild();
      // TODO(moz): Avoid adding blocks for cases like "label: let x;"
      switch (last.getToken()) {
        case LABEL:
        case BLOCK:
        case FOR:
        case FOR_IN:
        case FOR_OF:
        case FOR_AWAIT_OF:
        case WHILE:
        case DO:
          return;
        default:
          Node block = IR.block();
          block.srcrefIfMissing(last);
          last.replaceWith(block);
          block.addChildToFront(last);
          reportCodeChange("LABEL normalization", n);
          return;
      }
    }

    /**
     * Bring the initializers out of FOR loops. These need to be placed before any associated LABEL
     * nodes. This needs to be done from the top level label first so this is called as a pre-order
     * callback (from shouldTraverse).
     *
     * @param n The node to inspect.
     * @param before The node to insert the initializer before.
     * @param beforeParent The parent of the node before which the initializer will be inserted.
     */
    private void extractForInitializer(Node n, @Nullable Node before, @Nullable Node beforeParent) {

      for (Node next, c = n.getFirstChild(); c != null; c = next) {
        next = c.getNext();
        Node insertBefore = (before == null) ? c : before;
        Node insertBeforeParent = (before == null) ? n : beforeParent;
        switch (c.getToken()) {
          case LABEL:
            extractForInitializer(c, insertBefore, insertBeforeParent);
            break;
          case FOR_IN:
          case FOR_OF:
          case FOR_AWAIT_OF:
            Node first = c.getFirstChild();
            if (first.isVar()) {
              Node lhs = first.getFirstChild();
              if (lhs.isDestructuringLhs()) {
                // Transform:
                //    for (var [a, b = 3] in c) {}
                // to:
                //    var a; var b; for ([a, b = 3] in c) {}
                NodeUtil.visitLhsNodesInNode(
                    lhs,
                    (name) -> {
                      // Add a declaration outside the for loop for the given name.
                      checkState(
                          name.isName(),
                          "lhs in destructuring declaration should be a simple name. (%s)",
                          name);
                      Node newName = IR.name(name.getString()).srcref(name);
                      Node newVar = IR.var(newName).srcref(name);
                      newVar.insertBefore(insertBefore);
                    });

                // Transform for (var [a, b]... ) to for ([a, b]...
                Node destructuringPattern = lhs.removeFirstChild();
                first.replaceWith(destructuringPattern);
              } else {
                // Transform:
                //    for (var a in b) {}
                // to:
                //    var a; for (a in b) {};
                Node newStatement = first;
                Node name = newStatement.getFirstChild().cloneNode();
                first.replaceWith(name);
                newStatement.insertBefore(insertBefore);
              }
              reportCodeChange("FOR-IN var declaration", n);
            }
            break;
          case FOR:
            if (!c.getFirstChild().isEmpty()) {
              Node init = c.getFirstChild();

              if (init.isLet() || init.isConst() || init.isClass() || init.isFunction()) {
                continue;
              }

              Node empty = IR.empty();
              empty.srcrefIfMissing(c);
              init.replaceWith(empty);

              Node newStatement;
              // Only VAR statements, and expressions are allowed,
              // but are handled differently.
              if (init.isVar()) {
                newStatement = init;
              } else {
                newStatement = NodeUtil.newExpr(init);
              }

              newStatement.insertBefore(insertBefore);
              reportCodeChange("FOR initializer", n);
            }
            break;
          default:
            break;
        }
      }
    }

    /**
     * Split a var (or let or const) node such as:
     *
     * <p>var a, b;
     *
     * <p>into individual statements:
     *
     * <p>var a; var b;
     *
     * @param n The whose children we should inspect.
     */
    private void splitVarDeclarations(Node n) {
      for (Node next, c = n.getFirstChild(); c != null; c = next) {
        next = c.getNext();
        if (NodeUtil.isNameDeclaration(c)) {
          if (assertOnChange && !c.hasChildren()) {
            throw new IllegalStateException("Empty VAR node.");
          }

          while (c.getFirstChild() != c.getLastChild()) {
            Node name = c.getFirstChild();
            name.detach();
            Node newVar = new Node(c.getToken(), name).srcref(n);
            newVar.insertBefore(c);
            reportCodeChange("VAR with multiple children", n);
          }
        }
      }
    }

    /**
     * Move all the functions that are valid at the execution of the first statement of the function
     * or static block to the beginning of the function definition or class static block.
     */
    private void moveNamedFunctions(Node body) {
      checkState(body.getParent().isFunction() || NodeUtil.isClassStaticBlock(body));
      Node insertAfter = null;
      Node current = body.getFirstChild();
      // Skip any declarations at the beginning of the function body, they
      // are already in the right place.
      while (current != null && NodeUtil.isFunctionDeclaration(current)) {
        insertAfter = current;
        current = current.getNext();
      }

      // Find any remaining declarations and move them.
      while (current != null) {
        // Save off the next node as the current node maybe removed.
        Node next = current.getNext();
        if (NodeUtil.isFunctionDeclaration(current)) {
          // Remove the declaration from the body.
          current.detach();

          // Read the function at the top of the function body (after any
          // previous declarations).
          insertAfter = addToFront(body, current, insertAfter);
          reportCodeChange(
              "Move function declaration not at top of function or class static block", body);
        }
        current = next;
      }
    }

    private void normalizeAssignShorthand(Node shorthand) {
      if (!shorthand.getFirstChild().isName()) {
        return;
      }
      Node name = shorthand.getFirstChild();
      shorthand.setToken(NodeUtil.getOpFromAssignmentOp(shorthand));
      Node insertPoint = IR.empty();
      shorthand.replaceWith(insertPoint);
      Node assign =
          astFactory.createAssign(name.cloneNode().srcref(name), shorthand).srcref(shorthand);
      assign.setJSDocInfo(shorthand.getJSDocInfo());
      shorthand.setJSDocInfo(null);
      insertPoint.replaceWith(assign);
      reportCodeChange("assign shorthand", assign);
    }

    /**
     * @param after The child node to insert the newChild after, or null if newChild should be added
     *     to the front of parent's child list.
     * @return The inserted child node.
     */
    @CanIgnoreReturnValue
    private static Node addToFront(Node parent, Node newChild, Node after) {
      if (after == null) {
        parent.addChildToFront(newChild);
      } else {
        newChild.insertAfter(after);
      }
      return newChild;
    }
  }

  /** ScopeCreator duplicate declaration handler. */
  private final class DuplicateDeclarationHandler
      implements SyntacticScopeCreator.RedeclarationHandler {

    private final Set<Var> hasOkDuplicateDeclaration = new LinkedHashSet<>();

    /** Remove duplicate VAR declarations discovered during scope creation. */
    @Override
    public void onRedeclaration(Scope s, String name, Node n, CompilerInput input) {
      checkState(n.isName());
      Node parent = n.getParent();
      Var v = s.getVar(name);

      if (s.isGlobal()) {
        // We allow variables to be duplicate declared if one
        // declaration appears in source and the other in externs.
        // This deals with issues where a browser built-in is declared
        // in one browser but not in another.
        if (v.isExtern() && !input.isExtern()) {
          if (hasOkDuplicateDeclaration.add(v)) {
            return;
          }
        }
      }

      if (parent.isFunction()) {
        if (v.getParentNode().isVar()) {
          s.undeclare(v);
          s.declare(name, n, v.getInput());
          replaceVarWithAssignment(
              v.getNameNode(), v.getParentNode(), v.getParentNode().getParent());
        }
      } else if (parent.isVar()) {
        checkState(parent.hasOneChild());
        replaceVarWithAssignment(n, parent, parent.getParent());
      }
    }

    /**
     * Remove the parent VAR. There is only one case that need to be handled: "var a = b;" which is
     * replaced with "a = b"
     *
     * <p>Cases we don't need to handle are VARs with multiple children, which have already been
     * split into separate declarations, so there is no need to handle that here; "for (var
     * a;;);"/"for (var a of b)"/"for (var a in b)", which have been moved out of the loop; and
     * "LABEL: var x;" which has been put in a BLOCK
     *
     * <p>The result of this is that in each case the parent node is replaced which is generally
     * dangerous in a traversal but is fine here with the scope creator, as the next node of
     * interest is the parent's next sibling.
     */
    private void replaceVarWithAssignment(Node n, Node parent, Node grandparent) {
      if (n.hasChildren()) {
        // The  *  is being initialize, preserve the new value.
        n.detach();
        // Convert "var name = value" to "name = value"
        Node value = n.getFirstChild();
        value.detach();
        Node replacement = astFactory.createAssign(n, value);
        replacement.setJSDocInfo(parent.getJSDocInfo());
        replacement.srcrefIfMissing(parent);
        Node statement = NodeUtil.newExpr(replacement);
        parent.replaceWith(statement);
        reportCodeChange("Duplicate VAR declaration", statement);
      } else {
        // It is an empty reference. Remove it.
        checkState(NodeUtil.isStatementBlock(grandparent), grandparent);
        parent.detach();
        reportCodeChange("Duplicate VAR declaration", grandparent);
      }
    }
  }
}
