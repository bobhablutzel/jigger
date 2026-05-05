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
import com.jme3.scene.Mesh;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static app.cadette.MeshInvariants.*;

/**
 * Tests the custom mesh builder for parts with rect cutouts — both through
 * (E3a) and partial-depth pockets (E3b).
 *
 * <p>Assertions are expressed via {@link MeshInvariants} so they describe the
 * <em>geometric contract</em> the mesh must satisfy (extent, closure, enclosed
 * volume, material presence at sample points) rather than triangle counts that
 * depend on the specific decomposition strategy. This lets the underlying
 * algorithm change without rewriting the tests.
 */
class PartMeshBuilderTest {

    /** Volume tolerance for axis-aligned (rect) cutouts — coordinates are
     *  integer-mm so float drift stays under a mm³. */
    private static final float V_TOL = 1f;

    /** Volume tolerance for circle-derived geometry: JTS uses doubles
     *  internally and we cast to float, plus 32 sin/cos values accumulate
     *  small rounding. ~20 mm³ on multi-million mm³ panels = sub-2 ppm,
     *  well below woodworking tolerances. */
    private static final float V_TOL_CIRCLE = 50f;

    /** Linear tolerance for face-extent comparisons. */
    private static final float L_TOL = 0.01f;

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

    /** Mesh-space X for a part-local X coordinate (mesh is centred). */
    private static float mx(float partLocalX, float widthMm)  { return partLocalX - widthMm * 0.5f; }
    private static float my(float partLocalY, float heightMm) { return partLocalY - heightMm * 0.5f; }

    // ---- No cutouts: baseline solid box ---------------------------------

    @Test
    void partWithNoCutoutsProducesSolidBox() {
        Mesh m = buildRaw(600, 900, 18, List.of());
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600 * 900 * 18, V_TOL);
    }

    @Test
    void partBoundingBoxMatchesDeclaredSize() {
        assertExtent(buildRaw(600, 900, 18, List.of()), 600, 900, 18);
    }

    // ---- Interior through-cut -------------------------------------------

    @Test
    void interiorCutoutRemovesMaterial() {
        // 600 × 900 × 18 panel with a 100 × 100 through cut centred at (300, 450).
        // Volume removed = 100 · 100 · 18 = 180_000 mm³.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(250, 400, 100, 100, null, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600 * 900 * 18 - 100 * 100 * 18, V_TOL);
        // Mesh centre lies inside the through cut → no material at any Z.
        assertNoMaterialAt(m, 0, 0, 0);
        // Far from the cutout → solid.
        assertHasMaterialAt(m, mx(50, 600), my(50, 900), 0);
    }

    @Test
    void cutoutDoesNotChangeOverallBoundingBox() {
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(250, 400, 100, 100, null, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
    }

    // ---- Edge / corner cutouts (toe-kick pattern) -----------------------

    @Test
    void cornerCutoutBecomesAnLShape() {
        // 600 × 900 panel with a 75 × 75 corner notch at (0, 0). Removed 75²·18.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(0, 0, 75, 75, null, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600f * 900 * 18 - 75 * 75 * 18, V_TOL);
        // Inside the corner notch → empty.
        assertNoMaterialAt(m, mx(30, 600), my(30, 900), 0);
        // Far corner → solid.
        assertHasMaterialAt(m, mx(550, 600), my(850, 900), 0);
    }

    @Test
    void cutoutExtendingPastEdgeClipsToPart() {
        // Cutout at (550, 850) size 200 × 200 — clips to a 50 × 50 corner notch.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(550, 850, 200, 200, null, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600f * 900 * 18 - 50 * 50 * 18, V_TOL);
        assertNoMaterialAt(m, mx(575, 600), my(875, 900), 0);  // inside clipped notch
    }

    @Test
    void cutoutEntirelyOutsidePartIsNoOp() {
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(1000, 1000, 50, 50, null, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600f * 900 * 18, V_TOL);
    }

    // ---- Partial-depth pockets (E3b) ------------------------------------

    @Test
    void pocketRemovesPartialMaterial() {
        // 50 × 50 × 5mm-deep pocket. Removed 50²·5 = 12_500 mm³.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 5f, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600f * 900 * 18 - 50 * 50 * 5, V_TOL);
    }

    @Test
    void pocketFloorSitsAtCorrectZ() {
        // 5mm-deep front pocket on an 18mm panel: floor at +halfT − 5 = +4.
        // Material exists below the floor, not above it.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 5f, Cutout.Face.FRONT)));
        float cx = mx(125, 600), cy = my(125, 900);
        assertHasMaterialAt(m, cx, cy, 3f);   // just below floor (+4)
        assertNoMaterialAt(m, cx, cy, 5f);    // just above floor
        assertHasMaterialAt(m, cx, cy, 0f);   // mid-thickness, solid
    }

    @Test
    void pocketLeavesBottomFaceSolid() {
        // The −Z face still spans the full panel — pocket only eats into +Z.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 5f, Cutout.Face.FRONT)));
        assertFaceCoversAtZ(m, -9f, -300f, 300f, -450f, 450f, L_TOL);
    }

    @Test
    void throughCutOverlappingPocketWinsNoFloorInOverlap() {
        // through (200, 200, 100, 100) and pocket (250, 250, 100, 100, 5).
        // Overlap region (250, 250)–(300, 300): through wins → no material.
        // Pocket-only region: 100·100 − 50·50 = 7500 mm² at depth 5.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(200, 200, 100, 100, null, Cutout.Face.FRONT),
                new Cutout.Rect(250, 250, 100, 100, 5f,  Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        float removed = 100 * 100 * 18 + 7500 * 5;
        assertVolume(m, 600f * 900 * 18 - removed, V_TOL);
        // In overlap region → no material at any Z.
        assertNoMaterialAt(m, mx(275, 600), my(275, 900), 0);
        assertNoMaterialAt(m, mx(275, 600), my(275, 900), -5);
        // Pocket-only region (e.g. (325, 325)) → material below floor (+4).
        assertHasMaterialAt(m, mx(325, 600), my(325, 900), 0);
        assertNoMaterialAt(m, mx(325, 600), my(325, 900), 6);
    }

    @Test
    void overlappingPocketsDeepestWins() {
        // P1 (100, 100, 100, 100, depth 3) and P2 (150, 150, 100, 100, depth 8).
        // Overlap: (150, 150)–(200, 200), 2500 mm². Deeper (8) wins in overlap.
        // Removed: P1-only 7500·3 + P2-only 7500·8 + overlap 2500·8 = 22_500 + 60_000 + 20_000.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 100, 100, 3f, Cutout.Face.FRONT),
                new Cutout.Rect(150, 150, 100, 100, 8f, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600f * 900 * 18 - (22_500 + 60_000 + 20_000), V_TOL);
        // P1-only region (e.g. (125, 125)) → floor at +6.
        assertHasMaterialAt(m, mx(125, 600), my(125, 900), 5f);
        assertNoMaterialAt(m, mx(125, 600), my(125, 900), 7f);
        // P2-only region (e.g. (225, 225)) → floor at +1.
        assertHasMaterialAt(m, mx(225, 600), my(225, 900), 0f);
        assertNoMaterialAt(m, mx(225, 600), my(225, 900), 5f);
        // Overlap region (e.g. (175, 175)) → deeper (8) wins, floor at +1.
        assertHasMaterialAt(m, mx(175, 600), my(175, 900), 0f);
        assertNoMaterialAt(m, mx(175, 600), my(175, 900), 5f);
    }

    // ---- Back-face pockets (B.5) ----------------------------------------

    @Test
    void backFacePocketRecessesOnNegativeZSide() {
        // Back-face pocket of depth 5 on an 18mm panel: floor at −halfT + 5 = −4.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 5f, Cutout.Face.BACK)));
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600f * 900 * 18 - 50 * 50 * 5, V_TOL);
        float cx = mx(125, 600), cy = my(125, 900);
        assertHasMaterialAt(m, cx, cy, -3f);   // just above floor (−4)
        assertNoMaterialAt(m, cx, cy, -5f);    // just below floor
        // Front face still continuous.
        assertFaceCoversAtZ(m, 9f, -300f, 300f, -450f, 450f, L_TOL);
    }

    @Test
    void backFacePocketLeavesFrontFaceContinuous() {
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 5f, Cutout.Face.BACK)));
        assertFaceCoversAtZ(m, 9f, -300f, 300f, -450f, 450f, L_TOL);
    }

    @Test
    void coincidentFrontAndBackPocketsThatMeetActAsThrough() {
        // Front depth 10 + back depth 10 on 18mm panel: pockets meet, no material.
        // Equivalent to a 50 × 50 through cut.
        Mesh meeting = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, 10f, Cutout.Face.FRONT),
                new Cutout.Rect(100, 100, 50, 50, 10f, Cutout.Face.BACK)));
        Mesh through = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 50, 50, null, Cutout.Face.FRONT)));
        assertVolume(meeting, signedVolume(through), V_TOL);
        assertNoMaterialAt(meeting, mx(125, 600), my(125, 900), 0);
    }

    // ---- Multiple cutouts -----------------------------------------------

    @Test
    void twoSeparateCutoutsEachPunchAHole() {
        // Two non-overlapping 100 × 100 through cuts.
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 100, 100, null, Cutout.Face.FRONT),
                new Cutout.Rect(400, 700, 100, 100, null, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600f * 900 * 18 - 2 * 100 * 100 * 18, V_TOL);
        assertNoMaterialAt(m, mx(150, 600), my(150, 900), 0);  // first cutout
        assertNoMaterialAt(m, mx(450, 600), my(750, 900), 0);  // second cutout
        assertHasMaterialAt(m, mx(50, 600), my(50, 900), 0);   // solid corner
    }

    @Test
    void overlappingCutoutsBehaveAsTheirUnion() {
        // 100 × 100 cuts at (100, 100) and (150, 150). Union = 2·10_000 − 50²
        // (the 50 × 50 overlap) = 17_500 mm².
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 100, 100, null, Cutout.Face.FRONT),
                new Cutout.Rect(150, 150, 100, 100, null, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600f * 900 * 18 - 17_500 * 18, V_TOL);
    }

    // ---- Circle cutouts -------------------------------------------------

    /** Polygon-approximation area for a circle of radius r at the segmentation
     *  resolution PartMeshBuilder uses internally — used as expected volume
     *  in circle-cutout tests so the assertion isn't tied to the exact N. */
    private static float circleArea(float r) {
        int n = 32;  // matches PartMeshBuilder.CIRCLE_SEGMENTS
        return 0.5f * n * r * r * (float) Math.sin(2 * Math.PI / n);
    }

    @Test
    void throughCircleRemovesMaterial() {
        // 35mm-diameter through hole at panel centre.
        float radius = 17.5f;
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Circle(300, 450, radius, null, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        assertVolume(m, 600f * 900 * 18 - circleArea(radius) * 18, V_TOL_CIRCLE);
        // Mesh centre (at panel centre = circle centre) → no material.
        assertNoMaterialAt(m, 0, 0, 0);
        // Far from the circle → solid.
        assertHasMaterialAt(m, mx(50, 600), my(50, 900), 0);
    }

    @Test
    void cupHolePartialDepthCircle() {
        // 35mm-diameter, 13mm-deep cup hole on a 19mm panel — European hinge
        // cup. Floor at +halfT − 13 = 9.5 − 13 = −3.5.
        float radius = 17.5f;
        float depth = 13f;
        Mesh m = buildRaw(600, 900, 19, List.of(
                new Cutout.Circle(300, 450, radius, depth, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 19);
        assertVolume(m, 600f * 900 * 19 - circleArea(radius) * depth, V_TOL_CIRCLE);
        // Below the floor (−4 < −3.5) → solid.
        assertHasMaterialAt(m, 0, 0, -4f);
        // Above the floor (−3 > −3.5) → empty.
        assertNoMaterialAt(m, 0, 0, -3f);
        // Bottom face still spans the full panel.
        assertFaceCoversAtZ(m, -9.5f, -300f, 300f, -450f, 450f, L_TOL);
    }

    @Test
    void rectAndCircleCutoutsOnSamePanel() {
        // Non-overlapping rect through and circle through.
        float radius = 25f;
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(100, 100, 100, 100, null, Cutout.Face.FRONT),
                new Cutout.Circle(450, 700, radius, null, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        float removed = (100f * 100 + circleArea(radius)) * 18;
        assertVolume(m, 600f * 900 * 18 - removed, V_TOL_CIRCLE);
        assertNoMaterialAt(m, mx(150, 600), my(150, 900), 0);  // rect centre
        assertNoMaterialAt(m, mx(450, 600), my(700, 900), 0);  // circle centre
    }

    @Test
    void circlePocketOverlappingRectThrough() {
        // T-slot-ish: a rect through-cut crossed by a wider circular pocket
        // that opens into the slot (entry hole pattern). Through wins in the
        // overlap, so the removed-volume contribution from the pocket is only
        // its area minus the overlap, times its depth.
        //
        // Through rect: (250, 400, 100, 100) — 10_000 mm² × 18 mm thickness.
        // Circle pocket centred at (300, 450), radius 80, depth 5. The rect
        // sits entirely inside the circle (max-distance corner is √(50²+50²)
        // ≈ 70.7 mm < 80 mm radius). So pocket-only area = circle − rect.
        float radius = 80f;
        float depth = 5f;
        Mesh m = buildRaw(600, 900, 18, List.of(
                new Cutout.Rect(250, 400, 100, 100, null, Cutout.Face.FRONT),
                new Cutout.Circle(300, 450, radius, depth, Cutout.Face.FRONT)));
        assertExtent(m, 600, 900, 18);
        float pocketOnlyArea = circleArea(radius) - 10_000f;
        float removed = 10_000f * 18 + pocketOnlyArea * depth;
        assertVolume(m, 600f * 900 * 18 - removed, V_TOL_CIRCLE);
        // Rect centre → through, no material at any Z.
        assertNoMaterialAt(m, 0, 0, 0);
        assertNoMaterialAt(m, 0, 0, -5);
        // Pocket-only ring (outside rect, inside circle): material below floor.
        // Sample at (370, 450) — 70 mm from circle centre, well outside the
        // rect (which ends at x=350) and well inside the circle.
        float halfT = 9f;
        assertHasMaterialAt(m, mx(370, 600), my(450, 900), 0f);          // mid-thickness, solid
        assertNoMaterialAt(m, mx(370, 600), my(450, 900), halfT - 4f);   // above pocket floor (+5)
    }
}
