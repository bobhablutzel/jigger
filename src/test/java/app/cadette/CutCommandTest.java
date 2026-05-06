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
import app.cadette.model.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase E2 — `cut` command end-to-end through the command parser and visitor.
 *
 * The grammar accepts `cut <objectName> rect at x, y size w, h [depth N]`.
 * Cutouts land in the target part's cutouts list; undo/redo preserve order;
 * the BOM surfaces them as operations alongside dados and rabbets.
 *
 * <p>Cuts that fall <em>entirely</em> outside the part's cut face are
 * rejected at command time — silently dropping them at mesh-build time
 * was a footgun (no visual feedback, no error). Cuts that partially
 * overhang or overlap previous cuts are still accepted and stored;
 * Phase E3's mesh pipeline clips them to the part rectangle. Under
 * set-theoretic semantics, order of specification doesn't change the
 * final shape, but the cutouts list preserves insertion order for
 * traceability in the cut list / undo stack.
 */
class CutCommandTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
        exec("create part \"panel\" size 600, 900 at 0, 0, 0");
    }

    private List<Cutout> cutoutsOf(String name) {
        Part p = sceneManager.getPart(name);
        assertNotNull(p, "part '" + name + "' not found");
        return p.getCutouts();
    }

    // ---- Basic grammar + model wiring ----

    @Test
    void simpleRectCutAddsToThePart() {
        String result = exec("cut \"panel\" rect at 0, 0 size 75, 75");
        assertFalse(result.toLowerCase().contains("error"), result);

        List<Cutout> cuts = cutoutsOf("panel");
        assertEquals(1, cuts.size());
        assertInstanceOf(Cutout.Rect.class, cuts.get(0));
        Cutout.Rect r = (Cutout.Rect) cuts.get(0);
        assertEquals(0, r.xMm(), 0.001);
        assertEquals(0, r.yMm(), 0.001);
        assertEquals(75, r.widthMm(), 0.001);
        assertEquals(75, r.heightMm(), 0.001);
        assertNull(r.depthMm(), "no depth clause → through-cut (null)");
    }

    @Test
    void partialDepthCutCarriesDepthValue() {
        exec("cut \"panel\" rect at 50, 100 size 10, 10 depth 5");
        Cutout.Rect r = (Cutout.Rect) cutoutsOf("panel").get(0);
        assertEquals(5f, r.depthMm(), 0.001);
    }

    @Test
    void expressionsAcceptedInCoordinatesAndSize() {
        // Mirror how a real template would compute positions.
        exec("cut \"panel\" rect at 10 + 5, 20 size 50 * 2, 30");
        Cutout.Rect r = (Cutout.Rect) cutoutsOf("panel").get(0);
        assertEquals(15, r.xMm(), 0.001);
        assertEquals(20, r.yMm(), 0.001);
        assertEquals(100, r.widthMm(), 0.001);
        assertEquals(30, r.heightMm(), 0.001);
    }

    @Test
    void cutOnMissingPartReturnsError() {
        String result = exec("cut \"no_such_part\" rect at 0, 0 size 10, 10");
        assertTrue(result.contains("not found"), "expected a not-found error, got: " + result);
        assertEquals(0, cutoutsOf("panel").size(), "'panel' must be unaffected");
    }

    // ---- Boundary edge cases: cuts outside / partially-outside / overlapping ----

    @Test
    void simpleCircleCutAddsToThePart() {
        // 35mm-diameter cup hole at (50, 50), 11mm deep — typical European
        // hinge cup. Confirms the circle grammar wires through to a stored
        // Cutout.Circle on the part.
        String result = exec("cut \"panel\" circle at 50, 50 radius 17.5 depth 11");
        assertFalse(result.toLowerCase().contains("error"), result);

        List<Cutout> cuts = cutoutsOf("panel");
        assertEquals(1, cuts.size());
        assertInstanceOf(Cutout.Circle.class, cuts.get(0));
        Cutout.Circle c = (Cutout.Circle) cuts.get(0);
        assertEquals(50, c.cxMm(), 0.001);
        assertEquals(50, c.cyMm(), 0.001);
        assertEquals(17.5, c.radiusMm(), 0.001);
        assertEquals(11f, c.depthMm(), 0.001);
    }

    @Test
    void throughCutCircleHasNullDepth() {
        exec("cut \"panel\" circle at 100, 100 radius 25");
        Cutout.Circle c = (Cutout.Circle) cutoutsOf("panel").get(0);
        assertNull(c.depthMm(), "no depth clause → through-cut (null)");
    }

    @Test
    void circleCommandResponseDescribesPocket() {
        // 3D mesh now renders circle pockets, so no heads-up needed — the
        // response just confirms the cutout that was added.
        String result = exec("cut \"panel\" circle at 50, 50 radius 17.5 depth 11");
        assertTrue(result.contains("circle"), "response should describe the cutout: " + result);
        assertTrue(result.contains("17.5"), "response should include the radius: " + result);
        assertFalse(result.contains("does not yet render"),
                "stale heads-up should be gone: " + result);
    }

    @Test
    void circleEntirelyOutsidePartBoundsIsRejected() {
        // A 600×900 panel; a circle centered at (1000, 1000) radius 25 lies
        // entirely off the cut face. Same rejection path as for rect.
        String result = exec("cut \"panel\" circle at 1000, 1000 radius 25");
        assertTrue(result.contains("falls entirely outside"),
                "expected rejection: " + result);
        assertEquals(0, cutoutsOf("panel").size());
    }

    @Test
    void cutEntirelyOutsidePartBoundsIsRejected() {
        // The `panel` is 600×900. A cut at (1000, 1000) size 50×50 is
        // entirely off the part's cut face. PartMeshBuilder would silently
        // clip it to nothing — instead we reject at command time so the
        // user sees a clear message and the cutout is never stored.
        String result = exec("cut \"panel\" rect at 1000, 1000 size 50, 50");
        assertTrue(result.contains("falls entirely outside"),
                "expected rejection message: " + result);
        assertEquals(0, cutoutsOf("panel").size(),
                "rejected cuts must not be stored");
    }

    @Test
    void cutPartiallyExtendingBeyondPartIsAccepted() {
        // At (580, 0) size 50×50 on a 600-wide panel — 30mm extends past the edge.
        exec("cut \"panel\" rect at 580, 0 size 50, 50");
        Cutout.Rect r = (Cutout.Rect) cutoutsOf("panel").get(0);
        assertEquals(580, r.xMm(), 0.001);
        assertEquals(50, r.widthMm(), 0.001);  // stored as specified; clipping is a render concern
    }

    @Test
    void overlappingCutsAreBothStoredInOrder() {
        exec("cut \"panel\" rect at 0, 0 size 100, 100");       // #1
        exec("cut \"panel\" rect at 50, 50 size 100, 100");     // #2 — overlaps #1

        List<Cutout> cuts = cutoutsOf("panel");
        assertEquals(2, cuts.size(), "overlap doesn't dedupe — both are stored");
        assertEquals(0f, ((Cutout.Rect) cuts.get(0)).xMm(), 0.001);
        assertEquals(50f, ((Cutout.Rect) cuts.get(1)).xMm(), 0.001);
    }

    @Test
    void cutInsidePreviouslyRemovedAreaIsStored() {
        // First cut removes the bottom-left 100×100 corner. Second cut sits
        // entirely inside that already-removed region. Accepted and stored;
        // rendering will naturally produce the same visual as just the first.
        exec("cut \"panel\" rect at 0, 0 size 100, 100");
        exec("cut \"panel\" rect at 25, 25 size 50, 50");

        List<Cutout> cuts = cutoutsOf("panel");
        assertEquals(2, cuts.size());
        // Order preserved as specified.
        assertEquals(0f, ((Cutout.Rect) cuts.get(0)).xMm(), 0.001);
        assertEquals(25f, ((Cutout.Rect) cuts.get(1)).xMm(), 0.001);
    }

    @Test
    void multipleCutsPreserveSpecificationOrder() {
        exec("cut \"panel\" rect at 10, 10 size 5, 5");    // #1
        exec("cut \"panel\" rect at 20, 20 size 5, 5");    // #2
        exec("cut \"panel\" rect at 30, 30 size 5, 5");    // #3
        exec("cut \"panel\" rect at 40, 40 size 5, 5");    // #4

        List<Cutout> cuts = cutoutsOf("panel");
        assertEquals(4, cuts.size());
        for (int i = 0; i < 4; i++) {
            Cutout.Rect r = (Cutout.Rect) cuts.get(i);
            float expectedX = 10 + i * 10;
            assertEquals(expectedX, r.xMm(), 0.001,
                    "cut " + (i + 1) + " should preserve order");
        }
    }

    // ---- Undo / redo ----

    @Test
    void undoRemovesTheCutout() {
        exec("cut \"panel\" rect at 0, 0 size 75, 75");
        assertEquals(1, cutoutsOf("panel").size());

        exec("undo");
        assertEquals(0, cutoutsOf("panel").size(), "undo should strip the cutout");
    }

    @Test
    void redoRestoresTheCutout() {
        exec("cut \"panel\" rect at 0, 0 size 75, 75");
        exec("undo");
        exec("redo");
        assertEquals(1, cutoutsOf("panel").size());
        assertEquals(75, ((Cutout.Rect) cutoutsOf("panel").get(0)).widthMm(), 0.001);
    }

    @Test
    void undoThroughMultipleCutsPreservesOrder() {
        exec("cut \"panel\" rect at 10, 10 size 5, 5");    // #1
        exec("cut \"panel\" rect at 20, 20 size 5, 5");    // #2
        exec("cut \"panel\" rect at 30, 30 size 5, 5");    // #3
        assertEquals(3, cutoutsOf("panel").size());

        exec("undo");
        assertEquals(2, cutoutsOf("panel").size(),
                "LIFO undo — most recent cut removed first");
        assertEquals(20f, ((Cutout.Rect) cutoutsOf("panel").get(1)).xMm(), 0.001,
                "remaining cuts stay in original order");

        exec("undo");
        assertEquals(1, cutoutsOf("panel").size());
        assertEquals(10f, ((Cutout.Rect) cutoutsOf("panel").get(0)).xMm(), 0.001);

        exec("redo");
        exec("redo");
        assertEquals(3, cutoutsOf("panel").size());
        for (int i = 0; i < 3; i++) {
            assertEquals(10 + i * 10f,
                    ((Cutout.Rect) cutoutsOf("panel").get(i)).xMm(), 0.001,
                    "redo restores cut " + (i + 1) + " in original position");
        }
    }

    @Test
    void undoOnPartialDepthCutPreservesDepth() {
        exec("cut \"panel\" rect at 0, 0 size 10, 10 depth 5");
        exec("undo");
        exec("redo");
        Cutout.Rect r = (Cutout.Rect) cutoutsOf("panel").get(0);
        assertEquals(5f, r.depthMm(), 0.001,
                "depth must survive the undo/redo round-trip");
    }

    // ---- BOM integration ----

    @Test
    void bomListsCutoutAsAnOperation() {
        exec("cut \"panel\" rect at 0, 0 size 75, 75");
        String bom = exec("show cutlist");
        assertTrue(bom.contains("cutout"),
                "expected 'cutout' in BOM output, got:\n" + bom);
        assertTrue(bom.contains("75"),
                "BOM should include the cutout dimensions, got:\n" + bom);
    }

    @Test
    void bomDistinguishesThroughFromPartialDepth() {
        exec("cut \"panel\" rect at 0, 0 size 75, 75");
        exec("cut \"panel\" rect at 100, 100 size 10, 10 depth 5");
        String bom = exec("show cutlist");
        assertTrue(bom.contains("through"), "through-cut indicator missing:\n" + bom);
        assertTrue(bom.contains("deep"), "partial-depth indicator missing:\n" + bom);
    }

    @Test
    void bomListsMultipleCutoutsInInsertionOrder() {
        exec("cut \"panel\" rect at 10, 10 size 5, 5");
        exec("cut \"panel\" rect at 200, 200 size 5, 5");
        String bom = exec("show cutlist");
        int firstIdx = bom.indexOf("at (10.0, 10.0)");
        int secondIdx = bom.indexOf("at (200.0, 200.0)");
        assertTrue(firstIdx >= 0, "first cut missing from BOM:\n" + bom);
        assertTrue(secondIdx >= 0, "second cut missing from BOM:\n" + bom);
        assertTrue(firstIdx < secondIdx, "BOM should list cuts in insertion order");
    }

    @Test
    void bomUsesCurrentDisplayUnitsForCutoutDimensions() {
        // Create a 762mm (30") part in mm, make a 75mm cutout, then switch
        // to inches. The cut list should show the cutout in inches, matching
        // the rest of the displayed dimensions. Matches existing part-header
        // behavior; fixes the pre-existing mm-hardcode in operations.
        exec("cut \"panel\" rect at 0, 0 size 75, 75");
        exec("set units inches");
        String bom = exec("show cutlist");
        // 75 mm = 2.953... inches, rounds to "3.0" with %.1f
        assertTrue(bom.contains(" in "),
                "cut list should use the current unit abbreviation ('in'):\n" + bom);
        assertTrue(bom.contains("cutout rect 3.0×3.0 in"),
                "cutout size should render in inches, not mm:\n" + bom);
        assertFalse(bom.contains("75.0mm") || bom.contains("75mm"),
                "cutout line should not leak mm after unit switch:\n" + bom);
    }

    // ---- Polygon cuts -------------------------------------------------

    @Test
    void polygonCutAddsToThePart() {
        // Triangle through-cut.
        String result = exec("cut \"panel\" polygon (10, 10), (20, 10), (15, 25)");
        assertFalse(result.toLowerCase().contains("error"), result);

        List<Cutout> cuts = cutoutsOf("panel");
        assertEquals(1, cuts.size());
        assertInstanceOf(Cutout.Polygon.class, cuts.get(0));
        Cutout.Polygon p = (Cutout.Polygon) cuts.get(0);
        assertEquals(3, p.vertices().size());
        assertEquals(10, p.vertices().get(0).xMm(), 0.001);
        assertEquals(10, p.vertices().get(0).yMm(), 0.001);
        assertEquals(20, p.vertices().get(1).xMm(), 0.001);
        assertEquals(15, p.vertices().get(2).xMm(), 0.001);
        assertNull(p.depthMm(), "no depth clause → through-cut (null)");
    }

    @Test
    void polygonCutAcceptsDepth() {
        exec("cut \"panel\" polygon (10, 10), (20, 10), (15, 25) depth 5");
        Cutout.Polygon p = (Cutout.Polygon) cutoutsOf("panel").get(0);
        assertEquals(5f, p.depthMm(), 0.001);
    }

    @Test
    void polygonCutCommandResponseDescribesShape() {
        String result = exec("cut \"panel\" polygon (10, 10), (20, 10), (15, 25)");
        assertTrue(result.contains("polygon"), "response should describe polygon: " + result);
        assertTrue(result.contains("3 vertices"), "response should mention vertex count: " + result);
    }
}
