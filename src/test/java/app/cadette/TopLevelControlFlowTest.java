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
 * if/for blocks at top level (scripts, REPL) — not just inside a template
 * body. The REPL accumulates lines until they parse as a complete `program`,
 * then the visitor walks the tree. Also covers arithmetic inside {@code ${…}}
 * string interpolation, which is what makes back-referencing previous
 * iterations (`"b${$i - 1}"`) actually work.
 */
class TopLevelControlFlowTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    @Test
    void topLevelForLoopRunsCommands() {
        exec("for $i = 1 to 3");
        exec("create part \"p_$i\" size 10, 10 at 0, 0, 0");
        exec("end for");

        assertNotNull(sceneManager.getPart("p_1"));
        assertNotNull(sceneManager.getPart("p_2"));
        assertNotNull(sceneManager.getPart("p_3"));
        assertNull(sceneManager.getPart("p_4"));
    }

    @Test
    void topLevelForWithTemplateCreation() {
        // The archetypal use case: loop creating assemblies via a template.
        exec("for $i = 1 to 3");
        exec("create base_cabinet \"c_$i\" w 500 h 600 d 400");
        exec("end for");

        assertNotNull(sceneManager.getAssembly("c_1"));
        assertNotNull(sceneManager.getAssembly("c_2"));
        assertNotNull(sceneManager.getAssembly("c_3"));
    }

    @Test
    void topLevelIfConditional() {
        // Condition references only literal values since top-level has no
        // scope unless we're inside a block. Here we're testing the block
        // grammar path, not the expression side.
        exec("if 1 > 0 then");
        exec("create part \"only_on_true\" size 5, 5 at 0, 0, 0");
        exec("end if");

        assertNotNull(sceneManager.getPart("only_on_true"));
    }

    @Test
    void topLevelIfFalseBranchSkipsBody() {
        exec("if 0 then");
        exec("create part \"never\" size 5, 5 at 0, 0, 0");
        exec("end if");

        assertNull(sceneManager.getPart("never"));
    }

    @Test
    void nestedForInsideForAtTopLevel() {
        // Block-depth tracking in the executor must handle nesting, not just
        // simple pairs.
        exec("for $i = 1 to 2");
        exec("for $j = 1 to 2");
        exec("create part \"cell_${$i}_${$j}\" size 5, 5 at 0, 0, 0");
        exec("end for");
        exec("end for");

        assertNotNull(sceneManager.getPart("cell_1_1"));
        assertNotNull(sceneManager.getPart("cell_1_2"));
        assertNotNull(sceneManager.getPart("cell_2_1"));
        assertNotNull(sceneManager.getPart("cell_2_2"));
    }

    // ---- ${expression} arithmetic interpolation ----

    @Test
    void arithmeticInStringInterpolationWithoutDollarPrefix() {
        // Inside ${…} bare identifiers are treated as variable references —
        // users can write `${i - 1}` without the inner $.
        exec("create part \"b_1\" size 10, 10 at 0, 0, 0");
        exec("for $i = 2 to 3");
        exec("create part \"b_$i\" size 10, 10 at 0, 0, 0");
        exec("create part \"back_of_b_${i - 1}\" size 5, 5 at 0, 0, 0");
        exec("end for");

        assertNotNull(sceneManager.getPart("back_of_b_1"));
        assertNotNull(sceneManager.getPart("back_of_b_2"));
        assertNull(sceneManager.getPart("back_of_b_0"));
    }

    @Test
    void arithmeticInStringInterpolationWithDollarPrefix() {
        // `${$i - 1}` keeps working for consistency with expression syntax
        // elsewhere in the grammar.
        exec("for $i = 2 to 3");
        exec("create part \"p_${$i - 1}\" size 5, 5 at 0, 0, 0");
        exec("end for");

        assertNotNull(sceneManager.getPart("p_1"));
        assertNotNull(sceneManager.getPart("p_2"));
    }

    @Test
    void functionCallsInInterpolationStillWork() {
        // `min` / `max` inside ${…} stay as function calls, not treated as
        // bare-identifier var refs. Using distinct $i values so part names
        // don't collide.
        exec("for $i = 1 to 2");
        exec("create part \"capped_${min($i, 5)}\" size 5, 5 at 0, 0, 0");
        exec("end for");

        assertNotNull(sceneManager.getPart("capped_1"));
        assertNotNull(sceneManager.getPart("capped_2"));
    }

    @Test
    void bareNameInBracesStillWorks() {
        // ${i} (without the $) keeps the old simple-var-lookup semantics so
        // existing templates don't break.
        exec("for $i = 1 to 2");
        exec("create part \"x_${i}\" size 5, 5 at 0, 0, 0");
        exec("end for");

        assertNotNull(sceneManager.getPart("x_1"));
        assertNotNull(sceneManager.getPart("x_2"));
    }

    @Test
    void backReferenceViaArithmeticInterpolation() {
        // The scenario from island2.cds: chain cabinets right-of-previous.
        exec("create base_cabinet \"b_1\" w 60 h 60 d 40");
        exec("for $i = 2 to 3");
        exec("create base_cabinet \"b_$i\" w 60 h 60 d 40 right of \"b_${$i - 1}\"");
        exec("end for");

        assertNotNull(sceneManager.getAssembly("b_1"));
        assertNotNull(sceneManager.getAssembly("b_2"));
        assertNotNull(sceneManager.getAssembly("b_3"));
    }

    // ---- line accumulation: only blocks continue across lines ----

    @Test
    void truncatedSimpleCommandErrorsRatherThanWaitingForMore() {
        // A bare `create box` ends at EOF, but the parser is NOT inside an
        // open for/if/define — so it must report a parse error immediately,
        // not silently buffer the line waiting for a continuation that a
        // single-line command will never get.
        String result = exec("create box");

        assertTrue(result.contains("Parse error"),
                "a truncated simple command must error, not buffer: " + result);
    }

    @Test
    void failFastOnBadLineInsideOpenBlock() {
        // A syntax error inside an open block surfaces the moment the bad
        // line is entered — the block is abandoned rather than swallowed
        // until `end for`.
        exec("for $i = 1 to 3");
        String result = exec("  thisisnotacommand foo bar");

        assertTrue(result.contains("Parse error"),
                "a bad line inside a block should fail fast: " + result);
        // The buffer is cleared, so a normal command runs cleanly afterward.
        exec("create part \"after_error\" size 5, 5 at 0, 0, 0");
        assertNotNull(sceneManager.getPart("after_error"));
    }
}
