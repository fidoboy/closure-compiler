/*
 * Copyright 2021 The Closure Compiler Authors.
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

package com.google.javascript.jscomp.serialization;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.javascript.jscomp.testing.ColorSubject.assertThat;
import static com.google.javascript.rhino.testing.NodeSubject.assertNode;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.AstValidator;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.CompilerTestCase;
import com.google.javascript.jscomp.PassFactory;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.jscomp.colors.ColorRegistry;
import com.google.javascript.jscomp.colors.StandardColors;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.serialization.TypedAstDeserializer.DeserializedAst;
import com.google.javascript.jscomp.testing.TestExternsBuilder;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.InputId;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.StaticSourceFile;
import com.google.javascript.rhino.Token;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests that both serialize and then deserialize a compiler AST.
 *
 * <p>Due to the difference from a normal compiler pass, this is not actually able to reuse much of
 * the infrastructure inherited from CompilerTestCase, and thus it may make sense to separate these
 * tests more fully.
 */
@RunWith(JUnit4.class)
public final class SerializeAndDeserializeAstTest extends CompilerTestCase {

  private @Nullable Consumer<TypedAst> consumer = null;
  private boolean includeTypes;
  private boolean resolveSourceMapAnnotations;
  private boolean parseInlineSourceMaps;
  private ImmutableList<String> runtimeLibraries = null;
  private Optional<PassFactory> preSerializePassFactory = Optional.empty();
  private boolean skipMatchingScriptFeaturesBeforeAndAfterSerialization = false;

  @Override
  protected CompilerPass getProcessor(Compiler compiler) {
    CompilerPass serializationPass =
        new SerializeTypedAstPass(
            compiler,
            consumer,
            SerializationOptions.builder()
                .setIncludeDebugInfo(false)
                .setRuntimeLibraries(this.runtimeLibraries)
                .build());
    if (preSerializePassFactory.isEmpty()) {
      return serializationPass;
    }
    return new CompilerPass() {
      @Override
      public void process(Node externs, Node root) {
        preSerializePassFactory.get().create(compiler).process(externs, root);
        serializationPass.process(externs, root);
      }
    };
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    enableTypeCheck();
    enableCreateModuleMap();
    enableSourceInformationAnnotator();
    this.includeTypes = true;
    this.resolveSourceMapAnnotations = true;
    this.parseInlineSourceMaps = true;
    this.runtimeLibraries = ImmutableList.of();
  }

  @Test
  public void testConstNumberDeclaration() {
    testSame("const x = 7;");
  }

  @Test
  public void testConstBigIntDeclaration() {
    testSame("const x = 7n;");
  }

  @Test
  public void testConstStringDeclaration() {
    testSame("const x = 'x';");
  }

  @Test
  public void testConstRegexDeclaration() {
    testSame("const x = /regexp/;");
  }

  @Test
  public void testConstObjectDeclaration() {
    testSame("const obj = {x: 7};");
  }

  @Test
  public void testObjectWithShorthandProperty() {
    testSame("const z = 0; const obj = {z};");
  }

  @Test
  public void testObjectWithMethod() {
    testSame("let obj = {method() {}};");
  }

  @Test
  public void testObjectWithQuotedMethod() {
    testSame("let obj = {'method'() {}};");
  }

  @Test
  public void testObjectWithGetter() {
    testSame("let obj = {get x() {}};");
  }

  @Test
  public void testObjectWithQuotedGetter() {
    testSame("let obj = {get 'x'() {}};");
  }

  @Test
  public void testObjectWithSetter() {
    testSame("let obj = {set x(value) {}};");
  }

  @Test
  public void testObjectWithQuotedSetter() {
    testSame("let obj = {set 'x'(value) {}};");
  }

  @Test
  public void testTemplateLiteral_simple() {
    testSame("let obj = `foobar`;");
  }

  @Test
  public void testTemplateLiteral_withSubstitution() {
    testSame("let obj = `Hello ${2+3}`;");
  }

  @Test
  public void testTemplateLiteral_withIllegalEscape() {
    // https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Template_literals#es2018_revision_of_illegal_escape_sequences
    testSame("latex`\\unicode`;");
  }

  @Test
  public void testConstRegexpDeclaration() {
    testSame("const x = /hello world/;");
  }

  @Test
  public void testVanillaForLoop() {
    testSame("for (let x = 0; x < 10; ++x);");
  }

  @Test
  public void testForOfLoop() {
    testSame("for (let elem of []);");
  }

  @Test
  public void testForAwaitOfLoop() {
    testSame("async function f() { for await (let elem of []); }");
  }

  @Test
  public void testConstructorJsdoc() {
    testSame("/** @constructor */ function Foo() {}");
  }

  @Test
  public void testSideEffectsJsdoc() {
    testSame("/** @nosideeffects */ function f(x) {}");
    testSame("/** @modifies {arguments} */ function f(x) {}");
    testSame("/** @modifies {this} */ function f(x) {}");
  }

  @Test
  public void testCollapsePropertiesJsdoc() {
    testSame("const ns = {}; /** @const */ ns.f = (x) => x;");
    testSame("const ns = {}; /** @nocollapse */ ns.f = (x) => x;");
    // Replace types with colors before serializing for this case.
    // 1. It's good to have a test case that covers serializing an AST that already has Colors
    // 2. The test plumbing in this class expects that the AST that was serialized will match the
    //    AST that was deserialized and both will match the expected code.
    replaceTypesWithColors();
    test(
        "/** @enum {string} */ const Enum = { A: 'string' };",
        "/** @enum {!JSDocSerializer_placeholder_type} */ const Enum = { A: 'string' };");
  }

  @Test
  public void testEmptyClassDeclaration() {
    skipMatchingScriptFeaturesBeforeAndAfterSerialization = true;
    testSame("class Foo {}");
  }

  @Test
  public void testEmptyClassDeclarationWithExtends() {
    skipMatchingScriptFeaturesBeforeAndAfterSerialization = true;
    testSame("class Foo {} class Bar extends Foo {}");
  }

  @Test
  public void testClassDeclarationWithMethods() {
    testSame(
        """
        class Foo {
          a() {}
          get c() {}
          set d(x) {}
        }
        """);

    // Type checking will report computed property accesses as errors for a class,
    // so disable it for this case which contains several.
    disableTypeCheck();
    testSame(
        """
        class Foo {
          a() {}
          'b'() {}
          get c() {}
          set d(x) {}
          ['e']() {}
        }
        """);
  }

  @Test
  public void testClassDeclarationWithFields() {
    skipMatchingScriptFeaturesBeforeAndAfterSerialization = true;
    testSame(
        """
        class Foo {
          a = 1;
          d;
        }
        """);

    // Type checking will report computed property accesses as errors for a class,
    // so disable it for this case which contains several.
    disableTypeCheck();
    testSame(
        """
        class Foo {
          a = 1;
          'b' = 4;
          static ['c'] = 'hi';
          d;
          ['e'];
          1 = 2;
        }
        """);
  }

  @Test
  public void testEmptyClassStaticBlock() {
    skipMatchingScriptFeaturesBeforeAndAfterSerialization = true;
    testSame(
        """
        class Foo {
          static {
          }
        }
        """);
  }

  @Test
  public void testClassStaticBlock_variables() {
    skipMatchingScriptFeaturesBeforeAndAfterSerialization = true;
    testSame(
        """
        class Foo {
          static {
            this.x=1;
            let y =2;
            var z =3;
          }
        }
        """);
  }

  @Test
  public void testClassStaticBlock_function() {
    skipMatchingScriptFeaturesBeforeAndAfterSerialization = true;
    testSame(
        """
        class Foo {
          static {
            function x() {
            }
          }
        }
        """);
  }

  @Test
  public void testMultipleClassStaticBlocks() {
    skipMatchingScriptFeaturesBeforeAndAfterSerialization = true;
    testSame(
        """
        class Foo {
          static {
            this.x=1;
          }
          static {
            this.y=2;
          }
        }
        """);
  }

  @Test
  public void testVanillaFunctionDeclaration() {
    testSame("function f(x, y) { return x ** y; }");
  }

  @Test
  public void testBlockScopedFunctionDeclaration() {
    testSame("if (true) { function f() {} }");
  }

  @Test
  public void testAsyncVanillaFunctionDeclaration() {
    testSame("async function f() {}");
  }

  @Test
  public void testArrowFunctionDeclaration() {
    testSame("let fn = (x) => x >>> 0x80;");
  }

  @Test
  public void testAsyncFunctionDeclaration() {
    testSame("async function f(x) {};");
  }

  @Test
  public void testGeneratorFunctionDeclaration() {
    testSame("function* f(x) {};");
  }

  @Test
  public void testAsyncGeneratorFunctionDeclaration() {
    testSame("async function* f(x) {};");
  }

  @Test
  public void testYieldAll() {
    testSame("function* f(x) { yield* x; };");
  }

  @Test
  public void testFunctionCallRestAndSpread() {
    skipMatchingScriptFeaturesBeforeAndAfterSerialization = true;
    testSame("function f(...x) {} f(...[1, 2, 3]);");
  }

  @Test
  public void testFunctionDefaultAndDestructuringParameters() {
    skipMatchingScriptFeaturesBeforeAndAfterSerialization = true;
    testSame("function f([a, b], x = 0, {y, ...z} = {y: 1}) {}");
  }

  @Test
  public void testTrailingComma() {
    testSame("function f(x, y,) {}");
    testSame("function f(x) {} f(1,);");
    testSame("class C {constructor(x) {}} new C(1,);");
  }

  @Test
  public void testComputedProperty() {
    testSame("const o = {['foo']: 33};");
  }

  @Test
  public void testLabledStatement() {
    testSame("label: for (;;);");
  }

  @Test
  public void testIdGenerator() {
    testSame("/** @idGenerator {xid} */ function xid(id) {}");
  }

  @Test
  public void testUnpairedSurrogateStrings() {
    testSame("const s = '\ud800';");
  }

  @Test
  public void testLogicalAssignment() {
    testSame("let x = 0; x ||= 1");
    testSame("function f(x, y) { return x &&= y; }");
    testSame("let y = null; if (y ??= 2) { function f() {} }");
  }

  @Test
  public void testExponentAssignment() {
    testSame("let x = 2; x **= 0;");
  }

  @Test
  public void testEsModule() {
    testSame(
        new String[] {
          "export const x = 0; const y = 1; export {y};",
          "import {x, y as z} from './testcode0'; import * as input0 from './testcode0'"
        });
  }

  private static final String BASE64_PREFIX = "data:application/json;base64,";
  private static final String ENCODED_SOURCE_MAP =
      "eyJ2ZXJzaW9uIjozLCJmaWxlIjoiZm9vLmpzIiwic291cmNlUm9vdCI6IiIsInNvdXJjZXMiOlsiZm9vLnRzIl0sIm5hbWVzIjpbXSwibWFwcGluZ3MiOiJBQUFBO0lBR0UsV0FBWSxLQUFhO1FBQ3ZCLElBQUksQ0FBQyxDQUFDLEdBQUcsS0FBSyxDQUFDO0lBQ2pCLENBQUM7SUFDSCxRQUFDO0FBQUQsQ0FBQyxBQU5ELElBTUM7QUFFRCxPQUFPLENBQUMsR0FBRyxDQUFDLElBQUksQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDLENBQUMifQ==";

  @Test
  public void testInlineSourceMaps() {
    String sourceMapTestCode =
        """
        var X = (function () {
            function X(input) {
                this.y = input;
            }
            return X;
        }());
        """;
    String sourceMappingURLComment = "//# sourceMappingURL=" + BASE64_PREFIX + ENCODED_SOURCE_MAP;
    ;
    String code = sourceMapTestCode + "\n" + sourceMappingURLComment;

    Result result = testAndReturnResult(srcs(code), expected(code));
    assertThat(result.compiler.getBase64SourceMapContents("testcode"))
        .isEqualTo(ENCODED_SOURCE_MAP);
  }

  @Test
  public void testSourceMapsInSeparateMapFiles() {
    // Sourcemap URLs should be a base64 encoded data url, not the name of a .js.map file.
    // If we see a .js.map file, we will not serialize it.
    String sourceMapTestCode =
        """
        var X = (function () {
            function X(input) {
                this.y = input;
            }
            return X;
        }());
        """;
    String sourceMappingURL = "foo.js.map";
    String sourceMappingURLComment = "//# sourceMappingURL=" + sourceMappingURL;
    String code = sourceMapTestCode + "\n" + sourceMappingURLComment;

    Result result = testAndReturnResult(srcs(code), expected(code));
    assertThat(result.compiler.getBase64SourceMapContents("testcode")).isNull();
  }

  @Test
  public void testSourceMapsWithoutResolvingSourceMapAnnotations() {
    this.resolveSourceMapAnnotations = false;
    String sourceMapTestCode =
        """
        var X = (function () {
            function X(input) {
                this.y = input;
            }
            return X;
        }());
        """;
    String sourceMappingURLComment = "//# sourceMappingURL=" + BASE64_PREFIX + ENCODED_SOURCE_MAP;
    ;
    String code = sourceMapTestCode + "\n" + sourceMappingURLComment;

    Result result = testAndReturnResult(srcs(code), expected(code));
    // Source map not registered because `resolveSourceMapAnnotations = false`
    assertThat(result.compiler.getBase64SourceMapContents("testcode")).isNull();
  }

  @Test
  public void testSourceMapsWithoutParsingInlineSourceMaps() {
    this.parseInlineSourceMaps = false;
    String sourceMapTestCode =
        """
        var X = (function () {
            function X(input) {
                this.y = input;
            }
            return X;
        }());
        """;
    String sourceMappingURLComment = "//# sourceMappingURL=" + BASE64_PREFIX + ENCODED_SOURCE_MAP;
    ;
    String code = sourceMapTestCode + "\n" + sourceMappingURLComment;

    Result result = testAndReturnResult(srcs(code), expected(code));
    // Source map is registered when `parseInlineSourceMaps = false`, but we won't try to
    // parse it as a Base64 encoded source map.
    assertThat(result.compiler.getBase64SourceMapContents("testcode")).isNull();
  }

  @Test
  public void testConfiguredDirectorySourceMaps() {
    // We do not allow the TypeScript compiler to set "compilerOptions.sourceRoot" (option to
    // configure a directory to store
    // sourcemaps). Sourcemap URLs should be a base64 encoded data url, not a path to the
    // sourcemap file. If we see a path, we will not serialize anything.
    String sourceMapTestCode =
        """
        var X = (function () {
            function X(input) {
                this.y = input;
            }
            return X;
        }());
        """;
    String sourceMappingURLPath = "directory/foo.js.map";
    String sourceMappingURLComment = "//# sourceMappingURL=" + sourceMappingURLPath;
    String code = sourceMapTestCode + "\n" + sourceMappingURLComment;

    Result result = testAndReturnResult(srcs(code), expected(code));
    assertThat(result.compiler.getBase64SourceMapContents("testcode")).isNull();
  }

  @Test
  public void testConvertsNumberTypeToColor() {
    enableTypeCheck();

    Result result = testAndReturnResult(srcs("3"), expected("3"));
    Node newScript = result.sourceRoot.getFirstChild();
    assertNode(newScript).hasToken(Token.SCRIPT);
    Node three = newScript.getFirstFirstChild();

    assertNode(three).hasToken(Token.NUMBER);
    assertThat(three.getColor()).isSameInstanceAs(StandardColors.NUMBER);
  }

  @Test
  public void testConvertsArrayTypeToColor_andStoresInRegistry() {
    enableTypeCheck();

    Result result = testAndReturnResult(srcs("[]"), expected("[]"));
    Node newScript = result.sourceRoot.getFirstChild();
    assertNode(newScript).hasToken(Token.SCRIPT);
    Node three = newScript.getFirstFirstChild();

    assertNode(three).hasToken(Token.ARRAYLIT);
    assertThat(three.getColor()).isSameInstanceAs(result.registry.get(StandardColors.ARRAY_ID));
  }

  @Test
  public void testSkipAddingColors() {
    enableTypeCheck();
    // the pre-serialization AST includes JSTypes, but the deserialized AST has no Colors
    this.includeTypes = false;

    Result result = testAndReturnResult(srcs("3"), expected("3"));
    Node newScript = result.sourceRoot.getFirstChild();
    assertNode(newScript).hasToken(Token.SCRIPT);
    Node three = newScript.getFirstFirstChild();

    assertNode(three).hasToken(Token.NUMBER);
    assertThat(three.getColor()).isNull();
    assertThat(result.registry).isNull();
  }

  @Test
  public void testOriginalNamePreserved() {
    Node newRoot =
        testAndReturnResult(srcs("const x = 0;"), expected("const x = 0;"))
            .sourceRoot
            .getFirstChild();

    Node constDeclaration = newRoot.getFirstChild();
    assertNode(constDeclaration).hasToken(Token.CONST);

    Node x = constDeclaration.getOnlyChild();
    assertNode(x).hasStringThat().isEqualTo("x");
    assertNode(x).hasOriginalNameThat().isEqualTo("x");
  }

  @Test
  public void testOriginalNamePreservedAfterModuleRewriting() {
    enableRewriteClosureCode();

    Node newRoot =
        testAndReturnResult(
                externs(new TestExternsBuilder().addClosureExterns().build()),
                srcs("goog.module('a.b.c'); const x = 0;"),
                expected(
                    """
                    /** @const */ var module$exports$a$b$c = {};
                    const module$contents$a$b$c_x = 0;
                    """))
            .sourceRoot
            .getFirstChild();

    Node constDeclaration = newRoot.getSecondChild();
    assertNode(constDeclaration).hasToken(Token.CONST);

    Node globalizedXName = constDeclaration.getOnlyChild();
    assertNode(globalizedXName).hasStringThat().isEqualTo("module$contents$a$b$c_x");
    assertNode(globalizedXName).hasOriginalNameThat().isEqualTo("x");
  }

  @Test
  public void serializesFileWithPreloadedCode() throws IOException {
    SourceFile a = SourceFile.fromCode("a.js", "const a = 0;");
    SourceFile b = SourceFile.fromCode("b.js", "const b = a;");

    Result result =
        this.testAndReturnResult(srcs(ImmutableList.of(a, b)), expected(ImmutableList.of(a, b)));
    Node scriptA = result.sourceRoot.getFirstChild();
    Node scriptB = result.sourceRoot.getSecondChild();

    assertThat(scriptA.getStaticSourceFile()).isInstanceOf(SourceFile.class);
    assertThat(scriptB.getStaticSourceFile()).isInstanceOf(SourceFile.class);

    assertThat(((SourceFile) scriptA.getStaticSourceFile()).getCode()).isEqualTo("const a = 0;");
    assertThat(((SourceFile) scriptB.getStaticSourceFile()).getCode()).isEqualTo("const b = a;");
  }

  @Test
  public void serializeAndDeserializeFileOnDiskWithUTF16() throws IOException {
    Path pathA = Files.createTempFile("tmp", "a.js");
    Files.write(pathA, ImmutableList.of("const ಠ_ಠ = 0;"), UTF_16);

    SourceFile a = SourceFile.fromFile(pathA.toString(), UTF_16);

    Result result =
        this.testAndReturnResult(srcs(ImmutableList.of(a)), expected(ImmutableList.of(a)));
    Node scriptA = result.sourceRoot.getFirstChild();

    assertThat(scriptA.getStaticSourceFile()).isInstanceOf(SourceFile.class);
    assertThat(((SourceFile) scriptA.getStaticSourceFile()).getCode())
        .isEqualTo("const ಠ_ಠ = 0;\n");
  }

  @Test
  public void serializeAndDeserializeFileOnDiskWithOriginalName() throws IOException {
    Path pathA = Files.createTempFile("tmp", "a.js");
    Files.write(pathA, ImmutableList.of("const a = 0;"));

    SourceFile a =
        SourceFile.builder().withOriginalPath("original_a.js").withPath(pathA.toString()).build();

    Result result =
        this.testAndReturnResult(srcs(ImmutableList.of(a)), expected(ImmutableList.of(a)));
    Node scriptA = result.sourceRoot.getFirstChild();

    assertThat(scriptA.getStaticSourceFile()).isInstanceOf(SourceFile.class);
    assertThat(((SourceFile) scriptA.getStaticSourceFile()).getCode()).isEqualTo("const a = 0;\n");
    assertThat(scriptA.getSourceFileName()).isEqualTo("original_a.js");
  }

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void serializesZipEntries() throws IOException {
    // Setup environment.
    String expectedContent = "const a = 0;";
    Path jsZipPath = folder.newFile("test.js.zip").toPath();
    createZipWithContent(jsZipPath, expectedContent);

    SourceFile a = SourceFile.fromFile(jsZipPath + "!/a.js", UTF_8);

    Result result =
        this.testAndReturnResult(srcs(ImmutableList.of(a)), expected(ImmutableList.of(a)));
    Node scriptA = result.sourceRoot.getFirstChild();

    assertThat(scriptA.getStaticSourceFile()).isInstanceOf(SourceFile.class);
    assertThat(((SourceFile) scriptA.getStaticSourceFile()).getCode()).isEqualTo("const a = 0;");
    assertThat(scriptA.getStaticSourceFile().getName()).isEqualTo(a.getName());
  }

  @Test
  public void setsSourceFileOfSyntheticCode() throws IOException {
    ensureLibraryInjected("base");

    Result result =
        this.testAndReturnResult(
            srcs("0;"),
            // the injected "base" library is merged into the first file's script. ensure that
            // SourceFiles are wired up correctly.
            expected(
                """
                /** @const */ var $jscomp = $jscomp || {};
                /** @const */
                $jscomp.scope = {};
                0;
                """));

    Node script = result.sourceRoot.getFirstChild();
    assertNode(script).hasToken(Token.SCRIPT);
    assertThat(script.getSourceFileName()).isEqualTo("testcode");

    Node jscompDeclaration = script.getFirstChild();
    assertThat(jscompDeclaration.getSourceFileName()).endsWith("js/base.js");
    assertThat(jscompDeclaration.getFirstChild().getSourceFileName()).endsWith("js/base.js");

    Node number = script.getLastChild();
    assertThat(number.getSourceFileName()).isEqualTo("testcode");
  }

  @Test
  public void includesExternsSummary() throws IOException {
    enableGatherExternProperties();

    Result result =
        this.testAndReturnResult(
            externs(
                """
                class Foo {
                // Ensure JSDoc properties are included (b/180424427)
                  /** @param {{arg: string}} x */
                  method(x) { }
                }
                """),
            srcs(""),
            expected(""));

    assertThat(result.ast.getExternProperties()).containsAtLeast("method", "arg");
  }

  @Test
  public void includesShadowedCode() throws IOException {
    preSerializePassFactory =
        Optional.of(
            PassFactory.builder()
                .setName("createShadow")
                .setInternalFactory(
                    (AbstractCompiler c) ->
                        new CompilerPass() {
                          @Override
                          public void process(Node externs, Node root) {
                            Node functionToBeShadowed =
                                root.getFirstFirstChild().getFirstFirstChild();
                            checkState(functionToBeShadowed.isFunction());

                            Node name = IR.name("SHADOW");
                            functionToBeShadowed.replaceWith(name);

                            Node shadowRoot =
                                IR.root(IR.script(IR.exprResult(functionToBeShadowed)));
                            name.setClosureUnawareShadow(shadowRoot);

                            c.reportChangeToEnclosingScope(name);
                          }
                        })
                .build());
    Result result =
        testAndReturnResult(
            srcs(
                """
                (function() {
                  window['foo'] = 5;
                })();
                """),
            expected("SHADOW();"));
    assertThat(result.compiler.getErrors()).isEmpty();

    // Also validate that the content of the shadow is actually correct
    Node shadowHost = result.sourceRoot.getFirstFirstChild().getFirstFirstChild();

    Node shadowedContent = shadowHost.getClosureUnawareShadow();
    Node expectedShadowContent =
        this.parseExpectedJs(
            """
            (function() {
              window['foo'] = 5;
            })
            """);
    assertNode(shadowedContent).isEqualIncludingJsDocTo(expectedShadowContent);

    assertThat(result.compiler.toSource(result.sourceRoot))
        .isEqualTo("(function(){window[\"foo\"]=5})()");
  }

  @Test
  public void includesRuntimeLibraryPaths() throws IOException {
    enableGatherExternProperties();
    this.runtimeLibraries = ImmutableList.of("base", "es6/string");

    Result result = this.testAndReturnResult(externs(""), srcs(""), expected(""));

    assertThat(result.ast.getRuntimeLibraries()).containsExactly("base", "es6/string");
  }

  @Test
  public void wellKnownSymbolsBecomeSymbol() throws IOException {
    enableTypeCheck();
    Result result =
        testAndReturnResult(
            externs(
                """
                /** @type {symbol} */
                Symbol.foobar;
                """),
            srcs("Symbol.foobar"),
            expected("Symbol.foobar"));

    Node newScript = result.sourceRoot.getFirstChild();
    checkState(newScript.isScript(), newScript);

    Node foobar = newScript.getFirstFirstChild();

    assertNode(foobar).matchesQualifiedName("Symbol.foobar");
    assertThat(foobar.getColor()).isSameInstanceAs(StandardColors.SYMBOL);
  }

  @Override
  public void testSame(String code) {
    this.test(code, code);
  }

  public void testSame(String[] sources) {
    this.testAndReturnResult(srcs(sources), expected(sources));
  }

  @Override
  public void test(String code, String expected) {
    this.testAndReturnResult(srcs(code), expected(expected));
  }

  private Result testAndReturnResult(Sources code, Expected expected) {
    return this.testAndReturnResult(externs(ImmutableList.of()), code, expected);
  }

  private Result testAndReturnResult(Externs externs, Sources code, Expected expected) {
    InputStream serializedStream = toInputStream(externs, code, expected);
    Compiler serializingCompiler = getLastCompiler();

    Node oldRoot = serializingCompiler.getRoot();

    ImmutableList<SourceFile> externFiles = collectSourceFilesFromScripts(oldRoot.getFirstChild());
    ImmutableList<SourceFile> codeFiles = collectSourceFilesFromScripts(oldRoot.getSecondChild());

    // NOTE: We need a fresh compiler instance in which to deserialize, because:
    // 1. This is a better representation of what will happen in production use.
    // 2. Deserializing expects to start with a compiler which has no Color-related state.
    //    For example, both serializing and deserializing currently need to call
    //    `compiler.initRuntimeLibraryTypedAsts()`, which may only be called once.
    Compiler deserializingCompiler = createAndInitializeCompiler(externs, code);
    DeserializedAst ast =
        TypedAstDeserializer.deserializeFullAst(
            deserializingCompiler,
            SourceFile.fromCode("syntheticExterns", "", StaticSourceFile.SourceKind.EXTERN),
            ImmutableSet.<SourceFile>builder().addAll(externFiles).addAll(codeFiles).build(),
            serializedStream,
            includeTypes,
            resolveSourceMapAnnotations,
            parseInlineSourceMaps);

    ColorRegistry registry = ast.getColorRegistry().orNull();
    Node newExternsRoot = IR.root();
    Node newSourceRoot = IR.root();

    // this code is a little clunky, but basically the TypedAstDeserializer API assumes you already
    // know what order the sources/externs are expected to be in. The easiest way for us to get that
    // ordering is by looking at the pre-serialized AST, since the Sources/Expected APIs hide the
    // actual file names.
    for (Node oldExtern = serializingCompiler.getRoot().getFirstFirstChild();
        oldExtern != null;
        oldExtern = oldExtern.getNext()) {
      SourceFile extern = (SourceFile) oldExtern.getStaticSourceFile();
      Node script = ast.getFilesystem().get(extern).get();
      script.setInputId(new InputId(extern.getName()));
      newExternsRoot.addChildToBack(script);
    }
    for (Node oldSource = serializingCompiler.getRoot().getSecondChild().getFirstChild();
        oldSource != null;
        oldSource = oldSource.getNext()) {
      SourceFile source = (SourceFile) oldSource.getStaticSourceFile();
      Node script = ast.getFilesystem().get(source).get();
      script.setInputId(new InputId(source.getName()));
      newSourceRoot.addChildToBack(script);
    }

    Node expectedRoot = this.parseExpectedJs(expected);
    assertNode(newSourceRoot).isEqualIncludingJsDocTo(expectedRoot);
    Node newRoot = IR.root(newExternsRoot, newSourceRoot);

    new AstValidator(deserializingCompiler, /* validateScriptFeatures= */ true)
        .validateRoot(newRoot);

    // TODO(b/394454662): Remove this condition once feature set with deserialization and parser are
    // in sync.
    if (!skipMatchingScriptFeaturesBeforeAndAfterSerialization) {
      assertFeatureSetsOfScriptsMatch(oldRoot, newRoot);
    }

    consumer = null;
    return new Result(ast, registry, newSourceRoot, deserializingCompiler);
  }

  private void assertFeatureSetsOfScriptsMatch(Node oldRoot, Node newRoot) {
    ImmutableMap<SourceFile, FeatureSet> featureSetsOfOldScripts = getFeatureSetsOfScripts(oldRoot);
    ImmutableMap<SourceFile, FeatureSet> featureSetsOfNewScripts = getFeatureSetsOfScripts(newRoot);
    assertThat(featureSetsOfNewScripts.keySet()).isEqualTo(featureSetsOfOldScripts.keySet());
    for (SourceFile sourceFile : featureSetsOfOldScripts.keySet()) {
      FeatureSet oldFeatureSet = featureSetsOfOldScripts.get(sourceFile);
      FeatureSet newFeatureSet = featureSetsOfNewScripts.get(sourceFile);
      checkState(
          oldFeatureSet.equals(newFeatureSet),
          "Feature sets of scripts do not match for file %s. Old: %s, New: %s",
          sourceFile.getName(),
          oldFeatureSet,
          newFeatureSet);
    }
  }

  private ImmutableMap<SourceFile, FeatureSet> getFeatureSetsOfScripts(Node root) {
    ImmutableMap.Builder<SourceFile, FeatureSet> builder = ImmutableMap.builder();
    Node externsRoot = root.getFirstChild();
    Node sourceRoot = root.getSecondChild();
    for (Node script = externsRoot.getFirstChild(); script != null; script = script.getNext()) {
      checkState(script.isScript());
      builder.put(
          (SourceFile) script.getStaticSourceFile(), (FeatureSet) script.getProp(Node.FEATURE_SET));
    }
    for (Node script = sourceRoot.getFirstChild(); script != null; script = script.getNext()) {
      checkState(script.isScript());
      builder.put(
          (SourceFile) script.getStaticSourceFile(), (FeatureSet) script.getProp(Node.FEATURE_SET));
    }
    return builder.buildOrThrow();
  }

  private ImmutableList<SourceFile> collectSourceFilesFromScripts(Node root) {
    ImmutableList.Builder<SourceFile> files = ImmutableList.builder();
    for (Node n = root.getFirstChild(); n != null; n = n.getNext()) {
      files.add((SourceFile) n.getStaticSourceFile());
    }
    return files.build();
  }

  private static class Result {
    final DeserializedAst ast;
    final ColorRegistry registry;
    final Node sourceRoot;
    final Compiler compiler;

    Result(DeserializedAst ast, ColorRegistry registry, Node sourceRoot, Compiler compiler) {
      this.ast = ast;
      this.registry = registry;
      this.sourceRoot = sourceRoot;
      this.compiler = compiler;
    }
  }

  InputStream toInputStream(Externs externs, Sources code, Expected expected) {
    TypedAst[] result = new TypedAst[1];
    consumer = ast -> result[0] = ast;
    // The process of serializing modifies the input code (e.g. removing casts and changing JSDoc)
    // to the expected result.
    super.test(externs, code, expected);
    TypedAst.List ast = TypedAst.List.newBuilder().addTypedAsts(result[0]).build();
    return new ByteArrayInputStream(ast.toByteArray());
  }

  private static void createZipWithContent(Path zipFile, String content) throws IOException {
    Instant lastModified = Instant.now();
    if (zipFile.toFile().exists()) {
      // Ensure that file modified date is updated, otherwise could cause flakiness (b/123962282).
      lastModified = Files.getLastModifiedTime(zipFile).toInstant().plusSeconds(1);
      zipFile.toFile().delete();
    }

    zipFile.toFile().createNewFile();
    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
      zos.putNextEntry(new ZipEntry("a.js"));
      zos.write(content.getBytes(UTF_8));
      zos.closeEntry();
    }
    Files.setLastModifiedTime(zipFile, FileTime.from(lastModified));
  }
}
