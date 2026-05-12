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
 * Math functions in the expression grammar — sin/cos/tan/atan2/sqrt/hypot/
 * abs/pow/floor/ceil/round/log/exp/radians/degrees, plus pi/e constants.
 * Trig is degrees (matches rotate/orient).
 */
class MathFunctionsTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
        // Wipe any persistent vars.
        exec("let $a = 0");
        exec("let $b = 0");
    }

    @Test
    void sinCosTanInDegrees() {
        // sin(30°) = 0.5 exactly mathematically; FP comes out as 0.4999...
        // Check via comparison rather than printed string match.
        exec("let $a = sin(30)");
        String r = exec("print \"${$a}\"");
        double v = Double.parseDouble(r);
        assertEquals(0.5, v, 1e-9, "sin(30°)");
    }

    @Test
    void hypotComputesEuclidean() {
        // 3-4-5 triangle.
        exec("let $a = hypot(3, 4)");
        String r = exec("print \"${$a}\"");
        assertEquals("5", r);
    }

    @Test
    void atan2GivesAngleInDegrees() {
        // atan2(1, 1) = 45°.
        exec("let $a = atan2(1, 1)");
        String r = exec("print \"${$a}\"");
        assertEquals("45", r);
    }

    @Test
    void sqrtOfFourIsTwo() {
        exec("let $a = sqrt(4)");
        assertEquals("2", exec("print \"${$a}\""));
    }

    @Test
    void absOfNegative() {
        exec("let $a = abs(0 - 7)");
        assertEquals("7", exec("print \"${$a}\""));
    }

    @Test
    void powThreeSquared() {
        exec("let $a = pow(3, 2)");
        assertEquals("9", exec("print \"${$a}\""));
    }

    @Test
    void floorAndCeilAndRound() {
        exec("let $a = floor(3.7)");
        assertEquals("3", exec("print \"${$a}\""));
        exec("let $b = ceil(3.2)");
        assertEquals("4", exec("print \"${$b}\""));
        exec("let $a = round(3.5)");
        assertEquals("4", exec("print \"${$a}\""));
    }

    @Test
    void piIsRoughly3pt14() {
        exec("let $a = pi");
        String r = exec("print \"${$a}\"");
        assertTrue(r.startsWith("3.14"), "expected pi ≈ 3.14, got: " + r);
    }

    @Test
    void userVariableShadowsPi() {
        // Naming a variable `pi` overrides the math constant — no surprise
        // global captures.
        exec("let $pi = 5");
        assertEquals("5", exec("print \"${$pi}\""));
    }

    @Test
    void wrongArityIsAnError() {
        String result = exec("let $a = sin(30, 45)");
        assertTrue(result.toLowerCase().contains("sin")
                && (result.toLowerCase().contains("argument")
                    || result.toLowerCase().contains("error")), result);
    }

    @Test
    void unknownFunctionIsAnError() {
        String result = exec("let $a = bogus(1)");
        assertTrue(result.toLowerCase().contains("unknown")
                || result.toLowerCase().contains("error"), result);
    }

    @Test
    void braceMathScenario() {
        // The motivating case: parametric brace dimensions from gate inside W/H.
        exec("let $w = 914");      // inside width mm
        exec("let $h = 610");      // inside height mm
        exec("let $brace_len = hypot($w, $h)");
        exec("let $brace_angle = atan2($h, $w)");
        // hypot(914, 610) ≈ 1099.0; atan2(610, 914) ≈ 33.7°
        String len = exec("print \"${$brace_len}\"");
        String ang = exec("print \"${$brace_angle}\"");
        assertTrue(len.startsWith("1099") || len.startsWith("1098"), "len: " + len);
        assertTrue(ang.startsWith("33.7") || ang.startsWith("33.6"), "ang: " + ang);
    }
}
