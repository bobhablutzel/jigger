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

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds a jME3 {@link Mesh} for a {@link Part}, respecting any through-cut
 * {@link Cutout.Rect}s attached to it. The resulting mesh is centered at the
 * origin with dimensions {@code (cutWidthMm × cutHeightMm × thicknessMm)},
 * matching jME3's {@code Box} primitive convention so downstream scene
 * placement is unchanged.
 *
 * <h2>Algorithm (rect decomposition)</h2>
 *
 * <p>The cut face is a rectangle with some rectangular holes. We build a
 * grid whose x-coordinates are the sorted distinct x-edges of the part and
 * all cutouts (clipped to the part), and likewise for y. Every cell in the
 * grid is either entirely inside a cutout (removed) or entirely outside all
 * cutouts (kept). For each kept cell we emit a top quad, a bottom quad, and
 * a wall on each of its four edges whose neighbour cell is <em>not</em> kept.
 *
 * <p>This handles three cases uniformly:
 * <ul>
 *   <li>No cutouts — the grid is a single cell, produces the same 6-face box
 *       as jME3's Box primitive.</li>
 *   <li>Interior cutouts — kept cells ring the cutout, and the boundary walls
 *       between kept and removed cells become the cutout's interior walls.</li>
 *   <li>Cutouts touching or extending past an edge — clipping collapses the
 *       external portion to nothing, and the resulting "notch" in the outer
 *       boundary is emitted correctly because the missing cells turn the
 *       outer wall into a U-shape automatically.</li>
 * </ul>
 *
 * <p><strong>Phase E3a scope:</strong> Only through-cuts ({@code depthMm == null})
 * affect geometry. Partial-depth cutouts are ignored here and will be handled
 * in E3b — they need pocket geometry (inset floor + pocket walls) that this
 * builder doesn't emit. The cut list already shows them either way.
 *
 * <p>Non-{@link Cutout.Rect} variants (circle, polygon, spline) are ignored
 * entirely — they aren't reachable through the grammar yet and will get
 * proper triangulation when they are.
 */
public final class PartMeshBuilder {

    private PartMeshBuilder() {}

    public static Mesh build(Part part) {
        return build(part.getCutWidthMm(), part.getCutHeightMm(),
                part.getThicknessMm(), part.getCutouts());
    }

    /** Overload used by tests — no Part required, just raw dimensions + cutouts. */
    static Mesh build(float widthMm, float heightMm, float thicknessMm,
                      List<Cutout> cutouts) {
        // Only through-cut Rects participate in E3a geometry. Clip each
        // to the part bounds; any cutout that ends up with zero area is
        // dropped.
        List<Cutout.Rect> throughRects = new ArrayList<>();
        for (Cutout c : cutouts) {
            if (!(c instanceof Cutout.Rect r)) continue;
            if (r.depthMm() != null) continue;  // E3b will render pockets
            float x0 = Math.max(0, r.xMm());
            float y0 = Math.max(0, r.yMm());
            float x1 = Math.min(widthMm, r.xMm() + r.widthMm());
            float y1 = Math.min(heightMm, r.yMm() + r.heightMm());
            if (x1 > x0 && y1 > y0) {
                throughRects.add(new Cutout.Rect(x0, y0, x1 - x0, y1 - y0, null));
            }
        }

        // Grid coordinates — always include the part edges so the outer
        // boundary is a grid line, then every cutout edge (clipped above).
        float[] xs = distinctEdges(widthMm, throughRects, true);
        float[] ys = distinctEdges(heightMm, throughRects, false);
        int nx = xs.length - 1;
        int ny = ys.length - 1;

        // kept[i][j] = whether the (i,j) cell is retained (not inside any cutout).
        boolean[][] kept = new boolean[nx][ny];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                float cx = (xs[i] + xs[i + 1]) * 0.5f;
                float cy = (ys[j] + ys[j + 1]) * 0.5f;
                kept[i][j] = !pointInsideAnyCutout(cx, cy, throughRects);
            }
        }

        MeshBuf buf = new MeshBuf();
        float halfW = widthMm * 0.5f;
        float halfH = heightMm * 0.5f;
        float halfT = thicknessMm * 0.5f;

        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                if (!kept[i][j]) continue;
                float x0 = xs[i] - halfW, x1 = xs[i + 1] - halfW;
                float y0 = ys[j] - halfH, y1 = ys[j + 1] - halfH;

                // Top face (+Z) and bottom face (-Z).
                buf.addQuad(
                        x0, y0, halfT,  x1, y0, halfT,  x1, y1, halfT,  x0, y1, halfT,
                        0, 0, 1);
                buf.addQuad(
                        x0, y1, -halfT, x1, y1, -halfT, x1, y0, -halfT, x0, y0, -halfT,
                        0, 0, -1);

                // Walls — one per cell edge whose neighbour is cut (or off-grid).
                // Left edge (x = x0, neighbour at i-1). Normal points -X.
                if (!isKept(kept, i - 1, j, nx, ny)) {
                    buf.addQuad(
                            x0, y0, -halfT, x0, y1, -halfT, x0, y1, halfT, x0, y0, halfT,
                            -1, 0, 0);
                }
                // Right edge (x = x1). Normal +X.
                if (!isKept(kept, i + 1, j, nx, ny)) {
                    buf.addQuad(
                            x1, y0, halfT, x1, y1, halfT, x1, y1, -halfT, x1, y0, -halfT,
                            1, 0, 0);
                }
                // Bottom edge (y = y0). Normal -Y.
                if (!isKept(kept, i, j - 1, nx, ny)) {
                    buf.addQuad(
                            x0, y0, halfT, x1, y0, halfT, x1, y0, -halfT, x0, y0, -halfT,
                            0, -1, 0);
                }
                // Top edge (y = y1). Normal +Y.
                if (!isKept(kept, i, j + 1, nx, ny)) {
                    buf.addQuad(
                            x0, y1, -halfT, x1, y1, -halfT, x1, y1, halfT, x0, y1, halfT,
                            0, 1, 0);
                }
            }
        }

        return buf.toMesh();
    }

    // ---- Helpers ----

    private static boolean isKept(boolean[][] kept, int i, int j, int nx, int ny) {
        if (i < 0 || j < 0 || i >= nx || j >= ny) return false;
        return kept[i][j];
    }

    private static boolean pointInsideAnyCutout(float x, float y, List<Cutout.Rect> rects) {
        for (Cutout.Rect r : rects) {
            if (x >= r.xMm() && x <= r.xMm() + r.widthMm()
                    && y >= r.yMm() && y <= r.yMm() + r.heightMm()) {
                return true;
            }
        }
        return false;
    }

    /** Sorted unique x- (or y-) coordinates from part edges and cutout edges. */
    private static float[] distinctEdges(float partSize, List<Cutout.Rect> rects, boolean xAxis) {
        Set<Float> coords = new TreeSet<>();
        coords.add(0f);
        coords.add(partSize);
        for (Cutout.Rect r : rects) {
            if (xAxis) {
                coords.add(r.xMm());
                coords.add(r.xMm() + r.widthMm());
            } else {
                coords.add(r.yMm());
                coords.add(r.yMm() + r.heightMm());
            }
        }
        float[] out = new float[coords.size()];
        int i = 0;
        for (float c : coords) out[i++] = c;
        return out;
    }

    /**
     * Accumulator for vertex / normal / index data. Each face uses distinct
     * vertices so jME3's lighting can apply a flat per-face normal — matches
     * how the built-in Box primitive handles its 24 vertices.
     */
    private static final class MeshBuf {
        private final List<Float> positions = new ArrayList<>();
        private final List<Float> normals = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();

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
            // Quad split: (a, b, c) + (a, c, d). CCW in the +normal direction.
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
            m.setBuffer(VertexBuffer.Type.Normal, 3, normBuf);
            m.setBuffer(VertexBuffer.Type.Index, 3, idxBuf);
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
