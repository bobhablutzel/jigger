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

import app.cadette.model.Cutout;
import app.cadette.model.GrainRequirement;
import app.cadette.model.SheetLayout;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the cutout-to-sheet coordinate mapping in
 * {@link CutSheetRenderer#cutoutToSheetRect}. The drawing path itself is
 * exercised indirectly via UI integration; this nails down the math
 * separately so the rotated case can't silently regress.
 */
class CutSheetCutoutOverlayTest {

    private static SheetLayout.PlacedPart placed(float x, float y, float w, float h, boolean rotated) {
        return new SheetLayout.PlacedPart("part", x, y, w, h, rotated, GrainRequirement.ANY);
    }

    @Test
    void unrotated_cutoutCoordsMapDirectly() {
        // Part placed at sheet (100, 200), 600×900 mm. A 50×30 cutout at
        // local (10, 20) should land at sheet (100 + 10*scale, 200 + 20*scale)
        // with the same width/height (just scaled).
        SheetLayout.PlacedPart part = placed(0, 0, 600, 900, false);
        Cutout.Rect rect = new Cutout.Rect(10, 20, 50, 30, null, Cutout.Face.FRONT);
        float scale = 0.5f;

        Rectangle2D.Float r = CutSheetRenderer.cutoutToSheetRect(rect, part, 100, 200, scale);

        assertEquals(100 + 10 * scale, r.x, 0.001);
        assertEquals(200 + 20 * scale, r.y, 0.001);
        assertEquals(50 * scale, r.width, 0.001);
        assertEquals(30 * scale, r.height, 0.001);
    }

    @Test
    void rotated_cutoutCoordsAndDimsSwap() {
        // Same part rotated 90° on the sheet. The cutout's local X axis now
        // runs along sheet Y, and local Y runs along sheet X. So a cutout at
        // local (10, 20) size (50, 30) should land at sheet
        // (px + 20*scale, py + 10*scale) size (30*scale, 50*scale).
        SheetLayout.PlacedPart part = placed(0, 0, 900, 600, true);  // rotated dims
        Cutout.Rect rect = new Cutout.Rect(10, 20, 50, 30, null, Cutout.Face.FRONT);
        float scale = 0.5f;

        Rectangle2D.Float r = CutSheetRenderer.cutoutToSheetRect(rect, part, 100, 200, scale);

        assertEquals(100 + 20 * scale, r.x, 0.001);
        assertEquals(200 + 10 * scale, r.y, 0.001);
        assertEquals(30 * scale, r.width, 0.001);
        assertEquals(50 * scale, r.height, 0.001);
    }

    @Test
    void rotated_cornerCutoutLandsAtPlacedOrigin() {
        // A cutout at local (0, 0) — the part's origin corner — should land
        // at the placed part's (px, py) regardless of rotation, since both
        // mappings preserve the origin.
        SheetLayout.PlacedPart part = placed(0, 0, 900, 600, true);
        Cutout.Rect rect = new Cutout.Rect(0, 0, 50, 30, null, Cutout.Face.FRONT);
        float scale = 1.0f;

        Rectangle2D.Float r = CutSheetRenderer.cutoutToSheetRect(rect, part, 100, 200, scale);

        assertEquals(100, r.x, 0.001);
        assertEquals(200, r.y, 0.001);
    }
}
