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
 * Parse-error messages should include the offending token's column (and line,
 * for multi-line bodies) so the user can locate the problem without eyeballing.
 */
class ParseErrorPositionTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    @Test
    void singleLineParseErrorIncludesColumnAndPointer() {
        // "sizz" is a typo for "size"; the parser will mismatch on it.
        String input = "create part \"x\" sizz 100, 100 at 0, 0, 0";
        String result = exec(input);

        assertTrue(result.startsWith("Parse error at column "),
                "single-line parse error should lead with column: " + result);
        assertTrue(result.contains(input),
                "error should echo the offending line: " + result);
        assertTrue(result.contains("^"),
                "error should include a caret pointer: " + result);
    }

    @Test
    void defineBlockParseErrorIncludesLineColumnAndPointer() {
        // The REPL accumulates the open `define` block; a gibberish body line
        // fails fast — the error surfaces the moment that line is entered,
        // with its line and column inside the accumulated input.
        String badLine = "  this line is not a valid cadette command at all";
        exec("define acme/broken params width");
        exec("  create part \"side\" size $width, 100 at 0, 0, 0");
        String result = exec(badLine);

        assertTrue(result.contains("line ") && result.contains("column "),
                "define-body parse error should include line and column: " + result);
        assertTrue(result.contains(badLine.trim()),
                "error should echo the offending body line: " + result);
        assertTrue(result.contains("^"),
                "error should include a caret pointer: " + result);
    }

    @Test
    void topLevelBlockParseErrorIncludesLineColumnAndPointer() {
        // A bad line inside an open top-level `if` block fails fast with
        // line+column, rather than waiting for `end if`.
        String badLine = "  this line is not a valid cadette command at all";
        exec("if 1 == 1 then");
        String result = exec(badLine);

        assertTrue(result.contains("line ") && result.contains("column "),
                "top-level block parse error should include line and column: " + result);
        assertTrue(result.contains(badLine.trim()),
                "error should echo the offending block line: " + result);
        assertTrue(result.contains("^"),
                "error should include a caret pointer: " + result);
    }
}
