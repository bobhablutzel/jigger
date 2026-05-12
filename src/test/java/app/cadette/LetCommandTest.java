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
 * Tests for `let $name = expr` — variable binding in the innermost active
 * scope. Top-level binds into the always-on global scope and persists across
 * commands; template/for-loop bodies bind in their own scope (gone when the
 * block ends). Mutable: rebinding the same name is allowed.
 */
class LetCommandTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
        // Wipe top-level globals between tests — the executor's global scope
        // persists across exec() calls, so prior bindings would leak.
        exec("let $a = 0");
        exec("let $b = 0");
        exec("let $x = 0");
        exec("let $y = 0");
        exec("let $shelf_w = 0");
    }

    @Test
    void letBindsTopLevelNumericLiteral() {
        String result = exec("let $a = 42");
        assertEquals("$a = 42", result);
    }

    @Test
    void letPersistsAcrossCommands() {
        exec("let $a = 10");
        String result = exec("print \"a is ${$a}\"");
        assertEquals("a is 10", result);
    }

    @Test
    void letBindsExpression() {
        exec("let $a = 5");
        exec("let $b = $a * 3 + 1");
        assertEquals("a is 5, b is 16", exec("print \"a is ${$a}, b is ${$b}\""));
    }

    @Test
    void letAllowsRebind() {
        exec("let $x = 1");
        exec("let $x = 99");
        assertEquals("x is 99", exec("print \"x is ${$x}\""));
    }

    @Test
    void letFormatsFractionalValues() {
        // .4g precision keeps the echo readable without a trailing .0 on whole numbers.
        String result = exec("let $a = 3.14");
        assertEquals("$a = 3.140", result);
    }

    @Test
    void letRejectsUndefinedRhs() {
        // RHS references an unbound name; evaluation throws — surfaced as
        // an error result string by the executor.
        String result = exec("let $a = $never_defined + 1");
        assertTrue(result.toLowerCase().contains("undefined")
                || result.toLowerCase().contains("error"), result);
    }

    @Test
    void letBindingVisibleInsideTemplate() {
        // Global $shelf_w should be visible in a template body that
        // references it (scope walks outward).
        exec("define test_uses_global params w");
        exec("create part \"P\" size $shelf_w, $w");
        exec("end define");
        exec("let $shelf_w = 250");
        String result = exec("create test_uses_global \"A\" w 100");
        assertTrue(result.contains("Created"), result);
        assertNotNull(sceneManager.getPart("A/P"));
    }

    @Test
    void letInsideTemplateScopesToTemplate() {
        // A let inside the template body should NOT leak out to the global
        // scope. After instantiation, $local_only must still be unset.
        exec("define test_local_let params w");
        exec("let $local_only = 777");
        exec("create part \"P\" size $w, $w");
        exec("end define");
        exec("create test_local_let \"L\" w 50");
        // Reading $local_only after the template returns should fail
        // (undefined) — interpolation leaves it raw.
        String result = exec("print \"v is $local_only\"");
        assertEquals("v is $local_only", result);
    }
}
