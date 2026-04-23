/*
 * Copyright 2026 Bob Hablutzel
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
 *
 * Source: https://github.com/bobhablutzel/cadette
 */

package app.cadette;

import app.cadette.model.TemplateRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the post-load validator that walks each template body and reports
 * references to templates that exist nowhere in the registry. The point is
 * to catch these at startup rather than at instantiation time, where they'd
 * surface as a mysterious "not found" on a single user action.
 */
class TemplateValidationTest extends HeadlessTestBase {

    @AfterEach
    void restoreStandardTemplates() {
        // We inject ad-hoc templates by path below; restore the shared registry
        // to its bundled state before the next test class runs.
        TemplateRegistry.instance().clear();
        executor.loadBundledTemplates();
        executor.drainLoaderMessages();
    }

    private static Path writeTree(String... pathContentPairs) throws IOException {
        Path root = Files.createTempDirectory("cadette-validate-");
        root.toFile().deleteOnExit();
        for (int i = 0; i < pathContentPairs.length; i += 2) {
            Path f = root.resolve(pathContentPairs[i]);
            Files.createDirectories(f.getParent());
            Files.writeString(f, pathContentPairs[i + 1]);
            f.toFile().deleteOnExit();
        }
        return root;
    }

    @Test
    void bundledStandardTemplatesValidateClean() {
        // The shipped standard templates must not reference anything missing —
        // this guards against accidental typos in a future template refactor.
        executor.drainLoaderMessages();
        executor.validateTemplateReferences();
        List<String> warnings = executor.drainLoaderMessages();
        assertTrue(warnings.isEmpty(),
                "standard templates should have no unresolved references, got: " + warnings);
    }

    @Test
    void unknownQualifiedReferenceIsFlagged() throws IOException {
        Path root = writeTree(
                "acme/widget.cds",
                """
                #! cadette
                define acme/widget params width
                  create acme/nonexistent Sub w $width
                end define
                """);

        executor.loadTemplatesFromDirectory(root);
        executor.drainLoaderMessages();  // clear loader chatter before validating
        executor.validateTemplateReferences();
        List<String> warnings = executor.drainLoaderMessages();

        assertTrue(warnings.stream().anyMatch(w -> w.contains("acme/nonexistent")),
                "validator should flag the unknown qualified reference, got: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("acme/widget")),
                "warning should name the template holding the bad reference: " + warnings);
    }

    @Test
    void unknownBareReferenceIsFlagged() throws IOException {
        Path root = writeTree(
                "acme/outer.cds",
                """
                #! cadette
                define acme/outer params width
                  create totally_bogus_template Sub w $width
                end define
                """);

        executor.loadTemplatesFromDirectory(root);
        executor.drainLoaderMessages();
        executor.validateTemplateReferences();
        List<String> warnings = executor.drainLoaderMessages();

        assertTrue(warnings.stream().anyMatch(w -> w.contains("totally_bogus_template")),
                "validator should flag the unknown bare reference, got: " + warnings);
    }

    @Test
    void knownBareReferenceResolvesViaLastSegmentMatch() throws IOException {
        // `create base_cabinet ...` resolves because standard/cabinets/base_cabinet
        // exists, even without any `using` statement in scope.
        Path root = writeTree(
                "acme/kitchen.cds",
                """
                #! cadette
                define acme/kitchen params width, height, depth
                  create base_cabinet K w $width h $height d $depth
                end define
                """);

        executor.loadTemplatesFromDirectory(root);
        executor.drainLoaderMessages();
        executor.validateTemplateReferences();
        List<String> warnings = executor.drainLoaderMessages();

        assertFalse(warnings.stream().anyMatch(w -> w.contains("base_cabinet")),
                "bare name resolving via registry-wide last-segment should not warn: " + warnings);
    }

    @Test
    void variableInExpressionPositionIsNotFlagged() throws IOException {
        // $var is a first-class grammar token inside expressions — body lines
        // with $var in numeric positions parse cleanly at load time, no
        // placeholder-substitution hack required.
        Path root = writeTree(
                "acme/expr.cds",
                """
                #! cadette
                define acme/expr params width, height
                  create part "side" size $width, $height at 0, 0, 0
                end define
                """);

        executor.loadTemplatesFromDirectory(root);
        executor.drainLoaderMessages();
        executor.validateTemplateReferences();
        List<String> warnings = executor.drainLoaderMessages();

        assertFalse(warnings.stream().anyMatch(w -> w.contains("acme/expr")),
                "$var in expression positions must parse cleanly: " + warnings);
    }

    @Test
    void partCreationIsNotMistakenForTemplateReference() throws IOException {
        // `create part "name" ...` is part creation; `part` is a keyword not a template.
        Path root = writeTree(
                "acme/boxy.cds",
                """
                #! cadette
                define acme/boxy params width
                  create part "side" size $width, 100 at 0, 0, 0
                end define
                """);

        executor.loadTemplatesFromDirectory(root);
        executor.drainLoaderMessages();
        executor.validateTemplateReferences();
        List<String> warnings = executor.drainLoaderMessages();

        assertFalse(warnings.stream().anyMatch(w -> w.contains("acme/boxy")),
                "part-creation lines must not be treated as template references: " + warnings);
    }

    @Test
    void syntaxErrorInBodyLineIsReported() throws IOException {
        // Body lines aren't parsed at define time — the loader just records
        // them. The validator is the first place a malformed line gets seen
        // (without waiting for instantiation). No $vars on the bad line, so
        // we know the parser sees a real syntax problem.
        Path root = writeTree(
                "acme/syntactically_broken.cds",
                """
                #! cadette
                define acme/syntactically_broken params width
                  this line is not a valid cadette command at all
                end define
                """);

        executor.loadTemplatesFromDirectory(root);
        executor.drainLoaderMessages();
        executor.validateTemplateReferences();
        List<String> warnings = executor.drainLoaderMessages();

        assertTrue(warnings.stream().anyMatch(w ->
                        w.contains("syntax error") && w.contains("acme/syntactically_broken")),
                "validator should surface the body-line syntax error at load time, got: " + warnings);
    }

    @Test
    void variableInTemplateRefPositionIsRejected() throws IOException {
        // `create $dynamic Sub` isn't allowed — templateRef accepts bare names,
        // qualified names, or quoted strings, not variable substitutions.
        // Under the old text-substitution regime this was silently accepted
        // but never actually used; the grammar now surfaces the constraint.
        Path root = writeTree(
                "acme/dynamic_ref.cds",
                """
                #! cadette
                define acme/dynamic_ref params choice
                  create $choice Sub
                end define
                """);

        executor.loadTemplatesFromDirectory(root);
        executor.drainLoaderMessages();
        executor.validateTemplateReferences();
        List<String> warnings = executor.drainLoaderMessages();

        assertTrue(warnings.stream().anyMatch(w ->
                        w.contains("syntax error") && w.contains("acme/dynamic_ref")),
                "$var in template-ref position should produce a syntax error: " + warnings);
    }
}
