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
 * Verifies that {@code create part} / {@code create &lt;shape&gt;} reject a
 * name that's already in use, instead of silently overwriting the registry
 * while leaving the previous wrapper attached to the scene graph (which
 * produced a "ghost" object — visible in 3D but invisible to commands).
 */
class NameCollisionTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    @Test
    void duplicatePartNameIsRejected() {
        String first = exec("create part panel size 60, 90");
        assertFalse(first.toLowerCase().contains("error"), "first create should succeed: " + first);

        String second = exec("create part panel size 30, 50");
        assertTrue(second.contains("already exists"),
                "duplicate create should be rejected: " + second);
        // Original part is still the only one — registry shouldn't have been touched.
        // (Units are mm in tests, so size 60 → 60mm.)
        assertNotNull(sceneManager.getPart("panel"));
        assertEquals(60f, sceneManager.getPart("panel").getCutWidthMm(), 0.001);
    }

    @Test
    void duplicateShapeNameIsRejected() {
        String first = exec("create box thing size 10, 10, 10");
        assertFalse(first.toLowerCase().contains("error"), "first create should succeed: " + first);

        String second = exec("create box thing size 5, 5, 5");
        assertTrue(second.contains("already exists"),
                "duplicate create should be rejected: " + second);
    }

    @Test
    void partNameCollidesWithExistingShape() {
        // Cross-kind collision: create a box, then try a part with same name.
        // Both share SceneManager's records map, so the collision check fires.
        exec("create box widget size 10, 10, 10");
        String result = exec("create part widget size 30, 50");
        assertTrue(result.contains("already exists"),
                "part should collide with existing shape: " + result);
    }

    @Test
    void afterDeletingFirstPartTheNameIsAvailable() {
        // Suggested workaround in the error message — deleting frees the name.
        exec("create part panel size 60, 90");
        exec("delete panel");
        String result = exec("create part panel size 30, 50");
        assertFalse(result.contains("already exists"),
                "name should be available after delete: " + result);
        assertEquals(30f, sceneManager.getPart("panel").getCutWidthMm(), 0.001);
    }
}
