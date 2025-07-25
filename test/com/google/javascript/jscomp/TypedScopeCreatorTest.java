/*
 * Copyright 2009 The Closure Compiler Authors.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.javascript.jscomp.TypedScopeCreator.CTOR_INITIALIZER;
import static com.google.javascript.jscomp.TypedScopeCreator.IFACE_INITIALIZER;
import static com.google.javascript.jscomp.modules.ModuleMapCreator.DOES_NOT_HAVE_EXPORT_WITH_DETAILS;
import static com.google.javascript.jscomp.testing.ScopeSubject.assertScope;
import static com.google.javascript.jscomp.testing.TypedVarSubject.assertThat;
import static com.google.javascript.rhino.jstype.JSTypeNative.BOOLEAN_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.NULL_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.NUMBER_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.OBJECT_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.STRING_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.UNKNOWN_TYPE;
import static com.google.javascript.rhino.testing.NodeSubject.assertNode;
import static com.google.javascript.rhino.testing.TypeSubject.assertType;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.deps.ModuleLoader;
import com.google.javascript.jscomp.deps.ModuleLoader.ResolutionMode;
import com.google.javascript.jscomp.modules.Export;
import com.google.javascript.jscomp.modules.ModuleMapCreator;
import com.google.javascript.jscomp.testing.TestExternsBuilder;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.InputId;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.jstype.EnumType;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.JSTypeNative;
import com.google.javascript.rhino.jstype.JSTypeRegistry;
import com.google.javascript.rhino.jstype.JSTypeResolver;
import com.google.javascript.rhino.jstype.KnownSymbolType;
import com.google.javascript.rhino.jstype.NamedType;
import com.google.javascript.rhino.jstype.ObjectType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link TypedScopeCreator} and {@link TypeInference}. Admittedly, the name is a bit of a
 * misnomer.
 */
@RunWith(JUnit4.class)
public final class TypedScopeCreatorTest extends CompilerTestCase {

  private JSTypeRegistry registry;
  private TypedScope globalScope;
  private TypedScope lastLocalScope;
  private TypedScope lastFunctionScope;
  private boolean processClosurePrimitives = false;

  /**
   * Maps a label name to information about the labeled statement.
   *
   * <p>This map is recreated each time parseAndRunTypeInference() is executed.
   * TODO(bradfordcsmith): This map and LabeledStatement are also in TypeInferenceTest. It would be
   * good to unify them.
   */
  private Map<String, LabeledStatement> labeledStatementMap;

  /** Stores information about a labeled statement and allows making assertions on it. */
  static class LabeledStatement {
    final Node statementNode;
    final TypedScope enclosingScope;

    LabeledStatement(Node statementNode, TypedScope enclosingScope) {
      this.statementNode = checkNotNull(statementNode);
      this.enclosingScope = checkNotNull(enclosingScope);
    }
  }

  public TypedScopeCreatorTest() {
    super(DEFAULT_EXTERNS);
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    enableTypeInfoValidation();
  }

  private class ScopeFinder extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      TypedScope scope = t.getTypedScope();
      if (scope.isGlobal()) {
        globalScope = scope;
      } else if (scope.isBlockScope()) {
        // TODO(bradfordcsmith): use labels to find scopes instead of lastLocalScope
        lastLocalScope = scope;
      } else if (scope.isFunctionScope()) {
        lastFunctionScope = scope;
      }
      if (parent != null && parent.isLabel() && !n.isLabelName()) {
        // First child of a LABEL is a LABEL_NAME, n is the second child.
        Node labelNameNode = checkNotNull(n.getPrevious(), n);
        checkState(labelNameNode.isLabelName(), labelNameNode);
        String labelName = labelNameNode.getString();
        assertWithMessage("Duplicate label name: %s", labelName)
            .that(labeledStatementMap)
            .doesNotContainKey(labelName);
        labeledStatementMap.put(labelName, new LabeledStatement(n, scope));
      }
    }
  }

  private LabeledStatement getLabeledStatement(String label) {
    assertWithMessage("No statement found for label: %s", label)
        .that(labeledStatementMap)
        .containsKey(label);
    return labeledStatementMap.get(label);
  }

  @Override
  protected CompilerPass getProcessor(final Compiler compiler) {
    registry = compiler.getTypeRegistry();
    // Create a fresh statement map for each test case.
    labeledStatementMap = new HashMap<>();
    return (Node externs, Node root) -> {
      new GatherModuleMetadata(compiler, false, ResolutionMode.BROWSER).process(externs, root);
      new ModuleMapCreator(compiler, compiler.getModuleMetadataMap()).process(externs, root);
      new InferConsts(compiler).process(externs, root);

      TypedScopeCreator scopeCreator = new TypedScopeCreator(compiler);
      new TypeInferencePass(compiler, compiler.getReverseAbstractInterpreter(), scopeCreator)
          .inferAllScopes(root.getParent());
      NodeTraversal.builder()
          .setCompiler(compiler)
          .setCallback(new ScopeFinder())
          .setScopeCreator(scopeCreator)
          .traverseRoots(externs, root);
    };
  }

  @Override
  protected CompilerOptions getOptions() {
    CompilerOptions options = super.getOptions();
    options.setClosurePass(processClosurePrimitives);
    return options;
  }

  @Test
  public void testVarDeclarationWithJSDocForObjPatWithOneVariable() {
    // Ignore JSDoc on a destructuring declaration, and just infer the type.
    // CheckJSDoc will issue a warning for the @type annotation.
    testSame("/** @type {string} */ var {a} = {a: 1};");
    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testVarDeclarationWithJSDocForObjPatWithMultipleVariables() {
    // Ignore JSDoc on a destructuring declaration
    // CheckJSDoc will issue a warning for the @type annotation.
    testSame("/** @type {string} */ var {a, b} = {a: 1};");
    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isTrue();

    TypedVar bVar = checkNotNull(globalScope.getVar("b"));
    assertType(bVar.getType()).toStringIsEqualTo("?");
    assertThat(bVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testVarDeclarationObjPatShorthandProp() {
    testSame("var {/** number */ a} = {a: 1};");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testVarDeclarationObjPatShorthandPropWithDefault() {
    testSame("var {/** number */ a = 2} = {a: 1};");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testVarDeclarationObjPatNormalProp() {
    testSame("var {a: /** number */ a} = {a: 1};");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testVarDeclarationObjPatNormalPropWithDefault() {
    testSame("var {a: /** number */ a = 2} = {a: 1};");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testVarDeclarationObjPatComputedProp() {
    testSame("var {['a']: /** number */ a} = {a: 1};");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testVarDeclarationObjPatComputedPropWithDefault() {
    testSame("var {['a']: /** number */ a = 2} = {a: 1};");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testVarDeclarationArrayPat() {
    testSame("var [ /** number */ a ] = [1];");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testVarDeclarationArrayPatWithDefault() {
    testSame("var [ /** number */ a = 2 ] = [1];");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testVarDeclarationArrayPatRest() {
    // TODO(bradfordcsmith): Add a TypeCheck test case to ensure rest values are always Arrays
    testSame("var [ ... /** !Array<number> */ a ] = [1];");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("Array<number>");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testConstDeclarationObjectPatternInfersType_forAliasedConstructor() {
    testSame(
        """
        const ns = {};
        /** @constructor */
        ns.Foo = function() {}

        const {Foo} = ns;
        const /** !Foo */ fooInstance = new Foo();
        """);

    TypedVar fooVar = checkNotNull(globalScope.getVar("Foo"));
    assertType(fooVar.getType()).toStringIsEqualTo("(typeof ns.Foo)");
    assertThat(fooVar.isTypeInferred()).isFalse();

    TypedVar fooInstanceVar = checkNotNull(globalScope.getVar("fooInstance"));
    assertType(fooInstanceVar.getType()).toStringIsEqualTo("ns.Foo");
    assertThat(fooInstanceVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testConstDeclarationObjectPatternInfersType_forAliasedTypedef() {
    testSame(
        """
        const ns = {};
        /** @typedef {string} */ ns.Foo;
        const {Foo} = ns;
        let /** !Foo */ f = 'bar';
        """);

    TypedVar fooVar = checkNotNull(globalScope.getVar("Foo"));
    assertType(fooVar.getType()).toStringIsEqualTo("None");

    JSType fooType = registry.getGlobalType("Foo");
    assertType(fooType).isEqualTo(getNativeType(JSTypeNative.STRING_TYPE));

    TypedVar fooInstanceVar = checkNotNull(globalScope.getVar("f"));
    assertType(fooInstanceVar.getType()).toStringIsEqualTo("string");
    assertThat(fooInstanceVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testConstDeclarationObjectPatternInfersTypeAsDeclared() {
    testSame(
        """
        const /** {a: number} */ obj = {a: 3}; // preserve newline
        const {a} = obj;
        """);

    // we treat this as declaring a type on `a`
    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testConstDeclarationObjectPatternInfersTypeGivenComputedProperty() {
    testSame(
        """
        const /** !IObject<string, number> */ obj = {a: 3}; // preserve newline
        const {['foobar']: a} = obj;
        """);

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testConstDeclarationObjectPatternInfersTypeGivenUnknownComputedProperty() {
    testSame(
        """
        var obj = {}; // preserve newline
        const {['foobar']: a} = obj;
        """);

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("?");
    assertThat(aVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testConstDeclarationArrayPatternInfersType() {
    testSame(
        """
        const /** !Iterable<number> */ arr = [1, 2, 3]; // preserve newline
        const [a] = arr;
        """);

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testConstDeclarationWithOrInRhs() {
    // needed because there is a special case for `var goog = goog || {};` that was crashing when
    // given a destructuring lhs.
    testSame("let obj; const {a} = obj || {};");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("?");
    assertThat(aVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testConstDestructuringDeclarationWithUnknownTypeInExtendsClause() {
    disableTypeInfoValidation();
    testWarning(
        externs("var someUnknownExtern;"),
        srcs("const {Parent} = someUnknownExtern; class Child extends Parent {}"),
        warning(RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR));

    TypedVar parentVar = checkNotNull(globalScope.getVar("Parent"));
    assertType(parentVar.getType()).isUnknown();

    TypedVar childVar = checkNotNull(globalScope.getVar("Child"));
    FunctionType childType = childVar.getType().toMaybeFunctionType();
    JSType superclassCtor = childType.getSuperClassConstructor();
    assertType(superclassCtor).isNull();
  }

  @Test
  public void testConstDestructuringDeclarationWithUnknownPropertyInExtendsClause() {
    disableTypeInfoValidation();
    testWarning(
        externs("var /** !Object */ someUnknownExtern;"),
        srcs("const {Parent} = someUnknownExtern; class Child extends Parent {}"),
        warning(RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR));

    TypedVar parentVar = checkNotNull(globalScope.getVar("Parent"));
    assertType(parentVar.getType()).isUnknown();

    TypedVar childVar = checkNotNull(globalScope.getVar("Child"));
    FunctionType childType = childVar.getType().toMaybeFunctionType();
    JSType superclassCtor = childType.getSuperClassConstructor();
    assertType(superclassCtor).isNull();
  }

  // TODO(bradfordcsmith): Add Object rest test case.

  @Test
  public void testVarDeclarationNestedPatterns() {
    testSame(
        """
        var [
            {a: /** number */ a1},
            {a: /** string */ a2},
            ...{/** number */ length}
          ] = [{a: 1}, {a: '2'}, 1, 2, 3];
        """);

    TypedVar a1Var = checkNotNull(globalScope.getVar("a1"));
    assertType(a1Var.getType()).toStringIsEqualTo("number");
    assertThat(a1Var.isTypeInferred()).isFalse();

    TypedVar a2Var = checkNotNull(globalScope.getVar("a2"));
    assertType(a2Var.getType()).toStringIsEqualTo("string");
    assertThat(a2Var.isTypeInferred()).isFalse();

    TypedVar lengthVar = checkNotNull(globalScope.getVar("length"));
    assertType(lengthVar.getType()).toStringIsEqualTo("number");
    assertThat(lengthVar.isTypeInferred()).isFalse();
  }

  // The following testAssign* tests check that we never treat qualified names in destructuring
  // patterns as declared. CheckJSDoc will warn on those cases, so TypedScopeCreator just ignores
  // them. The only way to 'declare' a qualified name is:
  //    /** @type {number} */ a.b.c = rhs;

  @Test
  public void testAssignWithJSDocForObjPatWithOneVariable() {
    // Ignore the JSDoc on the assignment
    testSame("const ns = {}; (/** @type {number} */ {a: ns.a} = {a: 1});");

    TypedVar aVar = globalScope.getVar("ns.a");
    assertThat(aVar).isNull();
  }

  @Test
  public void testAssignWithJSDocForObjPatWithMultipleVariables() {
    // Ignore the JSDoc on the assignment
    testSame("const ns = {}; (/** @type {number} */ {a: ns.a, b: ns.b} = {a: 1});");

    TypedVar aVar = globalScope.getVar("ns.a");
    assertThat(aVar).isNull();
  }

  @Test
  public void testAssignObjPatNormalProp() {
    // CheckJSDoc will warn on the inline type annotation here, typechecking just ignores it.
    testSame("const ns = {}; ({a: /** number */ ns.a} = {a: 1});");

    TypedVar aVar = globalScope.getVar("ns.a");
    assertThat(aVar).isNull();
  }

  @Test
  public void testAssignObjPatComputedProp() {
    // CheckJSDoc will warn on the inline type annotation here, typechecking just ignores it.
    testSame("const ns = {}; ({['a']: /** number */ ns.a} = {a: 1});");

    TypedVar aVar = globalScope.getVar("ns.a");
    assertThat(aVar).isNull();
  }

  @Test
  public void testAssignArrayPatWithJSDocOnAssign() {
    testSame("const ns = {}; /** @type {number} */ [ ns.a ] = [1];");

    TypedVar aVar = globalScope.getVar("ns.a");
    assertThat(aVar).isNull();
  }

  @Test
  public void testAssignArrayPatWithQualifiedName() {
    // CheckJSDoc will warn on the inline type annotation here, typechecking just ignores it.
    testSame("const ns = {}; [ /** number */ ns.a ] = [1];");

    TypedVar aVar = globalScope.getVar("ns.a");
    assertThat(aVar).isNull();
  }

  @Test
  public void testAssignArrayPatWithQualifiedNameAndDefaultValue() {
    // CheckJSDoc will warn on the inline type annotation here, typechecking just ignores it.
    testSame("const ns = {}; [ /** number */ ns.a = 1 ] = [];");

    TypedVar aVar = globalScope.getVar("ns.a");
    assertThat(aVar).isNull();
  }

  @Test
  public void testForOfWithObjectPatVarDeclarationWithShorthand() {
    testSame("for (var {/** number */ a} of {}) {}");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testForOfWithArrayPatVarDeclaration() {
    testSame("for (var [/** number */ a] of []) {}");

    TypedVar aVar = checkNotNull(globalScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testCastOnLhsDoesntDeclareProperty() {
    testSame("const ns = {}; /** @type {null} */ (ns.a) = null;");

    assertThat(globalScope.getVar("ns.a")).isNull();
  }

  @Test
  public void testClassInstancePropertyInArrowFunction() {
    testSame(
        """
        class C {
          constructor() {
            (() => {
              /** @type {number} */
              this.size = 0;
            })();
            CTOR: 0;
          }
        }
        """);

    // Declare `this.size` in the enclosing constructor's scope.
    assertScope(getLabeledStatement("CTOR").enclosingScope.getParent())
        .declares("this.size")
        .directly();
  }

  @Test
  public void testObjectLiteralShadowingFnExpressionName() {
    testSame(
        """
        var obj = {
          key: function f() {
            var f = {x: 0};
            FN: 0;
          }
        };
        """);

    // Test that we declare 'f.x' on the function block scope, not the enclosing function scope,
    // which also contains `f`.
    assertScope(getLabeledStatement("FN").enclosingScope).declares("f.x").directly();
  }

  @Test
  public void testForOfWithObjectPatConstDeclarationShadowedInLoop() {
    testSame(
        """
        for (const {/** number */ a} of {}) {
          const /** string */ a = 'foo';
          IN_LOOP: a;
        }
        """);

    assertScope(globalScope).doesNotDeclare("a");
    TypedScope loopBlockScope = getLabeledStatement("IN_LOOP").enclosingScope;
    TypedScope loopInitializerScope = loopBlockScope.getParent();
    assertThat(loopInitializerScope).isNotEqualTo(globalScope);

    TypedVar aVarloopBlock = loopBlockScope.getVar("a");
    assertType(aVarloopBlock.getType()).toStringIsEqualTo("string");
    assertThat(aVarloopBlock.isTypeInferred()).isFalse();

    TypedVar aVarLoopInit = loopInitializerScope.getVar("a");
    assertType(aVarLoopInit.getType()).toStringIsEqualTo("number");
    assertThat(aVarLoopInit.isTypeInferred()).isFalse();
  }

  @Test
  public void testDeclarativelyUnboundVarsWithoutTypes() {
    testSame(
        """
        var uninitializedVar;
        let uninitializedLet;

        /** @type {?} */ var uninitializedVarWithType;
        /** @type {?} */ let uninitializedLetWithType;

        /** @typedef {number} */ var typedefVar;
        /** @typedef {number} */ let typedefLet;

        var initializedVar = 1;
        let initializedLet = 1;

        /** @type {number} */ var initializedVarWithType = 1;
        /** @type {number} */ let initializedLetWithType = 1;

        /** @const */ var CONST_VAR = 1;
        /** @const */ let CONST_LET = 1;
        const CONST = 1;

        /** @const {number} */ var   CONST_VAR_WITH_TYPE = 1;
        /** @const {number} */ let   CONST_LET_WITH_TYPE = 1;
        /** @type  {number} */ const CONST_WITH_TYPE     = 1;
        """);

    // After TypeInference, there should be no more variables in this set.
    assertThat(globalScope.getDeclarativelyUnboundVarsWithoutTypes()).isEmpty();

    assertThat(globalScope.getVar("uninitializedVar")).hasJSTypeThat().isUnknown();
    assertThat(globalScope.getVar("uninitializedLet")).hasJSTypeThat().isUnknown();
  }

  @Test
  public void testRestParameters() {
    testSame(
        """
        /** // preserve newlines
         * @param {string} str
         * @param {...number} nums
         */
        function doSomething(str, ...nums) {
          FUNCTION_BODY: 0;
        }
        """);
    TypedScope functionBodyScope = getLabeledStatement("FUNCTION_BODY").enclosingScope;
    assertScope(functionBodyScope)
        .declares("str")
        .onClosestContainerScope()
        .withTypeThat()
        .toStringIsEqualTo("string");
    assertScope(functionBodyScope)
        .declares("nums")
        .onClosestContainerScope()
        .withTypeThat()
        .toStringIsEqualTo("Array<number>");
  }

  @Test
  public void testRestObjectPatternParameters() {
    testSame(
        externs("/** @type {number} */ Array.prototype.length"),
        srcs(
            """
            /** // preserve newlines
             * @param {...string} strs
             */
            function doSomething(...{length}) {
              FUNCTION_BODY: 0;
            }
            """));
    TypedScope functionBodyScope = getLabeledStatement("FUNCTION_BODY").enclosingScope;
    assertScope(functionBodyScope)
        .declares("length")
        .onClosestContainerScope()
        .withTypeThat()
        .toStringIsEqualTo("number");
  }

  @Test
  public void testDefaultParameterFullJSDoc() {
    testSame(
        """
        /** // preserve newlines
         * @param {string=} str
         */
        function doSomething(str = '') {
          FUNCTION_BODY: 0;
        }
        """);
    TypedScope functionBodyScope = getLabeledStatement("FUNCTION_BODY").enclosingScope;
    assertScope(functionBodyScope)
        .declares("str")
        .onClosestContainerScope()
        .withTypeThat()
        // TODO(b/117162687): this should just be `string`
        .toStringIsEqualTo("(string|undefined)");
  }

  @Test
  public void testDefaultParameterInlineJSDoc() {
    testSame(
        """
         // preserve newlines
        function doSomething(/** string= */ str = '') {
          FUNCTION_BODY: 0;
        }
        """);
    TypedScope functionBodyScope = getLabeledStatement("FUNCTION_BODY").enclosingScope;
    assertScope(functionBodyScope)
        .declares("str")
        .onClosestContainerScope()
        .withTypeThat()
        // TODO(b/117162687): this should just be `string`
        .toStringIsEqualTo("(string|undefined)");
  }

  @Test
  public void testDefaultParameterNoJSDoc() {
    testSame(
        """
         // preserve newlines
        function doSomething(str = '') {
          FUNCTION_BODY: 0;
        }
        """);
    TypedScope functionBodyScope = getLabeledStatement("FUNCTION_BODY").enclosingScope;
    assertScope(functionBodyScope)
        .declares("str")
        .onClosestContainerScope()
        .withTypeThat()
        .toStringIsEqualTo("?");
  }

  @Test
  public void testDefaultParameterNoJSDocInferredToBeOptional() {
    testSame("function f(a = 3) {}");

    assertScope(globalScope)
        .declares("f")
        .withTypeThat()
        .toStringIsEqualTo("function(?=): undefined");
  }

  @Test
  public void testDefaultParameterConflictingJSDoc() {
    testSame(
        """
        /** // preserve newlines
         * @param {number=} str
         */
        function doSomething(/** string= */ str = '') {
          FUNCTION_BODY: 0;
        }
        """);
    TypedScope functionBodyScope = getLabeledStatement("FUNCTION_BODY").enclosingScope;
    assertScope(functionBodyScope)
        .declares("str")
        .onClosestContainerScope()
        .withTypeThat()
        // TODO(b/111523967): Should report an error when header and inline JSDoc types
        //     conflict.
        .toStringIsEqualTo("(number|undefined)");
  }

  @Test
  public void testDefaultParameterSetToUndefinedIsDeclaredAsPossiblyUndefined() {
    testSame(
        """
        /** @param {number=} num */
        function doSomething(num = undefined) {
          FUNCTION_BODY: 0;
        }
        """);
    TypedScope functionBodyScope = getLabeledStatement("FUNCTION_BODY").enclosingScope;
    assertScope(functionBodyScope)
        .declares("num")
        .onClosestContainerScope()
        .withTypeThat()
        .toStringIsEqualTo("(number|undefined)");
  }

  @Test
  public void testDefaultParameterFullJSDoc_setToUndefined() {
    testSame(
        """
        /** // preserve newlines
         * @param {(string|undefined)=} str // NOTE: we just drop the |undefined here
         */
        function doSomething(str = '') {
          FUNCTION_BODY: 0;
        }
        """);
    TypedScope functionBodyScope = getLabeledStatement("FUNCTION_BODY").enclosingScope;
    assertScope(functionBodyScope)
        .declares("str")
        .onClosestContainerScope()
        .withTypeThat()
        .toStringIsEqualTo("(string|undefined)");
  }

  @Test
  public void testDefaultParameterInferredNotUndefinedInCallback() {
    testSame(
        """
        function takesCallback(/** function(string=): ? */ cb) {}

        takesCallback((str = '') => {})
        """);

    TypedVar strVar = checkNotNull(lastFunctionScope.getVar("str"));
    assertType(strVar.getType()).isString();
    assertThat(strVar.isTypeInferred()).isTrue();

    JSType strType = findNameType("str", globalScope);
    assertType(strType).isString(); // the actual parameter is also typed as not-undefined
  }

  @Test
  public void testDefaultParameterInferredNotUndefinedInCallbackButLaterSetToUndefined() {
    testSame(
        """
        function takesCallback(/** function(string=): ? */ cb) {}

        takesCallback((str = '') => {
          str = undefined;
        })
        """);

    TypedVar strVar = checkNotNull(lastFunctionScope.getVar("str"));
    assertType(strVar.getType()).toStringIsEqualTo("(string|undefined)");
    assertThat(strVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testDefaultDestructuringParameterFullJSDoc() {
    testSame(
        """
        /** @param {{str: (string|undefined)}=} data */
        function f({str = ''} = {}) {
          FUNCTION_BODY: 0;
        }
        """);
    TypedScope functionBodyScope = getLabeledStatement("FUNCTION_BODY").enclosingScope;
    assertScope(functionBodyScope)
        .declares("str")
        .onClosestContainerScope()
        .withTypeThat()
        .toStringIsEqualTo("(string|undefined)");
  }

  @Test
  public void testDestructuringParameterWithNoJSDoc() {
    testSame("function f([x, y], {z}) {}");

    TypedVar fVar = checkNotNull(globalScope.getVar("f"));
    assertType(fVar.getType()).toStringIsEqualTo("function(?, ?): undefined");
    assertThat(fVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testArrayPatternParameterWithFullJSDoc() {
    testSame(
        """
        /**
         * @param {string} x
         * @param {!Iterable<number>} arr
         */
        function f(x, [y]) {}
        """);

    TypedVar fVar = checkNotNull(globalScope.getVar("f"));
    assertType(fVar.getType())
        .toStringIsEqualTo("function(string, Iterable<number,?,?>): undefined");
    assertThat(fVar.isTypeInferred()).isFalse();

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("string");
    assertThat(xVar.isTypeInferred()).isFalse();

    TypedVar yVar = checkNotNull(lastFunctionScope.getVar("y"));
    assertType(yVar.getType()).toStringIsEqualTo("number");
    assertThat(yVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testArrayPatternParameterWithRestWithFullJSDoc() {
    testSame("/** @param {!Iterable<number>} arr */ function f([x, ...y]) {}");

    TypedVar fVar = checkNotNull(globalScope.getVar("f"));
    assertType(fVar.getType()).toStringIsEqualTo("function(Iterable<number,?,?>): undefined");
    assertThat(fVar.isTypeInferred()).isFalse();

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("number");
    assertThat(xVar.isTypeInferred()).isFalse();

    TypedVar yVar = checkNotNull(lastFunctionScope.getVar("y"));
    assertType(yVar.getType()).toStringIsEqualTo("Array<number>");
    assertThat(yVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testArrayPatternParameterWithDefaultName() {
    testSame(
        """
        const /** !Iterable<number> */ iter = [0];

        /** @param {!Iterable<number>=} p */
        function f([x] = iter) {
        }
        """);

    // JSType on the actual node
    JSType xType = findNameType("x", lastFunctionScope);
    assertType(xType).isNumber();

    // JSType in the scope
    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("number");
    assertThat(xVar.isTypeInferred()).isTrue();

    // JSType on the array pattern
    JSType arrayPatternType = findTokenType(Token.ARRAY_PATTERN, globalScope);
    assertType(arrayPatternType).toStringIsEqualTo("Iterable<number,?,?>");
  }

  @Test
  public void testArrayDestructuringPatternParameterWithDefaultArray() {
    testSame(
        """
        /** @param {!Iterable<number>=} p */
        function f([x] = [0]) {}
        """);

    JSType xType = findNameType("x", lastFunctionScope);
    // This is unknown because we infer `[0]` to have type `!Array<?>`
    assertType(xType).isUnknown();
  }

  @Test
  public void testObjectPatternParameterWithFullJSDoc() {
    testSame("/** @param {{a: string, b: number}} arr */ function f({a, b}) {}");

    TypedVar fVar = checkNotNull(globalScope.getVar("f"));
    assertType(fVar.getType())
        .toStringIsEqualTo("function({\n  a: string,\n  b: number\n}): undefined");
    assertThat(fVar.isTypeInferred()).isFalse();

    TypedVar aVar = checkNotNull(lastFunctionScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("string");
    assertThat(aVar.isTypeInferred()).isFalse();

    TypedVar bVar = checkNotNull(lastFunctionScope.getVar("b"));
    assertType(bVar.getType()).toStringIsEqualTo("number");
    assertThat(bVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testObjectPatternParameterWithUnknownPropertyWithFullJSDoc() {
    testSame("/** @param {{a: string}} arr */ function f({a, b}) {}");

    TypedVar fVar = checkNotNull(globalScope.getVar("f"));
    assertType(fVar.getType()).toStringIsEqualTo("function({a: string}): undefined");
    assertThat(fVar.isTypeInferred()).isFalse();

    TypedVar aVar = checkNotNull(lastFunctionScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("string");
    assertThat(aVar.isTypeInferred()).isFalse();

    TypedVar bVar = checkNotNull(lastFunctionScope.getVar("b"));
    assertType(bVar.getType()).toStringIsEqualTo("?");
    assertThat(bVar.isTypeInferred()).isTrue();
  }

  /**
   * Note: these tests expect the parameters to have inferred, not declared, types because of the
   * order in which scope creation & named type resolution happens.
   *
   * <p>See also b/118710352
   */
  @Test
  public void testObjectPatternParameter_withUnresolvedForwardDeclaredType_isInferred() {
    // Note that this only emits one UNRECOGNIZED_TYPE_ERROR for SomeUnknownName. We don't emit
    // an error for SomeUnknownName#a, as that would be redundant.
    test(
        srcs(
            """
            goog.forwardDeclare('SomeUnknownName');
            /** @param {!SomeUnknownName} obj */ function f({a}) {}
            """),
        warning(RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR));

    TypedVar aVar = checkNotNull(lastFunctionScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("?");
    assertThat(aVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testObjectPatternParameterWithFullJSDoc_withMissingProperty_isInferred() {
    testSame(
        """
        class SomeName {}
        /** @param {!SomeName} obj */ function f({a}) {}
        """);

    TypedVar aVar = checkNotNull(lastFunctionScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("?");
    // we still consider this a declared type
    assertThat(aVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testNestedObjectPatternParameter_withNamedType() {
    testSame(
        """
        /** @param {!SomeName} obj */ function f({data: {a}}) { A: a; }
        class SomeOtherName {
          constructor() {
            /** @type {number} */
            this.a;
          }
        }
        class SomeName {
          constructor() {
            /** @type {!SomeOtherName} */
            this.data;
          }
        }
        """);

    // This variable becomes inferred, even though SomeName is declared before it is referenced,
    // just because the SomeName in the @param is still unresolved at type scope creation time.
    TypedVar aVar = checkNotNull(getLabeledStatement("A").enclosingScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testTypecheckingTemplatizedTypesForObjectParameter() {
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function TemplatizedClass() {
          /** @const {T} */
          this.data;
        }
        /** @param {!TemplatizedClass<!SomeName>} obj */
        function f({data}) { A: data.a; }

        class SomeName {
          constructor() {
            /** @type {number} */
            this.a;
          }
        }
        """);

    LabeledStatement aStatement = getLabeledStatement("A");
    TypedVar dataVar = checkNotNull(aStatement.enclosingScope.getVar("data"));

    assertType(dataVar.getType()).toStringIsEqualTo("SomeName");
    assertType(aStatement.statementNode.getOnlyChild().getJSType()).toStringIsEqualTo("number");
  }

  @Test
  public void testTypecheckingAliasOfTypedefType_forObjectParameter() {
    testSame(
        """
        class Foo {
          constructor() {
            /** @type {number} */
            this.a;
          }
        }
        /** @typedef {!Foo} */
        var TypedefOfFoo;

        /** @param {!ns.TypedefOfFoo} obj */
        function f({a}) { A: a; }
        const ns = {};
        /** @const */
        ns.TypedefOfFoo = TypedefOfFoo;
        """);

    TypedVar aVar = checkNotNull(getLabeledStatement("A").enclosingScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testArrayPatternParameter_withNamedType() {
    testSame(
        """
        /** @param {!ASubclass} obj */ function f([a]) { A: a; }
        /**
         * @interface
         * @extends {Iterable<T>}
         * @template T
         */
        function TemplatizedClass() {
        }
        /** @interface @extends {TemplatizedClass<number>} */
        class ASubclass {}
        """);

    // Verify we get the correct type for 'a' after name resolution is complete
    TypedVar aVar = checkNotNull(getLabeledStatement("A").enclosingScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testObjectPatternParameter_withNamedType_andRest() {
    setAcceptedLanguage(LanguageMode.ECMASCRIPT_2018);
    testSame(
        """
        class SomeUnknownName {
          constructor() {
            /** @type {number} */
            this.data;
          }
        }
        /** @param {!SomeUnknownName} obj */ function f({...rest}) { REST: rest; }
        """);

    TypedVar aVar = checkNotNull(getLabeledStatement("REST").enclosingScope.getVar("rest"));
    assertType(aVar.getType()).toStringIsEqualTo("Object");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testMixedParametersWithFullJSDoc() {
    testSame(
        """
        /**
         * @param {string} x
         * @param {{a: !Iterable<number>}} obj
         * @param {!Iterable<{z: null}>} arr
         */
        function f(x, {a: [y]}, [{z}]) {}
        """);

    TypedVar fVar = checkNotNull(globalScope.getVar("f"));
    assertType(fVar.getType())
        .toStringIsEqualTo(
            "function(string, {a: Iterable<number,?,?>}, Iterable<{z: null},?,?>): undefined");
    assertThat(fVar.isTypeInferred()).isFalse();

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("string");
    assertThat(xVar.isTypeInferred()).isFalse();

    TypedVar yVar = checkNotNull(lastFunctionScope.getVar("y"));
    assertType(yVar.getType()).toStringIsEqualTo("number");
    assertThat(yVar.isTypeInferred()).isFalse();

    TypedVar zVar = checkNotNull(lastFunctionScope.getVar("z"));
    assertType(zVar.getType()).toStringIsEqualTo("null");
    assertThat(zVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testObjectPatternParameterWithComputedPropertyWithFullJSDoc() {
    testSame(
        """
        /**
         * @param {string} x
         * @param {!Object<string, number>} arr
         */
        function f(x, {['foobar' + 3]: a}) {}
        """);

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("string");
    assertThat(xVar.isTypeInferred()).isFalse();

    TypedVar aVar = checkNotNull(lastFunctionScope.getVar("a"));
    assertType(aVar.getType()).toStringIsEqualTo("number");
    assertThat(aVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testDestructuringParametersInIifeInfersType_withNameArguments() {
    testSame(
        """
        const /** {x:  number} */ data = {x: 3};
        const /** !Iterable<string> */ strings = ['foo', 'bar'];

        (function ({x}, [y]) {})(data, strings);
        """);

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("number");
    assertThat(xVar.isTypeInferred()).isTrue();

    TypedVar yVar = checkNotNull(lastFunctionScope.getVar("y"));
    assertType(yVar.getType()).toStringIsEqualTo("string");
    assertThat(yVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testDestructuringParametersInIifeInfersType_withLiteralArguments() {
    testSame("(function ({x}) {})({x: 3});");

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("number");
    assertThat(xVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testDestructuringParametersInIifeInfersType_withLiteralArgumentsAndDefaultValue() {
    testSame("(function ({x = 'bar' + 'baz'}) {})({x: true ? 3 : undefined});");

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("(number|string)");
    assertThat(xVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testDestructuringParametersInCallbackInfersType() {
    testSame(
        """
        function f(/** function({x: number}, !Iterable<string>) */ callback) {}

        f(function ({x}, [y]) {});
        """);

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("number");
    assertThat(xVar.isTypeInferred()).isTrue();

    TypedVar yVar = checkNotNull(lastFunctionScope.getVar("y"));
    assertType(yVar.getType()).toStringIsEqualTo("string");
    assertThat(yVar.isTypeInferred()).isTrue();
  }

  @Test
  public void testOutOfOrderJSDocForDestructuringParameter() {
    // Even though regular JSDoc parameters can be out of order, there's currently no way for
    // putting arbitrary orders on destructuring parameters.
    testWarning(
        """
        /**
         * @param {!Iterable<number>} arr
         * @param {string} x
         */
        function f(x, [y, z]) {}
        """,
        FunctionTypeBuilder.INEXISTENT_PARAM);

    TypedVar fVar = checkNotNull(globalScope.getVar("f"));
    // TODO(b/77597706): it would make more sense for this to be function(string, ?) instead
    assertType(fVar.getType()).toStringIsEqualTo("function(string, string): undefined");
    assertThat(fVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testObjectPatternParameterWithInlineJSDoc() {
    testSame("function f({/** number */ x}) {}");

    // TODO(b/112651122): infer that f takes {x: number}
    TypedVar fVar = checkNotNull(globalScope.getVar("f"));
    assertType(fVar.getType()).toStringIsEqualTo("function(?): undefined");
    assertThat(fVar.isTypeInferred()).isFalse();

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("number");
    assertThat(xVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testArrayPatternParameterWithInlineJSDoc() {
    testSame("function f([/** number */ x]) {}");

    // TODO(b/112651122): either forbid this case or infer that f takes an !Iterable<number>
    TypedVar fVar = checkNotNull(globalScope.getVar("f"));
    assertType(fVar.getType()).toStringIsEqualTo("function(?): undefined");
    assertThat(fVar.isTypeInferred()).isFalse();

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("number");
    assertThat(xVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testArrayPatternParametersWithDifferingInlineJSDoc() {
    testSame("function f([/** number */ x, /** string */ y]) {}");

    // TODO(b/112651122): forbid this case, as there's not a good way to type the function without
    // having tuple types.
    TypedVar fVar = checkNotNull(globalScope.getVar("f"));
    assertType(fVar.getType()).toStringIsEqualTo("function(?): undefined");
    assertThat(fVar.isTypeInferred()).isFalse();

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("number");
    assertThat(xVar.isTypeInferred()).isFalse();

    TypedVar yVar = checkNotNull(lastFunctionScope.getVar("y"));
    assertType(yVar.getType()).toStringIsEqualTo("string");
    assertThat(yVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testObjectPatternCanAliasUnionTypeProperty() {
    // tests that we can get properties off the union type `{objA|objB}` even though it's not
    // represented as an ObjectType. (this used to crash in TypedScopeCreator)
    testSame(
        """
        const ns = {};

        /** @typedef {{propA: number}} */
        let objA;
        /** @typedef {{propB: string}} */
        let objB;

        /** @type {objA|objB} */
        ns.ctor;

        const {propA, propB} = ns.ctor;
        """);

    TypedVar propAVar = checkNotNull(globalScope.getVar("propA"));
    assertType(propAVar.getType()).isEqualTo(getNativeType(JSTypeNative.NUMBER_TYPE));
    assertThat(propAVar.isTypeInferred()).isFalse();

    TypedVar propBVar = checkNotNull(globalScope.getVar("propB"));
    assertType(propBVar.getType()).isEqualTo(getNativeType(JSTypeNative.STRING_TYPE));
    assertThat(propBVar.isTypeInferred()).isFalse();
  }

  @Test
  public void testConstNestedObjectPatternInArrayPattern() {
    // regression test for a TypedScopeCreator crash
    disableTypeInfoValidation();
    testSame(
        """
        const /** !Array<{b: string}> */ a = [{b: 'bbb'}];
        const [{b}] = a;
        """);

    TypedVar b = checkNotNull(globalScope.getVar("b"));
    assertType(b.getType()).isString();
    assertThat(b.isTypeInferred()).isTrue(); // b is inferred but not treated as declared
  }

  @Test
  public void testConstNestedObjectPatternWithComputedPropertyIsUnknown() {
    // regression test for a TypedScopeCreator crash
    disableTypeInfoValidation(); // fails on a['foo'].b = function() {};
    testSame(
        """
        const a = {};
        /** @const */ a['foo'] = {};
        /** @const */ a['foo'].b = function() {};

        const {['foo']: {b}} = a;
        """);

    TypedVar b = checkNotNull(globalScope.getVar("b"));
    assertType(b.getType()).isUnknown();
    assertThat(b.isTypeInferred()).isTrue();
  }

  @Test
  public void testStubProperty() {
    testSame("function Foo() {}; Foo.bar;");
    ObjectType foo = (ObjectType) globalScope.getVar("Foo").getType();
    assertThat(foo.hasProperty("bar")).isFalse();
    assertType(foo.getPropertyType("bar")).isEqualTo(registry.getNativeType(UNKNOWN_TYPE));
  }

  @Test
  public void testConstructorProperty() {
    testSame("var foo = {}; /** @constructor */ foo.Bar = function() {};");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertThat(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.isPropertyTypeInferred("Bar")).isFalse();

    JSType fooBar = foo.getPropertyType("Bar");
    assertThat(fooBar.toString()).isEqualTo("(typeof foo.Bar)");
  }

  @Test
  public void testPrototypePropertyMethodWithoutAnnotation() {
    // TODO(b/154044898): re-enable type info validation
    disableTypeInfoValidation();
    testSame(
        """
        var Foo = function Foo() {};
        var proto = Foo.prototype = {
           bar: function(a, b){}
        };
        proto.baz = function(c) {};
        (function() { proto.baz = function() {}; })();
        """);
    ObjectType foo = (ObjectType) findNameType("Foo", globalScope);
    assertThat(foo.hasProperty("prototype")).isTrue();

    ObjectType fooProto = (ObjectType) foo.getPropertyType("prototype");
    assertThat(fooProto.hasProperty("bar")).isTrue();
    assertThat(fooProto.getPropertyType("bar").toString()).isEqualTo("function(?, ?): undefined");

    assertThat(fooProto.hasProperty("baz")).isTrue();
    assertThat(fooProto.getPropertyType("baz").toString()).isEqualTo("function(?): undefined");
  }

  @Test
  public void testEnumProperty() {
    testSame("var foo = {}; /** @enum */ foo.Bar = {XXX: 'xxx'};");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertThat(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.isPropertyTypeInferred("Bar")).isFalse();
    assertThat(foo.isPropertyTypeDeclared("Bar")).isTrue();

    JSType fooBar = foo.getPropertyType("Bar");
    assertThat(fooBar.toString()).isEqualTo("enum{foo.Bar}");
  }

  @Test
  public void testInferredProperty1() {
    testSame("var foo = {}; foo.Bar = 3;");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertWithMessage(foo.toString()).that(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.getPropertyType("Bar").toString()).isEqualTo("number");
    assertThat(foo.isPropertyTypeInferred("Bar")).isTrue();
  }

  @Test
  public void testInferredProperty1a() {
    testSame("var foo = {}; /** @type {number} */ foo.Bar = 3;");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertWithMessage(foo.toString()).that(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.getPropertyType("Bar").toString()).isEqualTo("number");
    assertThat(foo.isPropertyTypeInferred("Bar")).isFalse();
  }

  @Test
  public void testInferredProperty2() {
    testSame("var foo = { Bar: 3 };");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertWithMessage(foo.toString()).that(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.getPropertyType("Bar").toString()).isEqualTo("number");
    assertThat(foo.isPropertyTypeInferred("Bar")).isTrue();
  }

  @Test
  public void testInferredProperty2b() {
    testSame("var foo = { /** @type {number} */ Bar: 3 };");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertWithMessage(foo.toString()).that(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.getPropertyType("Bar").toString()).isEqualTo("number");
    assertThat(foo.isPropertyTypeInferred("Bar")).isFalse();
  }

  @Test
  public void testInferredProperty2c() {
    testSame("var foo = { /** @return {number} */ Bar: 3 };");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertWithMessage(foo.toString()).that(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.getPropertyType("Bar").toString()).isEqualTo("function(): number");
    assertThat(foo.isPropertyTypeInferred("Bar")).isFalse();
  }

  @Test
  public void testInferredProperty3() {
    testSame("var foo = { /** @type {number} */ get Bar() { return 3 } };");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertWithMessage(foo.toString()).that(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.getPropertyType("Bar").toString()).isEqualTo("?");
    assertThat(foo.isPropertyTypeInferred("Bar")).isTrue();
  }

  @Test
  public void testInferredProperty4() {
    testSame("var foo = { /** @type {number} */ set Bar(a) {} };");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertWithMessage(foo.toString()).that(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.getPropertyType("Bar").toString()).isEqualTo("?");
    assertThat(foo.isPropertyTypeInferred("Bar")).isTrue();
  }

  @Test
  public void testInferredProperty5() {
    testSame("var foo = { /** @return {number} */ get Bar() { return 3 } };");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertWithMessage(foo.toString()).that(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.getPropertyType("Bar").toString()).isEqualTo("number");
    assertThat(foo.isPropertyTypeInferred("Bar")).isFalse();
  }

  @Test
  public void testInferredProperty6() {
    testSame("var foo = { /** @param {number} a */ set Bar(a) {} };");
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertWithMessage(foo.toString()).that(foo.hasProperty("Bar")).isTrue();
    assertThat(foo.getPropertyType("Bar").toString()).isEqualTo("number");
    assertThat(foo.isPropertyTypeInferred("Bar")).isFalse();
  }

  @Test
  public void testPrototypeInit() {
    testSame(
        """
        /** @constructor */ var Foo = function() {};
        Foo.prototype = {bar: 1}; var foo = new Foo();
        """);
    ObjectType foo = (ObjectType) findNameType("foo", globalScope);
    assertThat(foo.hasProperty("bar")).isTrue();
    assertThat(foo.getPropertyType("bar").toString()).isEqualTo("number");
    assertThat(foo.isPropertyTypeInferred("bar")).isTrue();
  }

  @Test
  public void testBogusPrototypeInit() {
    // This used to cause a compiler crash.
    testSame(
        """
        goog.F = {}; /** @const */ goog.F.prototype = {};
        /** @constructor */ goog.F = function() {};
        """);
  }

  @Test
  public void testInferredPrototypeProperty1() {
    testSame(
        """
        /** @constructor */ var Foo = function() {};
        Foo.prototype.bar = 1; var x = new Foo();
        """);

    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.hasProperty("bar")).isTrue();
    assertThat(x.getPropertyType("bar").toString()).isEqualTo("number");
    assertThat(x.isPropertyTypeInferred("bar")).isTrue();
  }

  @Test
  public void testInferredPrototypeProperty2() {
    testSame(
        """
        /** @constructor */ var Foo = function() {};
        Foo.prototype = {bar: 1}; var x = new Foo();
        """);

    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.hasProperty("bar")).isTrue();
    assertThat(x.getPropertyType("bar").toString()).isEqualTo("number");
    assertThat(x.isPropertyTypeInferred("bar")).isTrue();
  }

  @Test
  public void testEnum() {
    testSame("/** @enum */ var Foo = {BAR: 1}; var f = Foo;");
    ObjectType f = (ObjectType) findNameType("f", globalScope);
    assertThat(f.hasProperty("BAR")).isTrue();
    assertType(f.getPropertyType("BAR")).toStringIsEqualTo("Foo<number>");
    assertThat(f).isInstanceOf(EnumType.class);
  }

  @Test
  public void testLetEnum() {
    testSame("/** @enum */ let Foo = {BAR: 1}; let f = Foo;");
    ObjectType f = (ObjectType) findNameType("f", globalScope);
    assertThat(f.hasProperty("BAR")).isTrue();
    assertType(f.getPropertyType("BAR")).toStringIsEqualTo("Foo<number>");
    assertThat(f).isInstanceOf(EnumType.class);
  }

  @Test
  public void testConstEnum() {
    testSame("/** @enum */ const Foo = {BAR: 1}; const f = Foo;");
    ObjectType f = (ObjectType) findNameType("f", globalScope);
    assertThat(f.hasProperty("BAR")).isTrue();
    assertType(f.getPropertyType("BAR")).toStringIsEqualTo("Foo<number>");
    assertThat(f).isInstanceOf(EnumType.class);
  }

  @Test
  public void testEnumElement() {
    testSame("/** @enum */ var Foo = {BAR: 1}; var f = Foo;");
    TypedVar bar = globalScope.getVar("Foo.BAR");
    assertThat(bar).isNotNull();
    assertType(bar.getType()).toStringIsEqualTo("Foo<number>");
  }

  @Test
  public void testLetEnumElement() {
    testSame("/** @enum */ let Foo = {BAR: 1}; let f = Foo;");
    TypedVar bar = globalScope.getVar("Foo.BAR");
    assertThat(bar).isNotNull();
    assertType(bar.getType()).toStringIsEqualTo("Foo<number>");
  }

  @Test
  public void testConstEnumElement() {
    testSame("/** @enum */ const Foo = {BAR: 1}; const f = Foo;");
    TypedVar bar = globalScope.getVar("Foo.BAR");
    assertThat(bar).isNotNull();
    assertType(bar.getType()).toStringIsEqualTo("Foo<number>");
  }

  @Test
  public void testNamespacedEnum() {
    testSame(
        """
        goog.ui = {};
        /** @constructor */goog.ui.Zippy = function() {};
        /** @enum{string} */goog.ui.Zippy.EventType = { TOGGLE: 'toggle' };
        var x = goog.ui.Zippy.EventType;
        var y = goog.ui.Zippy.EventType.TOGGLE;
        """);

    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.isEnumType()).isTrue();
    assertThat(x.hasProperty("TOGGLE")).isTrue();
    assertThat(x.getReferenceName()).isEqualTo("enum{goog.ui.Zippy.EventType}");

    ObjectType y = (ObjectType) findNameType("y", globalScope);
    assertThat(y.isSubtypeOf(getNativeType(STRING_TYPE))).isTrue();
    assertThat(y.isEnumElementType()).isTrue();
    assertThat(y.getReferenceName()).isEqualTo("goog.ui.Zippy.EventType");
  }

  @Test
  public void testGlobalTypedefs1() {
    testSame(
        """
         // preserve newlines
        /** @typedef {number} */
        var VarTypedef;
        {
          /** @typedef {number} */
          var VarTypedefInBlock; // still global despite enclosing block
        }
        /** @typedef {number} */
        let LetTypedef;
        // TODO(bradfordcsmith): We should probably disallow @typedef on const.
        /** @typedef {number} */
        const ConstTypedef = undefined;
        """);
    assertType(registry.getGlobalType("VarTypedef")).isNumber();
    assertType(registry.getGlobalType("VarTypedefInBlock")).isNumber();
    assertType(registry.getGlobalType("LetTypedef")).isNumber();
    assertType(registry.getGlobalType("ConstTypedef")).isNumber();
  }

  @Test
  public void testGlobalTypedefs2() {
    testSame(
        """
         // preserve newlines
        {
          /** @typedef {number} */
          var VarTypedefInBlock; // still global despite enclosing block
        }
        """);
  }

  @Test
  public void testLocalTypedefs1() {
    testSame(
        """
         // preserve newlines
        function f() {
          /** @typedef {number} */
          var VarTypedefInFunc;
          /** @typedef {number} */
          let LetTypedefInFunc;
          /** @typedef {number} */
        // TODO(bradfordcsmith): We should probably disallow @typedef on const.
          const ConstTypedefInFunc = undefined;
        }
        """);
    TypedScope fnRoot = lastLocalScope;
    assertType(registry.getType(fnRoot, "VarTypedefInFunc")).isNumber();
    assertType(registry.getType(fnRoot, "LetTypedefInFunc")).isNumber();
    assertType(registry.getType(fnRoot, "ConstTypedefInFunc")).isNumber();
  }

  @Test
  public void testLocalTypedefs2() {
    testSame(
        """
         // preserve newlines
        {
          /** @typedef {number} */
          let LetTypedefInBlock;
          /** @typedef {number} */
          const ConstTypedefInBlock = undefined;
        }
        """);
    TypedScope fnRoot = lastLocalScope;

    assertType(registry.getType(fnRoot, "LetTypedefInBlock")).isNumber();
    assertType(registry.getType(fnRoot, "ConstTypedefInBlock")).isNumber();
  }

  @Test
  public void testEnumAlias() {
    testSame(
        """
        /** @enum */ var Foo = {BAR: 1};
        /** @enum */ var FooAlias = Foo; var f = FooAlias;
        """);

    assertThat(registry.getType(null, "FooAlias").toString()).isEqualTo("Foo<number>");
    assertType(registry.getType(null, "Foo")).isEqualTo(registry.getType(null, "FooAlias"));

    ObjectType f = (ObjectType) findNameType("f", globalScope);
    assertThat(f.hasProperty("BAR")).isTrue();
    assertThat(f.getPropertyType("BAR").toString()).isEqualTo("Foo<number>");
    assertThat(f).isInstanceOf(EnumType.class);
  }

  @Test
  public void testNamespacesEnumAlias() {
    testSame(
        """
        /** @enum */ goog.Foo = {BAR: 1};
        /** @enum */ goog.FooAlias = goog.Foo;
        """);

    assertThat(registry.getType(null, "goog.FooAlias").toString()).isEqualTo("goog.Foo<number>");
    assertType(registry.getType(null, "goog.FooAlias"))
        .isEqualTo(registry.getType(null, "goog.Foo"));
  }

  @Test
  public void testCollectedFunctionStub() {
    testSame(
        """
        /** @constructor */ function f() {
          /** @return {number} */ this.foo;
        }
        var x = new f();
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("function(this:f): number");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
  }

  @Test
  public void testCollectedFunctionStubLocal() {
    testSame(
        """
        (function() {
        /** @constructor */ function f() {
          /** @return {number} */ this.foo;
        }
        var x = new f();
        });
        """);
    ObjectType x = (ObjectType) findNameType("x", lastLocalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("function(this:f): number");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
  }

  @Test
  public void testNamespacedFunctionStub() {
    testSame("/** @param {number} x */ goog.foo;");

    ObjectType goog = (ObjectType) findNameType("goog", globalScope);
    assertThat(goog.hasProperty("foo")).isTrue();
    assertThat(goog.getPropertyType("foo").toString()).isEqualTo("function(number): ?");
    assertThat(goog.isPropertyTypeDeclared("foo")).isTrue();

    assertType(goog.getPropertyType("foo")).isEqualTo(globalScope.getVar("goog.foo").getType());
  }

  @Test
  public void testNamespacedFunctionStubLocal() {
    testSame(
        """
        (function() {
        var goog = {};
        /** @param {number} x */ goog.foo;
        });
        """);

    ObjectType goog = (ObjectType) findNameType("goog", lastLocalScope);
    assertThat(goog.hasProperty("foo")).isTrue();
    assertThat(goog.getPropertyType("foo").toString()).isEqualTo("function(number): ?");
    assertThat(goog.isPropertyTypeDeclared("foo")).isTrue();

    assertType(goog.getPropertyType("foo")).isEqualTo(lastLocalScope.getVar("goog.foo").getType());
  }

  @Test
  public void testCollectedCtorProperty1() {
    testSame(
        """
        /** @constructor */ function f() {
          /** @type {number} */ this.foo = 3;
        }
        var x = new f();
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("number");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testCollectedCtorProperty2() {
    testSame(
        """
        /** @constructor */ function f() {
          /** @const {number} */ this.foo = 3;
        }
        var x = new f();
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("number");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testCollectedCtorProperty3() {
    testSame(
        """
        /** @constructor */ function f() {
          /** @const */ this.foo = 3;
        }
        var x = new f();
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("number");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testCollectedCtorProperty5() {
    testSame(
        """
        /** @constructor */ function f() {
          /** @const */ this.foo = 'abc' + 'def';
        }
        var x = new f();
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("string");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testCollectedCtorBigIntProperty() {
    testSame(
        """
        /** @constructor */ function f() {
          /** @const */ this.foo = 1n + 2n;
        }
        var x = new f();
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("bigint");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testCollectedCtorProperty9() {
    testSame(
        """
        /** @constructor */ function f() {}
        f.prototype.init_f = function() {
          /** @const */ this.FOO = 'abc';
        };
        var x = new f();
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("FOO")).isTrue();
    assertThat(x.getPropertyType("FOO").toString()).isEqualTo("string");
    assertThat(x.isPropertyTypeInferred("FOO")).isFalse();
    assertThat(x.isPropertyTypeDeclared("FOO")).isTrue();
  }

  @Test
  public void testCollectedCtorProperty10() {
    testSame(
        """
        /** @constructor */ function f() {}
        f.prototype.init_f = function() {
          /** @const */ this.foo = new String();
        };
        var x = new f();
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("String");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testCollectedCtorProperty11() {
    testSame(
        """
        /** @constructor */ function f() {}
        f.prototype.init_f = function() {
          /** @const */ this.foo = [];
        };
        var x = new f();
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("Array");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testCollectedCtorProperty12() {
    testSame(
        externs("/** @type {?} */ let unknown;"),
        srcs(
            """
            /** @constructor */ function f() {}
            f.prototype.init_f = function() {
              /** @const */ this.foo = !!unknown;
            };
            var x = new f();
            """));
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("boolean");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testCollectedCtorProperty13() {
    testSame(
        externs("/** @type {?} */ let unknown;"),
        srcs(
            """
            /** @constructor */ function f() {}
            f.prototype.init_f = function() {
              /** @const */ this.foo = +unknown;
            };
            var x = new f();
            """));
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("number");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testCollectedCtorProperty14() {
    testSame(
        externs("/** @type {?} */ let unknown;"),
        srcs(
            """
            /** @constructor */ function f() {}
            f.prototype.init_f = function() {
              /** @const */ this.foo = unknown + '';
            };
            var x = new f();
            """));
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("string");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testCollectedCtorProperty15() {
    testSame(
        """
        /**
         * @constructor
         * @param {string} a
         */
         function f(a) {
          /** @const */ this.foo = a;
        };
        var x = new f();
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("f");
    assertThat(x.hasProperty("foo")).isTrue();
    assertThat(x.getPropertyType("foo").toString()).isEqualTo("string");
    assertThat(x.isPropertyTypeInferred("foo")).isFalse();
    assertThat(x.isPropertyTypeDeclared("foo")).isTrue();
  }

  @Test
  public void testPropertyOnUnknownSuperClass1() {
    testWarning(
        """
        var goog = this.foo();
        /** @constructor\s
         * @extends {goog.Unknown} */
        function Foo() {}
        Foo.prototype.bar = 1;
        var x = new Foo();
        """,
        RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("Foo");
    assertThat(x.getImplicitPrototype().hasOwnProperty("bar")).isTrue();
    assertThat(x.getPropertyType("bar").toString()).isEqualTo("?");
    assertThat(x.isPropertyTypeInferred("bar")).isTrue();
  }

  @Test
  public void testPropertyOnUnknownSuperClass2() {
    testWarning(
        """
        var goog = this.foo();
        /** @constructor\s
         * @extends {goog.Unknown} */
        function Foo() {}
        Foo.prototype = {bar: 1};
        var x = new Foo();
        """,
        RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("Foo");
    assertThat(x.getImplicitPrototype().toString()).isEqualTo("Foo.prototype");
    assertThat(x.getImplicitPrototype().hasOwnProperty("bar")).isTrue();
    assertThat(x.getPropertyType("bar").toString()).isEqualTo("?");
    assertThat(x.isPropertyTypeInferred("bar")).isTrue();
  }

  @Test
  public void testSubBeforeSuper1() {
    testSame(
        """
        /** @interface
         * @extends {MidI} */
        function LowI() {}
        /** @interface
         * @extends {HighI} */
        function MidI() {}
        /** @interface */
        function HighI() {}
        """);
  }

  @Test
  public void testSubBeforeSuper2() {
    testSame(
        """
        /** @constructor
         * @extends {MidI} */
        function LowI() {}
        /** @constructor
         * @extends {HighI} */
        function MidI() {}
        /** @constructor */
        function HighI() {}
        """);
  }

  @Test
  public void testMethodBeforeFunction1() {
    // Adding a method to a hoisted constructor before its definition doesn't typecheck correctly.
    // Technically this code is valid JS but the TypedScopeCreator doesn't model function hoisting.
    // This is because hoisting causes a lot of new NamedTypes to appear, and NamedTypes are
    // generally buggy.
    testSame(
        """
        var y = Window.prototype;
        Window.prototype.alert = function(message) {};
        /** @constructor */ function Window() {}
        var window = new Window();\s
        var x = window;
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("Window");
    assertThat(x.getImplicitPrototype().hasOwnProperty("alert")).isTrue();
    assertThat(x.getPropertyType("alert").toString()).isEqualTo("function(?): undefined");
    assertThat(x.isPropertyTypeDeclared("alert")).isTrue();

    ObjectType y = (ObjectType) findNameType("y", globalScope);
    assertThat(y.getPropertyType("alert").toString()).isEqualTo("function(?): undefined");
  }

  @Test
  public void testMethodBeforeFunction2() {
    testSame(
        """
        var y = Window.prototype;
        Window.prototype = {alert: function(message) {}};
        /** @constructor */ function Window() {}
        var window = new Window();\s
        var x = window;
        """);
    ObjectType x = (ObjectType) findNameType("x", globalScope);
    assertThat(x.toString()).isEqualTo("Window");
    assertThat(x.getImplicitPrototype().hasOwnProperty("alert")).isTrue();
    assertThat(x.getPropertyType("alert").toString()).isEqualTo("function(?): undefined");
    assertThat(x.isPropertyTypeDeclared("alert")).isFalse();

    ObjectType y = (ObjectType) findNameType("y", globalScope);
    assertType(y).withTypeOfProp("alert").isUnknown();
  }

  @Test
  public void testAddMethodsPrototypeTwoWays() {
    testSame(
        """
        /** @constructor */function A() {}
        A.prototype = {m1: 5, m2: true};
        A.prototype.m3 = 'third property!';
        var x = new A();
        """);

    ObjectType instanceType = (ObjectType) findNameType("x", globalScope);
    assertThat(instanceType.getPropertiesCount())
        .isEqualTo(getNativeObjectType(OBJECT_TYPE).getPropertiesCount() + 3);
    assertType(instanceType.getPropertyType("m1")).isEqualTo(getNativeType(NUMBER_TYPE));
    assertType(instanceType.getPropertyType("m2")).isEqualTo(getNativeType(BOOLEAN_TYPE));
    assertType(instanceType.getPropertyType("m3")).isEqualTo(getNativeType(STRING_TYPE));

    // Verify the prototype chain.
    // This is a special case where we want the anonymous object to
    // become a prototype.
    assertThat(instanceType.hasOwnProperty("m1")).isFalse();
    assertThat(instanceType.hasOwnProperty("m2")).isFalse();
    assertThat(instanceType.hasOwnProperty("m3")).isFalse();

    ObjectType proto1 = instanceType.getImplicitPrototype();
    assertThat(proto1.hasOwnProperty("m1")).isTrue();
    assertThat(proto1.hasOwnProperty("m2")).isTrue();
    assertThat(proto1.hasOwnProperty("m3")).isTrue();

    ObjectType proto2 = proto1.getImplicitPrototype();
    assertThat(proto2.hasProperty("m1")).isFalse();
    assertThat(proto2.hasProperty("m2")).isFalse();
    assertThat(proto2.hasProperty("m3")).isFalse();

    // Note: it's not necessary for correctness reasons to declare m1 and m2,  but it's trickier to
    // avoid it without extra string maniuplation
    assertScope(globalScope).declares("A.prototype.m1");
    assertScope(globalScope).declares("A.prototype.m2");
    assertScope(globalScope).doesNotDeclare("A.prototype.m3");
  }

  @Test
  public void testInferredVar() {
    testSame("var x = 3; x = 'x'; x = true;");

    TypedVar x = globalScope.getVar("x");
    assertType(x.getType()).toStringIsEqualTo("(boolean|number|string)");
    assertThat(x.isTypeInferred()).isTrue();
  }

  @Test
  public void testInferredLet() {
    testSame("let x = 3; x = 'x'; x = true;");

    TypedVar x = globalScope.getVar("x");
    assertType(x.getType()).toStringIsEqualTo("(boolean|number|string)");
    assertThat(x.isTypeInferred()).isTrue();
  }

  @Test
  public void testInferredConst() {
    testSame("const x = 3;");

    TypedVar x = globalScope.getVar("x");
    assertType(x.getType()).isNumber();
    assertThat(x.isConst()).isTrue();
    // Although we did infer the type, we'll consider it effectively declared because the variable
    // was declared to be constant. This is consistent with the way we handle the @const annotation
    // on var declarations.
    assertThat(x.isTypeInferred()).isFalse();
  }

  @Test
  public void testInferredAnnotatedConst() {
    testSame("/** @const */ var x = 3;");

    TypedVar x = globalScope.getVar("x");
    assertType(x.getType()).isNumber();
    assertThat(x.isConst()).isFalse();
    assertThat(x.isTypeInferred()).isFalse();
  }

  @Test
  public void testDeclaredVar() {
    testSame("/** @type {?number} */ var x = 3; var y = x;");

    assertScope(globalScope).declares("x").directly();
    TypedVar x = globalScope.getVar("x");
    assertType(x.getType()).toStringIsEqualTo("(null|number)");
    assertThat(x.isTypeInferred()).isFalse();

    JSType y = findNameType("y", globalScope);
    assertThat(y.toString()).isEqualTo("(null|number)");
  }

  @Test
  public void testDeclaredLet() {
    testSame("/** @type {?number} */ let x = 3; let y = x;");

    assertScope(globalScope).declares("x").directly();
    TypedVar x = globalScope.getVar("x");
    assertType(x.getType()).toStringIsEqualTo("(null|number)");
    assertThat(x.isTypeInferred()).isFalse();

    JSType y = findNameType("y", globalScope);
    assertThat(y.toString()).isEqualTo("(null|number)");
  }

  @Test
  public void testDeclaredConst() {
    testSame("/** @type {?number} */ const x = 3; const y = x;");

    assertScope(globalScope).declares("x").directly();
    TypedVar x = globalScope.getVar("x");
    assertType(x.getType()).toStringIsEqualTo("(null|number)");
    assertThat(x.isTypeInferred()).isFalse();
    assertThat(x.isConst()).isTrue();

    JSType y = findNameType("y", globalScope);
    assertThat(y.toString()).isEqualTo("(null|number)");
  }

  @Test
  public void testStructuralInterfaceMatchingOnInterface1() {
    testSame(
        """
        /** @record */ var I = function() {};
        /** @type {number} */ I.prototype.bar;
        I.prototype.baz = function(){};
        """);

    TypedVar i = globalScope.getVar("I");
    assertThat(i.getType().toString()).isEqualTo("(typeof I)");
    assertThat(i.getType().isInterface()).isTrue();
    assertThat(i.getType().isFunctionType()).isTrue();
    assertThat(i.getType().toMaybeFunctionType().isStructuralInterface()).isTrue();
  }

  @Test
  public void testStructuralInterfaceMatchingOnInterface2() {
    testSame(
        """
        /** @interface */ var I = function() {};
        /** @type {number} */ I.prototype.bar;
        I.prototype.baz = function(){};
        """);

    TypedVar i = globalScope.getVar("I");
    assertThat(i.getType().toString()).isEqualTo("(typeof I)");
    assertThat(i.getType().isInterface()).isTrue();
    assertThat(i.getType().isFunctionType()).isTrue();
    assertThat(i.getType().toMaybeFunctionType().isStructuralInterface()).isFalse();
  }

  @Test
  public void testStructuralInterfaceMatchingOnInterface3() {
    testSame(
        """
        /** @interface */ var I = function() {};
        /** @type {number} */ I.prototype.bar;
        /** @record */ I.prototype.baz = function() {};
        """);

    TypedVar iPrototype = globalScope.getVar("I.prototype");
    JSType baz = iPrototype.getType().findPropertyType("baz");
    assertThat(baz.isInterface()).isTrue();
    assertThat(baz.isFunctionType()).isTrue();
    assertThat(baz.toMaybeFunctionType().isStructuralInterface()).isTrue();
  }

  @Test
  public void testStructuralInterfaceMatchingOnInterface4() {
    testSame(
        """
        /** @interface */ var I = function() {};
        /** @type {number} */ I.prototype.bar;
        /** @interface */ I.prototype.baz = function() {};
        """);

    TypedVar iPrototype = globalScope.getVar("I.prototype");
    JSType baz = iPrototype.getType().findPropertyType("baz");
    assertThat(baz.isInterface()).isTrue();
    assertThat(baz.isFunctionType()).isTrue();
    assertThat(baz.toMaybeFunctionType().isStructuralInterface()).isFalse();
  }

  @Test
  public void testStructuralInterfaceMatchingOnInterface5() {
    testSame(
        """
        /** @constructor */ var C = function() {};
        /** @type {number} */ C.prototype.bar;
        /** @record */ C.prototype.baz = function() {};
        var c = new C(); var cbaz = c.baz;
        """);

    TypedVar cBaz = globalScope.getVar("cbaz");
    assertThat(cBaz.getType().isFunctionType()).isTrue();
    assertThat(cBaz.getType().toMaybeFunctionType().isStructuralInterface()).isTrue();
  }

  @Test
  public void testStructuralInterfaceMatchingOnInterface6() {
    testSame(
        """
        /** @constructor */ var C = function() {};
        /** @type {number} */ C.prototype.bar;
        /** @interface */ C.prototype.baz = function() {};
        var c = new C(); var cbaz = c.baz;
        """);

    TypedVar cBaz = globalScope.getVar("cbaz");
    assertThat(cBaz.getType().isFunctionType()).isTrue();
    assertThat(cBaz.getType().toMaybeFunctionType().isStructuralInterface()).isFalse();
  }

  @Test
  public void testStructuralInterfaceMatchingOnInterface_symbolProps() {
    testSame(
        externs(
            new TestExternsBuilder().addIterable().build(),
            """
            /** @const {symbol} */
            Symbol.number;
            /** @const {symbol} */
            Symbol.string;
            """),
        srcs(
            """
            /** @record */
            class I {
              constructor() {
                /** @type {string} */
                this[Symbol.string];
              }
              /** @type {number} */
              [Symbol.number];
            }
            """));

    FunctionType iCtor = globalScope.getVar("I").getType().assertFunctionType();
    assertType(iCtor).toStringIsEqualTo("(typeof I)");
    assertThat(iCtor.isStructuralInterface()).isTrue();
    KnownSymbolType stringSym =
        globalScope.getVar("Symbol.string").getType().toMaybeKnownSymbolType();
    KnownSymbolType numberSym =
        globalScope.getVar("Symbol.number").getType().toMaybeKnownSymbolType();

    assertType(iCtor.getInstanceType()).hasProperty(stringSym);
    assertType(iCtor.getPrototype()).hasProperty(numberSym);
  }

  @Test
  public void testPropertiesOnInterface() {
    testSame(
        """
        /** @interface */ var I = function() {};
        /** @type {number} */ I.prototype.bar;
        I.prototype.baz = function(){};
        """);

    TypedVar i = globalScope.getVar("I");
    assertThat(i.getType().toString()).isEqualTo("(typeof I)");
    assertThat(i.getType().isInterface()).isTrue();

    ObjectType iPrototype = (ObjectType) ((ObjectType) i.getType()).getPropertyType("prototype");
    assertThat(iPrototype.toString()).isEqualTo("I.prototype");
    assertThat(iPrototype.isFunctionPrototypeType()).isTrue();

    assertThat(iPrototype.getPropertyType("bar").toString()).isEqualTo("number");
    assertThat(iPrototype.getPropertyType("baz").toString())
        .isEqualTo("function(this:I): undefined");

    assertType(globalScope.getVar("I.prototype").getType()).isEqualTo(iPrototype);
  }

  @Test
  public void testPropertiesOnInterface2() {
    testSame(
        """
        /** @interface */ var I = function() {};
        I.prototype = {baz: function(){}};
        /** @type {number} */ I.prototype.bar;
        """);

    TypedVar i = globalScope.getVar("I");
    assertThat(i.getType().toString()).isEqualTo("(typeof I)");
    assertThat(i.getType().isInterface()).isTrue();

    ObjectType iPrototype = (ObjectType) ((ObjectType) i.getType()).getPropertyType("prototype");
    assertThat(iPrototype.toString()).isEqualTo("I.prototype");
    assertThat(iPrototype.isFunctionPrototypeType()).isTrue();

    assertThat(iPrototype.getPropertyType("bar").toString()).isEqualTo("number");

    assertThat(iPrototype.getPropertyType("baz").toString())
        .isEqualTo("function(this:I): undefined");

    assertThat(globalScope.getVar("I.prototype").getType()).isEqualTo(iPrototype);
  }

  // TODO(johnlenz): A syntax for stubs using object literals?

  @Test
  public void testStubsInExterns() {
    testSame(
        externs(
            """
            /** @constructor */ function Extern() {}
            Extern.prototype.bar;
            var e = new Extern(); e.baz;
            """),
        srcs(
            """
            /** @constructor */ function Foo() {}
            Foo.prototype.bar;
            var f = new Foo(); f.baz;
            """));

    ObjectType e = (ObjectType) globalScope.getVar("e").getType();
    assertThat(e.getPropertyType("bar").toString()).isEqualTo("?");
    assertThat(e.getPropertyType("baz").toString()).isEqualTo("?");

    ObjectType f = (ObjectType) globalScope.getVar("f").getType();
    assertThat(f.getPropertyType("bar").toString()).isEqualTo("?");
    assertThat(f.hasProperty("baz")).isFalse();
  }

  @Test
  public void testStubsInExterns2() {
    testSame(
        externs(
            """
            /** @constructor */ function Extern() {}
            /** @type {Extern} */ var myExtern;
            /** @type {number} */ myExtern.foo;
            """),
        srcs(""));

    JSType e = globalScope.getVar("myExtern").getType();
    assertThat(e.toString()).isEqualTo("(Extern|null)");

    ObjectType externType = (ObjectType) e.restrictByNotNullOrUndefined();
    assertWithMessage(globalScope.getRootNode().toStringTree())
        .that(externType.hasOwnProperty("foo"))
        .isTrue();
    assertThat(externType.isPropertyTypeDeclared("foo")).isTrue();
    assertThat(externType.getPropertyType("foo").toString()).isEqualTo("number");
    assertThat(externType.isPropertyInExterns("foo")).isTrue();
  }

  @Test
  public void testStubsInExterns3() {
    testSame(
        externs(
            """
            /** @type {number} */ myExtern.foo;
            /** @type {Extern} */ var myExtern;
            /** @constructor */ function Extern() {}
            """),
        srcs(""));

    JSType e = globalScope.getVar("myExtern").getType();
    assertThat(e.toString()).isEqualTo("(Extern|null)");

    ObjectType externType = (ObjectType) e.restrictByNotNullOrUndefined();
    assertWithMessage(globalScope.getRootNode().toStringTree())
        .that(externType.hasOwnProperty("foo"))
        .isTrue();
    assertThat(externType.isPropertyTypeDeclared("foo")).isTrue();
    assertThat(externType.getPropertyType("foo").toString()).isEqualTo("number");
    assertThat(externType.isPropertyInExterns("foo")).isTrue();
  }

  @Test
  public void testStubsInExterns4() {
    testSame(
        externs(
            """
            Extern.prototype.foo;
            /** @constructor */ function Extern() {}
            """),
        srcs(""));

    JSType e = globalScope.getVar("Extern").getType();
    assertThat(e.toString()).isEqualTo("(typeof Extern)");

    ObjectType externProto = ((FunctionType) e).getPrototype();
    assertWithMessage(globalScope.getRootNode().toStringTree())
        .that(externProto.hasOwnProperty("foo"))
        .isTrue();
    assertThat(externProto.isPropertyTypeInferred("foo")).isTrue();
    assertThat(externProto.getPropertyType("foo").toString()).isEqualTo("?");
    assertThat(externProto.isPropertyInExterns("foo")).isTrue();
  }

  @Test
  public void testPropertyInExterns1() {
    // Declaring a property on a non-native extern type (e.g. 'Extern') declares it as a property
    // on the instance type, but only in externs.
    testSame(
        externs(
            """
            /** @constructor */ function Extern() {}
            /** @type {Extern} */ var extern;
            /** @return {number} */ extern.one;
            """),
        srcs(
            """
            /** @constructor */ function Normal() {}
            /** @type {Normal} */ var normal;
            /** @return {number} */ normal.one;
            var result = new Extern().one();
            """));

    JSType e = globalScope.getVar("Extern").getType();
    ObjectType externInstance = ((FunctionType) e).getInstanceType();
    assertThat(externInstance.hasOwnProperty("one")).isTrue();
    assertThat(externInstance.isPropertyTypeDeclared("one")).isTrue();
    assertThat(externInstance.getPropertyType("one").toString()).isEqualTo("function(): number");
    assertThat(globalScope.getVar("result").getType().toString()).isEqualTo("number");

    JSType n = globalScope.getVar("Normal").getType();
    ObjectType normalInstance = ((FunctionType) n).getInstanceType();
    assertThat(normalInstance.hasOwnProperty("one")).isFalse();
  }

  @Test
  public void testPropertyInExterns2() {
    // Native extern types (such as Object) do not get stray properties declared, since this would
    // cause problems with bad externs (such as `/** @type {!Object} */ var api = {}; api.foo;`,
    // where we don't want to declare that all Objects have a "foo" property.  Nevertheless, the
    // specific qualified name (i.e. extern.one, in the example below) is still declared on the
    // global scope, so referring to the "one" property specifically on "extern" is still checked
    // as one would expect.
    testSame(
        externs(
            """
            /** @type {Object} */ var extern;
            /** @return {number} */ extern.one;
            """),
        srcs(
            """
            /** @type {Object} */ var normal;
            /** @return {number} */ normal.one;
            var result = extern.one();
            """));

    JSType e = globalScope.getVar("extern").getType();
    assertThat(e.dereference().hasOwnProperty("one")).isFalse();
    assertThat(globalScope.getVar("result").getType().toString()).isEqualTo("number");

    JSType normal = globalScope.getVar("normal").getType();
    assertThat(normal.dereference().hasOwnProperty("one")).isFalse();
  }

  @Test
  public void testPropertyInExterns3() {
    testSame(
        externs(
            """
            /** @constructor
             * @param {*=} x @return {!Object} */
            function Object(x) {}
            /** @type {number} */ Object.one;
            """),
        srcs(""));

    ObjectType obj = globalScope.getVar("Object").getType().dereference();
    assertThat(obj.hasOwnProperty("one")).isTrue();
    assertThat(obj.getPropertyType("one").toString()).isEqualTo("number");
  }

  @Test
  public void testTypedStubsInExterns() {
    testSame(
        externs(
            """
            /** @constructor
             * @param {*} var_args */
            function Function(var_args) {}
            /** @type {!Function} */ Function.prototype.apply;
            """),
        srcs("var f = new Function();"));

    ObjectType f = (ObjectType) globalScope.getVar("f").getType();

    // The type of apply() on a function instance is resolved dynamically,
    // since apply varies with the type of the function it's called on.
    assertThat(f.getPropertyType("apply").toString()).isEqualTo("function(?=, (Object|null)=): ?");

    // The type of apply() on the function prototype just takes what it was
    // declared with.
    FunctionType func = (FunctionType) globalScope.getVar("Function").getType();
    assertThat(func.getPrototype().getPropertyType("apply").toString()).isEqualTo("Function");
  }

  @Test
  public void testTypesInExterns() {
    testSame(externs(CompilerTypeTestCase.DEFAULT_EXTERNS), srcs(""));

    TypedVar v = globalScope.getVar("Object");
    FunctionType obj = (FunctionType) v.getType();
    assertThat(obj.toString()).isEqualTo("(typeof Object)");
    assertThat(v.getNode()).isNotNull();
    assertThat(v.getInput()).isNotNull();
  }

  @Test
  public void testSymbolsDeclaredInExterns() {
    testSame(
        externs(
            CompilerTypeTestCase.DEFAULT_EXTERNS,
            """
            /** @const {symbol} */
            Symbol.fooBar;
            /** @type {number} */
            Symbol.nonSymbolStatic;
            """),
        srcs(""));

    TypedVar fooBar = globalScope.getVar("Symbol.fooBar");
    TypedVar nonSymbolStatic = globalScope.getVar("Symbol.nonSymbolStatic");

    assertType(fooBar.getType()).toStringIsEqualTo("Symbol.fooBar");
    assertType(nonSymbolStatic.getType()).isNumber();
  }

  @Test
  public void testSymbolIterator_includedByDefault() {
    testSame(externs(""), srcs(""));

    TypedVar iterator = globalScope.getVar("Symbol.iterator");

    assertType(iterator.getType()).toStringIsEqualTo("Symbol.iterator");
    assertThat(iterator.getType().isKnownSymbolValueType()).isTrue();
  }

  @Test
  public void testNewSymbolsInSrcCode() {
    testSame(srcs("/** @type {symbol} */ const s = Symbol();"));

    TypedVar iterator = globalScope.getVar("s");
    assertThat(iterator.getType().toString()).isEqualTo("symbol");
  }

  @Test
  public void testPropertyDeclarationOnInstanceType() {
    testSame(
        """
        /** @type {!Object} */ var a = {};
        /** @type {number} */ a.name = 0;
        """);

    assertThat(globalScope.getVar("a.name").getType().toString()).isEqualTo("number");

    ObjectType a = (ObjectType) (globalScope.getVar("a").getType());
    assertThat(a.hasProperty("name")).isFalse();
    assertThat(getNativeObjectType(OBJECT_TYPE).hasProperty("name")).isFalse();
  }

  @Test
  public void testPropertyDeclarationOnRecordType() {
    testSame(
        """
        /** @type {{foo: number}} */ var a = {foo: 3};
        /** @type {number} */ a.name = 0;
        """);

    assertThat(globalScope.getVar("a.name").getType().toString()).isEqualTo("number");

    ObjectType a = (ObjectType) (globalScope.getVar("a").getType());
    assertThat(a.toString()).isEqualTo("{foo: number}");
    assertThat(a.hasProperty("name")).isFalse();
  }

  @Test
  public void testGlobalThis1() {
    testSame(
        """
        /** @constructor */ function Window() {}
        Window.prototype.alert = function() {};
        var x = this;
        """);

    ObjectType x = (ObjectType) (globalScope.getVar("x").getType());
    FunctionType windowCtor = (FunctionType) (globalScope.getVar("Window").getType());
    assertThat(x.toString()).isEqualTo("global this");
    assertThat(x.isSubtypeOf(windowCtor.getInstanceType())).isTrue();
    assertThat(x.equals(windowCtor.getInstanceType())).isFalse();
    assertThat(x.hasProperty("alert")).isTrue();
  }

  @Test
  public void testGlobalThis2() {
    testSame(
        """
        /** @constructor */ function Window() {}
        Window.prototype = {alert: function() {}};
        var x = this;
        """);

    ObjectType x = (ObjectType) (globalScope.getVar("x").getType());
    FunctionType windowCtor = (FunctionType) (globalScope.getVar("Window").getType());
    assertThat(x.toString()).isEqualTo("global this");
    assertThat(x.isSubtypeOf(windowCtor.getInstanceType())).isTrue();
    assertThat(x.equals(windowCtor.getInstanceType())).isFalse();
    assertThat(x.hasProperty("alert")).isTrue();
  }

  @Test
  public void testGlobalThis_includesProvidedNamesAsProperties() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.provide('my.alert');
            /** @param {string} msg */
            my.alert = function(msg) {};
            var x = this;
            """));

    ObjectType myType = findNameType("my", globalScope).toObjectType();
    ObjectType globalThis = findNameType("x", globalScope).toObjectType();
    assertType(globalThis).toStringIsEqualTo("global this");
    assertType(globalThis).withTypeOfProp("my").isSameInstanceAs(myType);
  }

  @Test
  public void testObjectLiteralCast() {
    // Verify that "goog.reflect.object" does not modify the types on
    // "A.B"
    testSame(
        """
        var A = {};
        /** @constructor */ A.B = function() {}
        A.B.prototype.isEnabled = true;
        goog.reflect.object(A.B, {isEnabled: 3})
        var x = (new A.B()).isEnabled;
        """);

    // Verify that "$jscomp.reflectObject" does not modify the types on
    // "A.B"
    testSame(
        """
        var A = {};
        var $jscomp = {};
        /** @constructor */ A.B = function() {}
        A.B.prototype.isEnabled = true;
        $jscomp.reflectObject(A.B, {isEnabled: 3})
        var x = (new A.B()).isEnabled;
        """);

    assertThat(findTokenType(Token.OBJECTLIT, globalScope).toString()).isEqualTo("A.B");
    assertThat(findNameType("x", globalScope).toString()).isEqualTo("boolean");
  }

  @Test
  public void testBadObjectLiteralCast1() {
    testWarning(
        """
        var A = {}; /** @constructor */ A.B = function() {}
        goog.reflect.object(A.B, 1)
        """,
        ClosureCodingConvention.OBJECTLIT_EXPECTED);
  }

  @Test
  public void testBadObjectLiteralCast2() {
    testWarning("var A; goog.reflect.object(A.B, {})", TypedScopeCreator.CONSTRUCTOR_EXPECTED);
  }

  @Test
  public void testConstructorNode() {
    testSame("/** @constructor */ goog.Foo = function() {};");

    ObjectType ctor = (ObjectType) (findNameType("goog.Foo", globalScope));
    assertThat(ctor).isNotNull();
    assertThat(ctor.isConstructor()).isTrue();
    assertThat(ctor.toString()).isEqualTo("(typeof goog.Foo)");
  }

  @Test
  public void testClassDeclaration() {
    testSame("class Foo {}");
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertThat(foo.isConstructor()).isTrue();
    assertScope(globalScope).declares("Foo").withTypeThat().isEqualTo(foo);
  }

  @Test
  public void testClassDeclarationWithoutConstructor() {
    testSame("class Foo {}");

    FunctionType fooClass = (FunctionType) findNameType("Foo", globalScope);
    ObjectType fooProto = fooClass.getPrototype();

    // Test class typing.
    assertThat(fooClass.isConstructor()).isTrue();

    // Test constructor property.
    assertThat(fooProto.hasOwnProperty("constructor")).isTrue();
    assertNode(fooProto.getOwnPropertyDefSite("constructor")).isNull();
    assertType(fooProto).withTypeOfProp("constructor").toStringIsEqualTo("(typeof Foo)");
  }

  @Test
  public void testClassDeclarationWithConstructor() {
    testSame(
        """
        class Foo {
          /** @param {number} arg */
          constructor(arg) {
            CTOR_BODY:;
          }
        }
        """);

    FunctionType fooClass = (FunctionType) findNameType("Foo", globalScope);
    ObjectType fooProto = fooClass.getPrototype();
    ImmutableList<FunctionType.Parameter> params = fooClass.getParameters();
    Node ctorDef = getLabeledStatement("CTOR_BODY").statementNode.getAncestor(3);

    // Test class typing.
    assertThat(fooClass.isConstructor()).isTrue();
    assertThat(params).hasSize(1);
    assertType(params.get(0).getJSType()).isNumber();

    // Test constructor property.
    assertThat(fooProto.hasOwnProperty("constructor")).isTrue();
    assertNode(fooProto.getOwnPropertyDefSite("constructor")).isSameInstanceAs(ctorDef);
    assertType(fooProto).withTypeOfProp("constructor").toStringIsEqualTo("(typeof Foo)");
  }

  @Test
  public void testInterfaceClassDeclarationWithConstructor() {
    testSame(
        """
        /** @interface */
        class Foo {
          constructor(arg) {}
        }
        """);

    FunctionType fooClass = (FunctionType) findNameType("Foo", globalScope);
    ObjectType fooProto = fooClass.getPrototype();

    // Test class typing.
    assertThat(fooClass.isInterface()).isTrue();

    // Test constructor property.
    assertThat(fooProto.hasOwnProperty("constructor")).isFalse();
  }

  @Test
  public void testClassDeclarationWithImplementsForwardRef() {
    testSame(
        """
        /** @implements {Bar} */
        class Foo {}
        /** @interface */
        class Bar {}
        class SubFoo extends Foo {}
        """);

    FunctionType fooCtorType = globalScope.getVar("Foo").getType().toMaybeFunctionType();
    ObjectType fooType = fooCtorType.getInstanceType();

    assertThat(fooCtorType.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(fooType.loosenTypecheckingDueToForwardReferencedSupertype()).isTrue();

    FunctionType subFooCtorType = globalScope.getVar("SubFoo").getType().toMaybeFunctionType();
    ObjectType subFooType = subFooCtorType.getInstanceType();

    assertThat(subFooCtorType.loosenTypecheckingDueToForwardReferencedSupertype()).isTrue();
    assertThat(subFooType.loosenTypecheckingDueToForwardReferencedSupertype()).isTrue();
  }

  @Test
  public void testClassDeclarationWithExtends() {
    testSame(
        """
        class Bar {}
        class Foo extends Bar {}
        """);
    FunctionType bar = (FunctionType) (findNameType("Bar", globalScope));
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo.getInstanceType()).isSubtypeOf(bar.getInstanceType());
    assertType(foo.getImplicitPrototype()).isEqualTo(bar);
    assertScope(globalScope).declares("Bar").withTypeThat().isEqualTo(bar);
    assertScope(globalScope).declares("Foo").withTypeThat().isEqualTo(foo);

    assertThat(foo.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.isAmbiguousConstructor()).isFalse();
  }

  @Test
  public void testClassDeclarationWithExtends_andCompatibleJsdoc() {
    testSame(
        """
        /** @template T */
        class Bar {}
        /** @extends {Bar<string>} */
        class Foo extends Bar {}
        """);
    FunctionType bar = (FunctionType) (findNameType("Bar", globalScope));
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo.getInstanceType()).isSubtypeOf(bar.getInstanceType());
    assertScope(globalScope).declares("Bar").withTypeThat().isEqualTo(bar);
    assertScope(globalScope).declares("Foo").withTypeThat().isEqualTo(foo);

    assertThat(foo.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.isAmbiguousConstructor()).isFalse();
  }

  @Test
  public void testClassDeclarationWithExtends_andIncompatibleJsdoc() {
    testSame(
        """
        class Bar {}
        class Qux {}
        /** @extends {Bar} */
        class Foo extends Qux {}
        """);
    FunctionType bar = (FunctionType) (findNameType("Bar", globalScope));
    FunctionType qux = (FunctionType) (findNameType("Qux", globalScope));
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo.getInstanceType()).isSubtypeOf(bar.getInstanceType());
    assertScope(globalScope).declares("Bar").withTypeThat().isEqualTo(bar);
    assertScope(globalScope).declares("Qux").withTypeThat().isEqualTo(qux);
    assertScope(globalScope).declares("Foo").withTypeThat().isEqualTo(foo);

    assertThat(foo.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.isAmbiguousConstructor()).isTrue();
  }

  @Test
  public void testAbstractClassDeclarationWithExtends_nameDeclAndAssign() {
    testSame(
        """
        var Bar_1;
        /** @abstract */
        let Bar = Bar_1 = class Bar {}
        class Foo extends Bar {}
        """);

    FunctionType bar1 = (FunctionType) findNameType("Bar_1", globalScope);
    FunctionType bar = (FunctionType) findNameType("Bar", globalScope);
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    assertThat(bar.isAbstract()).isTrue();
    assertThat(bar).isEqualTo(bar1);
    assertType(foo.getInstanceType()).isSubtypeOf(bar.getInstanceType());
    assertType(foo.getImplicitPrototype()).isEqualTo(bar);
    assertScope(globalScope).declares("Bar").withTypeThat().isEqualTo(bar);
    assertScope(globalScope).declares("Foo").withTypeThat().isEqualTo(foo);

    assertThat(foo.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.isAmbiguousConstructor()).isFalse();
  }

  @Test
  public void testClassDeclarationWithExtendsFromNamespace() {
    testSame(
        """
        const ns = {};
        ns.Bar = class {};
        const nsAliased = ns;
        class Foo extends nsAliased.Bar {}
        """);
    FunctionType bar = (FunctionType) findNameType("ns.Bar", globalScope);
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);

    assertType(foo.getInstanceType()).isSubtypeOf(bar.getInstanceType());
    assertType(foo.getImplicitPrototype()).isEqualTo(bar);

    assertThat(foo.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.isAmbiguousConstructor()).isFalse();
  }

  @Test
  public void testClassDeclarationWithExtendsFromNamespaceAndJSDoc() {
    testSame(
        """
        const ns = {};
        ns.Bar = class {};
        const nsAliased = ns;

        /** @extends {nsAliased.Bar} */
        class Foo extends nsAliased.Bar {}
        """);
    FunctionType bar = (FunctionType) findNameType("ns.Bar", globalScope);
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);

    assertType(foo.getInstanceType()).isSubtypeOf(bar.getInstanceType());
    assertType(foo.getImplicitPrototype()).isEqualTo(bar);

    assertThat(foo.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.isAmbiguousConstructor()).isFalse();
  }

  @Test
  public void testClassDeclarationWithExtendsFromNamespaceAndJSDoc_incompatible() {
    testSame(
        """
        const ns = {};
        /** @template T */
        ns.Bar = class {};
        ns.Qux = class {};
        const nsAliased = ns;

        /** @extends {nsAliased.Bar<number>} */
        class Foo extends nsAliased.Bar {}
        """);
    FunctionType bar = (FunctionType) findNameType("ns.Bar", globalScope);
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);

    assertType(foo.getInstanceType()).isSubtypeOf(bar.getInstanceType());
    assertType(foo.getImplicitPrototype()).isEqualTo(bar);

    assertThat(foo.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.isAmbiguousConstructor()).isFalse();
  }

  @Test
  public void testClassDeclarationWithNestedExtends() {
    testSame(
        """
        class Bar {}
        class Foo extends class extends class extends Bar {} {} {}
        """);
    FunctionType bar = (FunctionType) (findNameType("Bar", globalScope));
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo.getInstanceType()).isSubtypeOf(bar.getInstanceType());
  }

  @Test
  public void testClassDeclarationWithExtends_googModuleGet() {
    testSame(
        srcs(
            """
            goog.module('a.Bar');
            class Bar {}
            BAR: Bar;
            exports = Bar;
            """,
            "class Foo extends goog.module.get('a.Bar') {}"));
    TypedScope moduleScope = getLabeledStatement("BAR").enclosingScope;
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    FunctionType bar = (FunctionType) findNameType("Bar", moduleScope);
    assertType(foo.getInstanceType()).isSubtypeOf(bar.getInstanceType());
    assertType(foo.getImplicitPrototype()).isEqualTo(bar);
    assertScope(moduleScope).declares("Bar").withTypeThat().isEqualTo(bar);
    assertScope(globalScope).declares("Foo").withTypeThat().isEqualTo(foo);

    assertThat(foo.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.isAmbiguousConstructor()).isFalse();
  }

  @Test
  public void testClassDeclarationWithExtends_googModuleGetProperty() {
    testSame(
        srcs(
            """
            goog.module('a.b');
            class Bar {}
            BAR: Bar;
            exports = {Bar};
            """,
            "class Foo extends goog.module.get('a.b').Bar {}"));
    TypedScope moduleScope = getLabeledStatement("BAR").enclosingScope;
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    FunctionType bar = (FunctionType) findNameType("Bar", moduleScope);
    assertType(foo.getInstanceType()).isSubtypeOf(bar.getInstanceType());
    assertType(foo.getImplicitPrototype()).isEqualTo(bar);
    assertScope(moduleScope).declares("Bar").withTypeThat().isEqualTo(bar);
    assertScope(globalScope).declares("Foo").withTypeThat().isEqualTo(foo);

    assertThat(foo.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.isAmbiguousConstructor()).isFalse();
  }

  @Test
  public void testClassDeclarationWithExtends_googModuleGetNestedProperty() {
    testSame(
        srcs(
            """
            goog.module('a.b');
            class Bar {}
            Bar.NestedClass = class {};
            NESTED_CLASS: Bar.NestedClass;
            exports = {Bar};
            """,
            "class Foo extends goog.module.get('a.b').Bar.NestedClass {}"));
    TypedScope moduleScope = getLabeledStatement("NESTED_CLASS").enclosingScope;
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    FunctionType nestedClass =
        (FunctionType) findNameType("Bar", moduleScope).findPropertyType("NestedClass");
    assertType(foo.getInstanceType()).isSubtypeOf(nestedClass.getInstanceType());
    assertType(foo.getImplicitPrototype()).isEqualTo(nestedClass);

    assertThat(foo.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(foo.isAmbiguousConstructor()).isFalse();
  }

  @Test
  public void testClassDeclarationWithExtends_missingGoogModuleGet() {
    testSame(srcs("class Foo extends goog.module.get('not.a.real.module') {}"));
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    assertScope(globalScope).declares("Foo").withTypeThat().isEqualTo(foo);

    assertType(foo.getPrototype().getImplicitPrototype()).isEqualTo(getNativeType(OBJECT_TYPE));
  }

  @Test
  public void testClassDeclarationWithInheritedConstructor() {
    testSame(
        """
        class Bar {
          constructor(/** string */ arg) {}
        }
        class Foo extends Bar {}
        """);

    FunctionType fooClass = (FunctionType) findNameType("Foo", globalScope);
    ObjectType fooProto = fooClass.getPrototype();
    ImmutableList<FunctionType.Parameter> params = fooClass.getParameters();

    // Test class typing.
    assertThat(fooClass.isConstructor()).isTrue();
    assertThat(params).hasSize(1);
    assertType(params.get(0).getJSType()).isString();

    // Test constructor property.
    assertThat(fooProto.hasOwnProperty("constructor")).isTrue();
    assertNode(fooProto.getOwnPropertyDefSite("constructor")).isNull();
    assertType(fooProto).withTypeOfProp("constructor").toStringIsEqualTo("(typeof Foo)");
  }

  @Test
  public void testClassDeclarationWithOverriddenConstructor() {
    testSame(
        """
        class Bar {
          constructor(/** string */ arg) {}
        }
        class Foo extends Bar {
          constructor(/** number */ arg) { CTOR_BODY:super(''); }
          static method() {}
        }
        """);

    FunctionType barCtor = (FunctionType) findNameType("Bar", globalScope);
    ObjectType barObject = barCtor.getInstanceType();
    JSType barConstructorProperty = barObject.getPropertyType("constructor");

    FunctionType fooCtor = (FunctionType) findNameType("Foo", globalScope);
    ObjectType fooObject = fooCtor.getInstanceType();
    ObjectType fooProto = fooCtor.getPrototype();
    JSType fooConstructorProperty = fooObject.getPropertyType("constructor");

    Node superInvocation = getLabeledStatement("CTOR_BODY").statementNode.getFirstChild();

    Node superRef = superInvocation.getFirstChild();
    assertNode(superRef).hasToken(Token.SUPER).hasJSTypeThat().isEqualTo(barCtor);

    assertType(fooCtor).withTypeOfProp("method").isNotUnknown();
    assertType(fooCtor).withTypeOfProp("method").isNotEmpty();

    ImmutableList<FunctionType.Parameter> params = fooCtor.getParameters();
    assertThat(params).hasSize(1);
    assertType(params.get(0).getJSType()).isNumber();
    assertType(barConstructorProperty).toStringIsEqualTo("(typeof Bar)");

    Node fooCtorDef = NodeUtil.getEnclosingFunction(superInvocation);
    assertType(fooConstructorProperty).toStringIsEqualTo("(typeof Foo)");
    assertNode(fooProto.getOwnPropertyDefSite("constructor")).isSameInstanceAs(fooCtorDef);

    assertType(fooConstructorProperty).isSubtypeOf(barConstructorProperty);
    assertType(fooConstructorProperty)
        .withTypeOfProp("method")
        .isFunctionTypeThat()
        .hasTypeOfThisThat()
        .toStringIsEqualTo("(typeof Foo)");
  }

  @Test
  public void testClassDeclarationWithNestedExtendsAndInheritedConstructor() {
    testSame(
        """
        class Bar {
          constructor(/** string */ arg) {}
        }
        class Foo extends class extends class extends Bar {} {} {}
        """);
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    ImmutableList<FunctionType.Parameter> params = foo.getParameters();
    assertThat(params).hasSize(1);
    assertType(params.get(0).getJSType()).isString();
  }

  @Test
  public void testClassDeclarationWithMethod() {
    testSame(
        """
        class Foo {
          /** @param {string} arg */
          method(arg) {
            METHOD:;
            var /** number */ foo;
          }
        }
        """);
    TypedScope methodBlockScope = getLabeledStatement("METHOD").enclosingScope;
    TypedScope methodScope = methodBlockScope.getParentScope();
    assertScope(methodBlockScope).declares("foo").directly().withTypeThat().isNumber();
    assertScope(methodScope).declares("arg").directly().withTypeThat().isString();

    FunctionType method = (FunctionType) methodScope.getRootNode().getJSType();
    assertType(method).toStringIsEqualTo("function(this:Foo, string): undefined");
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo.getInstanceType()).withTypeOfProp("method").isEqualTo(method);
  }

  @Test
  public void testEs5ClassWithWellKnownSymbolTypedMethod() {
    testSame(
        externs(
            new TestExternsBuilder().addIterable().build(),
            """
            /** @const {symbol}  */
            Symbol.dispose;
            """),
        srcs(
            """
            /** @constructor */
            function Foo() {}
            /**
             * @param {string} x
             * @param {number} y
             */
            Foo.prototype[Symbol.iterator] = function(x, y) {}
            /** @return {symbol} */
            Foo.prototype[Symbol.dispose] = function() { return Symbol.dispose; }
            """));

    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    KnownSymbolType symbolIteratorType =
        (KnownSymbolType) findNameType("Symbol.iterator", globalScope);
    KnownSymbolType symbolDisposeType =
        (KnownSymbolType) findNameType("Symbol.dispose", globalScope);
    assertType(foo.getInstanceType()).hasProperty(symbolIteratorType);
    assertType(foo.getInstanceType()).hasProperty(symbolDisposeType);

    assertType(foo.getInstanceType())
        .withTypeOfProp(symbolIteratorType)
        .toStringIsEqualTo("function(this:Foo, string, number): undefined");
    assertType(foo.getInstanceType())
        .withTypeOfProp(symbolDisposeType)
        .toStringIsEqualTo("function(this:Foo): symbol");
  }

  @Test
  public void testClassWithWellKnownSymbolTypedMethod() {
    testSame(
        externs(
            new TestExternsBuilder().addIterable().build(),
            """
            /** @const {symbol}  */
            Symbol.dispose;
            """),
        srcs(
            """
            class Foo {
              /**
               * @param {string} x
               * @param {number} y
               */
              [Symbol.iterator](x, y) {}
              /** @return {symbol} */
              [Symbol.dispose]() { return Symbol.dispose; }
            }
            """));

    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    KnownSymbolType symbolIteratorType =
        (KnownSymbolType) findNameType("Symbol.iterator", globalScope);
    KnownSymbolType symbolDisposeType =
        (KnownSymbolType) findNameType("Symbol.dispose", globalScope);
    assertType(foo.getInstanceType()).hasProperty(symbolIteratorType);
    assertType(foo.getInstanceType()).hasProperty(symbolDisposeType);

    assertType(foo.getInstanceType())
        .withTypeOfProp(symbolIteratorType)
        .toStringIsEqualTo("function(this:Foo, string, number): undefined");
    assertType(foo.getInstanceType())
        .withTypeOfProp(symbolDisposeType)
        .toStringIsEqualTo("function(this:Foo): symbol");
  }

  @Test
  public void testClassDeclarationWithDucplicatePrototypeMethodAfterward() {
    // Given
    testSame(
        """
        class Foo {
          /** @param {string} arg */
          method(arg) { }
        }

        // This declaration should be ignored in preference of the one in the CLASS body.
        // Constructs like this are sometimes valid (e.g. in mod files) and have error reporting
        // separate from `TypedScopeCreator`.
        /** @param {number} arg */
        Foo.prototype.method = function(arg) { }
        """);

    // Then
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    assertType(foo.getInstanceType())
        .withTypeOfProp("method")
        .toStringIsEqualTo("function(this:Foo, string): undefined");
  }

  @Test
  public void testClassDeclarationWithDucplicateStaticMethodAfterward() {
    // Given
    testSame(
        """
        class Foo {
          /** @param {string} arg */
          static method(arg) { }
        }

        // This declaration should be ignored in preference of the one in the CLASS body.
        // Constructs like this are sometimes valid (e.g. in mod files) and have error reporting
        // separate from `TypedScopeCreator`.
        /** @param {number} arg */
        Foo.method = function(arg) { }
        """);

    // Then
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    assertType(foo)
        .withTypeOfProp("method")
        .toStringIsEqualTo("function(this:(typeof Foo), string): undefined");
  }

  @Test
  public void testClassDeclarationWithConflictingGetterSetter() {
    // Given
    testSame(
        """
        class Foo {
        // This is not valid, but will be warned about in TypeCheck later
          /** @return {string} */
          get field() { }
          /** @param {number} arg */
          set field(arg) {}
        }
        """);

    // Then
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    assertType(foo.getPrototype()).withTypeOfProp("field").isString();
  }

  @Test
  public void testClassDeclarationWithGetterWithDuplicateAfterward() {
    // Given
    testSame(
        """
        class Foo {
          /** @return {string} */
          get field() { }
        }

        // This declaration should be ignored in preference of the one in the CLASS body.
        // Constructs like this are sometimes valid (e.g. in mod files) and have error reporting
        // separate from `TypedScopeCreator`.
        /** @type {string} arg */
        Foo.method = function(arg) { }
        """);

    // Then
    FunctionType foo = (FunctionType) findNameType("Foo", globalScope);
    assertType(foo.getPrototype()).withTypeOfProp("field").isString();
  }

  @Test
  public void testClassDeclarationWithMethodAndInlineParamDocs() {
    testSame(
        """
        class Foo {
          method(/** string */ arg) {
            METHOD:;
            var /** number */ foo;
          }
        }
        """);
    TypedScope methodBlockScope = getLabeledStatement("METHOD").enclosingScope;
    TypedScope methodScope = methodBlockScope.getParentScope();
    assertScope(methodBlockScope).declares("foo").directly().withTypeThat().isNumber();
    assertScope(methodScope).declares("arg").directly().withTypeThat().isString();

    FunctionType method = (FunctionType) methodScope.getRootNode().getJSType();
    assertType(method).toStringIsEqualTo("function(this:Foo, string): undefined");
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo.getInstanceType()).withTypeOfProp("method").isEqualTo(method);
  }

  @Test
  public void testClassDeclarationWithOverriddenMethod() {
    testSame(
        """
        class Bar {
          method(/** string */ arg) {}
        }
        class Foo extends Bar {
          method(arg) {
            METHOD:;
          }
        }
        """);
    ObjectType bar = ((FunctionType) findNameType("Bar", globalScope)).getInstanceType();
    ObjectType foo = ((FunctionType) findNameType("Foo", globalScope)).getInstanceType();

    TypedScope methodBlockScope = getLabeledStatement("METHOD").enclosingScope;
    TypedScope methodScope = methodBlockScope.getParentScope();
    assertScope(methodScope).declares("arg").directly().withTypeThat().isString();
    assertScope(methodScope).declares("super").withTypeThat().isEqualTo(bar);

    FunctionType method = (FunctionType) methodScope.getRootNode().getJSType();
    assertType(method).toStringIsEqualTo("function(this:Foo, string): undefined");
    assertType(foo).withTypeOfProp("method").isEqualTo(method);
  }

  @Test
  public void testClassDeclarationWithStaticMethod() {
    testSame(
        """
        class Foo {
          static method(/** string */ arg) {
            METHOD:;
            var /** number */ foo;
          }
        }
        """);
    TypedScope methodBlockScope = getLabeledStatement("METHOD").enclosingScope;
    TypedScope methodScope = methodBlockScope.getParentScope();
    assertScope(methodBlockScope).declares("foo").directly().withTypeThat().isNumber();
    assertScope(methodScope).declares("arg").directly().withTypeThat().isString();

    FunctionType method = (FunctionType) methodScope.getRootNode().getJSType();
    assertType(method).toStringIsEqualTo("function(this:(typeof Foo), string): undefined");
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo).withTypeOfProp("method").isEqualTo(method);
  }

  @Test
  public void testClassDeclarationWithOverriddenStaticMethod() {
    testSame(
        """
        class Foo {
          /** @param {number} arg */
          static method(arg) {}
        }
        class Bar extends Foo {
          static method(arg) {
            METHOD: super.method(arg);
          }
        }
        """);
    FunctionType fooCtor = (FunctionType) findNameType("Foo", globalScope);
    LabeledStatement superMethodCall = getLabeledStatement("METHOD");

    TypedScope methodOverrideBlockScope = superMethodCall.enclosingScope;
    TypedScope methodOverrideScope = methodOverrideBlockScope.getParentScope();
    assertScope(methodOverrideScope).declares("arg").directly().withTypeThat().isNumber();
    assertScope(methodOverrideScope).declares("super").withTypeThat().isEqualTo(fooCtor);

    FunctionType methodOverride = (FunctionType) methodOverrideScope.getRootNode().getJSType();
    assertType(methodOverride).toStringIsEqualTo("function(this:(typeof Bar), number): undefined");
    assertType(fooCtor)
        .withTypeOfProp("method")
        .toStringIsEqualTo("function(this:(typeof Foo), number): undefined");

    Node superRef = superMethodCall.statementNode.getFirstChild().getFirstFirstChild();
    assertNode(superRef).hasToken(Token.SUPER).hasJSTypeThat().isEqualTo(fooCtor);
  }

  @Test
  public void testClassDeclarationWithAsyncMethod() {
    testSame(
        """
        class Foo {
          /** @param {string} arg */
          async method(arg) {
            METHOD:;
          }
        }
        """);
    TypedScope methodBlockScope = getLabeledStatement("METHOD").enclosingScope;
    TypedScope methodScope = methodBlockScope.getParentScope();
    assertScope(methodScope).declares("arg").directly().withTypeThat().isString();

    FunctionType method = (FunctionType) methodScope.getRootNode().getJSType();
    assertType(method).toStringIsEqualTo("function(this:Foo, string): Promise<undefined>");
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo.getInstanceType()).withTypeOfProp("method").isEqualTo(method);
  }

  @Test
  public void testClassDeclarationWithGeneratorMethod() {
    testSame(
        """
        class Foo {
          /** @param {string} arg */
          * method(arg) {
            METHOD:;
          }
        }
        """);
    TypedScope methodBlockScope = getLabeledStatement("METHOD").enclosingScope;
    TypedScope methodScope = methodBlockScope.getParentScope();
    assertScope(methodScope).declares("arg").directly().withTypeThat().isString();

    FunctionType method = (FunctionType) methodScope.getRootNode().getJSType();
    assertType(method).toStringIsEqualTo("function(this:Foo, string): Generator<?,?,?>");
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo.getInstanceType()).withTypeOfProp("method").isEqualTo(method);
  }

  @Test
  public void testClassDeclarationWithComputedPropertyMethod() {
    testSame(
        """
        class Foo {
          /** @param {string} arg */
          ['method'](arg) {
            METHOD:;
            var /** number */ foo;
          }
        }
        """);
    TypedScope methodBlockScope = getLabeledStatement("METHOD").enclosingScope;
    TypedScope methodScope = methodBlockScope.getParentScope();
    assertScope(methodBlockScope).declares("foo").directly().withTypeThat().isNumber();
    assertScope(methodScope).declares("arg").directly().withTypeThat().isString();

    FunctionType method = (FunctionType) methodScope.getRootNode().getJSType();
    assertType(method).toStringIsEqualTo("function(this:Foo, string): undefined");
  }

  @Test
  public void testClassDeclarationWithField() {
    testSame(
        """
        class Foo {
          /** @type {string} */
          a = 'hi';
          /** @type {symbol|undefined} */
          b;
          c = 0;
          /** @type {string|null} */
          d = null;
        }
        """);

    FunctionType fooCtor = globalScope.getVar("Foo").getType().assertFunctionType();
    ObjectType fooInstance = fooCtor.getInstanceType();

    assertThat(fooInstance.hasOwnDeclaredProperty("a")).isTrue();
    assertType(fooInstance.getPropertyType("a")).isString();

    assertThat(fooInstance.hasOwnDeclaredProperty("b")).isTrue();
    assertType(fooInstance.getPropertyType("b")).toStringIsEqualTo("(symbol|undefined)");

    assertThat(fooInstance.hasOwnDeclaredProperty("c")).isTrue();
    assertType(fooInstance.getPropertyType("c")).isNumber();

    assertThat(fooInstance.hasOwnDeclaredProperty("d")).isTrue();
    assertType(fooInstance.getPropertyType("d")).toStringIsEqualTo("(null|string)");
  }

  @Test
  public void testClassDeclarationWithFieldThisAndSuper() {
    testSame(
        """
        class Foo {
          /** @type {number} */
          a = 2;
          /** @type {number} */
          b = this.a;
        }
        class Bar extends Foo {
          /** @type {number} */
          c = this.a + 1; // Must use `this` and not `super` to access field of parent class
        }
        """);

    FunctionType fooCtor = globalScope.getVar("Foo").getType().assertFunctionType();
    ObjectType fooInstance = fooCtor.getInstanceType();
    FunctionType barCtor = globalScope.getVar("Bar").getType().assertFunctionType();
    ObjectType barInstance = barCtor.getInstanceType();

    assertThat(fooInstance.hasOwnDeclaredProperty("a")).isTrue();
    assertType(fooInstance.getPropertyType("a")).isNumber();
    assertThat(barInstance.hasOwnDeclaredProperty("a")).isFalse();

    assertThat(fooInstance.hasOwnDeclaredProperty("b")).isTrue();
    assertType(fooInstance.getPropertyType("b")).isNumber();
    assertThat(barInstance.hasOwnDeclaredProperty("b")).isFalse();

    assertThat(fooInstance.hasOwnDeclaredProperty("c")).isFalse();
    assertThat(barInstance.hasOwnDeclaredProperty("c")).isTrue();
    assertType(barInstance.getPropertyType("c")).isNumber();
  }

  // When there is no JSDoc type declaration for a field, and the RHS is uninferrable or unknown,
  // the field's type is set to the ALL type ('*')
  @Test
  public void testPublicFieldWithUnknownTypeCast() {
    testSame(
        """
        class Bar {
          x = /** @type {?} */ ({});
        }
        """);
    FunctionType barCtor = globalScope.getVar("Bar").getType().assertFunctionType();
    ObjectType barInstance = barCtor.getInstanceType();
    assertThat(barInstance.hasOwnDeclaredProperty("x")).isTrue();
    assertType(barInstance.getPropertyType("x")).toStringIsEqualTo("*");

    testSame("class Foo { x; }");
    FunctionType fooCtor = globalScope.getVar("Foo").getType().assertFunctionType();
    ObjectType fooInstance = fooCtor.getInstanceType();
    assertThat(fooInstance.hasOwnDeclaredProperty("x")).isTrue();
    assertType(fooInstance.getPropertyType("x")).toStringIsEqualTo("*");
  }

  @Test
  public void testStaticFieldWithUnknownTypeCast() {
    testSame("class Drum { static y = /** @type {?} */ (5); }");
    FunctionType drumCtor = globalScope.getVar("Drum").getType().assertFunctionType();
    assertThat(drumCtor.hasOwnDeclaredProperty("y")).isTrue();
    assertType(drumCtor.getPropertyType("y")).toStringIsEqualTo("*");
  }

  @Test
  public void testClassDeclarationWithStaticField() {
    testSame(
        """
        class Foo {
          /** @type {string} */
          static a = 'hi';
          /** @type {symbol|undefined} */
          static b;
          static c = 0;
          static d;
          /** @type {string|null} */
          static e = null;
        }
        """);

    FunctionType fooCtor = globalScope.getVar("Foo").getType().assertFunctionType();

    assertThat(fooCtor.hasOwnDeclaredProperty("a")).isTrue();
    assertType(fooCtor.getPropertyType("a")).isString();

    assertThat(fooCtor.hasOwnDeclaredProperty("b")).isTrue();
    assertType(fooCtor.getPropertyType("b")).toStringIsEqualTo("(symbol|undefined)");

    assertThat(fooCtor.hasOwnDeclaredProperty("c")).isTrue();
    assertType(fooCtor.getPropertyType("c")).isNumber();

    assertThat(fooCtor.hasOwnDeclaredProperty("d")).isTrue();
    assertType(fooCtor.getPropertyType("d")).toStringIsEqualTo("*");

    assertThat(fooCtor.hasOwnDeclaredProperty("e")).isTrue();
    assertType(fooCtor.getPropertyType("e")).toStringIsEqualTo("(null|string)");
  }

  @Test
  public void testClassDeclarationWithStaticFieldThisAndSuper() {
    testSame(
        """
        class Foo {
          /** @type {number} */
          static a = 2;
          /** @type {number} */
          static b = this.a;
        }
        class Bar extends Foo {
          /** @type {number} */
          static c = this.a + 1; // Must use `this` and not `super` to access field of parent
        // class
        }
        """);

    FunctionType fooCtor = globalScope.getVar("Foo").getType().assertFunctionType();
    FunctionType barCtor = globalScope.getVar("Bar").getType().assertFunctionType();

    assertThat(fooCtor.hasOwnDeclaredProperty("a")).isTrue();
    assertType(fooCtor.getPropertyType("a")).isNumber();
    assertThat(barCtor.hasOwnDeclaredProperty("a")).isFalse();

    assertThat(fooCtor.hasOwnDeclaredProperty("b")).isTrue();
    assertType(fooCtor.getPropertyType("b")).isNumber();
    assertThat(barCtor.hasOwnDeclaredProperty("b")).isFalse();

    assertThat(fooCtor.hasOwnDeclaredProperty("c")).isFalse();
    assertThat(barCtor.hasOwnDeclaredProperty("c")).isTrue();
    assertType(barCtor.getPropertyType("c")).isNumber();
  }

  @Test
  public void testTypeOfThisInObjectLitInPublicField() {
    // Make sure the `this` on the RHS of a class field declaration has the appropriate type, even
    // when it appears within an object literal
    testSame(
        """
        class Foo {
          x = 3;
          bar = {
            b: this.x
          };
        }
        """);

    FunctionType fooCtor = globalScope.getVar("Foo").getType().assertFunctionType();
    ObjectType fooInstance = fooCtor.getInstanceType();
    Node getProp =
        fooInstance
            .getPropertyNode("bar")
            .getOnlyChild() // OBJECTLIT
            .getOnlyChild() // STRING_KEY
            .getOnlyChild(); // GETPROP
    assertNode(getProp).hasJSTypeThat().isNumber();
    Node thisNode = getProp.getOnlyChild();
    assertNode(thisNode).hasJSTypeThat().isEqualTo(fooInstance);

    testSame(
        """
        class Foo {
          static baz = {
            e: this
          };
        }
        """);

    fooCtor = globalScope.getVar("Foo").getType().assertFunctionType();
    thisNode =
        fooCtor
            .getPropertyNode("baz")
            .getOnlyChild() // OBJECTLIT
            .getOnlyChild() // STRING_KEY
            .getOnlyChild(); // THIS
    assertNode(thisNode).hasJSTypeThat().isEqualTo(fooCtor);
  }

  @Test
  public void testGetterInObjectLitInPublicField() {
    // Previously a bug caused getters & setters in object literals on the RHS of a field
    // declaration to be treated as if they belonged to the class.
    //
    // Make sure that `this` in an object literal getter or setter has the unknown type.
    testSame(
        """
        class Foo {
          bar = {
            get baz() {
              X: this;
            }
          };
        }
        """);
    Node thisNode = getLabeledStatement("X").statementNode.getOnlyChild();
    // We don't create unique types for object literals, so even though we know
    // the getter will (probably) only be called on this object, we just
    // use unknown for the type of `this`.
    assertNode(thisNode).hasJSTypeThat().isUnknown();

    testSame(
        """
        class Foo {
          static bar = {
            /** @this {string} */
            get baz() { Y: this; }
          };
        }
        """);

    thisNode = getLabeledStatement("Y").statementNode.getOnlyChild();
    assertNode(thisNode).hasJSTypeThat().isString();
  }

  @Test
  public void testMemberFunctionInObjectLitInPublicField() {
    // Previously a bug caused methods in object literals on the RHS of a field declaration to be
    // treated as if they were methods on the class.
    //
    // Make sure that `this` in an object literal method has the unknown type.
    testSame(
        """
        class Foo {
          bar = {
            /** @this {number} */
            member() {
              X: this;
            }
          };
        }
        """);

    Node thisNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(thisNode).hasJSTypeThat().isNumber();
  }

  @Test
  public void testClassDeclarationWithDeprecatedField() {
    testSame(
        """
        class Foo {
          /** @deprecated There is a madness to this method */
          a = 'hi';
        }
        """);
  }

  @Test
  public void testClassDeclarationWithDeprecatedComputedField() {
    testSame(
        """
        class Foo {
          /** @deprecated There is a madness to this method */
          ['a'] = 'hi';
        }
        """);
  }

  @Test
  public void testClassExpressionAssignment() {
    testSame("var Foo = class Bar {}");
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertThat(foo.isConstructor()).isTrue();
    FunctionType bar = (FunctionType) (findNameType("Bar", globalScope));
    assertThat(bar).isEqualTo(foo);
  }

  @Test
  public void testClassExpressionBleedingNameScope() {
    testSame(
        """
        var Foo = class Bar {
          constructor() {
            CTOR:;
          }
        };
        """);
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    TypedScope ctorBlockScope = getLabeledStatement("CTOR").enclosingScope;
    TypedScope ctorScope = ctorBlockScope.getParentScope();
    TypedScope classScope = ctorScope.getParentScope();
    assertScope(globalScope).declares("Foo").withTypeThat().isEqualTo(foo);
    assertScope(classScope).declares("Bar").directly().withTypeThat().isEqualTo(foo);
    assertScope(globalScope).doesNotDeclare("Bar");
  }

  @Test
  public void testClassExpression_nestedAssignmentToName() {
    testSame(
        externs("function somethingElse() {}"),
        // test a pattern that tsickle generates for decorated classes
        srcs(
            """
            var Foo_1;
            var Foo_2;
            var Foo = Foo_1 = Foo_2 = class Bar {};
            Foo = /** @type {typeof Foo} */ (somethingElse());
            /** @type {!Foo} */ var f;
            """));

    JSType foo = findNameType("Foo", globalScope);
    FunctionType bar = (FunctionType) findNameType("Bar", globalScope);
    assertThat(globalScope.getVar("Foo")).hasJSTypeThat().isEqualTo(bar);
    assertType(foo).isEqualTo(bar);
    assertThat(findNameType("f", globalScope)).isEqualTo(bar.getInstanceType());
  }

  @Test
  public void testClassExpression_nestedAssignmentToConstQName() {
    testSame(
        srcs(
            """
            var Foo_1;
            const ns = {};
            /** @const */
            ns.Foo = Foo_1 = class Bar {};
            /** @type {!ns.Foo} */ var f;
            """));

    FunctionType bar = (FunctionType) findNameType("Bar", globalScope);
    assertThat(findNameType("f", globalScope)).isEqualTo(bar.getInstanceType());
  }

  @Test
  public void testClassExpression_nestedAssignmentToNonConstQName() {
    testSame(
        externs("function somethingElse() {}"),
        // test a pattern that tsickle generates for decorated classes
        srcs(
            """
            var Foo_1;
            const ns = {};
            ns.Foo = Foo_1 = class Bar {};
            ns.Foo = Foo_1 = /** @type {typeof ns.Foo} */ (somethingElse());
            /** @type {!ns.Foo} */ var f;
            """));

    FunctionType bar = (FunctionType) findNameType("Bar", globalScope);
    assertThat(findNameType("f", globalScope)).isEqualTo(bar.getInstanceType());
  }

  @Test
  public void testClassExpressionWithMethod() {
    testSame(
        """
        var Foo = class {
          /** @param {string} arg */
          method(arg) {
            METHOD:;
          }
        };
        """);
    TypedScope methodBlockScope = getLabeledStatement("METHOD").enclosingScope;
    TypedScope methodScope = methodBlockScope.getParentScope();
    assertScope(methodScope).declares("arg").directly().withTypeThat().isString();

    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo.getInstanceType())
        .withTypeOfProp("method")
        .toStringIsEqualTo("function(this:Foo, string): undefined");
  }

  @Test
  public void testClassExpressionWithStaticMethod() {
    testSame(
        """
        var Foo = class {
          static method(/** string */ arg) {
            METHOD:;
            var /** number */ foo;
          }
        }
        """);
    TypedScope methodBlockScope = getLabeledStatement("METHOD").enclosingScope;
    TypedScope methodScope = methodBlockScope.getParentScope();
    assertScope(methodBlockScope).declares("foo").directly().withTypeThat().isNumber();
    assertScope(methodScope).declares("arg").directly().withTypeThat().isString();

    FunctionType method = (FunctionType) methodScope.getRootNode().getJSType();
    assertType(method).toStringIsEqualTo("function(this:(typeof Foo), string): undefined");
    FunctionType foo = (FunctionType) (findNameType("Foo", globalScope));
    assertType(foo).withTypeOfProp("method").isEqualTo(method);
  }

  @Test
  public void testClassExpressionWithStaticClassAssignedLater() {
    testSame(
        """
        var Foo = class {};
        // even though there's no JSDoc, this should be treated as a type declaration.
        Foo.Something = class {};
        """);
    assertScope(globalScope)
        .declares("Foo")
        .directly()
        .withTypeThat()
        .toStringIsEqualTo("(typeof Foo)");
    assertScope(globalScope)
        .declares("Foo.Something")
        .directly()
        .withTypeThat()
        .toStringIsEqualTo("(typeof Foo.Something)");
  }

  @Test
  public void testClassExpressionInCallback() {
    testSame(
        """
        function use(arg) {}
        use(class Bar {
          constructor() {
            CTOR:;
          }
        });
        """);
    TypedScope ctorBlockScope = getLabeledStatement("CTOR").enclosingScope;
    TypedScope ctorScope = ctorBlockScope.getParentScope();
    TypedScope classScope = ctorScope.getParentScope();
    assertScope(classScope)
        .declares("Bar")
        .directly()
        .withTypeThat()
        // TODO(sdh): Print a better name (https://github.com/google/closure-compiler/issues/2982)
        .toStringIsEqualTo("(typeof <anonymous@testcode:2>)");
    assertScope(globalScope).doesNotDeclare("Bar");
  }

  @Test
  public void testClassStaticBlockAndVariableDeclarations() {
    testSame(
        """
        class Foo {
          static {
            STATIC:;
            let /** string */ x;
            const /** number */ y = 5;
            var /** boolean */ z;
          }
        };
        """);
    TypedScope staticScope = getLabeledStatement("STATIC").enclosingScope;
    TypedScope classScope = staticScope.getParentScope();
    assertScope(globalScope)
        .declares("Foo")
        .directly()
        .withTypeThat()
        .toStringIsEqualTo("(typeof Foo)");
    assertScope(staticScope).declares("x").directly().withTypeThat().isString();
    assertScope(classScope).doesNotDeclare("x");
    assertScope(globalScope).doesNotDeclare("x");
    assertScope(staticScope).declares("y").directly().withTypeThat().isNumber();
    assertScope(classScope).doesNotDeclare("y");
    assertScope(globalScope).doesNotDeclare("y");
    assertScope(staticScope).declares("z").directly().withTypeThat().isBoolean();
    assertScope(classScope).doesNotDeclare("z");
    assertScope(globalScope).doesNotDeclare("z");
  }

  @Test
  public void testMultipleClassStaticBlocks() {
    testSame(
        """
        class Foo {
          static {
            STATIC1:;
            var /** number */ x;
          }
          static {
            STATIC2:;
            var /** string */ y;
          }
        };
        """);
    TypedScope staticScope1 = getLabeledStatement("STATIC1").enclosingScope;
    TypedScope staticScope2 = getLabeledStatement("STATIC2").enclosingScope;
    TypedScope classScope = staticScope1.getParentScope();
    assertScope(globalScope)
        .declares("Foo")
        .directly()
        .withTypeThat()
        .toStringIsEqualTo("(typeof Foo)");
    assertThat(staticScope2.getParentScope()).isEqualTo(classScope);
    assertScope(staticScope1).declares("x").directly().withTypeThat().isNumber();
    assertScope(staticScope2).declares("y").directly().withTypeThat().isString();
    assertScope(staticScope1).doesNotDeclare("y");
    assertScope(staticScope2).doesNotDeclare("x");
    assertScope(classScope).doesNotDeclare("x");
    assertScope(classScope).doesNotDeclare("y");
    assertScope(globalScope).doesNotDeclare("x");
    assertScope(globalScope).doesNotDeclare("y");
  }

  @Test
  public void testClassStaticBlockWithIf() {
    testSame(
        """
        class Foo {
          static {
            STATIC:;
            if(true) {
              IF:;
              let /** boolean */ x;
              var /** string */ y;
              const /** number */ z = 1;
            }
          }
        };
        """);
    TypedScope ifScope = getLabeledStatement("IF").enclosingScope;
    TypedScope staticScope = getLabeledStatement("STATIC").enclosingScope;
    TypedScope classScope = staticScope.getParentScope();
    assertScope(globalScope)
        .declares("Foo")
        .directly()
        .withTypeThat()
        .toStringIsEqualTo("(typeof Foo)");
    assertScope(ifScope).declares("x").directly().withTypeThat().isBoolean();
    assertScope(staticScope).doesNotDeclare("x");
    assertScope(classScope).doesNotDeclare("x");
    assertScope(globalScope).doesNotDeclare("x");
    assertScope(ifScope).declares("y").onSomeParent().withTypeThat().isString();
    assertScope(staticScope).declares("y").directly().withTypeThat().isString();
    assertScope(classScope).doesNotDeclare("y");
    assertScope(globalScope).doesNotDeclare("y");
    assertScope(ifScope).declares("z").directly().withTypeThat().isNumber();
    assertScope(staticScope).doesNotDeclare("z");
    assertScope(classScope).doesNotDeclare("z");
    assertScope(globalScope).doesNotDeclare("z");
  }

  @Test
  public void testClassStaticBlockWithFunction() {
    testSame(
        """
        class Foo {
          static {
            STATIC:;
            function bar() {
              FUNCTION:;
            }
          }
        };
        """);
    TypedScope functionScope = getLabeledStatement("FUNCTION").enclosingScope;
    TypedScope staticScope = getLabeledStatement("STATIC").enclosingScope;
    TypedScope classScope = staticScope.getParentScope();
    assertThat(functionScope.getParentScope().getParentScope()).isEqualTo(staticScope);
    assertScope(globalScope)
        .declares("Foo")
        .directly()
        .withTypeThat()
        .toStringIsEqualTo("(typeof Foo)");
    assertScope(staticScope)
        .declares("bar")
        .directly()
        .withTypeThat()
        .toStringIsEqualTo("function(): undefined");
    assertScope(classScope).doesNotDeclare("bar");
    assertScope(globalScope).doesNotDeclare("bar");
  }

  @Test
  public void testClassStaticBlockWithThisLHS() {
    testSame(
        """
        class Foo {
          static {
            STATIC:;
            /** @type {string} */
            this.x = 'hello';
          }
        };
        """);
    FunctionType fooCtor = globalScope.getVar("Foo").getType().assertFunctionType();
    TypedScope staticScope = getLabeledStatement("STATIC").enclosingScope;
    TypedScope classScope = staticScope.getParentScope();
    assertThat(fooCtor.hasProperty("x")).isTrue();
    assertType(fooCtor.getPropertyType("x")).isString();
    assertThat(fooCtor.isPropertyTypeDeclared("x")).isTrue();
    assertThat(fooCtor.isPropertyTypeInferred("x")).isFalse();
    assertScope(staticScope).doesNotDeclare("x");
    assertScope(classScope).doesNotDeclare("x");
  }

  @Test
  public void testClassStaticBlockWithThisRHS() {
    testSame(
        """
        class Foo {
          /** @type {number} */
          static y = 1;
          static {
            STATIC:;
            var x = this.y;
          }
        };
        """);
    FunctionType fooCtor = globalScope.getVar("Foo").getType().assertFunctionType();
    TypedScope staticScope = getLabeledStatement("STATIC").enclosingScope;
    TypedScope classScope = staticScope.getParentScope();
    assertThat(fooCtor.hasOwnDeclaredProperty("y")).isTrue();
    assertType(fooCtor.getPropertyType("y")).isNumber();
    assertThat(fooCtor.hasProperty("x")).isFalse();
    assertScope(staticScope).declares("x").directly().withTypeThat().isNumber();
    assertScope(classScope).doesNotDeclare("x");
  }

  @Test
  public void testSuperInEmptyClassStaticBlock() {
    testSame(
        """
        class Foo {}
        class Bar extends Foo {
          static {
            STATIC:;
          }
        };
        """);
    FunctionType fooCtor = globalScope.getVar("Foo").getType().assertFunctionType();
    TypedScope staticScope = getLabeledStatement("STATIC").enclosingScope;
    TypedScope classBarScope = staticScope.getParentScope();
    assertScope(staticScope).declares("super").directly().withTypeThat().isEqualTo(fooCtor);
    assertScope(classBarScope).doesNotDeclare("super");
  }

  @Test
  public void testForLoopIntegration() {
    testSame("var y = 3; for (var x = true; x; y = x) {}");

    TypedVar y = globalScope.getVar("y");
    assertThat(y.isTypeInferred()).isTrue();
    assertThat(y.getType().toString()).isEqualTo("(boolean|number)");
  }

  @Test
  public void testConstructorAlias() {
    testSame(
        """
        /** @constructor */ var Foo = function() {};
        /** @constructor */ var FooAlias = Foo;
        """);
    assertThat(registry.getType(null, "FooAlias").toString()).isEqualTo("Foo");
    assertType(registry.getType(null, "FooAlias")).isEqualTo(registry.getType(null, "Foo"));
  }

  @Test
  public void testNamespacedConstructorAlias() {
    testSame(
        """
        /** @constructor */ goog.Foo = function() {};
        /** @constructor */ goog.FooAlias = goog.Foo;
        """);
    assertThat(registry.getType(null, "goog.FooAlias").toString()).isEqualTo("goog.Foo");
    assertType(registry.getType(null, "goog.FooAlias"))
        .isEqualTo(registry.getType(null, "goog.Foo"));
  }

  @Test
  public void testConstructorAliasWithPrototypeMethodHasCorrectThisType() {
    testSame(
        """
        class Foo {}
        const FooAlias = Foo;
        FooAlias.prototype.fn = function() {};
        """);

    Node fnAliasNameNode =
        findQualifiedNameNode("FooAlias.prototype.fn", globalScope.getRootNode());

    JSType fooCtorType = findNameType("Foo", globalScope);
    JSType fooType = fooCtorType.toMaybeFunctionType().getInstanceType();

    assertType(fnAliasNameNode.getJSType())
        .isFunctionTypeThat()
        .hasTypeOfThisThat()
        .isEqualTo(fooType);
  }

  @Test
  public void testConstructorAliasPrototypePropIsInScope() {
    testSame(
        """
        class Foo {}
        const FooAlias = Foo;
        FooAlias.prototype.fn = function() {};
        """);

    TypedVar fooAliasPrototype = globalScope.getSlot("FooAlias.prototype");
    assertThat(fooAliasPrototype).isNotNull();
    assertType(fooAliasPrototype.getType())
        .isEqualTo(globalScope.getSlot("Foo.prototype").getType());
  }

  @Test
  public void testNoConstructorAlias_namespaceIsShadowedInParameter() {
    testWarning(
        """
        const a = {};
        a.B = class {};
        function f(a) {
          const B = a.B; // This does /not/ create a constructor alias as `a` is shadowed.
          var /** !B */ b;
        }
        """,
        RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
  }

  @Test
  public void testNoTypeResolution_namespaceShadowedAfterReference() {
    testWarning(
        """
        const a = {};
        a.B = class {};
        function f() {
          var /** !a.B */ b; // Treat 'a' as coming from 'let a;' below.
          let a;
        }
        """,
        RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
  }

  @Test
  public void testNoTypeResolution_namespaceShadowedOutsideScopeAfterReference() {
    testWarning(
        """
        const a = {};
        a.B = class {};
        function f() {
          { var /** !a.B */ b; } // Treat 'a' as coming from 'let a;' below.
          let a;
        }
        """,
        RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
  }

  @Test
  public void testNoTypeResolution_namespaceShadowedAfterTemplateScopeReference() {
    testWarning(
        """
        const a = {};
        a.B = class {};
        function f() {
          /**
           * @param {(!a.B|T)} b // Treat 'a' as coming from 'let a;' below.
           * @template T
           */
          function f(b) {}
          let a;
        }
        """,
        RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
  }

  @Test
  public void testTemplateType1() {
    testSame(
        """
        /**
         * @param {function(this:T, ...)} fn
         * @param {T} thisObj
         * @template T
         */
        function bind(fn, thisObj) {}
        /** @constructor */
        function Foo() {}
        /** @return {number} */
        Foo.prototype.baz = function() {};
        bind(function() { var g = this; var f = this.baz(); }, new Foo());
        """);
    assertThat(findNameType("g", lastLocalScope).toString()).isEqualTo("Foo");
    assertThat(findNameType("f", lastLocalScope).toString()).isEqualTo("number");
  }

  @Test
  public void testTemplateType2() {
    testSame(
        """
        /**
         * @param {T} x
         * @return {T}
         * @template T
         */
        function f(x) {
          return x;
        }
        /** @type {string} */
        var val = 'hi';
        var result = f(val);
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("string");
  }

  @Test
  public void testTemplateType2a() {
    testSame(
        """
        /**
         * @param {T} x
         * @return {T|undefined}
         * @template T
         */
        function f(x) {
          return x;
        }
        /** @type {string} */
        var val = 'hi';
        var result = f(val);
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("(string|undefined)");
  }

  @Test
  public void testTemplateType2b() {
    testSame(
        """
        /**
         * @param {T} x
         * @return {T}
         * @template T
         */
        function f(x) {
          return x;
        }
        /** @type {string|undefined} */
        var val = 'hi';
        var result = f(val);
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("(string|undefined)");
  }

  @Test
  public void testTemplateType3() {
    testSame(
        """
        /**
         * @param {T} x
         * @return {T}
         * @template T
         */
        function f(x) {
          return x;
        }
        /** @type {string} */
        var val1 = 'hi';
        var result1 = f(val1);
        /** @type {number} */
        var val2 = 0;
        var result2 = f(val2);
        """);

    assertThat(findNameType("result1", globalScope).toString()).isEqualTo("string");
    assertThat(findNameType("result2", globalScope).toString()).isEqualTo("number");
  }

  @Test
  public void testTemplateType4() {
    testSame(
        """
        /**
         * @param {T} x
         * @return {T}
         * @template T
         */
        function f(x) {
          return x;
        }
        /** @type {!Array<string>} */
        var arr = [];
        (function() {var result = f(arr);})();
        """);

    JSType resultType = findNameType("result", lastLocalScope);
    assertThat(resultType.toString()).isEqualTo("Array<string>");
  }

  @Test
  public void testTemplateType4a() {
    testSame(
        """
        /**
         * @param {function():T} x
         * @return {T}
         * @template T
         */
        function f(x) {
          return x;
        }
        /** @return {string} */
        var g = function(){return 'hi'};
        (function() {var result = f(g);})();
        """);

    JSType resultType = findNameType("result", lastLocalScope);
    assertThat(resultType.toString()).isEqualTo("string");
  }

  @Test
  public void testTemplateType4b() {
    testSame(
        """
        /**
         * @param {function(T):void} x
         * @return {T}
         * @template T
         */
        function f(x) {
          return x;
        }
        /** @param {string} x */
        var g = function(x){};
        (function() {var result = f(g);})();
        """);

    JSType resultType = findNameType("result", lastLocalScope);
    assertThat(resultType.toString()).isEqualTo("string");
  }

  @Test
  public void testTemplateType5() {
    testSame(
        """
        /**
         * @param {Array<T>} arr
         * @return {!Array<T>}
         * @template T
         */
        function f(arr) {
          return arr;
        }
        /** @type {Array<string>} */
        var arr = [];
        var result = f(arr);
        """);

    assertThat(findNameTypeStr("result", globalScope)).isEqualTo("Array<string>");
  }

  @Test
  public void testTemplateType6() {
    testSame(
        """
        /**
         * @param {Array<T>|string|undefined} arr
         * @return {!Array<T>}
         * @template T
         */
        function f(arr) {
          return arr;
        }
        /** @type {Array<string>} */
        var arr = [];
        var result = f(arr);
        """);

    assertThat(findNameTypeStr("result", globalScope)).isEqualTo("Array<string>");
  }

  @Test
  public void testTemplateType7() {
    testSame(
        """
        goog.array = {};
        /**
         * @param {Array<T>} arr
         * @param {function(this:S, !T, number, !Array<!T>):boolean} f
         * @param {!S=} opt_obj
         * @return {!Array<T>}
         * @template T,S
         */
        goog.array.filter = function(arr, f, opt_obj) {
          var res = [];
          for (var i = 0; i < arr.length; i++) {
             const val = arr[i];
             if (f.call(opt_obj, val, i, arr)) {
                res.push(val);
             }
          }
          return res;
        }
        /** @constructor */
        function Foo() {}
        /** @type {Array<string>} */
        var arr = [];
        var result = goog.array.filter(arr,
          function(a,b,c) {var self=this;}, new Foo());
        """);

    assertThat(findNameType("self", lastFunctionScope).toString()).isEqualTo("Foo");
    assertThat(findNameType("a", lastFunctionScope).toString()).isEqualTo("string");
    assertThat(findNameType("b", lastFunctionScope).toString()).isEqualTo("number");
    assertThat(findNameType("c", lastFunctionScope).toString()).isEqualTo("Array<string>");
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("Array<string>");
  }

  @Test
  public void testTemplateType7b() {
    testSame(
        """
        goog.array = {};
        /**
         * @param {Array<T>} arr
         * @param {function(this:S, !T, number, !Array<T>):boolean} f
         * @param {!S=} opt_obj
         * @return {!Array<T>}
         * @template T,S
         */
        goog.array.filter = function(arr, f, opt_obj) {
          var res = [];
          for (var i = 0; i < arr.length; i++) {
             const val = arr[i];
             if (f.call(opt_obj, val, i, arr)) {
                res.push(val);
             }
          }
          return res;
        }
        /** @constructor */
        function Foo() {}
        /** @type {Array<string>} */
        var arr = [];
        var result = goog.array.filter(arr,
          function(a,b,c) {var self=this;}, new Foo());
        """);

    assertThat(findNameType("self", lastFunctionScope).toString()).isEqualTo("Foo");
    assertThat(findNameType("a", lastFunctionScope).toString()).isEqualTo("string");
    assertThat(findNameType("b", lastFunctionScope).toString()).isEqualTo("number");
    assertThat(findNameType("c", lastFunctionScope).toString()).isEqualTo("Array<string>");
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("Array<string>");
  }

  @Test
  public void testTemplateType7c() {
    testSame(
        """
        goog.array = {};
        /**
         * @param {Array<T>} arr
         * @param {function(this:S, T, number, Array<T>):boolean} f
         * @param {!S=} opt_obj
         * @return {!Array<T>}
         * @template T,S
         */
        goog.array.filter = function(arr, f, opt_obj) {
          var res = [];
          for (var i = 0; i < arr.length; i++) {
             const val = arr[i];
             if (f.call(opt_obj, val, i, arr)) {
                res.push(val);
             }
          }
          return res;
        }
        /** @constructor */
        function Foo() {}
        /** @type {Array<string>} */
        var arr = [];
        var result = goog.array.filter(arr,
          function(a,b,c) {var self=this;}, new Foo());
        """);

    assertThat(findNameType("self", lastFunctionScope).toString()).isEqualTo("Foo");
    assertThat(findNameType("a", lastFunctionScope).toString()).isEqualTo("string");
    assertThat(findNameType("b", lastFunctionScope).toString()).isEqualTo("number");
    assertThat(findNameType("c", lastFunctionScope).toString()).isEqualTo("(Array<string>|null)");
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("Array<string>");
  }

  @Test
  @Ignore
  public void testTemplateType8() {
    // TODO(johnlenz): somehow allow templated typedefs
    testSame(
        """
        /** @constructor */ NodeList = function() {};
        /** @constructor */ Arguments = function() {};
        var goog = {};
        goog.array = {};
        /**
         * @typedef {Array<T>|NodeList|Arguments|{length: number}}
         * @template T
         */
        goog.array.ArrayLike;
        /**
         * @param {function(this:T, ...)} fn
         * @param {T} thisObj
         * @template T
         */
        function bind(fn, thisObj) {}
        /** @constructor */
        function Foo() {}
        /** @return {number} */
        Foo.prototype.baz = function() {};
        bind(function() { var g = this; var f = this.baz(); }, new Foo());
        """);
    assertThat(findNameType("g", lastLocalScope).toString()).isEqualTo("T");
    assertThat(findNameType("g", lastLocalScope).equals(registry.getType(null, "Foo"))).isTrue();
    assertThat(findNameType("f", lastLocalScope).toString()).isEqualTo("number");
  }

  @Test
  public void testTemplateType9() {
    testSame(
        """
        /** @constructor */
        function Foo() {}
        /**
         * @this {T}
         * @return {T}
         * @template T
         */
        Foo.prototype.method = function() {};
        /**
         * @constructor
         * @extends {Foo}
         */
        function Bar() {}

        var g = new Bar().method();
        """);
    assertThat(findNameType("g", globalScope).toString()).isEqualTo("Bar");
  }

  @Test
  public void testTemplateType10() {
    // NOTE: we would like the type within the function to remain "Foo"
    // we can handle this by support template type like "T extends Foo"
    // to provide a "minimum" type for "Foo" within the function body.
    testSame(
        """
        /** @constructor */
        function Foo() {}

        /**
         * @this {T}
         * @return {T} fn
         * @template T
         */
        Foo.prototype.method = function() {var g = this;};
        """);
    assertThat(findNameType("g", lastLocalScope).toString()).isEqualTo("T");
  }

  @Test
  public void testTemplateType_shadowingNativeType() {
    // Verify that some special handling of externs for types built-in to JSTypeRegistry, like
    // Array, does not incorrectly run for local types shadowing native types.
    testSame(
        """
        function local() {
          /** @template U @constructor @param {U} e*/
          function Array(e) { this.e = e; }
          /** @return {U} */ Array.prototype.first = function() { return this.e; };
          /** @type {!Array<string>} */ let arr = new Array();

          const first = arr.first();
          LOCAL: local;
        }
        """);

    assertType(findNameType("arr", lastLocalScope)).toStringIsEqualTo("Array<string>");
    assertType(findNameType("first", lastLocalScope)).isString();

    assertType(findNameType("Array", lastLocalScope))
        .isNotEqualTo(registry.getNativeType(JSTypeNative.ARRAY_FUNCTION_TYPE));
  }

  @Test
  public void testTemplatedThis_inClassInstanceMethod_isInferredToBe_receiverType() {
    testSame(
        """
        class Foo {
          /**
           * @template THIS
           * @this {THIS}
           * @return {THIS}
           */
          clone() { return this; }
        }

        var result = new Foo().clone();
        """);

    assertType(findNameType("result", globalScope)).toStringIsEqualTo("Foo");
  }

  @Test
  public void testTemplatedThis_inClassInstanceMethod_invokedOnSuper_isInferredToBe_subtype() {
    testSame(
        """
        class Foo {
          /**
           * @template THIS
           * @this {THIS}
           * @return {THIS}
           */
          clone() { return this; }
        }

        class SubFoo extends Foo {
          other() {
            LABEL: super.clone();
          }
        }
        """);

    Node superCloneCall = getLabeledStatement("LABEL").statementNode.getOnlyChild();
    assertType(superCloneCall.getJSType()).toStringIsEqualTo("SubFoo");
  }

  @Test
  public void testTemplateType11() {
    testSame(
        """
        /**
         * @this {T}
         * @return {T} fn
         * @template T
         */
        var method = function() {};
        /**
         * @constructor
         */
        function Bar() {}

        var g = method().call(new Bar());
        """);
    // NOTE: we would like this to be "Bar"
    assertThat(findNameType("g", globalScope).toString()).isEqualTo("?");
  }

  @Test
  public void testTemplateType12() {
    testSame(
        """
        /** @constructor */
        function Foo() {}

        /**
         * @this {Array<T>|{length:number}}
         * @return {T} fn
         * @template T
         */
        Foo.prototype.method = function() {var g = this;};
        """);
    assertThat(findNameType("g", lastLocalScope).toString())
        .isEqualTo("(Array<T>|{length: number})");
  }

  @Test
  @Ignore
  public void testTemplateType13() {
    // TODO(johnlenz): allow template types in @type function expressions
    testSame(
        """
        /**
         * @type {function(T):T}
         * @template T
         */
        var f;
        /** @type {string} */
        var val = 'hi';
        var result = f(val);
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("(string|undefined)");
  }

  @Test
  public void testClassTemplateType1() {
    // Verify that template types used in method signature are resolved.
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function C() {};

        /** @return {T} */
        C.prototype.method = function() {}

        /** @type {C<string>} */ var x = new C();
        var result = x.method();
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("string");
  }

  @Test
  public void testClassTemplateType2() {
    // Verify that template types used in method signature on namespaced
    // objects are resolved.
    testSame(
        """
        /** @const */ var ns = {};
        /**
         * @constructor
         * @template T
         */
        ns.C = function() {};

        /** @return {T} */
        ns.C.prototype.method = function() {}

        /** @type {ns.C<string>} */ var x = new ns.C();
        var result = x.method();
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("string");
  }

  @Test
  public void testClassTemplateType3() {
    // Verify that template types used for instance properties are recognized.
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function C() {
          /** @type {T} */
          this.foo;
        };

        /** @type {C<string>} */ var x = new C();
        var result = x.foo;
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("string");
  }

  @Test
  public void testClassTemplateType4_instanceProperty() {
    // Verify that template types used for instance properties are recognized.
    testSame(
        """
        /** @const */ var ns = {};
        /**
         * @constructor
         * @template T
         */
        ns.C = function() {
          /** @type {T} */
          this.foo;
        };

        /** @type {ns.C<string>} */ var x = new ns.C();
        var result = x.foo;
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("string");
  }

  @Test
  public void testClassTemplateType4_instancePropertyWithInheritance() {
    // Verify that template types used for instance properties are recognized
    // when shadowing a template type on the superclass with the same name.
    testSame(
        """
        /**
         * @template T
         */
        class Parent {}

        /**
         * @template T
         * @extends {Parent<number>}
         */
        class Child extends Parent {
          constructor() {
            super();
            /** @type {T} */
            this.foo;
          }
        };

        /** @type {!Child<string>} */ var x = new Child();
        var result = x.foo;
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("string");
  }

  @Test
  public void testClassTemplateType5() {
    // Verify that template types used for prototype properties in stub
    // declarations are recognized.
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function C() {
        };

        /** @type {T} */
        C.prototype.foo;

        /** @type {C<string>} */ var x = new C();
        var result = x.foo;
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("string");
  }

  @Test
  public void testClassTemplateType6() {
    // Verify that template types used for prototype properties in assignment
    // expressions are recognized.
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function C() {
        };

        /** @type {T} */
        C.prototype.foo = 1;

        /** @type {C<string>} */ var x = new C();
        var result = x.foo;
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("string");
  }

  @Test
  public void testClassTemplateType7() {
    // Verify that template types used in prototype methods are recognized.
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function C() {};

        C.prototype.method = function() {
          /** @type {T} */ var local;
        }
        """);
    assertThat(findNameType("local", lastLocalScope).toString()).isEqualTo("T");
  }

  @Test
  public void testClassTemplateType8() {
    // Verify that template types used in casts are recognized.
    testSame(
        externs("/** @type {?} */ let unknown;"),
        srcs(
            """
            /**
             * @constructor
             * @template T
             */
            function C() {};

            C.prototype.method = function() {
              var local = /** @type {T} */ (unknown);
            }
            """));
    assertThat(findNameType("local", lastLocalScope).toString()).isEqualTo("T");
  }

  @Test
  public void testClassTemplateInheritance1() {
    // Verify that template type inheritance works for prototype properties.
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function C() {};

        /** @type {T} */
        C.prototype.foo = 1;

        /**
         * @constructor
         * @template T, U
         * @extends {C<U>}
         */
        function D() {};

        /** @type {T} */
        D.prototype.bar;

        /** @type {D<string, number>} */ var x = new D();
        var result1 = x.foo;
        var result2 = x.bar;
        """);
    assertThat(findNameType("result1", globalScope).toString()).isEqualTo("number");
    assertThat(findNameType("result2", globalScope).toString()).isEqualTo("string");
  }

  @Test
  public void testClassTemplateInheritance2() {
    // Verify that template type inheritance works for properties and methods.
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function C() {};

        /** @return {T} */
        C.prototype.method1 = function() {}

        /**
         * @constructor
         * @template T, U
         * @extends {C<U>}
         */
        function D() {};

        /** @return {T} */
        D.prototype.method2 = function() {}

        /** @type {D<boolean, string>} */ var x = new D();
        var result1 = x.method1();
        var result2 = x.method2();
        """);
    assertThat(findNameType("result1", globalScope).toString()).isEqualTo("string");
    assertThat(findNameType("result2", globalScope).toString()).isEqualTo("boolean");
  }

  @Test
  public void testClassTemplateInheritance3() {
    // Verify that template type inheritance works when the superclass template
    // types are not specified.
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function C() {
          /** @type {T} */
          this.foo;
        };

        /**
         * @constructor
         * @template T
         * @extends {C}
         */
        function D() {
          /** @type {T} */
          this.bar;
        };

        /** @type {D<boolean>} */ var x = new D();
        var result1 = x.foo;
        var result2 = x.bar;
        """);
    assertThat(findNameType("result1", globalScope).toString()).isEqualTo("?");
    // TODO(nicksantos): There's a bug where the template name T clashes between
    // D and C.
    // assertEquals("boolean", findNameType("result2", globalScope).toString());
  }

  @Test
  public void testClassTemplateInheritance4() {
    // Verify that overriding methods works with template type inheritance.
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function C() {};

        /** @return {T} */
        C.prototype.method = function() {}

        /**
         * @constructor
         * @template T, U
         * @extends {C<U>}
         */
        function D() {};

        /** @override */
        D.prototype.method = function() {}

        /** @type {D<boolean, string>} */ var x = new D();
        var result = x.method();
        """);
    assertThat(findNameType("result", globalScope).toString()).isEqualTo("string");
  }

  @Test
  public void testClassTemplateInheritance5() {
    // Verify that overriding methods works with template type inheritance.
    testSame(
        """
        /**
         * @constructor
         * @template T
         */
        function C() {};

        /** @return {T} */
        C.prototype.method1 = function() {}

        /**
         * @constructor
         * @template T, U
         * @extends {C<U>}
         */
        function D() {};

        /** @return {T} */
        D.prototype.method2 = function() {}

        /** @type {D<string, boolean>} */ var x = new D();
        /** @type {C<boolean>} */ var y = x;
        /** @type {C} */ var z = y;
        var result1 = x.method2();
        var result2 = y.method1();
        var result3 = z.method1();
        """);
    assertThat(findNameType("result1", globalScope).toString()).isEqualTo("string");
    assertThat(findNameType("result2", globalScope).toString()).isEqualTo("boolean");
    assertThat(findNameType("result3", globalScope).toString()).isEqualTo("T");
  }

  @Test
  public void testClosureParameterTypesWithoutJSDoc() {
    testSame(
        """
        /**
         * @param {function(!Object)} bar
         */
        function foo(bar) {}
        foo(function(baz) { var f = baz; })
        """);
    assertThat(findNameType("f", lastLocalScope).toString()).isEqualTo("Object");
  }

  @Test
  public void testDuplicateExternProperty1() {
    testSame(
        """
        /** @constructor */ function Foo() {}
        Foo.prototype.bar;
        /** @type {number} */ Foo.prototype.bar; var x = (new Foo).bar;
        """);
    assertThat(findNameType("x", globalScope).toString()).isEqualTo("number");
  }

  @Test
  public void testDuplicateExternProperty2() {
    testSame(
        """
        /** @constructor */ function Foo() {}
        /** @type {number} */ Foo.prototype.bar;
        Foo.prototype.bar; var x = (new Foo).bar;
        """);
    assertThat(findNameType("x", globalScope).toString()).isEqualTo("number");
  }

  @Test
  public void testAbstractMethodOnPrototype() {
    testSame(
        """
        /** @type {!Function} */ var abstractMethod;
        /** @constructor */ function Foo() {}
        /** @param {number} x */ Foo.prototype.bar = abstractMethod;
        """);
    assertThat(findNameType("abstractMethod", globalScope).toString()).isEqualTo("Function");

    FunctionType ctor = (FunctionType) findNameType("Foo", globalScope);
    ObjectType instance = ctor.getInstanceType();
    assertThat(instance.toString()).isEqualTo("Foo");

    ObjectType proto = instance.getImplicitPrototype();
    assertThat(proto.toString()).isEqualTo("Foo.prototype");

    assertThat(proto.getPropertyType("bar").toString()).isEqualTo("function(this:Foo, number): ?");
  }

  @Test
  public void testAbstractMethod_cannotBeAssignedToName() {
    testSame(
        "/** @type {!Function} */ var abstractMethod;\n"
            // this results in 'y' being typed as the type of 'abstractMethod'
            + "/** @param {number} x */ var y = abstractMethod;");
    assertThat(findNameType("y", globalScope).toString()).isEqualTo("Function");
    assertThat(globalScope.getVar("y").getType().toString()).isEqualTo("Function");
  }

  @Test
  public void testAbstractMethod_inPrototypeObjectLiteral() {
    testSame(
        """
        /** @type {!Function} */ var abstractMethod;
        /** @constructor */ function Foo() {}
        Foo.prototype = {/** @param {number} x */ bar: abstractMethod};
        """);
    assertThat(findNameType("abstractMethod", globalScope).toString()).isEqualTo("Function");

    FunctionType ctor = (FunctionType) findNameType("Foo", globalScope);
    ObjectType instance = ctor.getInstanceType();
    assertThat(instance.toString()).isEqualTo("Foo");

    ObjectType proto = instance.getImplicitPrototype();
    assertThat(proto.toString()).isEqualTo("Foo.prototype");

    assertType(proto.getPropertyType("bar")).toStringIsEqualTo("function(this:Foo, number): ?");
  }

  @Test
  public void testReturnTypeInference1() {
    testSame("function f() {}");
    assertThat(findNameType("f", globalScope).toString()).isEqualTo("function(): undefined");
  }

  @Test
  public void testReturnTypeInference2() {
    testSame("/** @return {?} */ function f() {}");
    assertThat(findNameType("f", globalScope).toString()).isEqualTo("function(): ?");
  }

  @Test
  public void testReturnTypeInference3() {
    testSame("function f() {x: return 3;}");
    assertThat(findNameType("f", globalScope).toString()).isEqualTo("function(): ?");
  }

  @Test
  public void testReturnTypeInference4() {
    testSame("function f() { throw 'error'; }");
    assertThat(findNameType("f", globalScope).toString()).isEqualTo("function(): ?");
  }

  @Test
  public void testReturnTypeInference5() {
    testSame("function f() { if (true) { return 1; } }");
    assertThat(findNameType("f", globalScope).toString()).isEqualTo("function(): ?");
  }

  @Test
  public void testLiteralTypesInferred() {
    setAcceptedLanguage(LanguageMode.UNSUPPORTED);
    testSame("null + true + false + 0 + '' + 0n + {}");
    assertThat(findTokenType(Token.NULL, globalScope).toString()).isEqualTo("null");
    assertThat(findTokenType(Token.TRUE, globalScope).toString()).isEqualTo("boolean");
    assertThat(findTokenType(Token.FALSE, globalScope).toString()).isEqualTo("boolean");
    assertThat(findTokenType(Token.NUMBER, globalScope).toString()).isEqualTo("number");
    assertThat(findTokenType(Token.STRINGLIT, globalScope).toString()).isEqualTo("string");
    assertThat(findTokenType(Token.BIGINT, globalScope).toString()).isEqualTo("bigint");
    assertThat(findTokenType(Token.OBJECTLIT, globalScope).toString()).isEqualTo("{}");
  }

  @Test
  public void testGlobalQualifiedNameInLocalScope() {
    // TODO(b/154044898): re-enable type info validation
    disableTypeInfoValidation();
    testSame(
        """
        var ns = {};
        (function() {
            /** @param {number} x */ ns.foo = function(x) {}; })();
        (function() { ns.foo(3); })();
        """);
    assertThat(globalScope.getVar("ns.foo")).isNotNull();
    assertThat(globalScope.getVar("ns.foo").getType().toString())
        .isEqualTo("function(number): undefined");
  }

  @Test
  public void testDeclaredObjectLitProperty1() {
    testSame("var x = {/** @type {number} */ y: 3};");
    ObjectType xType = ObjectType.cast(globalScope.getVar("x").getType());
    assertThat(xType.getPropertyType("y").toString()).isEqualTo("number");
    assertThat(xType.toString()).isEqualTo("{y: number}");
  }

  @Test
  public void testDeclaredObjectLitProperty2() {
    testSame("var x = {/** @param {number} z */ y: function(z){}};");
    ObjectType xType = ObjectType.cast(globalScope.getVar("x").getType());
    assertThat(xType.getPropertyType("y").toString()).isEqualTo("function(number): undefined");
    assertThat(xType.toString()).isEqualTo("{y: function(number): undefined}");
  }

  @Test
  public void testDeclaredObjectLitProperty3() {
    testSame(
        """
        function f() {
          var x = {/** @return {number} */ y: function(z){ return 3; }};
        }
        """);
    ObjectType xType = ObjectType.cast(lastLocalScope.getVar("x").getType());
    assertThat(xType.getPropertyType("y").toString()).isEqualTo("function(?): number");
    assertThat(xType.toString()).isEqualTo("{y: function(?): number}");
  }

  @Test
  public void testDeclaredObjectLitProperty4() {
    testSame("var x = {y: 5, /** @type {number} */ z: 3};");
    ObjectType xType = ObjectType.cast(globalScope.getVar("x").getType());
    assertThat(xType.getPropertyType("y").toString()).isEqualTo("number");
    assertThat(xType.isPropertyTypeDeclared("y")).isFalse();
    assertThat(xType.isPropertyTypeDeclared("z")).isTrue();
    assertThat(xType.toString()).isEqualTo("{\n  y: number,\n  z: number\n}");
  }

  @Test
  public void testDeclaredObjectLitProperty5() {
    // TODO(b/154044898): re-enable type info validation
    disableTypeInfoValidation();
    testSame(
        """
        var x = {/** @type {number} */ prop: 3};
        function f() { var y = x.prop; }
        """);
    JSType yType = lastLocalScope.getVar("y").getType();
    assertThat(yType.toString()).isEqualTo("number");
  }

  @Test
  public void testDeclaredObjectLitProperty6() {
    testSame("var x = {/** This is JsDoc */ prop: function(){}};");
    TypedVar prop = globalScope.getVar("x.prop");
    JSType propType = prop.getType();
    assertThat(propType.toString()).isEqualTo("function(): undefined");
    assertThat(prop.isTypeInferred()).isFalse();
    assertThat(ObjectType.cast(globalScope.getVar("x").getType()).isPropertyTypeInferred("prop"))
        .isFalse();
  }

  @Test
  public void testInferredObjectLitProperty1() {
    testSame("var x = {prop: 3};");
    TypedVar prop = globalScope.getVar("x.prop");
    JSType propType = prop.getType();
    assertThat(propType.toString()).isEqualTo("number");
    assertThat(prop.isTypeInferred()).isTrue();
    assertThat(ObjectType.cast(globalScope.getVar("x").getType()).isPropertyTypeInferred("prop"))
        .isTrue();
  }

  @Test
  public void testInferredObjectLitProperty2() {
    testSame("var x = {prop: function(){}};");
    TypedVar prop = globalScope.getVar("x.prop");
    JSType propType = prop.getType();
    assertThat(propType.toString()).isEqualTo("function(): undefined");
    assertThat(prop.isTypeInferred()).isTrue();
    assertThat(ObjectType.cast(globalScope.getVar("x").getType()).isPropertyTypeInferred("prop"))
        .isTrue();
  }

  @Test
  public void testDeclaredConstType1() {
    testSame(
        """
        /** @const */ var x = 3;
        function f() { var y = x; }
        """);
    JSType yType = lastLocalScope.getVar("y").getType();
    assertThat(yType.toString()).isEqualTo("number");
  }

  @Test
  public void testDeclaredConstType_symbolCall() {
    testSame("/** @const */ var x = Symbol('test');  function f() { var y = x; }");
    JSType yType = lastLocalScope.getVar("y").getType();
    assertThat(yType).isEqualTo(registry.getNativeType(JSTypeNative.SYMBOL_TYPE));
  }

  @Test
  public void testDeclaredConstType_shadowingGlobalSymbol_notConfusedForNativeSymbol() {
    testSame(
        """
        (function() {
        function Symbol() {}
        /** @const */ var x = Symbol();
        function f() { var y = x; Y: y; }
        })()
        """);
    JSType yType = getLabeledStatement("Y").statementNode.getOnlyChild().getJSType();
    assertThat(yType).isEqualTo(registry.getNativeType(JSTypeNative.VOID_TYPE));
  }

  @Test
  public void testDeclaredConstType2() {
    testSame(
        """
        /** @const */ var x = {};
        function f() { var y = x; }
        """);
    JSType yType = lastLocalScope.getVar("y").getType();
    assertThat(yType.toString()).isEqualTo("{}");
  }

  @Test
  public void testDeclaredConstType3() {
    testSame(
        """
        /** @const */ var x = {};
        /** @const */ x.z = 'hi';
        function f() { var y = x.z; }
        """);
    JSType yType = lastLocalScope.getVar("y").getType();
    assertThat(yType.toString()).isEqualTo("string");
  }

  @Test
  public void testDeclaredConstType4() {
    testSame(
        """
        /** @constructor */ function Foo() {}
        /** @const */ Foo.prototype.z = 'hi';
        function f() { var y = (new Foo()).z; }
        """);
    JSType yType = lastLocalScope.getVar("y").getType();
    assertThat(yType.toString()).isEqualTo("string");

    ObjectType fooType = ((FunctionType) globalScope.getVar("Foo").getType()).getInstanceType();
    assertThat(fooType.isPropertyTypeDeclared("z")).isTrue();
  }

  @Test
  public void testDeclaredConstType5a() {
    testSame(
        externs(""),
        srcs(
            """
            /** @const */ var goog = goog || {};
            function f() { var y = goog; }
            """));
    JSType yType = lastLocalScope.getVar("y").getType();
    assertThat(yType.toString()).isEqualTo("{}");
  }

  @Test
  public void testDeclaredConstType6() {
    testSame(
        """
        /**
         * @param {{y:string}} a
         * @constructor
        */
        var C = function(a) { /** @const */ this.x = a.y;};
        var instance = new C({y:'str'})
        """);
    ObjectType instance = (ObjectType) findNameType("instance", globalScope);
    assertThat(instance.toString()).isEqualTo("C");
    assertThat(instance.hasProperty("x")).isTrue();
    assertThat(instance.getPropertyType("x").toString()).isEqualTo("string");
    assertThat(instance.isPropertyTypeInferred("x")).isFalse();
  }

  @Test
  public void testBadCtorInit1() {
    testWarning("/** @constructor */ var f;", CTOR_INITIALIZER);
  }

  @Test
  public void testBadCtorInit2() {
    testWarning("var x = {}; /** @constructor */ x.f;", CTOR_INITIALIZER);
  }

  @Test
  public void testBadIfaceInit1() {
    testWarning("/** @interface */ var f;", IFACE_INITIALIZER);
  }

  @Test
  public void testBadIfaceInit2() {
    testWarning("var x = {}; /** @interface */ x.f;", IFACE_INITIALIZER);
  }

  @Test
  public void testDeclaredCatchExpression1() {
    // TODO(b/154044898): re-enable type info validation
    disableTypeInfoValidation();
    testSame("try {} catch (e) {}");
    assertThat(lastLocalScope.getVar("e")).hasJSTypeThat().isUnknown();
  }

  @Test
  public void testDeclaredCatchExpression2() {
    testSame("try {} catch (/** @type {string} */ e) {}");
    assertThat(lastLocalScope.getVar("e").getType().toString()).isEqualTo("string");
  }

  @Test
  public void testDestructuringCatch() {
    testSame("try {} catch ({/** string */ message, /** number */ errno}) {}");
    assertType(lastLocalScope.getVar("message").getType()).toStringIsEqualTo("string");
    assertType(lastLocalScope.getVar("errno").getType()).toStringIsEqualTo("number");
  }

  @Test
  public void testDuplicateCatchVariableNames() {
    // TODO(b/154044898): re-enable type info validation
    disableTypeInfoValidation();
    testSame(
        """
        try {} // preserve newlines
        catch (err) {
          FIRST_CATCH: err;
        }
        try {}
        catch (err) {
          SECOND_CATCH: err;
        }
        """);
    TypedScope firstCatchScope = getLabeledStatement("FIRST_CATCH").enclosingScope;
    assertScope(firstCatchScope).declares("err").directly();
    TypedVar firstErrVar = firstCatchScope.getVar("err");

    TypedScope secondCatchScope = getLabeledStatement("SECOND_CATCH").enclosingScope;
    assertScope(firstCatchScope).declares("err").directly();
    assertThat(firstCatchScope).isNotSameInstanceAs(secondCatchScope);

    TypedVar secondErrVar = secondCatchScope.getVar("err");
    assertThat(firstErrVar).isNotSameInstanceAs(secondErrVar);
  }

  @Test
  public void testGenerator1() {
    testSame("function *gen() { yield 1; } var g = gen();");
    assertThat(findNameType("gen", globalScope).toString())
        .isEqualTo("function(): Generator<?,?,?>");
    assertThat(findNameType("g", globalScope).toString()).isEqualTo("Generator<?,?,?>");
  }

  @Test
  public void testGenerator2() {
    testSame("var gen = function *() { yield 1; }; var g = gen();");
    assertThat(findNameType("gen", globalScope).toString())
        .isEqualTo("function(): Generator<?,?,?>");
    assertThat(findNameType("g", globalScope).toString()).isEqualTo("Generator<?,?,?>");
  }

  // Just check that this doesn't cause a StackOverflowError.
  @Test
  public void testArgumentsStackOverflow() {
    // TODO(b/154044898): re-enable type info validation
    disableTypeInfoValidation();
    String js =
        """
        /**
         * @fileoverview
         * @suppress {es5Strict}
         */

        function getStackTrace() {
          try {
            throw 'error';
          } catch (e) {
            return 0;
          }

          if (typeof (arguments.caller) != 'undefined') {}

          return '';
        }
        """;
    testSame(js);
  }

  @Test
  public void testSpreadInIifeBlocksTypeInference() {
    testSame(
        """
        const /** !Array<string> */ arr = [];
        (function (x = 4, y = 5) {})(...arr, undefined);
        """);

    TypedVar xVar = checkNotNull(lastFunctionScope.getVar("x"));
    assertType(xVar.getType()).toStringIsEqualTo("?");

    TypedVar yVar = checkNotNull(lastFunctionScope.getVar("y"));
    assertType(yVar.getType()).toStringIsEqualTo("?");
  }

  @Test
  public void testAliasTypeFromClass() {
    testSame(
        """
        class Foo {};
        /** @enum {number} */ Foo.E = {A: 1};
        const F = Foo;
        const E = F.E;
        """);
    assertThat(findNameType("E", globalScope).toString()).isEqualTo("enum{Foo.E}");
  }

  @Test
  public void testAliasTypeFromNamespace() {
    testSame(
        """
        const Foo = {};
        /** @enum {number} */ Foo.E = {A: 1};
        const F = Foo;
        const E = F.E;
        """);
    assertThat(findNameType("E", globalScope).toString()).isEqualTo("enum{Foo.E}");
  }

  @Test
  public void testAliasTypeFromClassPrototype() {
    // This is very weird but falls out of how we module types.
    testSame(
        """
        class Foo {}
        /** @enum {number} */ Foo.prototype.E = {A: 1};
        const F = new Foo();
        const E = F.E;
        """);
    assertThat(findNameType("E", globalScope).toString()).isEqualTo("enum{Foo.prototype.E}");
  }

  @Test
  public void testAliasTypedef() {
    testSame(
        """
        /** @typedef {number} */ let Foo;
        const E = Foo;
        /** @type {E} */ let x;
        """);
    assertThat(findNameType("x", globalScope).toString()).isEqualTo("number");
  }

  @Test
  public void testAliasTypedefOntoNamespace() {
    testSame(
        """
        /** @typedef {number} */ let Foo;
        const E = {};
        /** @const */
        E.F = Foo;
        /** @type {E.F} */ let x;
        """);
    assertType(findNameType("x", globalScope)).isNumber();
    ObjectType eType = findNameType("E", globalScope).toMaybeObjectType();
    assertThat(eType).isNotNull();
    assertThat(eType.isPropertyTypeDeclared("F")).isTrue();
  }

  @Test
  public void testAliasTypedefFromNamespace() {
    testSame(
        """
        const Foo = {};
        /** @typedef {number} */ Foo.E;
        const E = Foo.E;
        /** @type {E} */ let x;
        """);
    assertThat(findNameType("x", globalScope).toString()).isEqualTo("number");
  }

  @Test
  public void testAliasTypedefFromNamespaceAlias() {
    testSame(
        """
        const Foo = {};
        /** @typedef {number} */ Foo.E;
        const F = Foo;
        const E = F.E;
        /** @type {E} */ let x;
        """);
    assertThat(findNameType("x", globalScope).toString()).isEqualTo("number");
  }

  @Test
  public void testTypedefName_hasNoType() {
    testSame("/** @typedef {number} */ let Foo;");

    TypedVar foo = globalScope.getSlot("Foo");
    assertThat(foo).isNotInferred();
    assertThat(foo).hasJSTypeThat().isNoType();
  }

  @Test
  public void testTypedefQualifiedName_hasNoType() {
    testSame("const ns = {}; /** @typedef {number} */ ns.Foo;");

    TypedVar foo = globalScope.getSlot("ns.Foo");
    assertThat(foo).isNotInferred();
    assertThat(foo).hasJSTypeThat().isNoType(); // Note: we would like this to be 'void' eventually.
  }

  /** The {@link CheckJSDoc} pass already warns on these misplaced @typedefs */
  @Test
  public void testTypedef_notAllowedOnThisProp() {
    testSame("class C { constructor() { /** @typedef {string} */ this.name; } }");

    assertThat(registry.getType(globalScope, "this.name")).isNull();
  }

  @Test
  public void testTypedef_notAllowedOnPrototypeProp() {
    testSame("/** @constructor */ function C() {} /** @typedef {string} */ C.prototype.name;");

    assertThat(registry.getType(globalScope, "C.prototype.name")).isNull();
  }

  @Test
  public void testTypedefName_usingLetWithLiteralRhs_hasNoTypeIfReassigned() {
    // This will cause a type error during typechecking.
    testSame("/** @typedef {number} */ let Foo = 'a string'; Foo = 3;");

    TypedVar foo = globalScope.getSlot("Foo");
    assertThat(foo).isNotInferred();
    assertThat(foo).hasJSTypeThat().isNoType();
  }

  @Test
  public void testTypedefNameVar_usingConstWithLiteralRhs_hasLiteralType() {
    testSame("/** @enum */ let Enum = {A: 0}; /** @typedef {number} */ const Foo = Enum;");

    TypedVar foo = globalScope.getSlot("Foo");
    assertThat(foo).isNotInferred();
    // Currently a typedef can be a const alias of a type.
    assertThat(foo).hasJSTypeThat().toStringIsEqualTo("enum{Enum}");

    assertType(registry.getGlobalType("Foo")).isNumber();
  }

  @Test
  public void testTypedefNameVar_usingConstWithObjectLitRhs_canHaveAdditionalProperties() {
    testSame("/** @typedef {number} */ const Foo = {}; Foo.Builder = () => 0;");

    TypedVar foo = globalScope.getSlot("Foo");
    assertThat(foo).isNotInferred();
    assertThat(foo).hasJSTypeThat().isObjectTypeWithProperty("Builder");

    assertType(registry.getGlobalType("Foo")).isNumber();
  }

  @Test
  public void testTypedefQualifiedName_withLiteralRhs_hasNoType() {
    testSame("const ns = {}; /** @typedef {number} */ ns.Foo = 'a string';");

    TypedVar foo = globalScope.getSlot("ns.Foo");
    assertThat(foo).isNotNull();
    assertThat(foo).isNotInferred();
    assertThat(foo).hasJSTypeThat().isNoType();
  }

  @Test
  public void testTypedefQualifiedName_withObjectLiteralRhs_hasNoType() {
    testSame("const ns = {}; /** @typedef {number} */ ns.Foo = {}; ns.Foo.Builder = () => 0;");

    TypedVar foo = globalScope.getSlot("ns.Foo");
    assertThat(foo).isNotInferred();
    assertThat(foo).hasJSTypeThat().isNoType(); // Note: eventually we may want to make this {}.

    assertType(registry.getGlobalType("ns.Foo")).isNumber();
  }

  @Test
  public void testObjectPatternInParametersWithDefaultGetsCorrectType() {
    testSame("/** @param {{a: string}=} obj */ function f({a = 'foo'} = {}) {}");

    JSType patternType = findTokenType(Token.OBJECT_PATTERN, globalScope);
    // the pattern has the type after the default value is evaluated, not ({a:string}|undefined)
    assertType(patternType).toStringIsEqualTo("({a: string}|{})");

    JSType aNameType = findNameType("a", globalScope);
    assertType(aNameType).isString();
  }

  @Test
  public void testGoogModuleMissingNamespaceDoesntCrash() {
    getOptions().setContinueAfterErrors(true);
    testError("goog.module(); const x = 0;", ClosureRewriteModule.INVALID_MODULE_ID_ARG);
    testError("goog.module(0); const x = 0;", ClosureRewriteModule.INVALID_MODULE_ID_ARG);
  }

  @Test
  public void testScriptAndGoogModuleWithShadowedVar() {
    testSame(
        srcs(
            "var x = 0; GLOBAL_X: x;", //
            "goog.module('a'); var x = 'str'; LOCAL_X: x;"));
    TypedScope globalScope = getLabeledStatement("GLOBAL_X").enclosingScope;
    TypedScope localScope = getLabeledStatement("LOCAL_X").enclosingScope;

    TypedVar globalX = globalScope.getSlot("x");
    TypedVar localX = localScope.getSlot("x");

    assertThat(globalX).isNotEqualTo(localX);
    assertThat(globalX).hasJSTypeThat().isNumber();
    assertThat(localX).hasJSTypeThat().isString();
  }

  @Test
  public void testTwoGoogModulesWithSameNamedVar() {
    testSame(
        srcs(
            "goog.module('a'); var x = 'str'; MOD_A_X: x;", //
            "goog.module('b'); var x = 0; MOD_B_X: x"));

    assertScope(globalScope).doesNotDeclare("x");

    TypedVar modAX = getLabeledStatement("MOD_A_X").enclosingScope.getSlot("x");
    TypedVar modBX = getLabeledStatement("MOD_B_X").enclosingScope.getSlot("x");

    assertThat(modAX).isNotEqualTo(modBX);
    assertType(modAX.getType()).isString();
    assertType(modBX.getType()).isNumber();
  }

  @Test
  public void testTwoGoogModuleWithSameNamedFunctionDeclaration() {
    testSame(
        srcs(
            "goog.module('a'); function foo() {} MOD_A_FOO: foo;", //
            "goog.module('b'); function foo() {} MOD_B_FOO: foo;"));

    assertScope(globalScope).doesNotDeclare("foo");

    TypedVar modAX = getLabeledStatement("MOD_A_FOO").enclosingScope.getSlot("foo");
    TypedVar modBX = getLabeledStatement("MOD_B_FOO").enclosingScope.getSlot("foo");

    assertThat(modAX).isNotEqualTo(modBX);
  }

  @Test
  public void testGoogModule_requiringMissingNamespace() {
    testSame("goog.module('a'); const b = goog.require('b'); B: b;");

    assertNode(getLabeledStatement("B").statementNode.getOnlyChild()).hasJSTypeThat().isUnknown();
  }

  @Test
  public void testGoogModule_requiringMissingNamespace_emptyDestructuring() {
    testSame("goog.module('a'); const {} = goog.require('b');");
  }

  @Test
  public void testGoogModule_moduleBodyScopeIsStoredOnTheInput() {
    testSame("goog.module('a'); EXPORTS: exports;");

    final TypedScope moduleScope = getLabeledStatement("EXPORTS").enclosingScope;
    final Node moduleBodyNode = moduleScope.getRootNode();
    assertNode(moduleBodyNode).hasToken(Token.MODULE_BODY);
    final Compiler lastCompiler = getLastCompiler();
    final InputId inputId = NodeUtil.getInputId(moduleBodyNode);
    assertThat(inputId).isNotNull();
    final CompilerInput compilerInput = lastCompiler.getInput(inputId);
    assertThat(compilerInput).isNotNull();
    final TypedScope typedScopeStoredOnTheCompilerInput = compilerInput.getTypedScope();
    assertThat(typedScopeStoredOnTheCompilerInput).isNotNull();
    assertThat(typedScopeStoredOnTheCompilerInput).isSameInstanceAs(moduleScope);
  }

  @Test
  public void testGoogModule_declaresExportsVariableImplicitly() {
    testSame("goog.module('a'); EXPORTS: exports;");

    TypedVar exports = getLabeledStatement("EXPORTS").enclosingScope.getSlot("exports");
    assertThat(exports).isNotInferred();
    assertThat(exports).hasJSTypeThat().isLiteralObject();
  }

  @Test
  public void testGoogModule_exportsDeclaresTypeInModuleScope() {
    testSame("goog.module('mod.a'); exports = class {}; MOD_A: 0;");

    assertType(registry.getGlobalType("exports")).isNull();
    assertType(registry.getType(getLabeledStatement("MOD_A").enclosingScope, "exports"))
        .isNotNull();
  }

  @Test
  public void testGoogModule_moduleObjectTypeMatchesImportExportAndModuleBody() {
    testSame(
        srcs(
            """
            goog.module('mod.a');
            exports = class {};
            EXPORTED_A: exports;
            """,
            """
            goog.module('mod.b');
            const imported_a = goog.require('mod.a');
            IMPORTED_A: imported_a;
            """));

    final Node aExportsNode = getLabeledStatement("EXPORTED_A").statementNode.getOnlyChild();
    final JSType exportedAJSType = aExportsNode.getJSType();
    assertThat(exportedAJSType).isNotNull();

    final Node aModuleNode = NodeUtil.getEnclosingType(aExportsNode, Token.MODULE_BODY);
    assertNode(aModuleNode).hasJSTypeThat().isEqualTo(exportedAJSType);

    final Node importedANode = getLabeledStatement("IMPORTED_A").statementNode.getOnlyChild();
    assertNode(importedANode).hasJSTypeThat().isEqualTo(exportedAJSType);
  }

  @Test
  public void testGoogModule_namedExportDeclaresTypeInModuleScope() {
    testSame("goog.module('mod.a'); exports.B = class {}; MOD_A: 0;");

    assertType(registry.getGlobalType("exports.B")).isNull();
    assertType(registry.getType(getLabeledStatement("MOD_A").enclosingScope, "exports.B"))
        .isNotNull();
  }

  @Test
  public void testGoogRequireDefaultExport_getsInferredType() {
    // Do some indirection when assigning `exports = f()` so  that we don't find the type of
    // `exports` until flow-sensitive inference runs. This is to verify that required variables
    // types are tightened after flow-sensitive type inference.
    testSame(
        srcs(
            "goog.module('a'); /** @return {number} */ function f() {} exports = f();",
            "goog.module('b'); const x = goog.require('a'); X: x;"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isNumber();

    TypedVar xVar = getLabeledStatement("X").enclosingScope.getSlot("x");
    assertType(xVar.getType()).isNumber();
    assertThat(xVar).isInferred();
  }

  @Test
  public void testGoogRequire_defaultExportWithExplicitType() {
    testSame(
        srcs(
            """
            goog.module('a');
            /** @return {number} */ function f() {}
            /** @type {number} */ exports = f();
            """,
            "goog.module('b'); const x = goog.require('a'); X: x;"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isNumber();

    TypedVar xVar = getLabeledStatement("X").enclosingScope.getSlot("x");
    assertThat(xVar).hasJSTypeThat().isNumber();
    assertThat(xVar).isNotInferred();
  }

  @Test
  public void testGoogRequire_propertyOnObjectLiteralWithArrayInitializer() {
    // Reproduce a bug seen when goog.requiring symbols from
    // https://github.com/google/closure-library/blob/master/closure/goog/i18n/datetimesymbols.js
    testSame(
        srcs(
            """
            /** @fileoverview @typeSummary */
            goog.provide('goog.i18n.DateTimeSymbols');
            goog.provide('goog.i18n.DateTimeSymbolsType');
            /** @type {!goog.i18n.DateTimeSymbolsType} */
            goog.i18n.DateTimeSymbols;

            /** @typedef {{MONTHS: !Array<string>}} */
            goog.i18n.DateTimeSymbolsType;
            """,
            """
            goog.module('b');
            const {MONTHS} = goog.require('goog.i18n.DateTimeSymbols');
            function i() { MONTHS: MONTHS; }
            """));

    Node monthsNode = getLabeledStatement("MONTHS").statementNode.getOnlyChild();
    assertNode(monthsNode).hasJSTypeThat().toStringIsEqualTo("Array<string>");

    TypedVar monthsVar = getLabeledStatement("MONTHS").enclosingScope.getSlot("MONTHS");
    assertThat(monthsVar).hasJSTypeThat().toStringIsEqualTo("Array<string>");
    assertThat(monthsVar).isNotInferred();
  }

  @Test
  public void testGoogForwardDeclare_defaultExportWithExplicitType() {
    testSame(
        srcs(
            "goog.module('b'); const x = goog.forwardDeclare('a'); function inner() { X: x; }",
            """
            goog.module('a');
            /** @return {number} */ function f() {}
            /** @type {number} */ exports = f();
            """));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isNumber();

    TypedVar xVar = getLabeledStatement("X").enclosingScope.getSlot("x");
    assertThat(xVar).hasJSTypeThat().isNumber();
    assertThat(xVar).isNotInferred();
  }

  @Test
  public void testGoogRequireType_namedExportOfClass() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('b');
            const x = goog.requireType('a.x');
            function inner() { var /** !x.Y */ y; Y: y; }
            """,
            """
            goog.module('a.x');
            exports.Y = class {};
            """));

    Node xNode = getLabeledStatement("Y").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("a.x.Y");
  }

  @Test
  public void testGoogForwardDeclare_providedClass() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('b');
            const X = goog.forwardDeclare('a.X');
            function inner() { var /** !X */ x; X: x; }
            """,
            """
            goog.provide('a.X');
            a.X = class {};
            """));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("a.X");
  }

  @Test
  public void testGoogRequireType_providedClass() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('b');
            const X = goog.requireType('a.X');
            function inner() { var /** !X */ x; X: x; }
            """,
            """
            goog.provide('a.X');
            a.X = class {};
            """));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("a.X");
  }

  @Test
  public void testGoogRequireType_classOnProvidedNamespace() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('b');
            const x = goog.requireType('a.x');
            function inner() { var /** !x.Y */ y; Y: y; }
            """,
            """
            goog.provide('a.x');
            a.x.Y = class {};
            """));

    Node xNode = getLabeledStatement("Y").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("a.x.Y");
  }

  @Test
  public void testGoogRequireType_destructuring_classOnProvidedNamespace() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('b');
            const {Y} = goog.requireType('a.x');
            function inner() { var /** !Y */ y; Y: y; }
            """,
            """
            goog.provide('a.x');
            a.x.Y = class {};
            """));

    Node xNode = getLabeledStatement("Y").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("a.x.Y");
  }

  @Test
  public void testGoogRequire_defaultExportOfConstructor() {
    testSame(
        srcs(
            "goog.module('a.Foo'); exports = class Foo {};",
            "goog.module('b'); const Foo = goog.require('a.Foo'); var /** !Foo */ foo; FOO: foo"));

    Node xNode = getLabeledStatement("FOO").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("a.Foo");
  }

  @Test
  public void testGoogModuleRequire_defaultExportAliasingLocalConstructor() {
    testSame(
        srcs(
            "goog.module('a.Foo'); class LocalFoo {} exports = LocalFoo;",
            "goog.module('b'); const Foo = goog.require('a.Foo'); var /** !Foo */ foo; FOO: foo"));

    Node fooNode = getLabeledStatement("FOO").statementNode.getOnlyChild();
    assertNode(fooNode).hasJSTypeThat().toStringIsEqualTo("LocalFoo");

    TypedVar fooCtorVar = getLabeledStatement("FOO").enclosingScope.getVar("Foo");
    assertThat(fooCtorVar).hasJSTypeThat().isFunctionTypeThat().isConstructorFor("LocalFoo");
    assertThat(fooCtorVar).isNotInferred();
  }

  @Test
  public void testGoogModuleRequire_defaultExportOfLocalTypedef() {
    testSame(
        srcs(
            "goog.module('a.Foo'); /** @typedef {number} */ var numType; exports = numType;",
            "goog.module('b'); const Foo = goog.require('a.Foo'); var /** !Foo */ x; X: x"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogModuleRequire_defaultExportOfEnum() {
    testSame(
        srcs(
            "goog.module('a.Foo'); exports = class Foo {};",
            "goog.module('b'); const Foo = goog.require('a.Foo'); var /** !Foo */ foo; FOO: foo"));

    Node xNode = getLabeledStatement("FOO").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("a.Foo");
  }

  @Test
  public void testGoogModuleRequireAndRequireType_defaultExportOfClasses() {
    testSame(
        srcs(
            """
            goog.module('a.Foo');
            const Bar = goog.requireType('b.Bar');
            var /** !Bar */ b;
            B: b;
            exports = class {};
            """,
            """
            goog.module('b.Bar');
            const Foo = goog.require('a.Foo');
            const /** !Foo */ f = new Foo();
            F: f;
            exports = class {};
            """));

    Node fNode = getLabeledStatement("F").statementNode.getOnlyChild();
    assertNode(fNode).hasJSTypeThat().toStringIsEqualTo("a.Foo");

    Node bNode = getLabeledStatement("B").statementNode.getOnlyChild();
    assertNode(bNode).hasJSTypeThat().toStringIsEqualTo("b.Bar");
  }

  @Test
  public void testGoogModuleRequireAndRequireType_namedExportOfClasses() {
    testSame(
        srcs(
            """
            goog.module('a');
            const {Bar} = goog.requireType('b');
            var /** !Bar */ b;
            B: b;
            exports.Foo = class {};
            """,
            """
            goog.module('b');
            const {Foo} = goog.require('a');
            const /** !Foo */ f = new Foo();
            F: f;
            exports.Bar = class {};
            """));

    Node fNode = getLabeledStatement("F").statementNode.getOnlyChild();
    assertNode(fNode).hasJSTypeThat().toStringIsEqualTo("a.Foo");

    Node bNode = getLabeledStatement("B").statementNode.getOnlyChild();
    assertNode(bNode).hasJSTypeThat().toStringIsEqualTo("b.Bar");
  }

  @Test
  public void testGoogModuleRequireAndRequireType_typedef() {
    testSame(
        srcs(
            """
            goog.module('a.Foo');
            const Bar = goog.requireType('b.Bar');
            var /** !Bar */ x;
            X: x;
            """,
            """
            goog.module('b.Bar');
            /** @typedef {number} */
            let numType;
            exports = numType;
            """));

    Node fNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(fNode).hasJSTypeThat().isNumber();

    Node barDeclaration = getLabeledStatement("X").enclosingScope.getVar("Bar").getNameNode();
    assertType(barDeclaration.getTypedefTypeProp()).isNumber();
  }

  @Test
  public void testGoogModuleRequireAndRequireType_invalidDestructuringImport() {
    testError(
        srcs(
            """
            goog.module('a.Foo');
            const {Bar} = goog.requireType('b.Bar');
            """,
            "goog.module('b.Bar');"),
        error(DOES_NOT_HAVE_EXPORT_WITH_DETAILS));
  }

  @Test
  public void testReferenceGoogModulesByType() {
    testSame(
        srcs(
            """
            goog.module('mod.A');
            class A {}
            exports = A;
            """,
            "var /** !mod.A */ a;"));

    assertThat(globalScope.getVar("a")).hasJSTypeThat().toStringIsEqualTo("A");
  }

  @Test
  public void testCannotReferenceGoogDeclareModuleIdByType() {
    testWarning(
        srcs(
            """
            goog.declareModuleId('mod');
            export class A {}
            """,
            "var /** !mod.A */ a;"),
        warning(RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR));
  }

  @Test
  public void testReferenceGoogModuleNestedClassByType() {
    testSame(
        srcs(
            """
            goog.module('mod.A');
            class A {}
            A.B = class {};
            A.B.C = class {};
            exports = A;
            """,
            "var /** !mod.A.B.C */ a;"));

    assertThat(globalScope.getVar("a")).hasJSTypeThat().toStringIsEqualTo("A.B.C");
  }

  @Test
  public void testReferenceGoogModuleNestedClassByType_exportDefaultTypedef() {
    // Verify that the unusal resolution of @typedefs via Node annotation works correctly.
    testSame(
        srcs(
            """
            goog.module('mod.A');
            /** @typedef {string} */
            let MyTypedef;
            exports = MyTypedef;
            """,
            "var /** !mod.A */ a;"));

    assertThat(globalScope.getVar("a")).hasJSTypeThat().isString();
  }

  @Test
  public void testReferenceGoogModuleByType_shadowingLocalCausingResolutionFailure() {
    testWarning(
        srcs(
            """
            goog.module('mod.A');
            class A {}
            A.B = class {};
            A.B.C = class {};
            exports = A;
            """,
            """
            goog.module('mod.B');
            const mod = {};
            var /** !mod.A.B.C */ a;
            """), // Resolves relative to the local `mod`.
        warning(RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR));
  }

  @Test
  public void testReferenceGoogModuleByType_googProvideExtensionCausesResolutionFailure() {
    processClosurePrimitives = true;
    testWarning(
        srcs(
            """
            goog.module('mod.A');
            class A {}
            A.B = class {};
            A.B.C = class {};
            exports = A;
            """,
            """
            goog.provide('mod.A.B');
            var /** !mod.A.B.C */ a;
            """), // Assumes this refers to the goog.provide
        warning(RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR));
  }

  @Test
  public void testGoogRequireModuleWithoutExports() {
    testSame(srcs("goog.module('a');", "goog.module('b'); const a = goog.require('a');"));
  }

  @Test
  public void testGoogRequire_exportObjectLiteralWithLiteralValue() {
    testSame(
        srcs(
            "goog.module('a'); exports = {x: 3};",
            "goog.module('b'); const x = goog.require('a'); X: x;" // can't destructure this
            ));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("{x: number}");
  }

  @Test
  public void testGoogRequire_exportObjectLiteralWithLiteralFunctions() {
    testSame(
        srcs(
            """
            goog.module('my.mod');
            function memoize(fn) { return fn; }
            exports = {
              /** @return {string} */
              bar: memoize(() => 'test')
            };
            """,
            """
            goog.module('other.mod');
            const mod = goog.require('my.mod');
            MOD: mod;
            """));

    Node moduleObject = getLabeledStatement("MOD").statementNode.getOnlyChild();
    assertNode(moduleObject)
        .hasJSTypeThat()
        .withTypeOfProp("bar")
        .isFunctionTypeThat()
        .hasReturnTypeThat()
        .isString();
  }

  @Test
  public void testModuleTypedef_isNonnullableWithinModule() {
    testSame(
        """
        goog.module('a.b'); var /** strType */ earlyRef;
        /** @typedef {string} */ let strType;
        var /** strType */ normalRef; MODULE: 0;
        """);

    TypedScope moduleScope = getLabeledStatement("MODULE").enclosingScope;
    assertThat(moduleScope.getSlot("earlyRef")).hasJSTypeThat().isString();
    assertThat(moduleScope.getSlot("normalRef")).hasJSTypeThat().isString();
  }

  @Test
  public void testModuleTypedef_isNonnullableWhenRequired() {

    testSame(
        srcs(
            """
            goog.module('root');
            /** @typedef {string} */ let strType;
            exports.strType = strType;
            """,
            """
            goog.module('a.b'); const {strType} = goog.require('root');
            /** @param {strType} earlyRef */ function f(earlyRef) { EARLY_REF: earlyRef; }
            var /** strType */ normalRef; NORMAL_REF: normalRef;
            """));

    assertNode(getLabeledStatement("EARLY_REF").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .isString();
    assertNode(getLabeledStatement("NORMAL_REF").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .isString();
  }

  @Test
  public void testGoogRequire_namedExportImportedAsNamespace() {
    testSame(
        srcs(
            "goog.module('a'); /** @type {number} */ exports.x = 0;",
            "goog.module('b'); const a = goog.require('a'); X: a.x;"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isNumber();

    TypedVar xVar = getLabeledStatement("X").enclosingScope.getSlot("a");
    assertThat(xVar).hasJSTypeThat().isObjectTypeWithProperty("x");
    assertThat(xVar).isNotInferred();
  }

  @Test
  public void testGoogRequire_namedExportImportedAsNamespace_andModded() {
    testSame(
        srcs(
            "goog.module('a'); class C { m() {} } exports = {C}; C: C;",
            "goog.module('b'); const a = goog.require('a'); a.C.prototype.n = function() {};"));

    Node cCtor = getLabeledStatement("C").statementNode.getOnlyChild();
    assertNode(cCtor).hasJSTypeThat().isFunctionTypeThat().isConstructorFor("C");

    FunctionType cCtorType = cCtor.getJSType().assertFunctionType();
    ObjectType cPrototype = cCtorType.getPrototype();
    assertType(cPrototype).hasDeclaredProperty("n");
    assertType(cPrototype.getPropertyType("n"))
        .isFunctionTypeThat()
        .hasTypeOfThisThat()
        .isEqualTo(cCtorType.getInstanceType());
  }

  @Test
  public void testGoogRequire_destructuringInferredNamedExport() {
    testSame(
        srcs(
            "goog.module('a'); /** @return {number} */ function f() {} exports.x = f();",
            "goog.module('b'); const {x} = goog.require('a'); X: x;"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogRequire_destructuringTypedNamedExport() {
    testSame(
        srcs(
            "goog.module('a'); /** @type {number} */ exports.x = 3;",
            "goog.module('b'); const {x} = goog.require('a'); X: x;"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isNumber();

    TypedVar xVar = getLabeledStatement("X").enclosingScope.getSlot("x");
    assertThat(xVar).hasJSTypeThat().isNumber();
    assertThat(xVar).isNotInferred();
  }

  @Test
  public void testGoogRequire_destructuringNonShorthand() {
    testSame(
        srcs(
            "goog.module('a'); /** @type {number} */ exports.x = 3;",
            "goog.module('b'); const {x: y} = goog.require('a'); Y: y;"));

    Node yNode = getLabeledStatement("Y").statementNode.getOnlyChild();
    assertNode(yNode).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogRequire_exportObjectLiteral() {
    testSame(
        srcs(
            "goog.module('a'); const y = 3; exports = {x: y};",
            "goog.module('b'); const {x} = goog.require('a'); X: x;"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogRequire_namedExportDeclaredAsConstructor() {
    testSame(
        srcs(
            "goog.module('a'); /** @constructor */ exports.X = function() {};",
            "goog.module('b'); const {X} = goog.require('a'); var /** !X */ x; X: x;"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("a.X");
  }

  @Test
  public void testGoogRequire_namedExportDeclaredAsEnum() {
    testSame(
        srcs(
            "goog.module('a'); /** @enum {number} */ exports.Enum = {A: 0};",
            "goog.module('b'); const {Enum} = goog.require('a'); var /** !Enum */ x; X: x;"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("a.Enum<number>");
  }

  @Test
  public void testGoogRequire_namedExportOfLocalEnum() {
    testSame(
        srcs(
            "goog.module('a'); /** @enum {number} */ const Enum = {A: 0}; exports.Enum = Enum;",
            "goog.module('b'); const {Enum} = goog.require('a'); var /** !Enum */ x; X: x"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("Enum<number>");
  }

  @Test
  public void testGoogRequire_namedExportOfLocalEnum_inObjectLit() {
    testSame(
        srcs(
            "goog.module('a'); /** @enum {number} */ const Enum = {A: 0}; exports = {Enum}",
            "goog.module('b'); const {Enum} = goog.require('a'); var /** !Enum */ x; X: x"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().toStringIsEqualTo("Enum<number>");
  }

  @Test
  public void testGoogRequire_namedExportDeclaredAsTypedef() {
    testSame(
        srcs(
            "goog.module('a'); /** @typedef {number} */ exports.numType;",
            "goog.module('b'); const {numType} = goog.require('a'); var /** numType */ x; X: x;"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogRequire_namedExportOfLocalTypedef() {
    testSame(
        srcs(
            "goog.module('a'); /** @typedef {number} */ let numType; exports.numType = numType;",
            "goog.module('b'); const {numType} = goog.require('a'); var /** numType */ x; X: x;"));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogRequire_destructuringMissingExportDoesntCrash() {
    testError(
        srcs("goog.module('a');", "goog.module('b'); const {x} = goog.require('a'); X: x;"),
        error(DOES_NOT_HAVE_EXPORT_WITH_DETAILS));

    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isUnknown(); // Note: we warn for this case elsewhere.
  }

  @Test
  public void testRequire_requireRegularModuleInLoadModule() {
    testSame(
        srcs(
            "goog.module('A'); exports = class {};",
            """
            goog.loadModule(function(exports) {
              goog.module('b');
              const A = goog.require('A');
              /** @type {!A} */ var a;
              A: a;
              return exports;
            });
            """));

    Node aNode = getLabeledStatement("A").statementNode.getOnlyChild();
    assertNode(aNode).hasJSTypeThat().toStringIsEqualTo("A");
  }

  @Test
  public void testGoogRequire_requireLoadModuleInRegularModule() {
    testSame(
        srcs(
            """
            goog.loadModule(function(exports) {
              goog.module('A');
              /** @constructor*/
              exports = function() {};
             return exports;
            });
            """,
            "goog.module('b'); const A = goog.require('A'); /** @type {!A} */ var a; A: a;"));

    Node aNode = getLabeledStatement("A").statementNode.getOnlyChild();
    assertNode(aNode).hasJSTypeThat().toStringIsEqualTo("A");
  }

  @Test
  public void testGoogRequire_requireLoadModuleInRegularModule_destructuring() {
    testSame(
        srcs(
            """
            goog.loadModule(function(exports) {
              goog.module('mod');
              /** @constructor*/
              exports.A = function() {};
              return exports;
            });
            """,
            "goog.module('b'); const {A} = goog.require('mod'); /** @type {!A} */ var a; A: a;"));

    Node aNode = getLabeledStatement("A").statementNode.getOnlyChild();
    assertNode(aNode).hasJSTypeThat().toStringIsEqualTo("mod.A");
  }

  @Test
  public void testRequire_requireLoadModuleInRegularModule_inferredDefaultExport() {
    testSame(
        srcs(
            """
            goog.loadModule(function(exports) {
              goog.module('a');
              /** @return {number} */ function f() {}
              exports = f();
              return exports;
            });
            """,
            "goog.module('b'); const x = goog.require('a'); X: x;"));

    // This is unknown because we visit the CFG for the module before visiting the CFG for the
    // function block. Users can work around this by explicitly typing their exports.
    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isUnknown();
  }

  @Test
  public void testRequire_requireLoadModuleInRegularModule_inferredNamedExport() {
    testSame(
        srcs(
            """
            goog.loadModule(function(exports) {
              goog.module('a');
              /** @return {number} */ function f() {}
              exports.x = f();
              return exports;
            });
            """,
            "goog.module('b'); const {x} = goog.require('a'); X: x;"));

    // This is unknown because we visit the CFG for the module before visiting the CFG for the
    // function block. Users can work around this by explicitly typing their exports.
    Node xNode = getLabeledStatement("X").statementNode.getOnlyChild();
    assertNode(xNode).hasJSTypeThat().isUnknown();
  }

  @Test
  public void testGoogModuleGet_nameDeclarationCreatesTypeAlias() {
    testSame(
        srcs(
            """
            (function() {
              const B = goog.module.get('a.B');
              var /** !B */ b;
              B: b;
            })();
            """,
            """
            goog.module('a.B');
            class B {}
            exports = B;
            """));

    assertType(getLabeledStatement("B").statementNode.getOnlyChild().getJSType())
        .toStringIsEqualTo("B");
  }

  @Test
  public void testGoogModuleGet_destructruringCreatesTypeAlias() {
    testSame(
        srcs(
            """
            (function() {
              const {B} = goog.module.get('a');
              var /** !B */ b;
              B: b;
            })();
            """,
            """
            goog.module('a');
            /** @typedef {number} */ let B;
            exports.B = B;
            """));

    assertType(getLabeledStatement("B").statementNode.getOnlyChild().getJSType()).isNumber();
  }

  @Test
  public void testGoogModuleGet_getpropDoesNotCreateTypeAlias() {
    // This test covers a pattern that works when the compiler rewrites goog.module.get before
    // typechecking, but is not supported in native module typechecking. It's a rare pattern and
    // would be more work to support in TypedScopeCreator.
    testWarning(
        srcs(
            """
            (function() {
              const B = goog.module.get('a').B;
              var /** !B */ b;
              B: b;
            })();
            """,
            """
            goog.module('a');
            /** @typedef {number} */ let B;
            exports.B = B;
            """),
        warning(RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR));

    assertType(getLabeledStatement("B").statementNode.getOnlyChild().getJSType()).isUnknown();
  }

  @Test
  public void testRequire_requireLoadModule_defaultExportOfLocalClass() {
    testSame(
        srcs(
            """
            goog.loadModule(function(exports) {
              goog.module('a');
              class X {}
              exports = X;
              return exports;
            });
            """,
            """
            goog.module('b');
            const Y = goog.require('a');
            var /** !Y */ x;
            """));
  }

  @Test
  public void testGoogProvide_singleNameWithConstJSDocIsDeclared() {
    processClosurePrimitives = true;
    testSame(srcs("goog.provide('foo'); /** @const */ foo = 3;"));

    TypedVar fooVar = globalScope.getVar("foo");
    assertThat(fooVar).hasJSTypeThat().isNumber();
    assertThat(fooVar).isNotInferred();
  }

  @Test
  public void testGoogProvide_singleNameWithAtTypeJSDocIsDeclared() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.provide('foo');
            class Bar {}
            /** @type {!Bar} */ foo = new Bar();
            """));

    TypedVar fooVar = globalScope.getVar("foo");
    assertThat(fooVar).hasJSTypeThat().toStringIsEqualTo("Bar");
    assertThat(fooVar).isNotInferred();
  }

  @Test
  public void testGoogProvide_singleNameAssignedInIfBlock() {
    processClosurePrimitives = true;
    testSame(srcs("goog.provide('foo'); if (!foo) { foo = {}; };"));

    TypedVar fooVar = globalScope.getVar("foo");
    assertThat(fooVar).hasJSTypeThat().toStringIsEqualTo("{}");
    assertThat(fooVar).isInferred();
  }

  @Test
  public void testGoogProvide_ignoresBlockScopedShadow() {
    processClosurePrimitives = true;
    testSame(srcs("goog.provide('foo'); if (true) { const foo = 3; }"));

    TypedVar fooVar = globalScope.getVar("foo");
    assertThat(fooVar).hasJSTypeThat().toStringIsEqualTo("{}");
    assertThat(fooVar).isNotInferred();
  }

  @Test
  public void testGoogRequire_ofProvide_usedInEs6ExtendsClause() {
    testSame(
        srcs(
            """
            goog.provide('a.b')
            a.b.Foo = class {};
            """,
            """
            goog.module('c');
            const b = goog.require('a.b');
            class C extends b.Foo {}
            """));

    FunctionType cType = (FunctionType) findNameType("C", lastLocalScope);
    FunctionType aFooType = (FunctionType) findNameType("a.b.Foo", globalScope);

    assertType(cType.getInstanceType()).isSubtypeOf(aFooType.getInstanceType());
    assertType(cType.getImplicitPrototype()).isEqualTo(aFooType);
  }

  @Test
  public void testGoogProvide_singleStubNameWithAtTypeJSDoc_ignoresJSDoc() {
    processClosurePrimitives = true;
    // NOTE: this causes a CheckJSDoc warning.
    testSame(srcs("goog.provide('foo'); class Bar {} /** @type {!Bar} */ foo;"));

    TypedVar fooVar = globalScope.getVar("foo");
    assertThat(fooVar).hasJSTypeThat().isLiteralObject();
    assertThat(fooVar).isNotInferred();
  }

  @Test
  public void testGoogProvide_singleNameWithoutJSDocIsInferred() {
    processClosurePrimitives = true;
    testSame(srcs("goog.provide('foo'); foo = 3;"));

    TypedVar fooVar = globalScope.getVar("foo");
    assertThat(fooVar).hasJSTypeThat().isNumber();
    assertThat(fooVar).isInferred();
  }

  @Test
  public void testGoogProvide_singleNameWithoutDefinition() {
    processClosurePrimitives = true;
    testSame(srcs("goog.provide('foo'); foo.Bar = class {};"));

    TypedVar fooVar = globalScope.getVar("foo");
    assertThat(fooVar).hasJSTypeThat().isObjectTypeWithProperty("Bar");
    assertThat(fooVar).isNotInferred();
  }

  @Test
  public void testGoogProvide_getPropWithoutJSDoc() {
    processClosurePrimitives = true;
    testSame(srcs("goog.provide('foo.bar'); foo.bar = 3;"));

    TypedVar fooVar = globalScope.getVar("foo");
    assertThat(fooVar).hasJSTypeThat().withTypeOfProp("bar").isNumber();
    assertThat(fooVar.getType().toMaybeObjectType().isPropertyTypeDeclared("bar")).isFalse();
  }

  @Test
  public void testGoogProvide_getPropTypedef_createsBothTypedefAndObjectType() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.provide('foo.bar');
            goog.provide('foo.bar.baz');
            /** @typedef {number} */ foo.bar;
            /** @const */ foo.bar.baz = '';
            """));

    TypedVar fooVar = globalScope.getVar("foo");
    assertThat(fooVar).hasJSTypeThat().withTypeOfProp("bar").isNoType();
    assertThat(fooVar).hasJSTypeThat().hasDeclaredProperty("bar");

    assertType(registry.getGlobalType("foo.bar")).isNumber();
    TypedVar fooBarVar = globalScope.getVar("foo.bar");
    assertThat(fooBarVar).hasJSTypeThat().isNoType(); // Note: this is questionable.

    TypedVar fooBarBaz = globalScope.getVar("foo.bar.baz");
    assertThat(fooBarBaz).hasJSTypeThat().isString();
  }

  @Test
  public void testGoogProvideGetPropWithTypeDeclaration() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.provide('foo.bar');
            /** @type {number} */ foo.bar = 3;
            FOOBAR: foo.bar;
            """));

    TypedVar fooVar = globalScope.getVar("foo");
    assertThat(fooVar).hasJSTypeThat().withTypeOfProp("bar").isNumber();
    assertThat(fooVar).hasJSTypeThat().hasDeclaredProperty("bar");

    assertType(getLabeledStatement("FOOBAR").statementNode.getOnlyChild().getJSType()).isNumber();
  }

  @Test
  public void testGoogProvide_namespaceWithMultipleChildren() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.provide('foo.bar');
            goog.provide('foo.baz');
            /** @const */
            foo.bar = 3;
            /** @const */
            foo.baz = 'str';
            """));

    TypedVar fooVar = globalScope.getVar("foo");
    assertType(fooVar.getType().findPropertyType("bar")).isNumber();
    assertThat(fooVar).hasJSTypeThat().hasDeclaredProperty("bar");
    assertType(fooVar.getType().findPropertyType("baz")).isString();
    assertThat(fooVar).hasJSTypeThat().hasDeclaredProperty("baz");
  }

  @Test
  public void testProvide_longNamespace_declaresProperties() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.provide('a.b.c.d.e.f');

            a.b.c.d.e.f.g = function() {};
            """));

    TypedVar abcdeVar = globalScope.getVar("a.b.c.d.e");
    assertThat(abcdeVar).hasJSTypeThat().hasDeclaredProperty("f");
  }

  @Test
  public void testGoogProvide_parentNamespaceIsInferred() {
    processClosurePrimitives = true;
    testSame(srcs("goog.provide('a.b.c'); a.b = {};"));

    assertThat(globalScope.getVar("a")).hasJSTypeThat().withTypeOfProp("b").isLiteralObject();
    assertThat(globalScope.getVar("a.b")).isNull(); // Only declared qnames get TypedVars.
    assertThat(globalScope.getVar("a.b.c")).isNotInferred();
  }

  @Test
  public void testGoogProvide_extendsExternsNamespace() {
    processClosurePrimitives = true;
    testSame(
        externs(
            new String[] {
              new TestExternsBuilder().addClosureExterns().build(),
              "/** @externs */ /** @const */ var google = {}; /** @type {string} */ google.boogle;"
            }),
        srcs(
            """
            goog.provide('google.ly');

            /** @return {number} */
            google.ly = function() { return 0; }
            """),
        warning(TypeValidator.DUP_VAR_DECLARATION_TYPE_MISMATCH));

    TypedVar fooVar = globalScope.getVar("google");
    assertThat(fooVar).hasJSTypeThat().hasDeclaredProperty("ly");
    assertThat(fooVar).hasJSTypeThat().hasDeclaredProperty("boogle");
  }

  @Test
  public void testGoogProvide_overwritingExternsFunction() {
    processClosurePrimitives = true;
    testSame(
        externs(
            new TestExternsBuilder()
                .addClosureExterns()
                .addExtra("/** @externs */ function eval(code) {}")
                .build()),
        srcs(
            """
            goog.provide('eval.subNs');
            eval.subNs = class {};
            new eval.subNs();
            """),
        warning(TypeValidator.DUP_VAR_DECLARATION_TYPE_MISMATCH));
  }

  @Test
  public void testLegacyGoogLoadModule_accessibleWithGoogRequire_exportingConstructor() {
    testSame(
        srcs(
            """
            goog.loadModule(function (exports) {
              goog.module('mod.A');
              goog.module.declareLegacyNamespace();

              exports = class A {};
            });
            """,
            """
            goog.require('mod.A');
            const /** !mod.A */ a = new mod.A();
            A: a;
            """));

    assertType(getLabeledStatement("A").statementNode.getOnlyChild().getJSType())
        .toStringIsEqualTo("mod.A");
  }

  @Test
  public void testLegacyGoogModule_declaresParentNamespacesGlobally() {
    processClosurePrimitives = true;
    testSame(srcs("goog.module('a.b.c'); goog.module.declareLegacyNamespace(); MOD: 0;"));

    assertScope(globalScope).declares("a").directly();
    assertScope(globalScope).declares("a.b").directly();
    assertScope(globalScope).declares("a.b.c").directly();
    assertScope(getLabeledStatement("MOD").enclosingScope).declares("a").onSomeParent();
  }

  @Test
  public void testLegacyGoogModule_isDeclaredPropertyOnParentNamespace() {
    processClosurePrimitives = true;
    testSame(srcs("goog.module('a.b.c'); goog.module.declareLegacyNamespace();"));

    assertThat(globalScope.getVar("a.b")).hasJSTypeThat().hasDeclaredProperty("c");
  }

  @Test
  public void testLegacyGoogModule_addsJSDocToPropertyOnParentNamespace() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('a.b.c');
            goog.module.declareLegacyNamespace();
            /** @package */ exports = 0;
            """));

    assertThat(globalScope.getVar("a.b")).hasJSTypeThat().hasDeclaredProperty("c");
    ObjectType abType = globalScope.getVar("a.b").getType().toMaybeObjectType();
    assertThat(abType.getPropertyJSDocInfo("c")).isNotNull();
    assertThat(abType.getPropertyJSDocInfo("c").getVisibility())
        .isEqualTo(JSDocInfo.Visibility.PACKAGE);
  }

  @Test
  public void testGoogRequire_multipleRequiresInModule() {
    testSame(
        srcs(
            """
            goog.module('mod.A');
            /** @interface */
            exports = class {};
            """,
            """
            goog.module('mod.B');
            /** @interface */
            exports = class {};
            """,
            """
            goog.module('mod.C');
            const A = goog.require('mod.A');
            const B = goog.require('mod.B');
            /** @implements {A} @implements {B} */
            class C {};
            MOD_C: 0;
            """));

    TypedScope modCScope = getLabeledStatement("MOD_C").enclosingScope;
    FunctionType aCtor = modCScope.getVar("A").getType().toMaybeFunctionType();
    FunctionType bCtor = modCScope.getVar("B").getType().toMaybeFunctionType();
    FunctionType cCtor = modCScope.getVar("C").getType().toMaybeFunctionType();

    assertThat(cCtor.getAllImplementedInterfaces())
        .containsExactly(aCtor.getInstanceType(), bCtor.getInstanceType());
  }

  @Test
  public void testLegacyGoogModule_accessibleWithGoogRequire_exportingConstructor() {
    testSame(
        srcs(
            """
            goog.module('mod.A');
            goog.module.declareLegacyNamespace();

            exports = class A {};
            """,
            """
            goog.require('mod.A');
            const /** !mod.A */ a = new mod.A();
            A: a;
            """));

    assertType(getLabeledStatement("A").statementNode.getOnlyChild().getJSType())
        .toStringIsEqualTo("mod.A");
  }

  @Test
  public void testLegacyGoogModule_accessibleWithGoogRequire_exportingLocalTypedef() {
    testSame(
        srcs(
            """
            goog.module('mod.A');
            goog.module.declareLegacyNamespace();

            /** @typedef {number} */
            let numType;

            exports = numType;
            """,
            """
            goog.require('mod.A');
            var /** !mod.A */ x;
            X: x
            """));

    assertType(getLabeledStatement("X").statementNode.getOnlyChild().getJSType()).isNumber();
  }

  @Test
  public void testLegacyGoogModule_accessibleWithGoogRequire_exportingNamespace() {
    testSame(
        srcs(
            """
            goog.module('mod');
            goog.module.declareLegacyNamespace();

            exports.A = class A {};
            """,
            """
            goog.require('mod');
            const /** !mod.A */ a = new mod.A();
            """));
  }

  @Test
  public void testLegacyGoogModule_withNamedExport_extendedByGoogProvide() {
    testSame(
        srcs(
            """
            goog.module('mod');
            goog.module.declareLegacyNamespace();

            exports.A = class A {};
            """,
            """
            goog.provide('mod.B'); // This is bad style, but probably people do it.
            mod.B = class B {};
            """));

    JSType modType = globalScope.getVar("mod").getType();
    assertType(modType).withTypeOfProp("A").toStringIsEqualTo("(typeof mod.A)");
    assertType(modType).withTypeOfProp("B").toStringIsEqualTo("(typeof mod.B)");
  }

  @Test
  public void testLegacyGoogModule_canBeAliasedInGlobalScopeThenUsed() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('mod.A');
            goog.module.declareLegacyNamespace();

            class AInternal {}
            exports = AInternal;
            """,
            """
            goog.require('mod.A');
            const A = mod.A;
            const /** !A */ a = new A();
            A: a;
            """));

    assertType(getLabeledStatement("A").statementNode.getOnlyChild().getJSType())
        .toStringIsEqualTo("AInternal");
  }

  @Test
  public void testLegacyGoogLoadModule_canBeAliasedInGlobalScopeThenUsed() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.loadModule(function(exports) {
              goog.module('mod.A');
              goog.module.declareLegacyNamespace();

              exports = class A {};
              return exports;
            });
            """,
            """
            goog.require('mod.A');
            const A = mod.A;
            const /** !A */ a = new A();
            A: a;
            """));

    assertType(getLabeledStatement("A").statementNode.getOnlyChild().getJSType())
        .toStringIsEqualTo("mod.A");
  }

  @Test
  public void testGoogModule_requiringGoogProvideShadowingGlobal() {
    processClosurePrimitives = true;

    testSame(
        externs(
            new TestExternsBuilder().addClosureExterns().build(),
            "/** @interface */ function EventTarget() {}"),
        srcs(
            """
            goog.provide('goog.events.EventTarget');
            /** @constructor */
            goog.events.EventTarget = function() {};
            """,
            """
            goog.module('mod.A');
            const EventTarget = goog.require('goog.events.EventTarget');
            LOCAL_EVENT_TARGET: EventTarget;
            """));

    assertType(getLabeledStatement("LOCAL_EVENT_TARGET").statementNode.getOnlyChild().getJSType())
        .isFunctionTypeThat()
        .isConstructorFor("goog.events.EventTarget");
  }

  @Test
  public void testGoogModule_requireShadowsGlobal_usedInExtendsClause() {
    processClosurePrimitives = true;

    testSame(
        externs(
            new TestExternsBuilder().addClosureExterns().build(),
            "/** @interface */ function EventTarget() {}"),
        srcs(
            """
            goog.provide('goog.events.EventTarget');
            /** @constructor */
            goog.events.EventTarget = function() {};
            """,
            """
            goog.module('mod.A');
            const EventTarget = goog.require('goog.events.EventTarget');
            class Proxy extends EventTarget {}
            PROXY: Proxy;
            """));

    FunctionType proxyType =
        getLabeledStatement("PROXY").statementNode.getOnlyChild().getJSType().toMaybeFunctionType();
    assertType(proxyType.getSuperClassConstructor())
        .isFunctionTypeThat()
        .isConstructorFor("goog.events.EventTarget");
  }

  @Test
  public void testLegacyGoogModule_withDefaultClassExport_createsGlobalType() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('mod.MyClass');
            goog.module.declareLegacyNamespace();
            class MyClass {}
            exports = MyClass;
            """));

    assertType(registry.getGlobalType("mod.MyClass")).toStringIsEqualTo("MyClass");
  }

  @Test
  public void testLegacyGoogModule_withDefaultEnumExport_createsGlobalType() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('mod.MyEnum');
            goog.module.declareLegacyNamespace();
            /** @enum {string} */
            const MyEnum = {A: 'a', B: 'b'};
            exports = MyEnum;
            """));

    assertType(registry.getGlobalType("mod.MyEnum")).toStringIsEqualTo("MyEnum<string>");
  }

  @Test
  public void testLegacyGoogModule_withDefaultTypedefExport_createsGlobalType() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('mod.MyTypedef');
            goog.module.declareLegacyNamespace();
            /** @typedef {number} */
            let MyTypedef;
            exports = MyTypedef;
            """));

    assertType(registry.getGlobalType("mod.MyTypedef")).isNumber();
  }

  @Test
  public void testLegacyGoogModule_withDefaultExport_extendedByGoogProvide() {
    testSame(
        srcs(
            """
            goog.module('mod');
            goog.module.declareLegacyNamespace();

            /** @return {number} */
            exports = function() { return 0; };
            """,
            """
            goog.provide('mod.B'); // This is bad style, but probably people do it.
            mod.B = class B {};
            """));

    JSType modType = globalScope.getVar("mod").getType();
    assertType(modType).isFunctionTypeThat().hasReturnTypeThat().isNumber();
    assertType(modType).withTypeOfProp("B").toStringIsEqualTo("(typeof mod.B)");
  }

  @Test
  public void testLegacyGoogModule_accessibleWithGoogRequire_exportingTypedef() {
    testSame(
        srcs(
            """
            goog.module('mod');
            goog.module.declareLegacyNamespace();

            /** @typedef {number} */
            exports.numType;
            """,
            """
            goog.require('mod');
            var /** !mod.numType */ a;
            """));

    JSType modType = globalScope.getVar("a").getType();
    assertType(modType).isNumber();
  }

  @Test
  public void testGoogModuleRequiringGoogProvide_class() {
    testSame(
        srcs(
            """
            goog.provide('a.b.Foo')
            a.b.Foo = class {};
            """,
            """
            goog.module('c');
            const Foo = goog.require('a.b.Foo');
            var /** !Foo */ x;
            X: x;
            """));

    assertType(getLabeledStatement("X").statementNode.getOnlyChild().getJSType())
        .toStringIsEqualTo("a.b.Foo");
  }

  @Test
  public void testTypedef_namedExportInObjectLit() {
    testSame(
        srcs(
            """
            goog.module('a');
            /** @typedef {number} */
            let Foo;
            exports = {Foo};
            """,
            """
            goog.module('b');
            const {Foo} = goog.require('a');
            var /** !Foo */ x;
            """));
  }

  @Test
  public void testGoogModuleRequiringGoogProvide_classWithDestructuring() {
    testSame(
        srcs(
            """
            goog.provide('a.b')
            a.b.Foo = class {};
            """,
            """
            goog.module('c');
            const {Foo} = goog.require('a.b');
            var /** !Foo */ x;
            X: x;
            """));

    assertType(getLabeledStatement("X").statementNode.getOnlyChild().getJSType())
        .toStringIsEqualTo("a.b.Foo");
  }

  @Test
  public void testEsModule_importStarMissingModule() {
    setLanguageOut(LanguageMode.ECMASCRIPT5);
    // Make sure this does not crash the typechecker.
    testError("import * as x from './invalid_path;'; X: x; export {x};", ModuleLoader.LOAD_WARNING);

    assertScope(getLabeledStatement("X").enclosingScope).declares("x").withTypeThat().isUnknown();
    assertScope(getLabeledStatement("X").enclosingScope)
        .declares(Export.NAMESPACE)
        .withTypeThat()
        .hasInferredProperty("x");
  }

  @Test
  public void testEsModule_importSpecsMissingModule() {
    setLanguageOut(LanguageMode.ECMASCRIPT5);
    // Make sure this does not crash the typechecker.
    testError("import {x} from './invalid_path;'; X: x; export {x};", ModuleLoader.LOAD_WARNING);

    assertScope(getLabeledStatement("X").enclosingScope).declares("x").withTypeThat().isUnknown();
    assertScope(getLabeledStatement("X").enclosingScope)
        .declares(Export.NAMESPACE)
        .withTypeThat()
        .hasInferredProperty("x");
  }

  @Test
  public void testEsModule_importNameFromScript() {
    // Make sure this does not crash the typechecker. Importing a script for the side effects is
    // fine, but you can't import a name.
    testSame(
        srcs(
            "const x = 'oops, you did not export me.';",
            """
            import {x} from './testcode0';
            X: x;
            """));

    assertScope(getLabeledStatement("X").enclosingScope).declares("x").withTypeThat().isUnknown();
  }

  @Test
  public void testEsModule_exportNameDeclaration() {
    testSame("/** @type {string|number} */ export let strOrNum = 0; MOD: 0;");

    TypedVar strOrNum = getLabeledStatement("MOD").enclosingScope.getVar("strOrNum");
    assertThat(strOrNum).isNotNull();
    assertThat(strOrNum).isNotInferred();
    assertThat(strOrNum).hasJSTypeThat().toStringIsEqualTo("(number|string)");
  }

  @Test
  public void testEsModule_importSpecs_declaredExport() {
    testSame(
        srcs(
            """
            let /** number */ num;
            export {num as y};
            """,
            """
            import {y as x} from './testcode0';
            X: x;
            """));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
    assertThat(getLabeledStatement("X").enclosingScope.getVar("x")).isNotInferred();
  }

  @Test
  public void testEsModule_importSpecs_inferredExport_nameDeclaration() {
    testSame(
        srcs(
            """
            /** @return {number} */ const f = () => 0;
            export const y = f();
            """,
            """
            import {y as x} from './testcode0';
            X: x;
            """));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
    assertThat(getLabeledStatement("X").enclosingScope.getVar("x")).isInferred();
  }

  @Test
  public void testEsModule_importSpecs_inferredExport_exportSpecs() {
    testSame(
        srcs(
            """
            /** @return {number} */ const f = () => 0;
            const num = f();
            export {num as y};
            """,
            """
            import {y as x} from './testcode0';
            X: x;
            """));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
    assertThat(getLabeledStatement("X").enclosingScope.getVar("x")).isInferred();
  }

  @Test
  public void testEsModule_importSpecs_typedef() {
    testSame(
        srcs(
            """
            /** @typedef {number} */ let numType;
            export {numType};
            """,
            """
            import {numType} from './testcode0';
            var /** !numType */ x;
            X: x;
            """));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_importSpecs_fromGoogColonProvide() {
    testSame(
        srcs(
            """
            goog.provide('foo.bar');
            /** @type {number} */
            foo.bar.x = 0;
            """,
            """
            import {x as xImported} from 'goog:foo.bar';
            X: xImported;
            """));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_importSpecs_fromGoogColonModule() {
    testSame(
        srcs(
            """
            goog.module('foo.bar');
            /** @type {number} */
            exports.x = 0;
            """,
            """
            import {x as xImported} from 'goog:foo.bar';
            X: xImported;
            """));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_moduleObjectTypeMatchesImportExportAndModuleBody() {
    testSame(
        srcs(
            SourceFile.fromCode(
                "a.js",
                """
                export class A {};
                MOD_A: 0; // there's no way to reference the module object within the module
                """),
            SourceFile.fromCode(
                "b.js",
                """
                import * as a from './a.js';
                IMPORTED_A: a;
                """)));

    final Node aModuleBody =
        NodeUtil.getEnclosingType(getLabeledStatement("MOD_A").statementNode, Token.MODULE_BODY);
    final JSType moduleAObjectType = aModuleBody.getJSType();
    assertThat(moduleAObjectType).isNotNull();

    final Node importedANode = getLabeledStatement("IMPORTED_A").statementNode.getOnlyChild();
    assertNode(importedANode).hasJSTypeThat().isEqualTo(moduleAObjectType);
  }

  @Test
  public void testEsModule_exportDefault() {
    testSame(srcs("export default 0;", "import x from './testcode0'; X: x;"));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_exportDefaultImportedWithSpecs() {
    testSame(srcs("export default 0;", "import {default as x} from './testcode0'; X: x;"));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_importDefault_exportedUsingDefaultNamedKey() {
    testSame(srcs("const x = 0; export {x as default};", "import x from './testcode0'; X: x;"));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_exportAndImportDefaultClassDeclaration() {
    testSame(
        srcs(
            "export default class Button {}; const /** !Button */ b = new Button(); B1: b;",
            "import Button from './testcode0'; const /** !Button */ b = new Button(); B2: b;"));

    assertNode(getLabeledStatement("B1").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Button");
    assertNode(getLabeledStatement("B2").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Button");
  }

  @Test
  public void testEsModule_importAndImportDefault_nameLocalToModule() {
    testSame(
        srcs(
            "class Button {} export default Button; const /** !Button */ b = new Button(); B1: b;",
            "import Button from './testcode0'; const /** !Button */ b = new Button(); B2: b;"));

    assertNode(getLabeledStatement("B1").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Button");
    assertNode(getLabeledStatement("B2").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Button");
  }

  @Test
  public void testEsModule_importDefault_fromGoogColonModule() {
    testSame(
        srcs(
            """
            goog.module('foo.bar');
            /** @type {number} */
            exports.x = 0;
            """,
            """
            import bar from 'goog:foo.bar';
            X: bar.x;
            """));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_exportNameFrom() {
    testSame(
        srcs(
            "export const x = 0; export class Button {}",
            "export {x, Button} from './testcode0';",
            """
            import {x as y, Button} from './testcode0';
            Y: y;
            const /** !Button */ b = new Button();
            """));

    assertNode(getLabeledStatement("Y").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_exportStarFrom() {
    testSame(
        srcs(
            "export const x = 0; export class Button {}",
            "export * from './testcode0';",
            """
            import {x as y, Button} from './testcode0';
            Y: y;
            const /** !Button */ b = new Button();
            """));

    assertNode(getLabeledStatement("Y").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_importStar_declaredExport() {
    testSame(
        srcs(
            "export let /** number */ x;",
            """
            import * as ns from './testcode0';
            NS: ns;
            X: ns.x;
            """));

    JSType nsType = getLabeledStatement("NS").statementNode.getOnlyChild().getJSType();
    assertType(nsType).hasDeclaredProperty("x");
    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_importStar_inferredExport() {
    testSame(
        srcs(
            """
            /** @return {number} */ const f = () => 0;
            export const x = f();
            """,
            """
            import * as ns from './testcode0';
            NS: ns;
            X: ns.x;
            """));

    JSType nsType = getLabeledStatement("NS").statementNode.getOnlyChild().getJSType();
    assertType(nsType).hasInferredProperty("x");
    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_importStar_typedefInExportSpec() {
    testSame(
        srcs(
            "/** @typedef {number} */ let numType; export {numType};",
            "import * as ns from './testcode0'; var /** !ns.numType */ x; X: x;"));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_importStar_typedefExportDeclaration() {
    testSame(
        srcs(
            "/** @typedef {number} */ export let numType;",
            "import * as ns from './testcode0'; var /** !ns.numType */ x; X: x;"));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_importStar_typedefReexported() {
    testSame(
        srcs(
            "/** @typedef {number} */ export let numType;",
            "import * as mod from './testcode0'; export {mod};",
            "import {mod} from './testcode1'; var /** !mod.numType */ x; X: x;"));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testEsModule_importStar_typedefReexportedThenImportStarred() {
    testSame(
        srcs(
            "/** @typedef {number} */ export let numType;",
            "import * as mod0 from './testcode0'; export {mod0};",
            "import * as mod1 from './testcode1'; var /** !mod1.mod0.numType */ x; X: x;"));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogRequire_esModuleId_namespaceRequire() {
    testSame(
        srcs(
            "goog.declareModuleId('a'); export const x = 0;",
            "goog.module('b'); const a = goog.require('a'); X: a.x;"));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogRequire_esModuleId_destructuringRequire() {
    testSame(
        srcs(
            "goog.declareModuleId('a'); export const x = 0;",
            "goog.module('b'); const {x} = goog.require('a'); X: x;"));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogRequire_insideEsModule_namedExport() {
    testSame(
        srcs(
            "goog.module('b'); exports.x = 0;",
            "const {x} = goog.require('b'); X: x; export {x};"));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild()).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogRequire_insideEsModule_class() {
    testSame(
        srcs(
            """
            goog.module('b');
            exports.X = class {};
            """,
            """
            const {X} = goog.require('b');
            var /** !X */ x;
            X: x;
            export {x};
            """));

    assertNode(getLabeledStatement("X").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("b.X");
  }

  @Test
  public void testEsModuleImportCycle_namedExports() {
    // Note: we cannot reference circular imports directly in the module body but we can reference
    // them by type and inside functions.
    testSame(
        srcs(
            """
            import {Bar} from './testcode1';
            var /** !Bar */ b;
            BAR: b;
            export class Foo {}
            """,
            """
            import {Foo} from './testcode0';
            var /** !Foo */ f;
            FOO: f;
            export class Bar {}
            """));

    assertNode(getLabeledStatement("FOO").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Foo");
    assertNode(getLabeledStatement("BAR").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Bar");
  }

  @Test
  public void testEsModuleImportCycle_aliasingNamedExports() {
    testSame(
        srcs(
            """
            import {Bar as BarLocal} from './testcode1';
            var /** !BarLocal */ b;
            BAR: b;
            export class Foo {}
            """,
            """
            import {Foo as FooLocal} from './testcode0';
            var /** !FooLocal */ f;
            FOO: f;
            export class Bar {}
            """));

    assertNode(getLabeledStatement("FOO").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Foo");
    assertNode(getLabeledStatement("BAR").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Bar");
  }

  @Test
  public void testEsModuleImportCycle_importStarPlusNamedExport() {
    testSame(
        srcs(
            """
            import * as mod from './testcode1';
            var /** !mod.Bar */ b;
            BAR: b;
            export class Foo {}
            """,
            """
            import {Foo} from './testcode0';
            var /** !Foo */ f;
            FOO: f;
            export class Bar {}
            """));

    assertNode(getLabeledStatement("FOO").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Foo");
    assertNode(getLabeledStatement("BAR").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Bar");
  }

  @Test
  public void testEsModuleImportStar_reexportLateReference() {
    testSame(
        srcs(
            """
            import * as mod from './testcode1';
            function f() {
              BAR1: new mod.Bar();
            }
            export {mod};
            """,
            """
            import {mod} from './testcode0';
            var /** !mod.Bar */ b;
            BAR: b;
            function f() {
              BAR2: new mod.Bar();
            }
            export class Bar {}
            """));

    assertNode(getLabeledStatement("BAR").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Bar");
    assertNode(getLabeledStatement("BAR1").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Bar");
    assertNode(getLabeledStatement("BAR2").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Bar");
  }

  @Test
  public void testEsModuleImportCycle_importStar() {
    testSame(
        srcs(
            """
            import * as mod from './testcode1';
            var /** !mod.Bar */ b;
            BAR: b;
            export class Foo {}
            """,
            """
            import * as mod from './testcode0';
            var /** !mod.Foo */ f;
            FOO: f;
            export class Bar {}
            """));

    assertNode(getLabeledStatement("FOO").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Foo");
    assertNode(getLabeledStatement("BAR").statementNode.getOnlyChild())
        .hasJSTypeThat()
        .toStringIsEqualTo("Bar");
  }

  @Test
  public void testInferredLegacyNsExport() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('a.b.c')
            goog.module.declareLegacyNamespace();
            MOD: 0;

            /** @return {number} */
            function getNumber() { return 0; }
            exports = getNumber();
            """));

    TypedVar ab = globalScope.getVar("a.b");
    assertThat(ab).isNotInferred();
    assertThat(ab).hasJSTypeThat().withTypeOfProp("c").isNumber();

    TypedVar abc = globalScope.getVar("a.b.c");
    assertThat(abc).hasJSTypeThat().isNumber();

    Node moduleBody = getLabeledStatement("MOD").enclosingScope.getRootNode();
    checkState(moduleBody.isModuleBody(), moduleBody);
    assertNode(moduleBody).hasJSTypeThat().isNumber();
  }

  @Test
  public void testInferredLegacyNsExport_bundled() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.loadModule(function(exports) {
            goog.module('a.b.c')
            goog.module.declareLegacyNamespace();
            MOD: 0;

            /** @return {number} */
            function getNumber() { return 0; }
            exports = getNumber();
            return exports;
            });
            """));

    TypedVar ab = globalScope.getVar("a.b");
    assertThat(ab).isNotInferred();
    assertThat(ab).hasJSTypeThat().withTypeOfProp("c").isNumber();

    TypedVar abc = globalScope.getVar("a.b.c");
    assertThat(abc).hasJSTypeThat().isNumber();

    Node fnBlock = getLabeledStatement("MOD").enclosingScope.getRootNode();
    Node exportsParam = fnBlock.getPrevious().getFirstChild();
    checkState(exportsParam.getString().equals("exports"));
    assertNode(exportsParam).hasJSTypeThat().isNumber();
  }

  @Test
  public void testGoogProvide_typedef_requiredInModule() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.provide('a.b.c');
            /** @typedef {string} */
            a.b.c;
            """,
            """
            goog.module('mod');
            goog.module.declareLegacyNamespace();
            const c = goog.require('a.b.c');
            /** @param {!c} d */
            function f(d) {
              D: d;
            }
            """));

    assertType(registry.getGlobalType("a.b.c")).isString();
    assertType(getLabeledStatement("D").statementNode.getOnlyChild().getJSType()).isString();
  }

  @Test
  public void testGoogModuleGetInBlockScopeDoesntCrash() {
    testSame(
        srcs(
            """
            goog.module('a');
            exports = 5;
            """,
            """
            goog.require('a');
            function f() {
              if (true) {
                D: var a = goog.module.get('a');
              }
            }
            """));

    assertType(getLabeledStatement("D").statementNode.getOnlyChild().getJSType()).isNumber();
  }

  @Test
  public void testLegacyGoogModule_withExportedTypedefOnProperty() {
    processClosurePrimitives = true;
    testSame(
        srcs(
            """
            goog.module('mod.a');
            goog.module.declareLegacyNamespace();

            /** @typedef {number} */
            exports.numType;
            """,
            """
            goog.require('mod.a');
            var /** !mod.a.numType */ a;
            """));

    JSType modType = globalScope.getVar("a").getType();
    assertType(modType).isNumber();
  }

  @Test
  public void testEsModule_exportFunctionDecl_createsLocalDeclaration() {
    testSame("export function foo() {}; MOD: 0;");

    assertScope(getLabeledStatement("MOD").enclosingScope)
        .declares("foo")
        .withTypeThat()
        .isFunctionTypeThat()
        .hasReturnTypeThat()
        .isVoid();
  }

  @Test
  public void testEsModule_exportDefaultFunctionDecl_createsLocalDeclaration() {
    testSame("export default function foo() {}; MOD: 0;");

    assertScope(getLabeledStatement("MOD").enclosingScope)
        .declares("foo")
        .withTypeThat()
        .isFunctionTypeThat()
        .hasReturnTypeThat()
        .isVoid();
  }

  @Test
  public void testConstantAliasOfForwardReferencedType_name() {
    testSame(
        """
        /** @const {!Type} */ let declaredVar;
        const constantAlias = declaredVar;
        class Type {}
        """);

    TypedVar declaredVar = globalScope.getVar("declaredVar");
    TypedVar constantAlias = globalScope.getVar("constantAlias");

    assertThat(declaredVar).isNotInferred();
    assertThat(constantAlias).isNotInferred();
  }

  @Test
  public void testConstantAliasOfForwardReferencedType_qname() {
    testSame(
        """
        /** @const {!Type} */ let declaredVar;
        const a = {};
        /** @const */
        a.constantAlias = declaredVar;
        class Type {}
        """);

    TypedVar declaredVar = globalScope.getVar("declaredVar");
    TypedVar aObject = globalScope.getVar("a");
    TypedVar constantAlias = globalScope.getVar("a.constantAlias");

    assertThat(declaredVar).isNotInferred();
    assertThat(aObject).hasJSTypeThat().hasDeclaredProperty("constantAlias");
    assertThat(constantAlias).isNotInferred();
  }

  @Test
  public void testConstantAliasOfProperty() {
    testSame(
        """
        /** @typedef {{name: string}} */
        let Type;
        class C {
          /** @param {!Type} obj */
          constructor(obj) {
            /** @private @const */
            this.name_ = obj.name;
          }
          method() {
            NAME: this.name_;
          }
        }
        """);

    Node thisDotName = getLabeledStatement("NAME").statementNode.getOnlyChild();
    assertNode(thisDotName).hasJSTypeThat().isString();

    ObjectType cType = thisDotName.getFirstChild().getJSType().toObjectType();
    assertType(cType).hasDeclaredProperty("name_");
    assertType(cType).withTypeOfProp("name_").isString();
  }

  @Test
  public void testConstantAliasOfForwardReferencedTypeProperty() {
    testSame(
        """
        class C {
          /** @param {!Type} obj */
          constructor(obj) {
            /** @private @const */
            this.name_ = obj.name;
          }
          method() {
            NAME: this.name_;
          }
        }
        /** @typedef {{name: string}} */
        let Type;
        """);

    Node thisDotName = getLabeledStatement("NAME").statementNode.getOnlyChild();
    assertNode(thisDotName).hasJSTypeThat().isString();

    ObjectType cType = thisDotName.getFirstChild().getJSType().toObjectType();
    assertType(cType).hasInferredProperty("name_");
    assertType(cType).withTypeOfProp("name_").isString();
  }

  @Test
  public void testReportBadTypeAnnotationInTemplateParameter() {
    testWarning("var /** !Array<!MissingType> */ x;", RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
  }

  @Test
  public void testReportBadTypeAnnotation_invalidEnumTemplateParametersAreParsed() {
    // Enums cannot actually be templatized, but we still parse any types a user attempts to write.
    testWarning(
        "/** @enum */ const Colors = {RED: 0}; var /** !Colors<!MissingType> */ x;",
        RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
  }

  @Test
  public void testReportBadTypeAnnotationInExtraTemplateParameter() {
    test(
        srcs("class C {} var /** !C<!MissingType> */ x;"),
        warning(RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR),
        warning(RhinoErrorReporter.TOO_MANY_TEMPLATE_PARAMS));
    test(
        srcs("var /** !Array<string, !MissingType> */ x;"),
        warning(RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR),
        warning(RhinoErrorReporter.TOO_MANY_TEMPLATE_PARAMS));
  }

  @Test
  public void testReportBadTypeAnnotationInForwardReferenceTemplateParameter() {
    testWarning(
        "var /** !C<!MissingType> */ x; /** @template T */ class C {}",
        RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
  }

  @Test
  public void testReportBadTypeAnnotationInExtraForwardReferenceTemplateParameter() {
    testWarning(
        "var /** !C<!MissingType> */ x; class C {}", RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
    testWarning(
        "var /** !C<string, !MissingType> */ x; /** @template T */ class C {}",
        RhinoErrorReporter.UNRECOGNIZED_TYPE_ERROR);
  }

  @Test
  public void testClassExtendsForwardReference_markedForLooserTypechecking() {
    testSame(
        """
        /** @return {function(new: Object): ?} */
        function mixin() {}
        /** @extends {Parent} */
        class Middle extends mixin() {}
        class Child extends Middle {}
        class Parent {}
        """);

    FunctionType parentCtor = (FunctionType) globalScope.getVar("Parent").getType();
    FunctionType middleCtor = (FunctionType) globalScope.getVar("Middle").getType();
    FunctionType childCtor = (FunctionType) globalScope.getVar("Child").getType();

    assertThat(parentCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(middleCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isTrue();
    assertThat(childCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isTrue();

    assertThat(parentCtor.getPrototype().loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();
    assertThat(middleCtor.getPrototype().loosenTypecheckingDueToForwardReferencedSupertype())
        .isTrue();
    assertThat(childCtor.getPrototype().loosenTypecheckingDueToForwardReferencedSupertype())
        .isTrue();

    assertThat(parentCtor.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();
    assertThat(middleCtor.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype())
        .isTrue();
    assertThat(childCtor.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype())
        .isTrue();
  }

  @Test
  public void testClassExtendsForwardReference_forwardReferenceMarkedForLooserTypechecking() {
    testSame(
        """
        /** @return {function(new: Object): ?} */
        function mixin() {}
        /** @extends {Parent} */
        class Middle extends mixin() {}
        class Parent {}
        """);
    NamedType namedMiddleType;
    try (JSTypeResolver.Closer closer = registry.getResolver().openForDefinition()) {
      namedMiddleType = registry.createNamedType(globalScope, "Middle", null, -1, -1);

      assertThat(namedMiddleType.isResolved()).isFalse();
      assertThat(namedMiddleType.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    } // types are resolved here

    assertThat(namedMiddleType.loosenTypecheckingDueToForwardReferencedSupertype()).isTrue();
  }

  @Test
  public void testClassImplementsForwardReference_markedForLooserTypechecking() {
    testSame(
        """
        /** @implements {Parent} */
        class Middle {}

        /** @interface @extends {Middle} */
        class ExtendingChild {}
        /** @implements {Middle} */
        class ImplementingChild {}

        /** @interface */
        class Parent {}
        """);

    FunctionType parentCtor = (FunctionType) globalScope.getVar("Parent").getType();
    FunctionType middleCtor = (FunctionType) globalScope.getVar("Middle").getType();
    FunctionType implementingChildCtor =
        (FunctionType) globalScope.getVar("ImplementingChild").getType();
    FunctionType extendingChildCtor = (FunctionType) globalScope.getVar("ExtendingChild").getType();

    assertThat(parentCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    // Static inheritance is not modeled for @implements so no need to loosen typechecking
    assertThat(middleCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(implementingChildCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(extendingChildCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();

    assertThat(parentCtor.getPrototype().loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();
    assertThat(middleCtor.getPrototype().loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();
    assertThat(
            implementingChildCtor
                .getPrototype()
                .loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();
    assertThat(
            extendingChildCtor.getPrototype().loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();

    assertThat(parentCtor.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();
    assertThat(middleCtor.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype())
        .isTrue();
    assertThat(
            implementingChildCtor
                .getInstanceType()
                .loosenTypecheckingDueToForwardReferencedSupertype())
        .isTrue();
    assertThat(
            extendingChildCtor
                .getInstanceType()
                .loosenTypecheckingDueToForwardReferencedSupertype())
        .isTrue();
  }

  @Test
  public void testInterfaceExtendsForwardReference_markedForLooserTypechecking() {
    testSame(
        """
        /** @interface @extends {Parent} */
        class Middle {}

        /** @interface @extends {Middle} */
        class ExtendingChild {}
        /** @implements {Middle} */
        class ImplementingChild {}

        /** @interface */
        class Parent {}
        """);

    FunctionType parentCtor = (FunctionType) globalScope.getVar("Parent").getType();
    FunctionType middleCtor = (FunctionType) globalScope.getVar("Middle").getType();
    FunctionType implementingChildCtor =
        (FunctionType) globalScope.getVar("ImplementingChild").getType();
    FunctionType extendingChildCtor = (FunctionType) globalScope.getVar("ExtendingChild").getType();

    assertThat(parentCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(middleCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(implementingChildCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();
    assertThat(extendingChildCtor.loosenTypecheckingDueToForwardReferencedSupertype()).isFalse();

    assertThat(parentCtor.getPrototype().loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();
    assertThat(middleCtor.getPrototype().loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();
    assertThat(
            implementingChildCtor
                .getPrototype()
                .loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();
    assertThat(
            extendingChildCtor.getPrototype().loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();

    assertThat(parentCtor.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype())
        .isFalse();
    assertThat(middleCtor.getInstanceType().loosenTypecheckingDueToForwardReferencedSupertype())
        .isTrue();
    assertThat(
            implementingChildCtor
                .getInstanceType()
                .loosenTypecheckingDueToForwardReferencedSupertype())
        .isTrue();
    assertThat(
            extendingChildCtor
                .getInstanceType()
                .loosenTypecheckingDueToForwardReferencedSupertype())
        .isTrue();
  }

  @Test
  public void testGoogModule_exportsPropertyIsObjectLit_whosePropertiesNotInferredConst() {
    testSame(
        srcs(
            """
            goog.module('a.b.c');
            function modifyThings() {
              exports.things.thing1 = 'something';
            }
            MOD_SCOPE: null;
            exports.things = {
              thing1: null,
            };
            """));

    TypedScope moduleScope = getLabeledStatement("MOD_SCOPE").enclosingScope;

    ObjectType exportsType = findNameType("exports", moduleScope).toObjectType();
    assertType(exportsType).hasDeclaredProperty("things");

    // Regression test for a bug where 'thing1' used to be a declared property, and assigning
    //   exports.things.thing1 = 'something'
    // caused a type mismatch error.
    ObjectType exportsThingsType = exportsType.getPropertyType("things").toObjectType();
    assertType(exportsThingsType).hasInferredProperty("thing1");
    assertType(exportsThingsType)
        .withTypeOfProp("thing1")
        .isEqualTo(
            registry.createUnionType(
                registry.getNativeType(STRING_TYPE), registry.getNativeType(NULL_TYPE)));
  }

  @Test
  public void testLegacyModuleExternsConflict_defaultExport() {
    testSame(
        externs(
            """
            /** @const */
            var a = {};
            a.Foo = class {};
            """),
        srcs(
            new TestExternsBuilder().addClosureExterns().build(),
            """
            goog.module('a.Foo');
            goog.module.declareLegacyNamespace();
            class Foo {}
            exports = Foo;
            """,
            """
            goog.module('a.Bar');
            const Foo = goog.require('a.Foo');
            REQUIRED_FOO: Foo;
            """),
        // There are two warnings, one for assigning to 'a' and one for 'a.Foo'.
        warning(TypeValidator.DUP_VAR_DECLARATION_TYPE_MISMATCH),
        warning(TypeValidator.DUP_VAR_DECLARATION_TYPE_MISMATCH));

    TypedVar aVar = globalScope.getVar("a");
    assertNode(aVar.getNameNode()).isFromExterns();

    TypedVar aDotFoo = globalScope.getVar("a.Foo");
    assertNode(aDotFoo.getNameNode()).isFromExterns();

    assertThat(aVar).hasJSTypeThat().withTypeOfProp("Foo").isEqualTo(aDotFoo.getType());

    assertType(registry.getGlobalType("a.Foo"))
        .isEqualTo(aDotFoo.getType().toMaybeFunctionType().getInstanceType());
  }

  @Test
  public void testLegacyModuleExternsConflict_namedExport() {
    testWarning(
        externs(
            DEFAULT_EXTERNS,
            """
            /** @const */
            var a = {};
            a.Foo = class {};
            """),
        srcs(
            """
            goog.module('a');
            goog.module.declareLegacyNamespace();
            class Foo {}
            exports.Foo = Foo;
            """),
        warning(TypeValidator.DUP_VAR_DECLARATION_TYPE_MISMATCH));

    TypedVar aVar = globalScope.getVar("a");
    assertNode(aVar.getNameNode()).isFromExterns();
    TypedVar aDotFoo = globalScope.getVar("a.Foo");
    assertNode(aDotFoo.getNameNode()).isFromExterns();

    assertThat(aVar).hasJSTypeThat().withTypeOfProp("Foo").isEqualTo(aDotFoo.getType());
  }

  @Test
  public void testRequireType_inheritanceChainWithIdenticalClassAndInterfaceName() {
    testSame(
        srcs(
            """
            goog.module('a.Foo');
            const BFoo = goog.requireType('b.Foo');
            var /** !BFoo */ b;

            /** @interface */
            class Foo {}
            exports = Foo;
            """,
            """
            goog.module('b.Foo');
            const AFoo = goog.require('a.Foo');
            /** @implements {AFoo} */
            class Foo {}
            exports = Foo;
            """));
  }

  @Test
  public void testUnorderedGoogProvideAndModule_doesntCrash() {
    testSame(
        srcs(
            "goog.provide('ns.boo');",
            """
            goog.module('test');
            const boo = goog.require('ns.boo');
            """,
            """
            ns = ns || {};
            ns.boo = ns.boo || {};
            """));
  }

  @Test
  public void testLegacyGoogModule_redeclaredAsVar_reportsError() {
    // This is a bad pattern but should not crash the compiler.
    test(
        srcs(
            "goog.module('GlobalName'); goog.module.declareLegacyNamespace();",
            "var GlobalName = class {};"),
        warning(TypeValidator.DUP_VAR_DECLARATION_TYPE_MISMATCH));
  }

  @Test
  public void testGoogModuleIdAttachment_underGoogModule() {
    testSame(
        srcs(
            """
            goog.module('my.module');

            class A { }; CLASS: A;
            /** @interface */ class B { }; IFACE: B;
            function C() { }; FUNC: C;
            /** @enum {number} */ const D = {}; ENUM: D;
            CAST: /** @type {function()} */ (function() { });

            function scope() {
              class A { }; SCOPED_CLASS: A;
              /** @interface */ class B { }; SCOPED_IFACE: B;
              function C() { }; SCOPED_FUNC: C;
              /** @enum {number} */ const D = {}; SCOPED_ENUM: D;
              SCOPED_CAST: /** @type {function()} */ (function() { });
            }
            """));

    assertAttachedGoogModuleId("CLASS", "my.module");
    assertAttachedGoogModuleId("IFACE", "my.module");
    assertAttachedGoogModuleId("FUNC", "my.module");
    assertAttachedGoogModuleId("ENUM", "my.module");
    assertAttachedGoogModuleId("CAST", null);

    assertAttachedGoogModuleId("SCOPED_CLASS", "my.module");
    assertAttachedGoogModuleId("SCOPED_IFACE", "my.module");
    assertAttachedGoogModuleId("SCOPED_FUNC", "my.module");
    assertAttachedGoogModuleId("SCOPED_ENUM", "my.module");
    assertAttachedGoogModuleId("SCOPED_CAST", null);
  }

  @Test
  public void testGoogModuleIdAttachment_underGoogLoadModule() {
    testSame(
        srcs(
            """
            goog.loadModule(function(exports) {
              goog.module('my.module.1');

              class A { }; CLASS1: A;
              /** @interface */ class B { }; IFACE1: B;
              function C() { }; FUNC1: C;
              /** @enum {number} */ const D = {}; ENUM1: D;
              CAST1: /** @type {function()} */ (function() { });

              return exports;
            })

            goog.loadModule(function(exports) {
              goog.module('my.module.2');

              class A { }; CLASS2: A;
              /** @interface */ class B { }; IFACE2: B;
              function C() { }; FUNC2: C;
              /** @enum {number} */ const D = {}; ENUM2: D;
              CAST2: /** @type {function()} */ (function() { });

              return exports;
            })
            """));

    assertAttachedGoogModuleId("CLASS1", "my.module.1");
    assertAttachedGoogModuleId("IFACE1", "my.module.1");
    assertAttachedGoogModuleId("FUNC1", "my.module.1");
    assertAttachedGoogModuleId("ENUM1", "my.module.1");
    assertAttachedGoogModuleId("CAST1", null);

    assertAttachedGoogModuleId("CLASS2", "my.module.2");
    assertAttachedGoogModuleId("IFACE2", "my.module.2");
    assertAttachedGoogModuleId("FUNC2", "my.module.2");
    assertAttachedGoogModuleId("ENUM2", "my.module.2");
    assertAttachedGoogModuleId("CAST2", null);
  }

  @Test
  public void testGoogModuleIdAttachment_underGoogProvide() {
    testSame(
        srcs(
            """
            goog.provide('my.module');

            class A { }; CLASS: A;
            /** @interface */ class B { }; IFACE: B;
            function C() { }; FUNC: C;
            /** @enum {number} */ const D = {}; ENUM: D;
            CAST: /** @type {function()} */ (function() { });

            function scope() {
              class A { }; SCOPED_CLASS: A;
              /** @interface */ class B { }; SCOPED_IFACE: B;
              function C() { }; SCOPED_FUNC: C;
              /** @enum {number} */ const D = {}; SCOPED_ENUM: D;
              SCOPED_CAST: /** @type {function()} */ (function() { });
            }
            """));

    assertAttachedGoogModuleId("CLASS", null);
    assertAttachedGoogModuleId("IFACE", null);
    assertAttachedGoogModuleId("FUNC", null);
    assertAttachedGoogModuleId("ENUM", null);
    assertAttachedGoogModuleId("CAST", null);

    assertAttachedGoogModuleId("SCOPED_CLASS", null);
    assertAttachedGoogModuleId("SCOPED_IFACE", null);
    assertAttachedGoogModuleId("SCOPED_FUNC", null);
    assertAttachedGoogModuleId("SCOPED_ENUM", null);
    assertAttachedGoogModuleId("SCOPED_CAST", null);
  }

  @Test
  public void testGoogModuleIdAttachment_underEsModule() {
    testSame(
        srcs(
            """
            export {};

            class A { }; CLASS: A;
            /** @interface */ class B { }; IFACE: B;
            function C() { }; FUNC: C;
            /** @enum {number} */ const D = {}; ENUM: D;
            CAST: /** @type {function()} */ (function() { });

            function scope() {
              class A { }; SCOPED_CLASS: A;
              /** @interface */ class B { }; SCOPED_IFACE: B;
              function C() { }; SCOPED_FUNC: C;
              /** @enum {number} */ const D = {}; SCOPED_ENUM: D;
              SCOPED_CAST: /** @type {function()} */ (function() { });
            }
            """));

    assertAttachedGoogModuleId("CLASS", null);
    assertAttachedGoogModuleId("IFACE", null);
    assertAttachedGoogModuleId("FUNC", null);
    assertAttachedGoogModuleId("ENUM", null);
    assertAttachedGoogModuleId("CAST", null);

    assertAttachedGoogModuleId("SCOPED_CLASS", null);
    assertAttachedGoogModuleId("SCOPED_IFACE", null);
    assertAttachedGoogModuleId("SCOPED_FUNC", null);
    assertAttachedGoogModuleId("SCOPED_ENUM", null);
    assertAttachedGoogModuleId("SCOPED_CAST", null);
  }

  @Test
  public void testGoogModuleIdAttachment_underGoogDeclareModuleId() {
    testSame(
        srcs(
            """
            export {};
            goog.declareModuleId('my.module');

            class A { }; CLASS: A;
            /** @interface */ class B { }; IFACE: B;
            function C() { }; FUNC: C;
            /** @enum {number} */ const D = {}; ENUM: D;
            CAST: /** @type {function()} */ (function() { });

            function scope() {
              class A { }; SCOPED_CLASS: A;
              /** @interface */ class B { }; SCOPED_IFACE: B;
              function C() { }; SCOPED_FUNC: C;
              /** @enum {number} */ const D = {}; SCOPED_ENUM: D;
              SCOPED_CAST: /** @type {function()} */ (function() { });
            }
            """));

    assertAttachedGoogModuleId("CLASS", "my.module");
    assertAttachedGoogModuleId("IFACE", "my.module");
    assertAttachedGoogModuleId("FUNC", "my.module");
    assertAttachedGoogModuleId("ENUM", "my.module");
    assertAttachedGoogModuleId("CAST", null);

    assertAttachedGoogModuleId("SCOPED_CLASS", "my.module");
    assertAttachedGoogModuleId("SCOPED_IFACE", "my.module");
    assertAttachedGoogModuleId("SCOPED_FUNC", "my.module");
    assertAttachedGoogModuleId("SCOPED_ENUM", "my.module");
    assertAttachedGoogModuleId("SCOPED_CAST", null);
  }

  private void assertAttachedGoogModuleId(String label, @Nullable String expectedId) {
    JSType type = this.getLabeledStatement(label).statementNode.getOnlyChild().getJSType();

    final String actualId;
    if (type.isFunctionType()) {
      actualId = type.toMaybeFunctionType().getGoogModuleId();
    } else if (type.isEnumType()) {
      actualId = type.toMaybeEnumType().getGoogModuleId();
    } else {
      throw new AssertionError(type);
    }

    assertThat(actualId).isEqualTo(expectedId);
  }

  @Test
  public void testObjectLitWithDeclaredSymbolProperty() {
    testSame(
        externs(
            new TestExternsBuilder().addIterable().build(),
            """
            /** @const {symbol}  */
            Symbol.foo;
            """),
        srcs(
            """
            const foo = {
              /** @const */
              [Symbol.foo]: 0
            };
            """));

    ObjectType foo = findNameType("foo", globalScope).assertObjectType();
    KnownSymbolType symbolFooType = (KnownSymbolType) findNameType("Symbol.foo", globalScope);
    assertType(foo).withTypeOfProp(symbolFooType).isNumber();
  }

  @Test
  public void testMemoization() {
    Node root1 = createEmptyRoot();
    Node root2 = createEmptyRoot();
    Compiler compiler = new Compiler();
    compiler.initOptions(new CompilerOptions());
    TypedScopeCreator creator = new TypedScopeCreator(compiler);
    TypedScope scopeA = creator.createScope(root1, null);
    assertThat(creator.createScope(root1, null)).isSameInstanceAs(scopeA);
    assertThat(creator.createScope(root2, null)).isNotSameInstanceAs(scopeA);
  }

  @Test
  public void testMemoizationPreconditionCheck() {
    Compiler compiler = new Compiler();
    compiler.initOptions(new CompilerOptions());
    Node root = createEmptyRoot();
    TypedScopeCreator creator = new TypedScopeCreator(compiler);
    TypedScope scopeA = creator.createScope(root, null);

    try {
      creator.createScope(root, scopeA);
      assertWithMessage("Expected an IllegalStateException").fail();
    } catch (IllegalStateException expected) {
    }
  }

  private static Node createEmptyRoot() {
    Node script = IR.script();
    Node root = IR.root(IR.root(), IR.root(script));
    script.setInputId(new InputId("input"));
    return root;
  }

  private JSType findNameType(String name, TypedScope scope) {
    return findTypeOnMatchedNode(n -> n.matchesQualifiedName(name), scope);
  }

  private String findNameTypeStr(String name, TypedScope scope) {
    return findNameType(name, scope).toString();
  }

  private JSType findTokenType(Token type, TypedScope scope) {
    return findTypeOnMatchedNode(n -> type == n.getToken(), scope);
  }

  private JSType findTypeOnMatchedNode(Predicate<Node> matcher, TypedScope scope) {
    Node root = scope.getRootNode();
    Deque<Node> queue = new ArrayDeque<>();
    queue.push(root);
    while (!queue.isEmpty()) {
      Node current = queue.pop();
      if (matcher.apply(current) && current.getJSType() != null) {
        return current.getJSType();
      }

      for (Node child = current.getFirstChild(); child != null; child = child.getNext()) {
        queue.push(child);
      }
    }
    return null;
  }

  private JSType getNativeType(JSTypeNative type) {
    return registry.getNativeType(type);
  }

  private ObjectType getNativeObjectType(JSTypeNative type) {
    return (ObjectType) registry.getNativeType(type);
  }
}
