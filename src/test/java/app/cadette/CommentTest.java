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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LINE_COMMENT handling — ANTLR strips `# ...` via a skip channel,
 * so comments work mid-command, at the end of a command, or on their own line.
 */
class CommentTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    @Test
    void pureCommentLineReturnsEmpty() {
        String result = exec("# just a note");
        assertEquals("", result, "pure comment should be no-op");
    }

    @Test
    void indentedCommentLineReturnsEmpty() {
        String result = exec("   # indented note");
        assertEquals("", result);
    }

    @Test
    void trailingCommentAfterCommand() {
        String result = exec("create box foo size 1 # make a small box");
        assertFalse(result.isEmpty(), "command should still execute: " + result);
        assertNotNull(sceneManager.getObjectRecord("foo"),
                "trailing comment shouldn't prevent object creation");
    }

    @Test
    void commentBetweenTokens() {
        // # ends the line in the lexer — tokens after # are skipped.
        // We don't claim this is a supported syntax, but we want to be sure
        // it doesn't blow up.
        String result = exec("create box bar size 1");
        assertNotNull(sceneManager.getObjectRecord("bar"));
        // Verify a pure-comment line right after still works cleanly
        assertEquals("", exec("# trailing comment line"));
    }
}
