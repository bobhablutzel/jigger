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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code set script_path} — the user-configurable prefix to the
 * `run` command's search path. Defaults (~/.cadette/scripts/, ./scripts/)
 * are always appended after the user-configured entries.
 */
class ScriptPathCommandTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
        executor.setUserScriptPath(List.of());
    }

    @Test
    void setScriptPath_replacesUserEntries() {
        exec("set script_path \"/a\", \"/b\"");
        assertEquals(List.of(Path.of("/a"), Path.of("/b")), executor.getUserScriptPath());
    }

    @Test
    void setScriptPath_none_clearsUserEntries() {
        executor.setUserScriptPath(List.of(Path.of("/x")));
        String result = exec("set script_path none");
        assertTrue(executor.getUserScriptPath().isEmpty(),
                "user entries should be cleared");
        assertTrue(result.contains("cleared"), "status message should mention cleared: " + result);
    }

    @Test
    void setScriptPath_expandsTildeToUserHome() {
        exec("set script_path \"~/myScripts\"");
        Path expected = Path.of(System.getProperty("user.home"), "myScripts");
        assertEquals(List.of(expected), executor.getUserScriptPath());
    }

    @Test
    void effectiveSearchPath_userEntriesComeBeforeDefaults() {
        executor.setUserScriptPath(List.of(Path.of("/user1"), Path.of("/user2")));
        List<Path> effective = executor.effectiveScriptSearchPath();
        assertEquals(4, effective.size(), "two user + two defaults: " + effective);
        assertEquals(Path.of("/user1"), effective.get(0));
        assertEquals(Path.of("/user2"), effective.get(1));
        // Default tail: ~/.cadette/scripts/, ./scripts/
        assertTrue(effective.get(2).endsWith(Path.of(".cadette", "scripts")),
                "third entry should be ~/.cadette/scripts/: " + effective.get(2));
        assertEquals(Path.of("scripts"), effective.get(3));
    }

    @Test
    void effectiveSearchPath_defaultsAlonePresentWithoutUserEntries() {
        // Even with no user entries set, the defaults are still consulted.
        List<Path> effective = executor.effectiveScriptSearchPath();
        assertEquals(2, effective.size(), "just the two defaults: " + effective);
    }

    @Test
    void run_resolvesScriptThroughUserPath(@TempDir Path tmp) throws IOException {
        // Drop a minimal .cds into the temp dir, point the user path at it,
        // and verify `run` finds the script there. (Actually executing it is
        // separate behavior — we just need "File not found" to NOT appear.)
        Files.writeString(tmp.resolve("hello.cds"), "# no-op script\n");
        executor.setUserScriptPath(List.of(tmp));
        String result = exec("run hello");
        assertFalse(result.contains("File not found"),
                "expected resolution via user path: " + result);
    }

    @Test
    void showScriptPath_withNoUserEntries_listsDefaultsOnly() {
        String result = exec("show script_path");
        assertTrue(result.contains("defaults only"), result);
        assertTrue(result.contains("Effective search order:"), result);
        assertTrue(result.contains(".cadette"), "should mention ~/.cadette/scripts default: " + result);
        assertTrue(result.contains("scripts"), "should mention ./scripts default: " + result);
    }

    @Test
    void showScriptPath_withUserEntries_listsThemFirst() {
        exec("set script_path \"/myscripts\", \"/extra\"");
        String result = exec("show script_path");
        assertTrue(result.contains("/myscripts"), result);
        assertTrue(result.contains("/extra"), result);
        // User entries should appear before defaults in the effective order.
        int userIdx = result.indexOf("/myscripts");
        int cadetteIdx = result.indexOf(".cadette");
        assertTrue(userIdx >= 0 && cadetteIdx >= 0 && userIdx < cadetteIdx,
                "user entry should precede default in effective order: " + result);
    }

    @Test
    void setScriptPath_undoRestoresPreviousEntries() {
        executor.setUserScriptPath(List.of(Path.of("/before")));
        exec("set script_path \"/after\"");
        assertEquals(List.of(Path.of("/after")), executor.getUserScriptPath());
        exec("undo");
        assertEquals(List.of(Path.of("/before")), executor.getUserScriptPath(),
                "undo should restore the pre-command snapshot");
    }
}
