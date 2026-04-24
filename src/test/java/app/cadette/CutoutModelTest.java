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

import app.cadette.model.BoundingBox;
import app.cadette.model.Cutout;
import app.cadette.model.GrainRequirement;
import app.cadette.model.Material;
import app.cadette.model.MaterialCatalog;
import app.cadette.model.Part;
import app.cadette.model.Point2D;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase E1 — pure model layer tests for {@link Cutout} variants and the
 * {@code Part.cutouts} collection. No grammar, no rendering, no scene —
 * just the data shape.
 */
class CutoutModelTest {

    // ---- Rect bounds ----

    @Test
    void rectBoundsMatchRectCoords() {
        var cut = new Cutout.Rect(10, 20, 75, 100, null);
        BoundingBox b = cut.bounds();
        assertEquals(10, b.xMm(), 0.001);
        assertEquals(20, b.yMm(), 0.001);
        assertEquals(75, b.widthMm(), 0.001);
        assertEquals(100, b.heightMm(), 0.001);
        assertEquals(85, b.maxXMm(), 0.001);
        assertEquals(120, b.maxYMm(), 0.001);
    }

    @Test
    void rectThroughVsPartialDepth() {
        var through = new Cutout.Rect(0, 0, 10, 10, null);
        var pocket = new Cutout.Rect(0, 0, 10, 10, 5f);
        assertNull(through.depthMm(), "null depthMm signals a through-cut");
        assertEquals(5f, pocket.depthMm());
    }

    // ---- Circle bounds (stub variant — not yet user-accessible) ----

    @Test
    void circleBoundsInscribedSquare() {
        var cut = new Cutout.Circle(50, 50, 10, 7f);  // shelf-pin-ish
        BoundingBox b = cut.bounds();
        assertEquals(40, b.xMm(), 0.001);
        assertEquals(40, b.yMm(), 0.001);
        assertEquals(20, b.widthMm(), 0.001);
        assertEquals(20, b.heightMm(), 0.001);
    }

    // ---- Polygon bounds ----

    @Test
    void polygonBoundsCoverAllVertices() {
        var cut = new Cutout.Polygon(List.of(
                new Point2D(5, 5),
                new Point2D(25, 10),
                new Point2D(20, 30),
                new Point2D(3, 12)
        ), null);
        BoundingBox b = cut.bounds();
        assertEquals(3, b.xMm(), 0.001);
        assertEquals(5, b.yMm(), 0.001);
        assertEquals(22, b.widthMm(), 0.001);   // 25 - 3
        assertEquals(25, b.heightMm(), 0.001);  // 30 - 5
    }

    @Test
    void polygonWithNoVerticesDegradesGracefully() {
        // Empty vertex list is nonsense in practice but shouldn't throw —
        // model-layer code should be defensively tolerant.
        var cut = new Cutout.Polygon(List.of(), null);
        BoundingBox b = cut.bounds();
        assertEquals(0, b.widthMm(), 0.001);
        assertEquals(0, b.heightMm(), 0.001);
    }

    // ---- Spline bounds (control-point hull is a conservative upper bound) ----

    @Test
    void splineBoundsFromControlPoints() {
        var cut = new Cutout.Spline(List.of(
                new Point2D(0, 0),
                new Point2D(50, -10),
                new Point2D(80, 30),
                new Point2D(100, 0)
        ), null);
        BoundingBox b = cut.bounds();
        assertEquals(0, b.xMm(), 0.001);
        assertEquals(-10, b.yMm(), 0.001);
        assertEquals(100, b.widthMm(), 0.001);
        assertEquals(40, b.heightMm(), 0.001);
    }

    // ---- Part.cutouts — add / remove / independence ----

    @Test
    void newPartHasNoCutouts() {
        Part p = testPart("pL");
        assertTrue(p.getCutouts().isEmpty());
    }

    @Test
    void addCutoutAppendsToList() {
        Part p = testPart("pL");
        Cutout c = new Cutout.Rect(0, 0, 75, 75, null);
        p.addCutout(c);
        assertEquals(1, p.getCutouts().size());
        assertSame(c, p.getCutouts().get(0));
    }

    @Test
    void addMultipleCutoutsPreservesOrderAndIndependence() {
        Part p = testPart("pL");
        var a = new Cutout.Rect(0, 0, 10, 10, null);
        var b = new Cutout.Rect(50, 50, 20, 20, 5f);
        var c = new Cutout.Circle(100, 100, 3, 10f);
        p.addCutout(a);
        p.addCutout(b);
        p.addCutout(c);
        assertEquals(List.of(a, b, c), p.getCutouts());
    }

    @Test
    void removeCutoutDropsThatInstanceOnly() {
        Part p = testPart("pL");
        var a = new Cutout.Rect(0, 0, 10, 10, null);
        var b = new Cutout.Rect(50, 50, 20, 20, null);
        p.addCutout(a);
        p.addCutout(b);
        assertTrue(p.removeCutout(a));
        assertEquals(List.of(b), p.getCutouts());
    }

    @Test
    void removeCutoutReturnsFalseWhenAbsent() {
        Part p = testPart("pL");
        var c = new Cutout.Rect(0, 0, 10, 10, null);
        assertFalse(p.removeCutout(c), "remove on an absent cutout is a no-op");
    }

    @Test
    void getCutoutsReturnsUnmodifiableView() {
        Part p = testPart("pL");
        p.addCutout(new Cutout.Rect(0, 0, 10, 10, null));
        assertThrows(UnsupportedOperationException.class,
                () -> p.getCutouts().add(new Cutout.Rect(5, 5, 3, 3, null)),
                "external callers must go through addCutout / removeCutout");
    }

    @Test
    void twoPartsHaveIndependentCutoutLists() {
        // Builder.Default should create a fresh list per instance — catching
        // a classic bug where @Data / @Builder can accidentally share a
        // single default List across all built instances.
        Part a = testPart("a");
        Part b = testPart("b");
        a.addCutout(new Cutout.Rect(0, 0, 10, 10, null));
        assertEquals(1, a.getCutouts().size());
        assertEquals(0, b.getCutouts().size(),
                "a second Part must not share a's cutouts list");
    }

    // ---- helpers ----

    private static Part testPart(String name) {
        Material mat = MaterialCatalog.instance().get("plywood-3/4");
        return Part.builder()
                .name(name)
                .material(mat)
                .cutWidthMm(600)
                .cutHeightMm(900)
                .position(new Vector3f(0, 0, 0))
                .grainRequirement(GrainRequirement.ANY)
                .build();
    }
}
