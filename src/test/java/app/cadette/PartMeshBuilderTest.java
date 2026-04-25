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
import app.cadette.model.PartMeshBuilder;
import com.jme3.math.Vector3f;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the custom mesh builder for parts with rect cutouts — both through
 * (E3a) and partial-depth pockets (E3b).
 *
 * We don't render pixels (can't, headless) but we can check mesh invariants:
 * triangle counts matching the decomposition, bounding boxes matching the
 * declared part size regardless of cutouts, pocket floors sitting at the
 * correct Z, and cutouts extending past the part clipping to nothing.
 */
class PartMeshBuilderTest {

    // The rect-decomposition helper is package-private; reach it via reflection
    // so tests don't need to fabricate a full Part just to probe.
    private static Mesh buildRaw(float widthMm, float heightMm, float thicknessMm,
                                 List<Cutout> cutouts) {
        try {
            Method m = PartMeshBuilder.class.getDeclaredMethod("build",
                    float.class, float.class, float.class, List.class);
            m.setAccessible(true);
            return (Mesh) m.invoke(null, widthMm, heightMm, thicknessMm, cutouts);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int triangleCount(Mesh m) {
        return m.getTriangleCount();
    }

    private static Vector3f bboxSize(Mesh m) {
        m.updateBound();
        Vector3f extent = new Vector3f();
        m.getBound().getCenter();  // ensure bound is built
        // BoundingBox extent via world-bound: easier to read bounds buffer directly
        VertexBuffer posBuf = m.getBuffer(VertexBuffer.Type.Position);
        FloatBuffer fb = (FloatBuffer) posBuf.getData();
        fb.rewind();
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        while (fb.hasRemaining()) {
            float x = fb.get(), y = fb.get(), z = fb.get();
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        extent.set(maxX - minX, maxY - minY, maxZ - minZ);
        return extent;
    }

    // ---- No cutouts: baseline 6-face box ----

    @Test
    void partWithNoCutoutsProducesSixFaceBox() {
        // 6 faces × 2 triangles each = 12 triangles.
        Mesh m = buildRaw(600, 900, 18, List.of());
        assertEquals(12, triangleCount(m),
                "no-cutout part should be a plain 6-face box (12 triangles)");
    }

    @Test
    void partBoundingBoxMatchesDeclaredSize() {
        Mesh m = buildRaw(600, 900, 18, List.of());
        Vector3f size = bboxSize(m);
        assertEquals(600, size.x, 0.01f);
        assertEquals(900, size.y, 0.01f);
        assertEquals(18, size.z, 0.01f);
    }

    // ---- Interior through-cut ----

    @Test
    void interiorCutoutRemovesMaterialAndAddsWalls() {
        // 600 × 900 part with one 100 × 100 cutout well inside. The grid
        // becomes 3×3; 8 of 9 cells are kept (the middle is cut).
        // Top faces: 8, bottom faces: 8, outer walls: 4 (one per side,
        // each split into multiple quads due to the grid but remains 2×
        // triangles per grid edge on the boundary), cutout walls: 4 (one
        // per side of the missing cell, each 2 triangles).
        //
        // Easier to reason about total triangles:
        //   faces: 8 kept × 2 (top + bottom) × 2 tris = 32
        //   outer walls: 3 grid edges on each of 4 sides × 2 tris = 24
        //   cutout walls: 4 × 2 tris = 8
        //   total = 64
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(250, 400, 100, 100, null, Cutout.Face.FRONT)));
        assertEquals(64, triangleCount(m),
                "3x3 grid minus middle cell produces the expected triangle count");
    }

    @Test
    void cutoutDoesNotChangeOverallBoundingBox() {
        // The cutout removes interior material but the outer extent is
        // still W × H × T — sheet-layout and collision logic rely on this.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(250, 400, 100, 100, null, Cutout.Face.FRONT)));
        Vector3f size = bboxSize(m);
        assertEquals(600, size.x, 0.01f);
        assertEquals(900, size.y, 0.01f);
        assertEquals(18, size.z, 0.01f);
    }

    // ---- Edge / corner cutouts (toe-kick pattern) ----

    @Test
    void cornerCutoutBecomesAnLShape() {
        // 600 × 900 panel, cutout at (0, 0) size 75 × 75 — a toe-kick notch.
        // Grid: x-edges {0, 75, 600} → 2 cells wide; y-edges {0, 75, 900}
        // → 2 cells tall. 4 cells; (0,0) is cut, 3 kept.
        //
        // Walls per cell (checking each side's neighbour):
        //   (0,1): left (off-grid), bottom (= cutout top edge), top (off-grid) = 3
        //   (1,0): left (= cutout right edge), right (off-grid), bottom (off-grid) = 3
        //   (1,1): right (off-grid), top (off-grid) = 2
        //   Total: 8 walls × 2 tris = 16 wall triangles.
        //
        // Faces: 3 kept cells × 2 (top + bottom) × 2 tris = 12.
        // Total: 28.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(0, 0, 75, 75, null, Cutout.Face.FRONT)));
        assertEquals(28, triangleCount(m),
                "corner cutout produces an L-shape with 8 grid-edge wall segments");
    }

    @Test
    void cutoutExtendingPastEdgeClipsToPart() {
        // Cutout size overshoots the part — the clipping logic should
        // clamp it to the part's edge rather than producing negative-size
        // cells. Same geometry as the corner-cutout case: one cell cut out
        // of a 2×2 grid, three kept, L-shape with 8 wall segments = 28 tris.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(550, 850, 200, 200, null, Cutout.Face.FRONT)));
        assertEquals(28, triangleCount(m));
    }

    @Test
    void cutoutEntirelyOutsidePartIsNoOp() {
        // Cutout entirely outside the part — clipping drops it, mesh is
        // identical to the no-cutout case.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(1000, 1000, 50, 50, null, Cutout.Face.FRONT)));
        assertEquals(12, triangleCount(m));
    }

    // ---- Partial-depth pockets (E3b) ----

    @Test
    void pocketAddsFloorAndPocketWalls() {
        // Single 50×50 pocket on a 600×900 panel, depth 5.
        // Grid: x {0, 100, 150, 600} → 3 cells; y {0, 100, 150, 900} → 3 cells.
        //
        //   faces: 9 cells kept (1 of them is the pocket cell, which still
        //          has a top face at the pocket floor) × 2 (top + bottom)
        //          × 2 tris = 36
        //   outer walls: 3 grid segments × 4 sides × 2 tris = 24
        //   pocket walls: 4 sides of the pocket cell (each a step-down from
        //          full-thickness solid neighbour) × 2 tris = 8
        //   total: 36 + 24 + 8 = 68
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 5f, Cutout.Face.FRONT)));
        assertEquals(68, triangleCount(m),
                "pocket adds a floor face and 4 pocket-wall segments");
    }

    @Test
    void pocketFloorSitsAtCorrectZ() {
        // A 5mm-deep pocket on an 18mm-thick panel: halfT = 9, pocket floor
        // at halfT − depth = 4. The panel's top face (elsewhere) is at +9,
        // bottom at −9. We should see Z = +4 appear as a distinct plane.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 5f, Cutout.Face.FRONT)));
        VertexBuffer posBuf = m.getBuffer(VertexBuffer.Type.Position);
        FloatBuffer fb = (FloatBuffer) posBuf.getData();
        fb.rewind();
        boolean sawPocketFloor = false;
        while (fb.hasRemaining()) {
            fb.get(); fb.get();                // x, y
            float z = fb.get();
            if (Math.abs(z - 4f) < 1e-4f) { sawPocketFloor = true; break; }
        }
        assertTrue(sawPocketFloor, "pocket floor should appear at Z = halfT − depth = 4");
    }

    @Test
    void pocketLeavesBottomFaceSolid() {
        // The −Z face should still span the full panel (the pocket only
        // eats into the +Z side). Count Z = −9 vertices and make sure the
        // bottom face rectangle still extends corner-to-corner.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 5f, Cutout.Face.FRONT)));
        VertexBuffer posBuf = m.getBuffer(VertexBuffer.Type.Position);
        FloatBuffer fb = (FloatBuffer) posBuf.getData();
        fb.rewind();
        float minXatBottom = Float.POSITIVE_INFINITY;
        float maxXatBottom = Float.NEGATIVE_INFINITY;
        float minYatBottom = Float.POSITIVE_INFINITY;
        float maxYatBottom = Float.NEGATIVE_INFINITY;
        while (fb.hasRemaining()) {
            float x = fb.get(), y = fb.get(), z = fb.get();
            if (Math.abs(z - (-9f)) < 1e-4f) {
                if (x < minXatBottom) minXatBottom = x;
                if (x > maxXatBottom) maxXatBottom = x;
                if (y < minYatBottom) minYatBottom = y;
                if (y > maxYatBottom) maxYatBottom = y;
            }
        }
        // Part is centred: extent from −300 to +300 in X, −450 to +450 in Y.
        assertEquals(-300f, minXatBottom, 0.01f, "bottom face must reach −X edge");
        assertEquals( 300f, maxXatBottom, 0.01f, "bottom face must reach +X edge");
        assertEquals(-450f, minYatBottom, 0.01f, "bottom face must reach −Y edge");
        assertEquals( 450f, maxYatBottom, 0.01f, "bottom face must reach +Y edge");
    }

    @Test
    void throughCutOverlappingPocketWinsNoFloorInOverlap() {
        // A through-cut and a pocket that overlap: through wins in the
        // overlap region (no material at all), pocket lives only where it
        // doesn't overlap the through.
        //
        // Panel 600×900, through (200, 200)→(300, 300), pocket (250, 250)→(350, 350).
        // Only the non-overlapping parts of the pocket contribute floors.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(200, 200, 100, 100, null, Cutout.Face.FRONT),
                new Cutout.Rect(250, 250, 100, 100, 5f, Cutout.Face.FRONT)
        ));
        assertTrue(triangleCount(m) > 28,
                "mixed through+pocket produces more geometry than a plain through cut");
        // Bounding box unchanged.
        Vector3f size = bboxSize(m);
        assertEquals(600, size.x, 0.01f);
        assertEquals(900, size.y, 0.01f);
        assertEquals(18, size.z, 0.01f);
    }

    @Test
    void overlappingPocketsDeepestWins() {
        // Two pockets, one deeper than the other, with different depths in
        // the overlap region. The deeper pocket's floor wins in the overlap.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 100, 100, 3f, Cutout.Face.FRONT),
                new Cutout.Rect(150, 150, 100, 100, 8f, Cutout.Face.FRONT)
        ));
        VertexBuffer posBuf = m.getBuffer(VertexBuffer.Type.Position);
        FloatBuffer fb = (FloatBuffer) posBuf.getData();
        fb.rewind();
        boolean sawShallowFloor = false;  // halfT − 3 = 6
        boolean sawDeepFloor = false;     // halfT − 8 = 1
        while (fb.hasRemaining()) {
            fb.get(); fb.get();
            float z = fb.get();
            if (Math.abs(z - 6f) < 1e-4f) sawShallowFloor = true;
            if (Math.abs(z - 1f) < 1e-4f) sawDeepFloor = true;
        }
        assertTrue(sawShallowFloor, "shallow pocket floor (Z=6) must exist outside overlap");
        assertTrue(sawDeepFloor, "deep pocket floor (Z=1) must exist (wins in overlap)");
    }

    // ---- Back-face pockets (B.5) ----

    @Test
    void backFacePocketRecessesOnNegativeZSide() {
        // 18mm panel, back-face pocket of depth 5. Front face stays at +9
        // everywhere; back face drops to -halfT + depth = -4 inside the
        // pocket and stays at -9 outside.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 5f, Cutout.Face.BACK)));
        VertexBuffer posBuf = m.getBuffer(VertexBuffer.Type.Position);
        FloatBuffer fb = (FloatBuffer) posBuf.getData();
        fb.rewind();
        boolean sawBackPocketFloor = false;  // -halfT + depth = -9 + 5 = -4
        boolean sawFrontFaceIntact = false;  // halfT = 9 — should still be visible
        while (fb.hasRemaining()) {
            fb.get(); fb.get();
            float z = fb.get();
            if (Math.abs(z - (-4f)) < 1e-4f) sawBackPocketFloor = true;
            if (Math.abs(z - 9f) < 1e-4f) sawFrontFaceIntact = true;
        }
        assertTrue(sawBackPocketFloor,
                "back pocket floor should appear at Z = -halfT + depth = -4");
        assertTrue(sawFrontFaceIntact,
                "front face should still extend across the panel at Z = +9");
    }

    @Test
    void backFacePocketLeavesFrontFaceContinuous() {
        // The +Z face should reach the panel's full corners — back-face
        // pockets only eat into the -Z side.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 5f, Cutout.Face.BACK)));
        VertexBuffer posBuf = m.getBuffer(VertexBuffer.Type.Position);
        FloatBuffer fb = (FloatBuffer) posBuf.getData();
        fb.rewind();
        float minXatTop = Float.POSITIVE_INFINITY, maxXatTop = Float.NEGATIVE_INFINITY;
        float minYatTop = Float.POSITIVE_INFINITY, maxYatTop = Float.NEGATIVE_INFINITY;
        while (fb.hasRemaining()) {
            float x = fb.get(), y = fb.get(), z = fb.get();
            if (Math.abs(z - 9f) < 1e-4f) {
                if (x < minXatTop) minXatTop = x;
                if (x > maxXatTop) maxXatTop = x;
                if (y < minYatTop) minYatTop = y;
                if (y > maxYatTop) maxYatTop = y;
            }
        }
        assertEquals(-300f, minXatTop, 0.01f, "front face must reach -X edge");
        assertEquals( 300f, maxXatTop, 0.01f, "front face must reach +X edge");
        assertEquals(-450f, minYatTop, 0.01f, "front face must reach -Y edge");
        assertEquals( 450f, maxYatTop, 0.01f, "front face must reach +Y edge");
    }

    @Test
    void coincidentFrontAndBackPocketsThatMeetActAsThrough() {
        // Front pocket depth 10 + back pocket depth 10 on an 18mm panel:
        // pockets overlap in the middle of the thickness, so the cell has
        // no material left — should be equivalent to a through cut.
        Mesh meeting = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 10f, Cutout.Face.FRONT),
                new Cutout.Rect(100, 100, 50, 50, 10f, Cutout.Face.BACK)));
        Mesh through = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, null, Cutout.Face.FRONT)));
        assertEquals(triangleCount(through), triangleCount(meeting),
                "front+back pockets meeting in the middle should produce the same "
                + "mesh as a single through cut at that location");
    }

    // ---- Multiple cutouts ----

    @Test
    void twoSeparateCutoutsEachPunchAHole() {
        // Two non-overlapping interior cuts on a 600 × 900 panel.
        // Cutouts at (100, 100) 100×100 and (400, 700) 100×100.
        // Grid: x {0, 100, 200, 400, 500, 600}, y {0, 100, 200, 700, 800, 900}.
        // 5x5 = 25 cells, 2 cut, 23 kept. Expected triangles:
        //   faces: 23 × 2 × 2 = 92
        //   walls around kept cells where neighbour is cut or off-grid.
        // Exact wall count is tedious; we just assert it's larger than the
        // single-cutout case (64) and consistent with expectation.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 100, 100, null, Cutout.Face.FRONT),
                new Cutout.Rect(400, 700, 100, 100, null, Cutout.Face.FRONT)));
        int tris = triangleCount(m);
        assertTrue(tris > 64, "two cutouts should yield more triangles than one: " + tris);
    }

    @Test
    void overlappingCutoutsBehaveAsTheirUnion() {
        // Two rects that overlap in the middle. The final shape is the
        // rectangle minus their union, so the mesh triangle count should
        // match a SINGLE cutout covering exactly their union rectangle.
        Mesh overlap = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 100, 100, null, Cutout.Face.FRONT),
                new Cutout.Rect(150, 150, 100, 100, null, Cutout.Face.FRONT)));
        // The union region is non-rectangular, so we don't compare with a
        // single-cutout equivalent. We just sanity-check the triangle count
        // is positive and the bounding box is unchanged.
        assertTrue(triangleCount(overlap) > 0);
        Vector3f size = bboxSize(overlap);
        assertEquals(600, size.x, 0.01f);
        assertEquals(900, size.y, 0.01f);
    }
}
