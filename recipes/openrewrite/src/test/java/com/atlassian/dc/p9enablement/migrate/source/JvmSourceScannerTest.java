package com.atlassian.dc.p9enablement.migrate.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmSourceScannerTest {

    @Test
    void scansKotlinImportsReferencesAndExecutableInterpolations(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("Usage.kt");
        Files.writeString(file, """
                package ignored.packageName
                import com.google.gson.Gson as JsonCodec
                import com.google.gson.*
                import com.google.gson.GsonBuilder.*
                import com.google./* comment between name parts */gson.Gson as CommentedImport

                // com.hidden.Commented()
                fun render() {
                    val literal = "com.hidden.StringText()"
                    val multiline = """
                            + "\"\"\"\ncom.hidden.TripleText()\n\"\"\"\n" + """
                    val codec = com.google.gson.Gson()
                    val templated = "${com.google.gson.JsonObject()}"
                    val nestedComment = 1 /* outer /* com.hidden.Nested() */ end */
                }
                """);

        JvmSourceScanner.Scan scan = JvmSourceScanner.scan(List.of(file));
        List<String> references = scan.references().get(file.toAbsolutePath().normalize());

        assertTrue(scan.errors().isEmpty(), scan.errors().toString());

        assertEquals(Set.of(
                "com.google.gson.Gson", "com.google.gson.*", "com.google.gson.GsonBuilder.*",
                "com.google.gson.JsonObject"), Set.copyOf(references));
    }

    @Test
    void scansScalaGroupedSelectorsAliasesWildcardAndInterpolations(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("Usage.scala");
        Files.writeString(file, """
                package ignored.packageName
                import com.google.gson.{
                  Gson,
                  JsonObject => Json,
                  Ignored => _,
                  *
                }
                import _root_.com.example.One, com.example.Two

                object Usage:
                  val client = com.google.gson.`Type With Spaces`()
                  val absolute = _root_.com.google.gson.JsonParser
                  val rendered = s"literal ${com.google.gson.Gson()}"
                  val escapedDollar = s"$$com.hidden.EscapedText()"
                  val ordinary = "literal ${com.hidden.NotCode()}"
                """);

        JvmSourceScanner.Scan scan = JvmSourceScanner.scan(List.of(file));
        List<String> references = scan.references().get(file.toAbsolutePath().normalize());

        assertTrue(scan.errors().isEmpty(), scan.errors().toString());

        assertEquals(Set.of(
                "com.google.gson.Gson", "com.google.gson.JsonObject", "com.google.gson.*",
                "com.google.gson.Type With Spaces", "com.google.gson.JsonParser",
                "com.example.One", "com.example.Two"), Set.copyOf(references));
    }

    @Test
    void scansGroovyStaticImportsSlashyStringsAndDivision(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("Usage.groovy");
        Files.writeString(file, """
                package ignored.packageName
                import com.google.gson.Gson as JsonCodec
                import static com.google.gson.JsonParser.parseString
                import com.google.gson.*

                // com.hidden.Commented()
                def ratio = 10 / 2
                def slashy = /text ${com.google.gson.Gson()}/
                def dollarSlashy = $/text ${com.google.gson.JsonObject()} $com.google.gson.JsonParser/$
                def regex() { return /com.hidden.ReturnSlashyText/ }
                def literal = 'com.hidden.SingleQuoted()'
                """);

        JvmSourceScanner.Scan scan = JvmSourceScanner.scan(List.of(file));
        List<String> references = scan.references().get(file.toAbsolutePath().normalize());

        assertTrue(scan.errors().isEmpty(), scan.errors().toString());

        assertEquals(Set.of(
                "com.google.gson.Gson", "com.google.gson.JsonParser.parseString", "com.google.gson.*",
                "com.google.gson.JsonObject", "com.google.gson.JsonParser"), Set.copyOf(references));
    }

    @Test
    void preservesLanguageSpecificEscapesAndNestedInterpolation(@TempDir Path directory) throws Exception {
        Path scala = directory.resolve("Escapes.scala");
        Files.writeString(scala, """
                val rawValue = raw"\\${com.google.gson.Gson()}"
                val custom = json"${com.google.gson.JsonObject()}"
                val escaped = s"$$com.hidden.NotCode"
                """);
        Path groovy = directory.resolve("Escapes.groovy");
        Files.writeString(groovy, """
                def escaped = \"\"\"\\${com.hidden.NotCode()}\"\"\"
                def slashy = /\\${com.google.gson.JsonObject()}/
                def dollarSlashy = $/escaped $/$ literal com.hidden.NotCode() /$
                def nested = "${render("literal com.hidden.NotCode()", com.google.gson.Gson())}"
                """);

        JvmSourceScanner.Scan scan = JvmSourceScanner.scan(List.of(scala, groovy));

        assertTrue(scan.errors().isEmpty(), scan.errors().toString());
        assertEquals(Set.of("com.google.gson.Gson", "com.google.gson.JsonObject"),
                Set.copyOf(scan.references().get(scala)));
        assertEquals(Set.of("com.google.gson.JsonObject", "com.google.gson.Gson"),
                Set.copyOf(scan.references().get(groovy)));
    }

    @Test
    void mixedScalaImportFormsRequireAssessmentInsteadOfLosingUsage(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("Mixed.scala");
        Files.writeString(source, "import java.util.List, com.google.gson.{Gson, JsonObject}");

        JvmSourceScanner.Scan scan = JvmSourceScanner.scan(List.of(source));

        assertTrue(scan.errors().stream().anyMatch(error -> error.contains("Mixed.scala:1:")));
    }

    @Test
    void reportsMalformedLexicalConstructsWithPathAndLine(@TempDir Path directory) throws Exception {
        Path comment = directory.resolve("BrokenComment.kt");
        Path string = directory.resolve("BrokenString.kt");
        Files.writeString(comment, "class Broken {\n  /* unfinished");
        Files.writeString(string, "fun broken() {\n  val text = \"unfinished");

        JvmSourceScanner.Scan scan = JvmSourceScanner.scan(List.of(comment, string));

        assertTrue(scan.errors().stream().anyMatch(error -> error.contains("BrokenComment.kt:2:")
                && error.contains("unterminated block comment")));
        assertTrue(scan.errors().stream().anyMatch(error -> error.contains("BrokenString.kt:2:")
                && error.contains("unterminated string literal")));
    }

    @Test
    void diagnosesAmbiguousGroovySlashWithoutLosingFollowingReferences(@TempDir Path directory) throws Exception {
        JvmSourceScanner.Scan scan = scanSource(directory, "Commands.groovy", """
                class Service {
                    def a() { assert s.matches /won't/ }
                    def lost = com.google.gson.Gson()
                    def b() { assert s.matches /can't/ }
                    def found = com.google.gson.JsonParser()
                    def c() { s.replaceAll /com.hidden.Leak/, '' }
                    def d() { s.matches2 /won't/ }
                    def afterDigit = com.google.gson.JsonObject()
                }
                """);

        assertTrue(scan.errors().stream().anyMatch(error -> error.contains("ambiguous")));
        assertTrue(referenceNames(scan).containsAll(Set.of("com.google.gson.Gson", "com.google.gson.JsonParser", "com.google.gson.JsonObject")));
        assertTrue(referenceNames(scan).stream().noneMatch(name -> name.startsWith("com.hidden")));
    }

    @Test
    void consumesFullRawStringClosingQuoteRun(@TempDir Path directory) throws Exception {
        for (String extension : List.of("kt", "scala")) {
            JvmSourceScanner.Scan scan = scanSource(directory, "Quotes." + extension,
                    "val msg = \"\"\"say \"hi\"\"\"\"\nval x = com.google.gson.JsonObject()\n"
                            + "val y = com.google.gson.JsonParser()\n");

            assertTrue(scan.errors().isEmpty(), scan.errors().toString());
            assertEquals(Set.of("com.google.gson.JsonObject", "com.google.gson.JsonParser"), referenceNames(scan));
        }
    }

    @Test
    void distinguishesScalaSymbolsCharactersAndQuotedMacros(@TempDir Path directory) throws Exception {
        JvmSourceScanner.Scan symbols = scanSource(directory, "Symbols.scala", """
                val mode = 'fast
                val char = 'a'
                val escaped = '\\''
                val next = com.google.gson.Gson()
                """);

        assertTrue(symbols.errors().isEmpty(), symbols.errors().toString());
        assertEquals(Set.of("com.google.gson.Gson"), referenceNames(symbols));

        JvmSourceScanner.Scan macro = scanSource(directory, "Macros.scala", """
                val expression = '{ com.google.gson.Gson() }
                val tpe = '[com.google.gson.JsonElement]
                val next = com.google.gson.JsonParser()
                """);

        assertTrue(macro.errors().stream().anyMatch(error -> error.contains("quoted macro")));
        assertTrue(referenceNames(macro).contains("com.google.gson.JsonParser"));
    }

    @Test
    void supportsMultilineImportsExclusionsAndContextualAliases(@TempDir Path directory) throws Exception {
        JvmSourceScanner.Scan scan = scanSource(directory, "Imports.scala", """
                import com.google.gson.
                  {Gson, JsonObject as Json}
                import com.example.dsl.{as, from, Hidden => _, Ignored as _}
                import com.example.dsl.{`as` => renamed, `import`}
                """);

        assertTrue(scan.errors().isEmpty(), scan.errors().toString());
        assertEquals(Set.of("com.google.gson.Gson", "com.google.gson.JsonObject",
                "com.example.dsl.as", "com.example.dsl.from", "com.example.dsl.import"), referenceNames(scan));

        JvmSourceScanner.Scan excluded = scanSource(directory, "Excluded.scala", "import com.google.gson.{Gson => _}");
        assertTrue(excluded.errors().isEmpty(), excluded.errors().toString());
        assertTrue(referenceNames(excluded).isEmpty());
    }

    @Test
    void doesNotInterpretMembersOrEscapedNamesAsImportDeclarations(@TempDir Path directory) throws Exception {
        for (String extension : List.of("kt", "scala", "groovy")) {
            String member = extension.equals("groovy") ? "import" : "`import`";
            JvmSourceScanner.Scan scan = scanSource(directory, "Members." + extension,
                    "val v = svc." + member + "\nval x = com.google.gson.Gson()\n");

            assertTrue(scan.errors().isEmpty(), scan.errors().toString());
            assertEquals(Set.of("svc.import", "com.google.gson.Gson"), referenceNames(scan));
        }
    }

    @Test
    void doesNotRescanGroupedImportSelectorsAsBareReferences(@TempDir Path directory) throws Exception {
        JvmSourceScanner.Scan scan = scanSource(directory, "Selectors.scala", """
                import com.google.gson.{
                    internal.bind.TypeAdapters,
                    Gson
                }
                """);

        // Even an unsupported selector shape must not leak into ordinary code scanning.
        assertTrue(referenceNames(scan).stream().noneMatch(name -> name.equals("internal.bind.TypeAdapters")));
    }

    @Test
    void appliesWildcardRulesForTheSelectedLanguage(@TempDir Path directory) throws Exception {
        for (String extension : List.of("kt", "groovy")) {
            JvmSourceScanner.Scan scan = scanSource(directory, "Invalid." + extension, "import com.google.gson._");
            assertFalse(scan.errors().isEmpty());
            assertTrue(referenceNames(scan).isEmpty());
        }
        JvmSourceScanner.Scan scala = scanSource(directory, "Wildcard.scala", "import com.google.gson._");
        assertTrue(scala.errors().isEmpty(), scala.errors().toString());
        assertEquals(Set.of("com.google.gson.*"), referenceNames(scala));
    }

    private static JvmSourceScanner.Scan scanSource(Path directory, String name, String source) throws Exception {
        Path file = directory.resolve(name);
        Files.writeString(file, source);
        return JvmSourceScanner.scan(List.of(file));
    }

    private static Set<String> referenceNames(JvmSourceScanner.Scan scan) {
        return Set.copyOf(scan.references().values().stream().flatMap(List::stream).toList());
    }

    @Test
    void returnsEmptyResultForNoFiles() throws Exception {
        assertEquals(new JvmSourceScanner.Scan(java.util.Map.of(), List.of()), JvmSourceScanner.scan(List.of()));
    }
}
