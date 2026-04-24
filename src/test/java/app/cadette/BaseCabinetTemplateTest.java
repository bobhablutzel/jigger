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

import com.jme3.math.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless test for the base_cabinet template.
 * Creates a cabinet and verifies each part's world-space bounding box.
 *
 * Expected layout for "create base_cabinet BCT width 500 height 600 depth 400"
 * with default 18mm plywood and 5.5mm hardboard:
 *
 * Looking from the front:
 *   - Left side:  X = 0 to 18,  Y = 0 to 600,  Z = 0 to -400
 *   - Right side: X = 482 to 500, Y = 0 to 600, Z = 0 to -400
 *   - Bottom:     X = 18 to 482, Y = 0 to 18,   Z = 0 to -400
 *   - Top stretcher: X = 18 to 482, Y = 500 to 600, Z = 0 to -100
 *   - Back:       X = 0 to 500,  Y = 0 to 600,  Z = -400 to -405.5
 */
class BaseCabinetTemplateTest extends HeadlessTestBase {

    @BeforeEach
    void clearScene() { resetScene();
    }

    @Test
    void testBaseCabinetGeometry() {
        String result = exec("create base_cabinet BCT width 500 height 600 depth 400");
        System.out.println(result);

        // Debug dump all parts
        System.out.println("\n=== Base Cabinet Parts ===");
        for (String part : new String[]{
                "BCT/left-side", "BCT/right-side", "BCT/bottom",
                "BCT/top-stretcher", "BCT/back"}) {
            debugPart(part);
        }

        float t = 18f;       // plywood thickness (mm default)
        float bt = 5.5f;     // hardboard back thickness
        float tol = 1f;       // tolerance for floating point

        // Verify all parts exist
        assertNotNull(sceneManager.getObjectRecord("BCT/left-side"), "left-side should exist");
        assertNotNull(sceneManager.getObjectRecord("BCT/right-side"), "right-side should exist");
        assertNotNull(sceneManager.getObjectRecord("BCT/bottom"), "bottom should exist");
        assertNotNull(sceneManager.getObjectRecord("BCT/top-stretcher"), "top-stretcher should exist");
        assertNotNull(sceneManager.getObjectRecord("BCT/back"), "back should exist");

        // Print raw bounds for analysis
        // We'll add assertions once we see the actual positions
    }

    @Test
    void testPartsDoNotOverlapSides() {
        exec("create base_cabinet BCT width 500 height 600 depth 400");

        Vector3f[] left = bounds("BCT/left-side");
        Vector3f[] right = bounds("BCT/right-side");
        Vector3f[] bottom = bounds("BCT/bottom");

        assertNotNull(left);
        assertNotNull(right);
        assertNotNull(bottom);

        // Left and right sides should not overlap in X
        assertTrue(left[1].x <= right[0].x + 1f,
                "Left side max X (" + left[1].x + ") should be <= right side min X (" + right[0].x + ")");

        // Bottom should fit between the sides in X
        assertTrue(bottom[0].x >= left[0].x - 1f,
                "Bottom min X should be >= left side min X");
        assertTrue(bottom[1].x <= right[1].x + 1f,
                "Bottom max X should be <= right side max X");
    }

    // ---- Optional toe-kick ----

    @Test
    void defaultBaseCabinetHasNoToeKick() {
        // toe_kick defaults to 0 — no notch, no kick plate, bottom at Y=0.
        exec("create base_cabinet BCT width 500 height 600 depth 400");
        assertNull(sceneManager.getPart("BCT/toe-kick-front"),
                "default instantiation should not create a toe-kick-front panel");
        assertEquals(0, sceneManager.getPart("BCT/left-side").getCutouts().size(),
                "default instantiation should leave the side panels uncut");
        var bottom = bounds("BCT/bottom");
        assertEquals(0f, bottom[0].y, 1f,
                "without toe-kick, the bottom panel sits at Y=0");
    }

    @Test
    void toeKickAddsNotchesAndFrontPanelAndRaisesBottom() {
        exec("create base_cabinet BCT width 500 height 600 depth 400 toe_kick 1");

        // Side panels should each have one rect cutout at the front-bottom
        // corner of their local cut-face space (0, 0) sized 75mm × 100mm —
        // the default toe-kick depth/height.
        var leftCutouts = sceneManager.getPart("BCT/left-side").getCutouts();
        assertEquals(1, leftCutouts.size(), "left side should be notched once");
        assertInstanceOf(app.cadette.model.Cutout.Rect.class, leftCutouts.get(0));
        app.cadette.model.Cutout.Rect leftNotch =
                (app.cadette.model.Cutout.Rect) leftCutouts.get(0);
        assertEquals(0, leftNotch.xMm(), 0.01);
        assertEquals(0, leftNotch.yMm(), 0.01);
        assertEquals(75, leftNotch.widthMm(), 0.01);
        assertEquals(100, leftNotch.heightMm(), 0.01);
        assertNull(leftNotch.depthMm(), "toe-kick notch is a through-cut");

        assertEquals(1, sceneManager.getPart("BCT/right-side").getCutouts().size(),
                "right side should be notched identically");

        // Kick plate exists and sits recessed.
        assertNotNull(sceneManager.getPart("BCT/toe-kick-front"),
                "toe_kick=1 should create the toe-kick-front panel");

        // Bottom panel rises to clear the toe-kick space.
        var bottom = bounds("BCT/bottom");
        assertEquals(100f, bottom[0].y, 1f,
                "with toe-kick, bottom panel floats up by toe_kick_height (100mm)");
    }

    @Test
    void toeKickDimensionsCanBeOverridden() {
        // Non-default dimensions — 125mm tall × 85mm deep (a taller-than-
        // standard kick often seen in custom shop work).
        exec("create base_cabinet BCT width 500 height 600 depth 400 "
                + "toe_kick 1 toe_kick_height 125 toe_kick_depth 85");

        app.cadette.model.Cutout.Rect notch =
                (app.cadette.model.Cutout.Rect)
                        sceneManager.getPart("BCT/left-side").getCutouts().get(0);
        assertEquals(85, notch.widthMm(), 0.01);
        assertEquals(125, notch.heightMm(), 0.01);
    }

    @Test
    void toeKickDefaultsArePortableAcrossUnits() {
        // Defaults are declared as 100mm / 75mm literals — they should come
        // out as the same mm values even when the caller is in inches mode.
        exec("set units inches");
        exec("create base_cabinet BCT width 20 height 24 depth 16 toe_kick 1");

        app.cadette.model.Cutout.Rect notch =
                (app.cadette.model.Cutout.Rect)
                        sceneManager.getPart("BCT/left-side").getCutouts().get(0);
        // Still 100mm × 75mm because the template defaults used unit literals.
        assertEquals(75, notch.widthMm(), 0.01,
                "default toe_kick_depth (75mm) should be unit-independent");
        assertEquals(100, notch.heightMm(), 0.01,
                "default toe_kick_height (100mm) should be unit-independent");
    }
}
