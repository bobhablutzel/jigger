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
 * Exercises the unified expression grammar (arithmetic + comparisons +
 * logical ops) by passing expressions through the `set kerf <expr>` command
 * and reading back the stored value. Comparison ops return 1.0 / 0.0 under
 * numeric truthiness.
 */
class ExpressionGrammarTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    private float kerfAfter(String expr) {
        exec("set kerf " + expr);
        return sceneManager.getKerfMm();
    }

    // ---- Arithmetic: regression on the existing behavior ----

    @Test
    void literalNumber() {
        assertEquals(3.2f, kerfAfter("3.2"), 0.001f);
    }

    @Test
    void addition() {
        assertEquals(5.0f, kerfAfter("2 + 3"), 0.001f);
    }

    @Test
    void subtractionAndUnaryMinus() {
        // NUMBER no longer allows a leading minus; unary MINUS handles it.
        assertEquals(2.0f, kerfAfter("5 - 3"), 0.001f);
    }

    @Test
    void multiplicationPrecedence() {
        // Multiplication binds tighter than addition: 2 + 3*4 = 14.
        assertEquals(14.0f, kerfAfter("2 + 3 * 4"), 0.001f);
    }

    @Test
    void parenthesesOverridePrecedence() {
        assertEquals(20.0f, kerfAfter("(2 + 3) * 4"), 0.001f);
    }

    @Test
    void minAndMaxFunctions() {
        assertEquals(3.0f, kerfAfter("min(3, 7)"), 0.001f);
        assertEquals(7.0f, kerfAfter("max(3, 7)"), 0.001f);
        // Variadic — more than two args.
        assertEquals(1.0f, kerfAfter("min(5, 2, 1, 3)"), 0.001f);
    }

    @Test
    void division() {
        assertEquals(2.5f, kerfAfter("5 / 2"), 0.001f);
    }

    // ---- New: comparison operators ----

    @Test
    void lessThanReturnsOneWhenTrue() {
        assertEquals(1.0f, kerfAfter("3 < 5"), 0.001f);
    }

    @Test
    void lessThanReturnsZeroWhenFalse() {
        assertEquals(0.0f, kerfAfter("5 < 3"), 0.001f);
    }

    @Test
    void lessThanOrEqualInclusiveBoundary() {
        assertEquals(1.0f, kerfAfter("5 <= 5"), 0.001f);
    }

    @Test
    void greaterThanAndGreaterThanOrEqual() {
        assertEquals(1.0f, kerfAfter("5 > 3"), 0.001f);
        assertEquals(1.0f, kerfAfter("5 >= 5"), 0.001f);
        assertEquals(0.0f, kerfAfter("3 >= 5"), 0.001f);
    }

    @Test
    void equalityAndInequality() {
        assertEquals(1.0f, kerfAfter("5 == 5"), 0.001f);
        assertEquals(0.0f, kerfAfter("5 == 6"), 0.001f);
        assertEquals(1.0f, kerfAfter("5 != 6"), 0.001f);
        assertEquals(0.0f, kerfAfter("5 != 5"), 0.001f);
    }

    // ---- New: logical operators ----

    @Test
    void logicalNotZeroToOne() {
        assertEquals(1.0f, kerfAfter("!0"), 0.001f);
    }

    @Test
    void logicalNotNonzeroToZero() {
        assertEquals(0.0f, kerfAfter("!5"), 0.001f);
    }

    @Test
    void logicalAndShortCircuit() {
        assertEquals(1.0f, kerfAfter("1 && 1"), 0.001f);
        assertEquals(0.0f, kerfAfter("1 && 0"), 0.001f);
        assertEquals(0.0f, kerfAfter("0 && 1"), 0.001f);
    }

    @Test
    void logicalOr() {
        assertEquals(1.0f, kerfAfter("1 || 0"), 0.001f);
        assertEquals(1.0f, kerfAfter("0 || 1"), 0.001f);
        assertEquals(0.0f, kerfAfter("0 || 0"), 0.001f);
    }

    // ---- Mixed operator precedence: mul > add > relational > eq > and > or ----

    @Test
    void compareWithArithmetic() {
        // (2 * 3) < (5 + 2) == (6 < 7) == 1
        assertEquals(1.0f, kerfAfter("2 * 3 < 5 + 2"), 0.001f);
    }

    @Test
    void andBindsTighterThanOr() {
        // 0 || 1 && 0  == 0 || (1 && 0) == 0 || 0 == 0
        assertEquals(0.0f, kerfAfter("0 || 1 && 0"), 0.001f);
    }
}
