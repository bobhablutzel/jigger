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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the `save output as` command — writes the command output log
 * to a .txt file. The log itself lives in the UI; here we feed it through
 * the executor's {@code outputLogSupplier} hook.
 *
 * <p>The path accepts both quoted and unquoted forms (AS switches the
 * lexer to PATH_MODE, as RUN does); an omitted path falls back to the
 * save dialog — which in headless tests has no chooser, so it cancels.
 */
class SaveOutputCommandTest extends HeadlessTestBase {

    @TempDir
    Path tempDir;

    @BeforeEach
    void clean() {
        resetScene();
    }

    @AfterEach
    void clearSupplier() {
        // The executor is shared across the suite — don't leak our hook.
        executor.setOutputLogSupplier(null);
    }

    @Test
    void savesLogToQuotedPath() throws Exception {
        executor.setOutputLogSupplier(() -> List.of("> create part", "Created part."));
        Path target = tempDir.resolve("session.txt");

        String result = exec("save output as \"" + target + "\"");

        assertTrue(result.startsWith("Saved output to"), result);
        assertEquals(List.of("> create part", "Created part."),
                Files.readAllLines(target));
    }

    @Test
    void savesLogToUnquotedPath() throws Exception {
        executor.setOutputLogSupplier(() -> List.of("unquoted works"));
        Path target = tempDir.resolve("plain.txt");

        String result = exec("save output as " + target);

        assertTrue(result.startsWith("Saved output to"), result);
        assertEquals(List.of("unquoted works"), Files.readAllLines(target));
    }

    @Test
    void appendsTxtExtensionWhenFileNameHasNone() throws Exception {
        executor.setOutputLogSupplier(() -> List.of("line one"));
        Path noExt = tempDir.resolve("session");

        exec("save output as \"" + noExt + "\"");

        Path withExt = tempDir.resolve("session.txt");
        assertTrue(Files.exists(withExt), "expected .txt to be appended");
        assertEquals(List.of("line one"), Files.readAllLines(withExt));
    }

    @Test
    void bareSaveOutputFallsBackToDialog() {
        // `save output` with no path opens the save dialog; in a headless
        // test no chooser is wired, so the dialog "cancels".
        executor.setOutputLogSupplier(() -> List.of("something"));

        assertEquals("Save cancelled.", exec("save output"));
        assertEquals("Save cancelled.", exec("save output as"));
    }

    @Test
    void reportsWhenLogIsEmpty() {
        executor.setOutputLogSupplier(List::of);

        String result = exec("save output as \"" + tempDir.resolve("empty.txt") + "\"");

        assertEquals("No command output to save.", result);
    }
}
