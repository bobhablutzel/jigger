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
        assertTrue(result.contains("falls outside"),
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
        assertTrue(result.contains("falls outside"),
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

    // ---- Spline cuts (Catmull-Rom) -------------------------------------

    @Test
    void splineCutAddsToThePart() {
        String result = exec("cut \"panel\" spline (100, 100), (200, 100), (200, 200), (100, 200)");
        assertFalse(result.toLowerCase().contains("error"), result);

        List<Cutout> cuts = cutoutsOf("panel");
        assertEquals(1, cuts.size());
        assertInstanceOf(Cutout.Spline.class, cuts.get(0));
        Cutout.Spline s = (Cutout.Spline) cuts.get(0);
        assertEquals(4, s.controlPoints().size());
        assertNull(s.depthMm(), "no depth clause → through-cut (null)");
    }

    @Test
    void splineCutAcceptsDepth() {
        exec("cut \"panel\" spline (100, 100), (200, 100), (200, 200), (100, 200) depth 5");
        Cutout.Spline s = (Cutout.Spline) cutoutsOf("panel").get(0);
        assertEquals(5f, s.depthMm(), 0.001);
    }

    @Test
    void splineCutCommandResponseDescribesShape() {
        String result = exec("cut \"panel\" spline (100, 100), (200, 100), (200, 200), (100, 200)");
        assertTrue(result.contains("spline"), "response should describe spline: " + result);
        assertTrue(result.contains("4 control points"),
                "response should mention control-point count: " + result);
    }

    // ---- Curve cuts (cubic Bezier) ----------------------------------

    @Test
    void curveCutAddsToThePart() {
        // 6 control points = 2 segments, the minimum closed-curve case.
        String result = exec("cut \"panel\" curve "
                + "(100, 100), (110, 80), (190, 80), "
                + "(200, 100), (190, 200), (110, 200)");
        assertFalse(result.toLowerCase().contains("error"), result);

        List<Cutout> cuts = cutoutsOf("panel");
        assertEquals(1, cuts.size());
        assertInstanceOf(Cutout.Curve.class, cuts.get(0));
        Cutout.Curve cv = (Cutout.Curve) cuts.get(0);
        assertEquals(6, cv.controlPoints().size());
        assertNull(cv.depthMm(), "no depth clause → through-cut (null)");
    }

    @Test
    void curveCutAcceptsDepth() {
        exec("cut \"panel\" curve "
                + "(100, 100), (110, 80), (190, 80), "
                + "(200, 100), (190, 200), (110, 200) depth 5");
        Cutout.Curve cv = (Cutout.Curve) cutoutsOf("panel").get(0);
        assertEquals(5f, cv.depthMm(), 0.001);
    }

    @Test
    void curveCutCommandResponseDescribesSegments() {
        String result = exec("cut \"panel\" curve "
                + "(100, 100), (110, 80), (190, 80), "
                + "(200, 100), (190, 200), (110, 200)");
        assertTrue(result.contains("curve"), "response should describe curve: " + result);
        assertTrue(result.contains("2 Bezier segments"),
                "response should mention 2 segments (6 control points / 3): " + result);
    }

    @Test
    void curveCutWithBadControlPointCountIsRejected() {
        // Not a multiple of 3 → visitor throws IllegalArgumentException.
        String result = exec("cut \"panel\" curve "
                + "(100, 100), (110, 80), (190, 80), (200, 100)");  // 4 points
        assertTrue(result.toLowerCase().contains("multiple of 3"),
                "expected error about control-point count: " + result);
    }

    // ---- Keep operation ---------------------------------------------

    @Test
    void keepRectAddsToThePart() {
        String result = exec("keep \"panel\" rect at 100, 100 size 200, 200");
        assertFalse(result.toLowerCase().contains("error"), result);
        assertTrue(result.contains("keep region"), "response should describe keep: " + result);

        Part p = sceneManager.getPart("panel");
        assertEquals(1, p.getKeeps().size());
        assertInstanceOf(Cutout.Rect.class, p.getKeeps().get(0));
        // Cuts list stays empty.
        assertEquals(0, p.getCutouts().size());
    }

    @Test
    void keepEntirelyOutsidePartIsRejected() {
        // A keep entirely off the panel would erase the part. Reject as
        // probable user error.
        String result = exec("keep \"panel\" rect at 700, 100 size 50, 50");
        assertTrue(result.toLowerCase().contains("falls outside"),
                "expected rejection: " + result);
        assertEquals(0, sceneManager.getPart("panel").getKeeps().size());
    }

    @Test
    void keepCanUseSplineForCurvedOutline() {
        // The motivating use case: trace the silhouette you want to keep.
        String result = exec("keep \"panel\" spline "
                + "(100, 100), (200, 100), (200, 200), (100, 200)");
        assertFalse(result.toLowerCase().contains("error"), result);
        assertEquals(1, sceneManager.getPart("panel").getKeeps().size());
        assertInstanceOf(Cutout.Spline.class, sceneManager.getPart("panel").getKeeps().get(0));
    }

    // ---- Fillet command --------------------------------------------

    @Test
    void filletAddsPolygonCutout() {
        String result = exec("fillet \"panel\" at 70, 40 radius 5 facing NW");
        assertFalse(result.toLowerCase().contains("error"), result);
        assertTrue(result.contains("Filleted"), "response should confirm: " + result);

        Part p = sceneManager.getPart("panel");
        assertEquals(1, p.getCutouts().size());
        assertInstanceOf(Cutout.Polygon.class, p.getCutouts().get(0));
        // 14 vertices: corner + B + 11 arc samples + D.
        assertEquals(14, ((Cutout.Polygon) p.getCutouts().get(0)).vertices().size());
    }

    @Test
    void filletAcceptsAllCardinalFacings() {
        for (String facing : List.of("NE", "NW", "SE", "SW")) {
            // Reset between iterations so each fillet is the only cutout.
            resetScene();
            exec("create part \"panel\" size 600, 900 at 0, 0, 0");
            String result = exec("fillet \"panel\" at 300, 450 radius 5 facing " + facing);
            assertFalse(result.toLowerCase().contains("unknown"),
                    "should accept " + facing + ": " + result);
            assertEquals(1, sceneManager.getPart("panel").getCutouts().size());
        }
    }

    @Test
    void filletWithDepthCreatesPocket() {
        exec("fillet \"panel\" at 70, 40 radius 5 facing NW depth 5");
        Cutout.Polygon p = (Cutout.Polygon) sceneManager.getPart("panel").getCutouts().get(0);
        assertEquals(5f, p.depthMm(), 0.001);
    }

    @Test
    void filletUnknownFacingRejected() {
        String result = exec("fillet \"panel\" at 70, 40 radius 5 facing UP");
        assertTrue(result.toLowerCase().contains("unknown facing"),
                "expected unknown-facing error: " + result);
        assertEquals(0, sceneManager.getPart("panel").getCutouts().size());
    }

    @Test
    void polygonEntirelyOutsidePartBoundsIsRejected() {
        // Triangle with all vertices beyond the panel's right edge.
        String result = exec("cut \"panel\" polygon (700, 100), (800, 100), (750, 200)");
        assertTrue(result.contains("falls outside"),
                "expected rejection: " + result);
        assertEquals(0, cutoutsOf("panel").size(), "rejected cut should not be added");
    }

    @Test
    void polygonWithExtremeAspectRatioStraddlingPanelEdgeDoesNotExplode() {
        // The user-reported case: thin sliver triangle with one vertex far
        // outside the panel. The bbox check would let it through; the legacy
        // JTS overlay would throw a TopologyException at mesh-build time.
        // OverlayNGRobust handles it; if even that fails to find a sane
        // intersection, the command rejects cleanly.
        String result = exec("cut \"panel\" polygon (100, 100), (200, 100), (15000, 250) depth 5");
        assertFalse(result.toLowerCase().contains("exception"),
                "should not propagate JTS exception: " + result);
    }

    // ---- "Cutout extends past panel" informational note ---------------

    @Test
    void rectExtendingPastPanelEmitsClipNote() {
        String result = exec("cut \"panel\" rect at 550, 850 size 200, 200");
        assertTrue(result.contains("clipped at the boundary"),
                "rect that extends past the panel should emit the clip note: " + result);
    }

    @Test
    void rectFullyInsidePanelDoesNotEmitClipNote() {
        String result = exec("cut \"panel\" rect at 100, 100 size 50, 50");
        assertFalse(result.contains("clipped at the boundary"),
                "fully-inside rect should not emit the clip note: " + result);
    }

    @Test
    void circleExtendingPastPanelEmitsClipNote() {
        // Centre near the right edge; circle bbox extends past x=600.
        String result = exec("cut \"panel\" circle at 580, 450 radius 50");
        assertTrue(result.contains("clipped at the boundary"),
                "circle that extends past the panel should emit the clip note: " + result);
    }

    @Test
    void polygonExtendingPastPanelEmitsClipNote() {
        // Triangle with one vertex far past the right edge.
        String result = exec("cut \"panel\" polygon (100, 100), (200, 100), (1500, 250)");
        assertTrue(result.contains("clipped at the boundary"),
                "polygon with off-panel vertex should emit the clip note: " + result);
    }

    @Test
    void splineExtendingPastPanelEmitsClipNote() {
        String result = exec("cut \"panel\" spline (100, 100), (200, 100), (1500, 250), (100, 250)");
        assertTrue(result.contains("clipped at the boundary"),
                "spline with off-panel control point should emit the clip note: " + result);
    }

    // ---- Named shapes -------------------------------------------------

    @Test
    void shapeDefinitionPolygonRegistersAndCanBeUsed() {
        // Define a shape at the origin, then use it at an anchor on the panel.
        // Translated vertices should be (anchor + shape coords).
        String defResult = exec("shape my_handle polygon (0, 0), (50, 0), (50, 30), (0, 30)");
        assertTrue(defResult.contains("Defined polygon shape 'my_handle'"),
                "shape definition should confirm: " + defResult);

        exec("cut \"panel\" shape my_handle at 100, 200");
        Cutout.Polygon p = (Cutout.Polygon) cutoutsOf("panel").get(0);
        assertEquals(4, p.vertices().size());
        // Anchor (100, 200) + shape vertex (0, 0) = (100, 200).
        assertEquals(100, p.vertices().get(0).xMm(), 0.001);
        assertEquals(200, p.vertices().get(0).yMm(), 0.001);
        // Anchor + (50, 30) = (150, 230).
        assertEquals(150, p.vertices().get(2).xMm(), 0.001);
        assertEquals(230, p.vertices().get(2).yMm(), 0.001);
    }

    @Test
    void shapeDefinitionSplineRegistersAndCanBeUsed() {
        exec("shape blob spline (0, 0), (40, 0), (40, 40), (0, 40)");
        exec("cut \"panel\" shape blob at 200, 300");
        Cutout.Spline s = (Cutout.Spline) cutoutsOf("panel").get(0);
        assertEquals(4, s.controlPoints().size());
        // Anchor (200, 300) + control (0, 0) = (200, 300).
        assertEquals(200, s.controlPoints().get(0).xMm(), 0.001);
        assertEquals(300, s.controlPoints().get(0).yMm(), 0.001);
    }

    @Test
    void namedShapeAcceptsDepth() {
        exec("shape pin polygon (0, 0), (10, 0), (10, 10), (0, 10)");
        exec("cut \"panel\" shape pin at 100, 100 depth 5");
        Cutout.Polygon p = (Cutout.Polygon) cutoutsOf("panel").get(0);
        assertEquals(5f, p.depthMm(), 0.001);
    }

    @Test
    void unknownShapeNameProducesError() {
        // Reference a shape that was never defined → command fails with a
        // recognisable error message (not a silent failure).
        exec("shape known_one polygon (0, 0), (10, 0), (10, 10)");
        String result = exec("cut \"panel\" shape mystery at 100, 100");
        assertTrue(result.toLowerCase().contains("unknown shape")
                || result.toLowerCase().contains("error"),
                "expected error for undefined shape: " + result);
    }

    @Test
    void sameShapeUsedAtMultipleAnchors() {
        // The whole point of named shapes — declare once, place repeatedly.
        exec("shape pin polygon (0, 0), (10, 0), (10, 10), (0, 10)");
        exec("cut \"panel\" shape pin at 100, 100");
        exec("cut \"panel\" shape pin at 300, 100");
        exec("cut \"panel\" shape pin at 500, 100");
        List<Cutout> cuts = cutoutsOf("panel");
        assertEquals(3, cuts.size());
        assertEquals(100, ((Cutout.Polygon) cuts.get(0)).vertices().get(0).xMm(), 0.001);
        assertEquals(300, ((Cutout.Polygon) cuts.get(1)).vertices().get(0).xMm(), 0.001);
        assertEquals(500, ((Cutout.Polygon) cuts.get(2)).vertices().get(0).xMm(), 0.001);
    }

    // ---- Order-independent shape args -------------------------------

    @Test
    void rectArgsAcceptAnyOrder() {
        exec("cut \"panel\" rect at 100, 100 size 50, 50");          // original order
        exec("cut \"panel\" rect size 60, 60 at 200, 200");          // swapped
        exec("cut \"panel\" rect depth 5 at 300, 300 size 70, 70");  // depth first

        assertEquals(3, cutoutsOf("panel").size());
        Cutout.Rect r0 = (Cutout.Rect) cutoutsOf("panel").get(0);
        Cutout.Rect r1 = (Cutout.Rect) cutoutsOf("panel").get(1);
        Cutout.Rect r2 = (Cutout.Rect) cutoutsOf("panel").get(2);
        assertEquals(50, r0.widthMm(), 0.001);
        assertEquals(60, r1.widthMm(), 0.001);
        assertEquals(70, r2.widthMm(), 0.001);
        assertEquals(5f, r2.depthMm(), 0.001);
    }

    @Test
    void circleArgsAcceptAnyOrder() {
        // The example the user gave: radius before at.
        exec("cut \"panel\" circle radius 25 at 200, 300");
        Cutout.Circle c = (Cutout.Circle) cutoutsOf("panel").get(0);
        assertEquals(200, c.cxMm(), 0.001);
        assertEquals(300, c.cyMm(), 0.001);
        assertEquals(25, c.radiusMm(), 0.001);
    }

    @Test
    void rectMissingSizeIsRejected() {
        String result = exec("cut \"panel\" rect at 100, 100");
        assertTrue(result.toLowerCase().contains("requires `size"),
                "expected size-required error: " + result);
    }

    @Test
    void circleWithSizeIsRejected() {
        // size doesn't make sense for a circle; visitor catches the mismatch.
        String result = exec("cut \"panel\" circle at 100, 100 size 50, 50");
        assertTrue(result.toLowerCase().contains("does not take `size"),
                "expected size-on-circle rejection: " + result);
    }

    @Test
    void duplicateClauseIsRejected() {
        String result = exec("cut \"panel\" rect at 100, 100 at 200, 200 size 50, 50");
        assertTrue(result.toLowerCase().contains("specified twice"),
                "expected duplicate-clause error: " + result);
    }

    // ---- face front|back -------------------------------------------

    @Test
    void rectCutDefaultsToFrontFace() {
        exec("cut \"panel\" rect at 100, 100 size 50, 50 depth 5");
        Cutout.Rect r = (Cutout.Rect) cutoutsOf("panel").get(0);
        assertEquals(Cutout.Face.FRONT, r.face());
    }

    @Test
    void rectCutAcceptsBackFace() {
        exec("cut \"panel\" rect at 100, 100 size 50, 50 depth 5 face back");
        Cutout.Rect r = (Cutout.Rect) cutoutsOf("panel").get(0);
        assertEquals(Cutout.Face.BACK, r.face());
    }

    @Test
    void circleCutAcceptsBackFace() {
        exec("cut \"panel\" circle at 200, 200 radius 17.5 depth 13 face back");
        Cutout.Circle c = (Cutout.Circle) cutoutsOf("panel").get(0);
        assertEquals(Cutout.Face.BACK, c.face());
    }

    @Test
    void polygonCutAcceptsBackFace() {
        exec("cut \"panel\" polygon (10, 10), (20, 10), (15, 25) depth 5 face back");
        Cutout.Polygon p = (Cutout.Polygon) cutoutsOf("panel").get(0);
        assertEquals(Cutout.Face.BACK, p.face());
    }

    @Test
    void faceArgOrderIndependent() {
        // face can come anywhere in the arg list.
        exec("cut \"panel\" rect face back at 100, 100 depth 5 size 50, 50");
        Cutout.Rect r = (Cutout.Rect) cutoutsOf("panel").get(0);
        assertEquals(Cutout.Face.BACK, r.face());
    }

    @Test
    void unknownFaceRejected() {
        // 'sideways' is a plain ID (not a keyword) → reaches the visitor's
        // face-name dispatcher, which rejects it.
        String result = exec("cut \"panel\" rect at 100, 100 size 50, 50 face sideways");
        assertTrue(result.toLowerCase().contains("unknown face"),
                "expected unknown-face error: " + result);
    }
}
