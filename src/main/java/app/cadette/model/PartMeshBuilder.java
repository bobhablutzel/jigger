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

package app.cadette.model;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import org.locationtech.jts.triangulate.polygon.PolygonTriangulator;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds a jME3 {@link Mesh} for a {@link Part}, respecting any
 * {@link Cutout}s attached to it. The resulting mesh is centred at the origin
 * with dimensions {@code (cutWidthMm × cutHeightMm × thicknessMm)}, matching
 * jME3's {@code Box} primitive convention so downstream scene placement is
 * unchanged.
 *
 * <h2>Algorithm — partition into regions of constant Z, triangulate via JTS</h2>
 *
 * <ol>
 *   <li><b>Through-cuts:</b> subtract their polygons from the panel rectangle
 *       via JTS Boolean difference. The result is the area of the panel that
 *       has any material at all (in some Z range).</li>
 *
 *   <li><b>Pockets:</b> overlay each partial-depth pocket on the existing
 *       region partition. For each pocket, every region either has no
 *       intersection (unchanged), is wholly inside the pocket (its (topZ,
 *       bottomZ) extents collapse toward the pocket floor), or splits into
 *       overlap + leftover sub-regions. Front pockets lower {@code topZ};
 *       back pockets raise {@code bottomZ}. Overlapping pockets at different
 *       depths resolve naturally via min/max — the deeper one wins because
 *       it pulls the floor further from the panel surface.</li>
 *
 *   <li><b>Front+back collision:</b> a region whose front and back pockets
 *       meet has {@code topZ <= bottomZ} — no material left. Drop it; its
 *       boundary becomes equivalent to a through-cut for neighbour regions.</li>
 *
 *   <li><b>Mesh emission:</b> each surviving region emits a top face at its
 *       {@code topZ}, a bottom face at its {@code bottomZ}, and a vertical
 *       wall per ring edge spanning {@code (bottomZ, topZ)}.</li>
 * </ol>
 *
 * <p>JTS canonical orientation (CW exterior + CCW interior, set via
 * {@code .normalize()}) puts filled material on the RIGHT of the walking
 * direction in either ring type, so the outward wall normal is uniformly the
 * left-hand perpendicular.
 *
 * <h2>Internal walls and visual rendering</h2>
 *
 * <p>At a boundary between two regions with different (topZ, bottomZ), each
 * region emits its own wall over its full Z range. The portion where both
 * regions have material is "internal" — both walls coincide spatially with
 * opposite normals. With back-face culling these don't Z-fight: each wall is
 * front-face from one side only, and the side with material occludes the view
 * of either wall from outside the panel. The portion where only one region
 * has material is the visible cliff (e.g. the pocket-floor-to-panel-top
 * transition), drawn by the taller region's wall.
 *
 * <p>Trade-off: this emits more triangles than the rect-grid algorithm did
 * (which computed wall ranges from band discrepancies). For the algorithm's
 * simplicity it's an acceptable cost — the volume is correct (internal walls
 * cancel by sign) and rendering is correct (occlusion + culling).
 *
 * <h2>Cutout shapes</h2>
 *
 * <p>All five {@link Cutout} shape variants ({@link Cutout.Rect},
 * {@link Cutout.Circle}, {@link Cutout.Polygon}, {@link Cutout.Spline},
 * {@link Cutout.Curve}) are supported. Circles sample to
 * {@value #CIRCLE_SEGMENTS}-segment polygons. Splines tessellate via
 * {@link SplineTessellator}. Curves tessellate as periodic cubic Beziers via
 * de Casteljau with {@value #CURVE_SAMPLES_PER_SEGMENT} samples per segment.
 */
public final class PartMeshBuilder {

    private static final GeometryFactory GF = new GeometryFactory();

    /** Number of segments used to approximate a circular cutout boundary. */
    private static final int CIRCLE_SEGMENTS = 32;

    /** Samples per segment when tessellating a cubic Bezier curve. */
    private static final int CURVE_SAMPLES_PER_SEGMENT = 16;

    private PartMeshBuilder() {}

    public static Mesh build(Part part) {
        return build(part.getCutWidthMm(), part.getCutHeightMm(),
                part.getThicknessMm(), part.getCutouts(), part.getKeeps());
    }

    /**
     * Build directly from raw dimensions + a cutout list — used by tests, and
     * by SceneManager when merging joint-inferred cutouts with the part's
     * explicit ones before mesh construction. No keep regions in this path.
     */
    public static Mesh build(float widthMm, float heightMm, float thicknessMm,
                             List<Cutout> cutouts) {
        return build(widthMm, heightMm, thicknessMm, cutouts, List.of());
    }

    /**
     * Full build with both cuts and keeps. Keeps are intersected with the
     * panel before cuts are subtracted — the panel becomes
     * {@code (panel ∩ keep_1 ∩ keep_2 ∩ ...) - cut_1 - cut_2 - ...}.
     */
    public static Mesh build(float widthMm, float heightMm, float thicknessMm,
                             List<Cutout> cutouts, List<Cutout> keeps) {
        float halfW = widthMm * 0.5f;
        float halfH = heightMm * 0.5f;
        float halfT = thicknessMm * 0.5f;

        Geometry hasMaterial = rectPolygon(0, 0, widthMm, heightMm);

        // Step 0: intersect with keep regions. Keeps constrain the panel
        // outline before any cuts are applied. v1 ignores keep depthMm —
        // through only ("relief carving" partial-depth keeps are deferred).
        for (Cutout k : keeps) {
            Polygon shape = polygonize(k);
            if (shape == null) continue;
            hasMaterial = robustIntersection(hasMaterial, shape);
            if (hasMaterial.isEmpty()) break;
        }

        // Step 1: subtract through-cuts. JTS clips out-of-bounds pieces.
        for (Cutout c : cutouts) {
            if (c.depthMm() != null) continue;       // partial-depth handled in step 2
            Polygon hole = polygonize(c);
            if (hole == null) continue;              // unsupported shape
            hasMaterial = robustDifference(hasMaterial, hole);
            if (hasMaterial.isEmpty()) break;
        }
        // JTS canonical: CW exterior + CCW interior. difference() produces this
        // form; freshly-built polygons keep their input winding. Normalise so
        // the emit code can assume one convention.
        hasMaterial.normalize();

        // Step 2: start with each remaining polygon as a full-thickness region,
        // then overlay each partial-depth pocket.
        List<Region> regions = new ArrayList<>();
        forEachPolygon(hasMaterial, p -> regions.add(new Region(p, halfT, -halfT)));
        for (Cutout c : cutouts) {
            if (c.depthMm() == null) continue;       // through-cut already applied
            Polygon pocket = polygonize(c);
            if (pocket == null) continue;            // unsupported shape
            applyPocket(regions, pocket, c.depthMm(), c.face(), halfT);
        }

        // Step 3: drop regions where front+back pockets met (no material left).
        regions.removeIf(rg -> rg.topZ - rg.bottomZ < 1e-7f);

        // Step 4: emit faces and walls for each surviving region.
        MeshBuf buf = new MeshBuf();
        for (Region rg : regions) {
            emitFaces(buf, rg, halfW, halfH);
            emitWalls(buf, rg, halfW, halfH);
        }
        return buf.toMesh();
    }

    // ---- Region partitioning --------------------------------------------

    /**
     * A maximal sub-area of the panel with constant solid material extents:
     * material exists for {@code z ∈ [bottomZ, topZ]} everywhere within
     * {@code polygon}. Through-cut areas are absent entirely (no region
     * covers them); front+back collisions show up as collapsed regions
     * with {@code topZ <= bottomZ} and are dropped before emit.
     */
    private record Region(Polygon polygon, float topZ, float bottomZ) {}

    /**
     * Splits each existing region by the pocket polygon. The intersection
     * gets the pocket's contribution to its top/bottom Z extent (front pocket
     * lowers {@code topZ}, back pocket raises {@code bottomZ}); the leftover
     * keeps the original extents.
     *
     * <p>Overlap with deeper pockets resolves naturally: a region that's
     * already been pulled to {@code topZ = halfT - 8} by a deep pocket stays
     * there when a shallow pocket (depth 3) is later applied, since
     * {@code min(halfT - 8, halfT - 3) = halfT - 8}.
     */
    private static void applyPocket(List<Region> regions, Polygon pocket,
                                    float depth, Cutout.Face face, float halfT) {
        boolean isFront = face == Cutout.Face.FRONT;
        List<Region> next = new ArrayList<>(regions.size() + 4);
        for (Region rg : regions) {
            Geometry overlap = robustIntersection(rg.polygon, pocket);
            Geometry leftover = robustDifference(rg.polygon, pocket);
            forEachPolygon(overlap, p -> {
                float newTop = isFront ? Math.min(rg.topZ, halfT - depth) : rg.topZ;
                float newBot = isFront ? rg.bottomZ : Math.max(rg.bottomZ, -halfT + depth);
                next.add(new Region(p, newTop, newBot));
            });
            forEachPolygon(leftover, p -> next.add(new Region(p, rg.topZ, rg.bottomZ)));
        }
        regions.clear();
        regions.addAll(next);
    }

    // ---- Robust overlay --------------------------------------------------

    /**
     * Boolean difference using {@link OverlayNGRobust}, which retries with
     * progressively coarser snapping when the legacy overlay's noding can't
     * resolve an intersection — important for cutouts with extreme aspect
     * ratios (a thin sliver triangle straddling a panel edge will explode in
     * legacy {@code Geometry.difference()} but works under OverlayNG).
     */
    private static Geometry robustDifference(Geometry a, Geometry b) {
        return OverlayNGRobust.overlay(a, b, OverlayNG.DIFFERENCE);
    }

    private static Geometry robustIntersection(Geometry a, Geometry b) {
        return OverlayNGRobust.overlay(a, b, OverlayNG.INTERSECTION);
    }

    // ---- Public command-time validation ---------------------------------

    /**
     * Compute the part's 2D outline after all keeps are intersected and all
     * through-cuts are subtracted. Partial-depth cutouts are <em>not</em>
     * applied — they're pockets that don't change the silhouette. The
     * result is the JTS geometry the cut sheet should render as a solid
     * "saw along here" boundary.
     *
     * <p>Returns a {@code Polygon} or {@code MultiPolygon} (possibly with
     * interior rings = holes from through-cuts). Empty geometry if all
     * material was removed.
     */
    public static Geometry computeFinalProfile(float widthMm, float heightMm,
                                               List<Cutout> cutouts, List<Cutout> keeps) {
        Geometry hasMaterial = rectPolygon(0, 0, widthMm, heightMm);
        for (Cutout k : keeps) {
            Polygon shape = polygonize(k);
            if (shape == null) continue;
            hasMaterial = robustIntersection(hasMaterial, shape);
            if (hasMaterial.isEmpty()) break;
        }
        for (Cutout c : cutouts) {
            if (c.depthMm() != null) continue;       // pocket — silhouette unchanged
            Polygon hole = polygonize(c);
            if (hole == null) continue;
            hasMaterial = robustDifference(hasMaterial, hole);
            if (hasMaterial.isEmpty()) break;
        }
        hasMaterial.normalize();
        return hasMaterial;
    }

    /**
     * The discarded region — the part rect minus the final profile. May be
     * a Polygon (single contiguous waste), a MultiPolygon (multiple disjoint
     * waste pieces, e.g. two strips on either side of a handle), or empty
     * (nothing discarded). Returns geometry in part-local mm.
     *
     * <p>Cut-sheet renderers iterate the components to place X markers
     * per-waste-piece, with a minimum-size threshold to skip slivers
     * (dado walls, kerf-thin strips) where an X would be invisible.
     */
    public static org.locationtech.jts.geom.Geometry computeDiscard(
            float widthMm, float heightMm, org.locationtech.jts.geom.Geometry profile) {
        org.locationtech.jts.geom.Geometry partRectGeom = rectPolygon(0, 0, widthMm, heightMm);
        if (profile.isEmpty()) return partRectGeom;
        return robustDifference(partRectGeom, profile);
    }

    /**
     * Returns true iff the cutout's actual shape (not just its bbox) overlaps
     * the panel face. Replaces a coarse bbox check that let polygons and
     * splines slip through when their bbox crossed the panel but the shape
     * itself didn't — those cuts then either silently produced no mesh
     * change or threw a JTS topology exception under extreme aspect ratios.
     */
    public static boolean intersectsPanelFace(Cutout c, float widthMm, float heightMm) {
        Polygon hole = polygonize(c);
        if (hole == null) return false;
        return hole.intersects(rectPolygon(0, 0, widthMm, heightMm));
    }

    // ---- Polygonisation --------------------------------------------------

    private static Polygon rectPolygon(float x, float y, float w, float h) {
        return GF.createPolygon(new Coordinate[] {
                new Coordinate(x,     y),
                new Coordinate(x + w, y),
                new Coordinate(x + w, y + h),
                new Coordinate(x,     y + h),
                new Coordinate(x,     y),
        });
    }

    private static Polygon circlePolygon(float cx, float cy, float r) {
        Coordinate[] cs = new Coordinate[CIRCLE_SEGMENTS + 1];
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            cs[i] = new Coordinate(cx + r * Math.cos(angle), cy + r * Math.sin(angle));
        }
        cs[CIRCLE_SEGMENTS] = cs[0];
        return GF.createPolygon(cs);
    }

    /**
     * JTS polygon for a cutout's footprint, or null for shape variants not yet
     * supported (Polygon, Spline). JTS handles out-of-bounds clipping during
     * the difference op, so we don't need to clip here.
     */
    private static Polygon polygonize(Cutout c) {
        return switch (c) {
            case Cutout.Rect r    -> rectPolygon(r.xMm(), r.yMm(), r.widthMm(), r.heightMm());
            case Cutout.Circle ci -> circlePolygon(ci.cxMm(), ci.cyMm(), ci.radiusMm());
            case Cutout.Polygon p -> polygonFromVertices(p.vertices());
            case Cutout.Spline s  -> polygonFromVertices(SplineTessellator.tessellateCatmullRom(s.controlPoints()));
            case Cutout.Curve cv  -> polygonFromVertices(tessellateBezier(cv.controlPoints()));
        };
    }

    /**
     * Tessellate a periodic cubic Bezier curve into a polygon vertex list.
     * Control points come in groups of 3: each group {@code (P[3i], P[3i+1],
     * P[3i+2])} plus the next group's start {@code P[3(i+1) mod 3N]} forms
     * a cubic segment. With N segments the curve closes back to P[0]
     * naturally — no straight closing chord is appended.
     *
     * <p>Evaluation uses de Casteljau (numerically robust, no power-basis
     * arithmetic). {@value #CURVE_SAMPLES_PER_SEGMENT} samples per segment;
     * for a quarter-arc with kappa control points this is well under
     * one-pixel chord error at any cabinet-rendering scale.
     */
    private static List<Point2D> tessellateBezier(List<Point2D> control) {
        int n = control.size();
        if (n < 6 || n % 3 != 0) return control;  // visitor validates; defense in depth
        int segments = n / 3;
        List<Point2D> result = new ArrayList<>(segments * CURVE_SAMPLES_PER_SEGMENT);
        for (int i = 0; i < segments; i++) {
            Point2D p0 = control.get(3 * i);
            Point2D p1 = control.get(3 * i + 1);
            Point2D p2 = control.get(3 * i + 2);
            Point2D p3 = control.get((3 * (i + 1)) % n);
            for (int j = 0; j < CURVE_SAMPLES_PER_SEGMENT; j++) {
                float t = (float) j / CURVE_SAMPLES_PER_SEGMENT;
                result.add(deCasteljau(p0, p1, p2, p3, t));
            }
        }
        return result;
    }

    /** de Casteljau evaluation of a cubic Bezier at parameter t ∈ [0, 1]. */
    private static Point2D deCasteljau(Point2D p0, Point2D p1, Point2D p2, Point2D p3, float t) {
        Point2D q0 = lerp(p0, p1, t);
        Point2D q1 = lerp(p1, p2, t);
        Point2D q2 = lerp(p2, p3, t);
        Point2D r0 = lerp(q0, q1, t);
        Point2D r1 = lerp(q1, q2, t);
        return lerp(r0, r1, t);
    }

    private static Point2D lerp(Point2D a, Point2D b, float t) {
        return new Point2D(
                a.xMm() + (b.xMm() - a.xMm()) * t,
                a.yMm() + (b.yMm() - a.yMm()) * t);
    }

    /**
     * JTS polygon from a vertex list. Auto-closes if the caller didn't repeat
     * the first vertex at the end. Degenerate inputs (fewer than 3 distinct
     * points) collapse to a zero-area polygon and JTS will discard them
     * harmlessly during the difference op.
     */
    private static Polygon polygonFromVertices(List<Point2D> vertices) {
        if (vertices.size() < 3) return null;
        boolean alreadyClosed =
                Math.abs(vertices.get(0).xMm() - vertices.get(vertices.size() - 1).xMm()) < 1e-6f
                && Math.abs(vertices.get(0).yMm() - vertices.get(vertices.size() - 1).yMm()) < 1e-6f;
        int n = vertices.size() + (alreadyClosed ? 0 : 1);
        Coordinate[] cs = new Coordinate[n];
        for (int i = 0; i < vertices.size(); i++) {
            cs[i] = new Coordinate(vertices.get(i).xMm(), vertices.get(i).yMm());
        }
        if (!alreadyClosed) cs[n - 1] = cs[0];
        return GF.createPolygon(cs);
    }

    private static void forEachPolygon(Geometry g, Consumer<Polygon> fn) {
        if (g.isEmpty()) return;
        if (g instanceof Polygon p) {
            fn.accept(p);
            return;
        }
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry sub = g.getGeometryN(i);
            if (sub instanceof Polygon p) fn.accept(p);
        }
    }

    // ---- Faces -----------------------------------------------------------

    /**
     * Triangulate the region's polygon; emit each triangle as a top face at
     * {@code topZ} and a bottom face at {@code bottomZ}.
     *
     * <p>Triangulator output follows the input polygon's orientation (CW from
     * above, since we normalised to JTS canonical). The top face needs CCW
     * from above for its +Z normal, so emit reversed (a, c, b). The bottom
     * face needs CW from above (= CCW from below) for its -Z normal, so emit
     * original (a, b, c).
     */
    private static void emitFaces(MeshBuf buf, Region rg, float halfW, float halfH) {
        Geometry tris = PolygonTriangulator.triangulate(rg.polygon);
        for (int i = 0; i < tris.getNumGeometries(); i++) {
            Polygon tri = (Polygon) tris.getGeometryN(i);
            Coordinate[] cs = tri.getExteriorRing().getCoordinates();
            float ax = (float) cs[0].x - halfW, ay = (float) cs[0].y - halfH;
            float bx = (float) cs[1].x - halfW, by = (float) cs[1].y - halfH;
            float cx = (float) cs[2].x - halfW, cy = (float) cs[2].y - halfH;
            buf.addTriangle(
                    ax, ay, rg.topZ, cx, cy, rg.topZ, bx, by, rg.topZ,
                    0, 0, 1);
            buf.addTriangle(
                    ax, ay, rg.bottomZ, bx, by, rg.bottomZ, cx, cy, rg.bottomZ,
                    0, 0, -1);
        }
    }

    // ---- Walls -----------------------------------------------------------

    /**
     * One vertical wall per edge of every ring (exterior + any holes), spanning
     * the region's full {@code (bottomZ, topZ)} range with the outward normal
     * (left-hand perpendicular of the walking direction in JTS canonical).
     */
    private static void emitWalls(MeshBuf buf, Region rg, float halfW, float halfH) {
        emitRingWalls(buf, rg.polygon.getExteriorRing(), rg, halfW, halfH);
        for (int i = 0; i < rg.polygon.getNumInteriorRing(); i++) {
            emitRingWalls(buf, rg.polygon.getInteriorRingN(i), rg, halfW, halfH);
        }
    }

    private static void emitRingWalls(MeshBuf buf, LinearRing ring, Region rg,
                                      float halfW, float halfH) {
        Coordinate[] cs = ring.getCoordinates();
        for (int i = 0; i < cs.length - 1; i++) {
            float x1 = (float) cs[i].x     - halfW;
            float y1 = (float) cs[i].y     - halfH;
            float x2 = (float) cs[i + 1].x - halfW;
            float y2 = (float) cs[i + 1].y - halfH;
            float dx = x2 - x1, dy = y2 - y1;
            float len = (float) Math.hypot(dx, dy);
            if (len < 1e-7f) continue;
            float nx = -dy / len;
            float ny =  dx / len;
            // CCW from the +normal side: walk p2 → p1 across the bottom, then
            // up to p1's top, then across to p2's top.
            buf.addQuad(
                    x2, y2, rg.bottomZ,
                    x1, y1, rg.bottomZ,
                    x1, y1, rg.topZ,
                    x2, y2, rg.topZ,
                    nx, ny, 0);
        }
    }

    // ---- Mesh accumulator ------------------------------------------------

    /**
     * Accumulator for vertex / normal / index data. Each face uses distinct
     * vertices so jME3's lighting can apply a flat per-face normal — matches
     * how the built-in Box primitive handles its 24 vertices.
     */
    private static final class MeshBuf {
        private final List<Float> positions = new ArrayList<>();
        private final List<Float> normals   = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();

        void addTriangle(float ax, float ay, float az,
                         float bx, float by, float bz,
                         float cx, float cy, float cz,
                         float nx, float ny, float nz) {
            int base = positions.size() / 3;
            addVertex(ax, ay, az, nx, ny, nz);
            addVertex(bx, by, bz, nx, ny, nz);
            addVertex(cx, cy, cz, nx, ny, nz);
            indices.add(base);     indices.add(base + 1); indices.add(base + 2);
        }

        void addQuad(float ax, float ay, float az,
                     float bx, float by, float bz,
                     float cx, float cy, float cz,
                     float dx, float dy, float dz,
                     float nx, float ny, float nz) {
            int base = positions.size() / 3;
            addVertex(ax, ay, az, nx, ny, nz);
            addVertex(bx, by, bz, nx, ny, nz);
            addVertex(cx, cy, cz, nx, ny, nz);
            addVertex(dx, dy, dz, nx, ny, nz);
            indices.add(base);     indices.add(base + 1); indices.add(base + 2);
            indices.add(base);     indices.add(base + 2); indices.add(base + 3);
        }

        private void addVertex(float x, float y, float z, float nx, float ny, float nz) {
            positions.add(x); positions.add(y); positions.add(z);
            normals.add(nx); normals.add(ny); normals.add(nz);
        }

        Mesh toMesh() {
            Mesh m = new Mesh();
            FloatBuffer posBuf = BufferUtils.createFloatBuffer(toFloatArray(positions));
            FloatBuffer normBuf = BufferUtils.createFloatBuffer(toFloatArray(normals));
            IntBuffer idxBuf = BufferUtils.createIntBuffer(toIntArray(indices));
            m.setBuffer(VertexBuffer.Type.Position, 3, posBuf);
            m.setBuffer(VertexBuffer.Type.Normal,   3, normBuf);
            m.setBuffer(VertexBuffer.Type.Index,    3, idxBuf);
            m.updateBound();
            return m;
        }

        private static float[] toFloatArray(List<Float> list) {
            float[] a = new float[list.size()];
            for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
            return a;
        }

        private static int[] toIntArray(List<Integer> list) {
            int[] a = new int[list.size()];
            for (int i = 0; i < list.size(); i++) a[i] = list.get(i);
            return a;
        }
    }
}
