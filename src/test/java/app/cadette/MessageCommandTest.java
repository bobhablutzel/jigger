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
21 */

package app.cadette;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for `print` / `warn` / `error` commands — string output with optional
 * {@code ${var}} interpolation. None terminate the script in v1.
 */
class MessageCommandTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    @Test
    void printEmitsBareString() {
        String result = exec("print \"hello world\"");
        assertEquals("hello world", result);
    }

    @Test
    void warnPrefixesWithWarning() {
        String result = exec("warn \"fence is short\"");
        assertEquals("Warning: fence is short", result);
    }

    @Test
    void errorPrefixesWithError() {
        String result = exec("error \"something is off\"");
        assertEquals("Error: something is off", result);
    }

    @Test
    void messagesSupportSimpleVarInterpolation() {
        // Top-level $var is left as-is when unresolved (matches existing
        // string-interpolation contract). Inside a template the var
        // resolves; tested separately.
        String result = exec("print \"raw: $unset\"");
        assertEquals("raw: $unset", result);
    }

    @Test
    void messagesSupportExpressionInterpolation() {
        // ${...} parses as an expression and evaluates.
        String result = exec("print \"answer is ${2 + 40}\"");
        assertEquals("answer is 42", result);
    }

    @Test
    void errorDoesNotTerminateScript() {
        // v1: error is just a prefixed message. Subsequent commands still run.
        // Using a script run via exec wouldn't show interleaving since each
        // command runs independently; we'd need a `run` script to test
        // mid-script termination. For now, just confirm that error returns
        // normally instead of throwing.
        String result = exec("error \"oh no\"");
        assertEquals("Error: oh no", result);
        // Subsequent command works.
        String result2 = exec("create part \"survivor\" size 100, 100");
        assertFalse(result2.toLowerCase().contains("error"), result2);
    }
}
