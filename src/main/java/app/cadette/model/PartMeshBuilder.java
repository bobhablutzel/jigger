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
 * <h2>Algorithm — regions of constant Z, triangulated via JTS</h2>
 *
 * <p>The panel is modelled as a 2D polygon (in part-local coordinates).
 * Through-cut shapes are subtracted from this polygon via JTS Boolean ops; the
 * remaining region — possibly multi-polygon, possibly with holes — is the area
 * of the panel that has full-thickness material. We emit a top face at
 * {@code +halfT}, a bottom face at {@code -halfT}, and a vertical wall per
 * edge of the boundary.
 *
 * <p>JTS {@code PolygonTriangulator} (ear-clipping) handles polygon-with-holes
 * triangulation in a single call, which is the work the old rect-grid
 * decomposer was hand-rolling. After normalising to JTS canonical orientation
 * (CW exterior + CCW interior), both ring types put filled material on the
 * RIGHT of the walking direction, so the outward wall normal is uniformly the
 * left-hand perpendicular to each edge.
 *
 * <h2>Partial-depth pockets — deferred</h2>
 *
 * <p>Partial-depth cutouts ({@code depthMm != null}) are silently skipped in
 * this commit. They will be reintroduced in a follow-up using the same
 * region-partition machinery: each pocket splits the affected region into
 * sub-regions with their own (top, bottom) Z extents, walls bridge any
 * Z-discrepancy at boundaries.
 */
public final class PartMeshBuilder {

    private static final GeometryFactory GF = new GeometryFactory();

    private PartMeshBuilder() {}

    public static Mesh build(Part part) {
        return build(part.getCutWidthMm(), part.getCutHeightMm(),
                part.getThicknessMm(), part.getCutouts());
    }

    /**
     * Build directly from raw dimensions + a cutout list — used by tests, and
     * by SceneManager when merging joint-inferred cutouts with the part's
     * explicit ones before mesh construction.
     */
    public static Mesh build(float widthMm, float heightMm, float thicknessMm,
                             List<Cutout> cutouts) {
        Geometry hasMaterial = rectPolygon(0, 0, widthMm, heightMm);
        for (Cutout c : cutouts) {
            Polygon hole = throughCutPolygon(c);
            if (hole == null) continue;  // partial-depth or non-rect: deferred
            hasMaterial = hasMaterial.difference(hole);
            if (hasMaterial.isEmpty()) break;
        }
        // JTS's canonical orientation is CW exterior + CCW interior; difference()
        // produces this form, but a freshly-built polygon (no-cutout case) keeps
        // its input winding. Normalise so emit code can assume one convention.
        hasMaterial.normalize();

        float halfW = widthMm * 0.5f;
        float halfH = heightMm * 0.5f;
        float halfT = thicknessMm * 0.5f;

        MeshBuf buf = new MeshBuf();
        forEachPolygon(hasMaterial, p -> {
            emitFaces(buf, p, halfW, halfH, halfT);
            emitWalls(buf, p, halfW, halfH, halfT);
        });
        return buf.toMesh();
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

    /**
     * Returns a JTS polygon for a through-cut, or null if the cutout is
     * partial-depth, non-rect, or not yet supported. JTS handles
     * out-of-bounds clipping during the difference operation, so we don't
     * need to clip here.
     */
    private static Polygon throughCutPolygon(Cutout c) {
        if (!(c instanceof Cutout.Rect r)) return null;  // circle/polygon/spline: TODO
        if (r.depthMm() != null) return null;            // partial-depth: TODO
        return rectPolygon(r.xMm(), r.yMm(), r.widthMm(), r.heightMm());
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
     * Triangulate the polygon (with any holes) once via JTS ear-clipping;
     * emit each triangle as a top face at {@code +halfT} and a bottom face at
     * {@code -halfT}.
     *
     * <p>The triangulator output follows the input polygon's orientation
     * (CW from above, since we normalised to JTS canonical). The top face
     * needs CCW from above for its +Z normal, so emit reversed (a, c, b).
     * The bottom face needs CW from above (= CCW from below) for its -Z
     * normal, so emit original (a, b, c).
     */
    private static void emitFaces(MeshBuf buf, Polygon p,
                                  float halfW, float halfH, float halfT) {
        Geometry tris = PolygonTriangulator.triangulate(p);
        for (int i = 0; i < tris.getNumGeometries(); i++) {
            Polygon tri = (Polygon) tris.getGeometryN(i);
            Coordinate[] cs = tri.getExteriorRing().getCoordinates();
            float ax = (float) cs[0].x - halfW, ay = (float) cs[0].y - halfH;
            float bx = (float) cs[1].x - halfW, by = (float) cs[1].y - halfH;
            float cx = (float) cs[2].x - halfW, cy = (float) cs[2].y - halfH;
            buf.addTriangle(
                    ax, ay, halfT, cx, cy, halfT, bx, by, halfT,
                    0, 0, 1);
            buf.addTriangle(
                    ax, ay, -halfT, bx, by, -halfT, cx, cy, -halfT,
                    0, 0, -1);
        }
    }

    // ---- Walls -----------------------------------------------------------

    /**
     * One vertical wall per edge of every ring (exterior + any holes). In JTS
     * canonical orientation (CW exterior + CCW interior), both ring types put
     * filled material on the RIGHT of the walking direction, so the outward
     * normal is uniformly the left-hand perpendicular.
     */
    private static void emitWalls(MeshBuf buf, Polygon p,
                                  float halfW, float halfH, float halfT) {
        emitRingWalls(buf, p.getExteriorRing(), halfW, halfH, halfT);
        for (int i = 0; i < p.getNumInteriorRing(); i++) {
            emitRingWalls(buf, p.getInteriorRingN(i), halfW, halfH, halfT);
        }
    }

    private static void emitRingWalls(MeshBuf buf, LinearRing ring,
                                      float halfW, float halfH, float halfT) {
        Coordinate[] cs = ring.getCoordinates();
        for (int i = 0; i < cs.length - 1; i++) {
            float x1 = (float) cs[i].x     - halfW;
            float y1 = (float) cs[i].y     - halfH;
            float x2 = (float) cs[i + 1].x - halfW;
            float y2 = (float) cs[i + 1].y - halfH;
            float dx = x2 - x1, dy = y2 - y1;
            float len = (float) Math.hypot(dx, dy);
            if (len < 1e-7f) continue;
            // Left-hand perpendicular = outward normal (filled material on the right).
            float nx = -dy / len;
            float ny =  dx / len;
            // Quad CCW from the +normal side. Walking p2 → p1 along the bottom,
            // up to p1's top, across to p2's top: this puts the geometric normal
            // on the LEFT of the original (p1 → p2) edge direction, matching
            // (nx, ny).
            buf.addQuad(
                    x2, y2, -halfT,
                    x1, y1, -halfT,
                    x1, y1,  halfT,
                    x2, y2,  halfT,
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
