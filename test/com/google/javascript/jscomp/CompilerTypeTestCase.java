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

import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.javascript.jscomp.testing.JSCompCorrespondences.DESCRIPTION_EQUALITY;
import static com.google.javascript.rhino.jstype.JSTypeNative.BOOLEAN_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.NUMBER_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.OBJECT_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.STRING_TYPE;
import static com.google.javascript.rhino.jstype.JSTypeNative.SYMBOL_TYPE;
import static com.google.javascript.rhino.testing.TypeSubject.assertType;
import static com.google.javascript.rhino.testing.TypeSubject.types;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.JSTypeNative;
import com.google.javascript.rhino.jstype.JSTypeRegistry;
import com.google.javascript.rhino.jstype.ObjectType;
import com.google.javascript.rhino.jstype.RecordTypeBuilder;
import com.google.javascript.rhino.jstype.TemplatizedType;
import com.google.javascript.rhino.testing.TestErrorReporter;
import org.junit.After;
import org.junit.Before;

/** This class is mostly used by passes testing {@link TypeCheck}. */
abstract class CompilerTypeTestCase {
  protected static final Joiner LINE_JOINER = Joiner.on('\n');

  static final String CLOSURE_DEFS =
      """
      goog.inherits = function(x, y) {};
      /** @type {!Function} */ goog.abstractMethod = function() {};
      goog.isFunction = function(x) {};
      goog.isObject = function(x) {};
      /** @const */ goog.array = {};
      // simplified ArrayLike definition
      /**
       * @typedef {Array|{length: number}}
       */
      goog.array.ArrayLike;
      /**
       * @param {Array<T>|{length:number}} arr
       * @param {function(this:S, T, number, goog.array.ArrayLike):boolean} f
       * @param {S=} obj
       * @return {!Array<T>}
       * @template T,S
       */
      // return empty array to satisfy return type
      goog.array.filter = function(arr, f, obj){ return []; };
      goog.asserts = {};
      /** @return {*} */ goog.asserts.assert = function(obj, msg = undefined) { return obj; };
      goog.loadModule = function(mod) {};
      """;

  /**
   * A default set of externs for testing.
   *
   * <p>TODO(bradfordcsmith): Replace this with externs built by TestExternsBuilder.
   */
  static final String DEFAULT_EXTERNS = CompilerTestCase.DEFAULT_EXTERNS;

  protected Compiler compiler;
  protected JSTypeRegistry registry;
  protected TestErrorReporter errorReporter;

  static final CompilerOptions defaultOptions() {
    CompilerOptions options = new CompilerOptions();
    options.setCodingConvention(new GoogleCodingConvention());
    options.setLanguage(LanguageMode.UNSUPPORTED);
    options.setWarningLevel(DiagnosticGroups.MISSING_PROPERTIES, CheckLevel.WARNING);
    options.setWarningLevel(DiagnosticGroups.MISPLACED_TYPE_ANNOTATION, CheckLevel.WARNING);
    options.setWarningLevel(DiagnosticGroups.INVALID_CASTS, CheckLevel.WARNING);
    options.setWarningLevel(DiagnosticGroups.LINT_CHECKS, CheckLevel.WARNING);
    options.setWarningLevel(DiagnosticGroups.JSDOC_MISSING_TYPE, CheckLevel.WARNING);
    options.setWarningLevel(DiagnosticGroups.BOUNDED_GENERICS, CheckLevel.WARNING);
    return options;
  }

  protected CompilerOptions getDefaultOptions() {
    return defaultOptions();
  }

  protected CodingConvention getCodingConvention() {
    return new GoogleCodingConvention();
  }

  protected void checkReportedWarningsHelper(String[] expected) {
    if (expected == null) {
      expected = new String[0];
    }

    assertWithMessage("Regarding warnings:")
        .that(compiler.getWarnings())
        .comparingElementsUsing(DESCRIPTION_EQUALITY)
        .containsExactlyElementsIn(expected)
        .inOrder();
  }

  @Before
  public void setUp() throws Exception {
    errorReporter = new TestErrorReporter();
    initializeNewCompiler(getDefaultOptions());
  }

  @After
  public void validateWarningsAndErrors() {
    errorReporter.verifyHasEncounteredAllWarningsAndErrors();
  }

  protected void initializeNewCompiler(CompilerOptions options) {
    compiler = new Compiler();
    compiler.initOptions(options);
    compiler.markFeatureNotAllowed(Feature.MODULES);
    registry = compiler.getTypeRegistry();
  }

  protected JSType createUnionType(JSType... variants) {
    return registry.createUnionType(variants);
  }

  protected RecordTypeBuilder createRecordTypeBuilder() {
    return new RecordTypeBuilder(registry);
  }

  protected JSType createNullableType(JSType type) {
    return registry.createNullableType(type);
  }

  protected JSType createOptionalType(JSType type) {
    return registry.createOptionalType(type);
  }

  protected TemplatizedType createTemplatizedType(
      ObjectType baseType, ImmutableList<JSType> templatizedTypes) {
    return registry.createTemplatizedType(baseType, templatizedTypes);
  }

  protected TemplatizedType createTemplatizedType(ObjectType baseType, JSType... templatizedType) {
    return createTemplatizedType(baseType, ImmutableList.copyOf(templatizedType));
  }

  /** Asserts that a Node representing a type expression resolves to the correct {@code JSType}. */
  protected void assertTypeEquals(JSType expected, Node actual) {
    assertTypeEquals(expected, new JSTypeExpression(actual, "<BaseJSTypeTestCase.java>"));
  }

  /** Asserts that a a type expression resolves to the correct {@code JSType}. */
  protected void assertTypeEquals(JSType expected, JSTypeExpression actual) {
    assertTypeEquals(expected, resolve(actual));
  }

  protected final void assertTypeEquals(JSType a, JSType b) {
    assertType(b).isEqualTo(a);
  }

  protected final void assertTypeEquals(String msg, JSType a, JSType b) {
    assertWithMessage(msg).about(types()).that(b).isEqualTo(a);
  }

  /** Resolves a type expression, expecting the given warnings. */
  protected JSType resolve(JSTypeExpression n, String... warnings) {
    errorReporter.expectAllWarnings(warnings);
    return n.evaluate(null, registry);
  }

  protected ObjectType getNativeNoObjectType() {
    return getNativeObjectType(JSTypeNative.NO_OBJECT_TYPE);
  }

  protected ObjectType getNativeArrayType() {
    return getNativeObjectType(JSTypeNative.ARRAY_TYPE);
  }

  protected ObjectType getNativeReadonlyArrayType() {
    return getNativeObjectType(JSTypeNative.READONLY_ARRAY_TYPE);
  }

  protected ObjectType getNativeStringObjectType() {
    return getNativeObjectType(JSTypeNative.STRING_OBJECT_TYPE);
  }

  protected ObjectType getNativeNumberObjectType() {
    return getNativeObjectType(JSTypeNative.NUMBER_OBJECT_TYPE);
  }

  protected ObjectType getNativeBooleanObjectType() {
    return getNativeObjectType(JSTypeNative.BOOLEAN_OBJECT_TYPE);
  }

  protected ObjectType getNativeNoType() {
    return getNativeObjectType(JSTypeNative.NO_TYPE);
  }

  protected ObjectType getNativeUnknownType() {
    return getNativeObjectType(JSTypeNative.UNKNOWN_TYPE);
  }

  protected ObjectType getNativeCheckedUnknownType() {
    return getNativeObjectType(JSTypeNative.CHECKED_UNKNOWN_TYPE);
  }

  protected ObjectType getNativeObjectType() {
    return getNativeObjectType(JSTypeNative.OBJECT_TYPE);
  }

  ObjectType getNativeObjectType(JSTypeNative jsTypeNative) {
    return registry.getNativeObjectType(jsTypeNative);
  }

  protected FunctionType getNativeObjectConstructorType() {
    return getNativeFunctionType(JSTypeNative.OBJECT_FUNCTION_TYPE);
  }

  protected FunctionType getNativeArrayConstructorType() {
    return getNativeFunctionType(JSTypeNative.ARRAY_FUNCTION_TYPE);
  }

  protected FunctionType getNativeBooleanObjectConstructorType() {
    return getNativeFunctionType(JSTypeNative.BOOLEAN_OBJECT_FUNCTION_TYPE);
  }

  protected FunctionType getNativeNumberObjectConstructorType() {
    return getNativeFunctionType(JSTypeNative.NUMBER_OBJECT_FUNCTION_TYPE);
  }

  protected FunctionType getNativeStringObjectConstructorType() {
    return getNativeFunctionType(JSTypeNative.STRING_OBJECT_FUNCTION_TYPE);
  }

  protected FunctionType getNativeDateConstructorType() {
    return getNativeFunctionType(JSTypeNative.DATE_FUNCTION_TYPE);
  }

  protected FunctionType getNativeRegexpConstructorType() {
    return getNativeFunctionType(JSTypeNative.REGEXP_FUNCTION_TYPE);
  }

  protected FunctionType getNativeFunctionType() {
    return getNativeFunctionType(JSTypeNative.FUNCTION_TYPE);
  }

  FunctionType getNativeFunctionType(JSTypeNative jsTypeNative) {
    return registry.getNativeFunctionType(jsTypeNative);
  }

  protected JSType getNativeVoidType() {
    return getNativeType(JSTypeNative.VOID_TYPE);
  }

  protected JSType getNativeNullType() {
    return getNativeType(JSTypeNative.NULL_TYPE);
  }

  protected JSType getNativeNullVoidType() {
    return getNativeType(JSTypeNative.NULL_VOID);
  }

  protected JSType getNativeNumberType() {
    return getNativeType(JSTypeNative.NUMBER_TYPE);
  }

  protected JSType getNativeBooleanType() {
    return getNativeType(JSTypeNative.BOOLEAN_TYPE);
  }

  protected JSType getNativeStringType() {
    return getNativeType(JSTypeNative.STRING_TYPE);
  }

  protected JSType getNativeObjectNumberStringBooleanType() {
    return registry.createUnionType(OBJECT_TYPE, NUMBER_TYPE, STRING_TYPE, BOOLEAN_TYPE);
  }

  protected JSType getNativeNumberStringBooleanType() {
    return getNativeType(JSTypeNative.NUMBER_STRING_BOOLEAN);
  }

  protected JSType getNativeObjectNumberStringBooleanSymbolType() {
    return registry.createUnionType(
        OBJECT_TYPE, NUMBER_TYPE, STRING_TYPE, BOOLEAN_TYPE, SYMBOL_TYPE);
  }

  protected JSType getNativeValueTypes() {
    return getNativeType(JSTypeNative.VALUE_TYPES);
  }

  JSType getNativeAllType() {
    return getNativeType(JSTypeNative.ALL_TYPE);
  }

  JSType getNativeType(JSTypeNative jsTypeNative) {
    return registry.getNativeType(jsTypeNative);
  }
}
