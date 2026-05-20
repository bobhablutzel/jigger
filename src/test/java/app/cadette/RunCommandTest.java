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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the `run` command.
 * Covers the PATH_MODE lexer mode: bare paths, quoted paths, and $var expansion.
 */
class RunCommandTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    private Path writeScript(String... lines) throws IOException {
        Path f = Files.createTempFile("cadette-run-", ".cds");
        Files.write(f, List.of(lines));
        f.toFile().deleteOnExit();
        return f;
    }

    @Test
    void runBarePath() throws IOException {
        Path script = writeScript("create box foo size 1");
        String result = exec("run " + script.toString());
        assertTrue(result.contains("Running"), "Should confirm start: " + result);
        assertNotNull(sceneManager.getObjectRecord("foo"), "Script should have created foo");
    }

    @Test
    void runQuotedPath() throws IOException {
        Path script = writeScript("create box quoted size 1");
        String result = exec("run \"" + script.toString() + "\"");
        assertTrue(result.contains("Running"), "Should confirm start: " + result);
        assertNotNull(sceneManager.getObjectRecord("quoted"), "Script should have created quoted");
    }

    @Test
    void runWithHomeVariable() throws IOException {
        String home = System.getProperty("user.home");
        assertNotNull(home, "user.home must be set for this test");
        // Write the script into $HOME
        Path script = Path.of(home, "cadette-home-test-" + System.nanoTime() + ".cds");
        Files.writeString(script, "create box homebox size 1\n");
        script.toFile().deleteOnExit();
        try {
            String scriptName = script.getFileName().toString();
            String result = exec("run $HOME/" + scriptName);
            assertTrue(result.contains("Running"), "Should confirm start: " + result);
            assertNotNull(sceneManager.getObjectRecord("homebox"), "Script should have created homebox");
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    void runMissingFile() {
        String result = exec("run /definitely/does/not/exist.cds");
        assertTrue(result.contains("File not found"), "Should report missing file: " + result);
    }

    @Test
    void runResolvesViaScriptsFallback() {
        // Bundled scripts live at <project-root>/scripts/. A relative path that
        // doesn't resolve from cwd should fall back through there.
        String result = exec("run tutorials/tutorial_1_simple_box.cds");
        assertFalse(result.contains("File not found"),
                "scripts/ fallback should locate the tutorial: " + result);
        assertTrue(result.contains("Running"), "Should confirm start: " + result);
    }

    @Test
    void runDefaultsCdsExtension() {
        // Same fallback as above, but without the .cds suffix in the input.
        String result = exec("run tutorials/tutorial_1_simple_box");
        assertFalse(result.contains("File not found"),
                ".cds default should still locate the tutorial: " + result);
        assertTrue(result.contains("Running"), "Should confirm start: " + result);
    }

    @Test
    void runWithNoPathAndNoChooser() {
        // No fileChooser is registered on the executor → message instead of NPE
        String result = exec("run");
        assertTrue(result.contains("No file chooser available"), "Should report missing chooser: " + result);
    }

    @Test
    void runScriptWithTopLevelForLoop() throws IOException {
        // A script is parsed as one program — a top-level `for` block spans
        // several lines of the file and runs without any line-by-line glue.
        Path script = writeScript(
                "for $i = 1 to 3",
                "  create part \"shelf_$i\" size 10, 10 at 0, 0, 0",
                "end for");
        String result = exec("run " + script);
        assertTrue(result.contains("Running"), "Should confirm start: " + result);
        assertNotNull(sceneManager.getPart("shelf_1"));
        assertNotNull(sceneManager.getPart("shelf_2"));
        assertNotNull(sceneManager.getPart("shelf_3"));
    }

    @Test
    void runScriptWithDefineBlock() throws IOException {
        // A `define` block in a script registers a usable template.
        Path script = writeScript(
                "define acme/run_box params width",
                "  create part \"panel\" size $width, 100 at 0, 0, 0",
                "end define",
                "create acme/run_box \"B\" width 50");
        String result = exec("run " + script);
        assertTrue(result.contains("Running"), "Should confirm start: " + result);
        assertNotNull(sceneManager.getAssembly("B"), "templated assembly should exist: " + result);
    }

    @Test
    void runScriptWithSyntaxErrorRunsNothing() throws IOException {
        // Whole-file parse: a syntax error is caught before execution, so a
        // malformed script applies none of its statements.
        Path script = writeScript(
                "create part \"good\" size 10, 10 at 0, 0, 0",
                "this is not a valid command");
        String result = exec("run " + script);
        assertTrue(result.contains("Parse error"), "Should report the parse error: " + result);
        assertNull(sceneManager.getPart("good"),
                "a script with a syntax error should apply nothing: " + result);
    }
}
