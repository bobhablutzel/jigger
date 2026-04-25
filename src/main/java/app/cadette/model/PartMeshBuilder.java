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
 * Builds a jME3 {@link Mesh} for a {@link Part}, respecting any rectangular
 * {@link Cutout.Rect}s attached to it — both through-cuts (full thickness)
 * and partial-depth pockets. The resulting mesh is centred at the origin with
 * dimensions {@code (cutWidthMm × cutHeightMm × thicknessMm)}, matching jME3's
 * {@code Box} primitive convention so downstream scene placement is unchanged.
 *
 * <h2>Algorithm (rect decomposition)</h2>
 *
 * <p>Every cutout edge (clipped to the part) contributes to a uniform grid
 * laid over the cut face. Each cell in the grid is classified by its
 * "top-solid Z" — the height to which the solid material extends above the
 * bottom face:
 *
 * <ul>
 *   <li>{@code +halfT} — fully solid (no cutout covers this cell).</li>
 *   <li>{@code +halfT − depth} — covered by a partial-depth pocket.</li>
 *   <li>{@code null} — covered by a through-cut; no material at all.</li>
 * </ul>
 *
 * <p>Where partial and through cuts overlap, through wins (removes all
 * material). Where two pockets of different depths overlap, the deeper one
 * wins (its floor is lower).
 *
 * <p>The mesh assembles in two passes:
 *
 * <ol>
 *   <li><em>Faces:</em> each non-through cell emits a top face at its
 *       top-solid Z (which is either the panel top or a pocket floor,
 *       always with {@code +Z} normal pointing up out of the material)
 *       and a bottom face at {@code −halfT} with {@code −Z} normal.</li>
 *   <li><em>Walls:</em> each unique edge in the grid is visited once. The
 *       two cells sharing that edge have top-solid Z values {@code a} and
 *       {@code b} (either may be {@code null} for through or off-grid).
 *       The wall bridges the range where one cell has solid material that
 *       the other lacks. If both have material to the same height, no wall.
 *       If one is {@code null}, the whole range of the other is exposed.
 *       Normal points toward the emptier side.</li>
 * </ol>
 *
 * <p>Non-{@link Cutout.Rect} variants (Circle, Polygon, Spline) are ignored
 * until proper triangulation lands in a later phase.
 */
public final class PartMeshBuilder {

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
        // Split and clip cutouts. Anything outside the part or zero-area
        // after clipping is dropped silently. Pockets are split by face so
        // the cell-classification step can apply each independently.
        List<Cutout.Rect> throughRects = new ArrayList<>();
        List<Cutout.Rect> frontPockets = new ArrayList<>();
        List<Cutout.Rect> backPockets = new ArrayList<>();
        for (Cutout c : cutouts) {
            if (!(c instanceof Cutout.Rect r)) continue;  // circle/polygon/spline: future
            Cutout.Rect clipped = clip(r, widthMm, heightMm);
            if (clipped == null) continue;
            if (clipped.depthMm() == null) throughRects.add(clipped);
            else if (clipped.face() == Cutout.Face.BACK) backPockets.add(clipped);
            else frontPockets.add(clipped);
        }

        List<Cutout.Rect> allRects = new ArrayList<>(throughRects);
        allRects.addAll(frontPockets);
        allRects.addAll(backPockets);
        float[] xs = distinctEdges(widthMm, allRects, true);
        float[] ys = distinctEdges(heightMm, allRects, false);
        int nx = xs.length - 1;
        int ny = ys.length - 1;

        float halfW = widthMm * 0.5f;
        float halfH = heightMm * 0.5f;
        float halfT = thicknessMm * 0.5f;

        // Per-cell solid material band [bottom, top] in mesh Z (centered at 0).
        // null entry = no material (through cut, or front+back pockets meeting).
        // Solid cell:           [-halfT, +halfT].
        // Front pocket depth d: [-halfT, halfT - d].
        // Back pocket depth d:  [-halfT + d, halfT].
        // Both:                 [-halfT + d_back, halfT - d_front]; null if it collapses.
        Band[][] bands = new Band[nx][ny];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                float cx = (xs[i] + xs[i + 1]) * 0.5f;
                float cy = (ys[j] + ys[j + 1]) * 0.5f;
                if (pointInside(cx, cy, throughRects)) {
                    bands[i][j] = null;
                    continue;
                }
                // Deepest pocket on each face wins.
                float top = halfT;
                for (Cutout.Rect r : frontPockets) {
                    if (rectContains(r, cx, cy)) {
                        float z = halfT - r.depthMm();
                        if (z < top) top = z;
                    }
                }
                float bottom = -halfT;
                for (Cutout.Rect r : backPockets) {
                    if (rectContains(r, cx, cy)) {
                        float z = -halfT + r.depthMm();
                        if (z > bottom) bottom = z;
                    }
                }
                // Pockets meeting in the middle = no material left.
                bands[i][j] = (bottom < top) ? new Band(bottom, top) : null;
            }
        }

        MeshBuf buf = new MeshBuf();

        // Pass 1: faces. Each surviving cell emits a top face at band.top
        // (normal +Z) and a bottom face at band.bottom (normal -Z).
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                Band band = bands[i][j];
                if (band == null) continue;
                float x0 = xs[i] - halfW, x1 = xs[i + 1] - halfW;
                float y0 = ys[j] - halfH, y1 = ys[j + 1] - halfH;
                buf.addQuad(
                        x0, y0, band.top, x1, y0, band.top,
                        x1, y1, band.top, x0, y1, band.top,
                        0, 0, 1);
                buf.addQuad(
                        x0, y1, band.bottom, x1, y1, band.bottom,
                        x1, y0, band.bottom, x0, y0, band.bottom,
                        0, 0, -1);
            }
        }

        // Pass 2: walls. Each cell boundary may need up to two wall segments
        // — one at the top discrepancy, one at the bottom discrepancy —
        // since the two cells can differ in either or both extents.
        for (int i = 0; i <= nx; i++) {
            for (int j = 0; j < ny; j++) {
                Band a = bandAt(bands, i - 1, j, nx, ny);
                Band b = bandAt(bands, i, j, nx, ny);
                for (WallRange w : wallRanges(a, b)) {
                    emitVerticalEdgeWall(buf, xs[i] - halfW,
                            ys[j] - halfH, ys[j + 1] - halfH, w);
                }
            }
        }
        for (int j = 0; j <= ny; j++) {
            for (int i = 0; i < nx; i++) {
                Band a = bandAt(bands, i, j - 1, nx, ny);
                Band b = bandAt(bands, i, j, nx, ny);
                for (WallRange w : wallRanges(a, b)) {
                    emitHorizontalEdgeWall(buf, ys[j] - halfH,
                            xs[i] - halfW, xs[i + 1] - halfW, w);
                }
            }
        }

        return buf.toMesh();
    }

    // ---- Edge-wall emission ----

    /**
     * Wall at a vertical edge (fixed X). The Y range covers the full cell
     * boundary; Z range and normal direction come from {@link WallRange}.
     */
    private static void emitVerticalEdgeWall(MeshBuf buf, float x,
                                             float y0, float y1, WallRange w) {
        if (w.normalSign > 0) {
            buf.addQuad(
                    x, y0, w.zLow,  x, y1, w.zLow,  x, y1, w.zHigh,  x, y0, w.zHigh,
                    1, 0, 0);
        } else {
            buf.addQuad(
                    x, y0, w.zHigh, x, y1, w.zHigh, x, y1, w.zLow,  x, y0, w.zLow,
                    -1, 0, 0);
        }
    }

    /** Wall at a horizontal edge (fixed Y), spanning X range x0..x1. */
    private static void emitHorizontalEdgeWall(MeshBuf buf, float y,
                                               float x0, float x1, WallRange w) {
        if (w.normalSign > 0) {
            buf.addQuad(
                    x0, y, w.zHigh, x1, y, w.zHigh, x1, y, w.zLow,  x0, y, w.zLow,
                    0, 1, 0);
        } else {
            buf.addQuad(
                    x0, y, w.zLow,  x1, y, w.zLow,  x1, y, w.zHigh, x0, y, w.zHigh,
                    0, -1, 0);
        }
    }

    /**
     * Compute the wall segments between two adjacent cells, given each cell's
     * solid material band. With pockets coming from either face, two cells
     * may differ at the TOP of their bands (one front-pocketed, one not), at
     * the BOTTOM (one back-pocketed, one not), or both — so up to two
     * disjoint wall segments per cell boundary.
     *
     * <p>{@code normalSign} on each {@code WallRange} is {@code +1} if the
     * wall's normal points toward cell {@code b} (the +axis-side cell),
     * {@code -1} if toward {@code a}.
     */
    private static List<WallRange> wallRanges(Band a, Band b) {
        if (a == null && b == null) return List.of();
        if (a == null) {
            return List.of(new WallRange(b.bottom, b.top, -1));  // normal → a
        }
        if (b == null) {
            return List.of(new WallRange(a.bottom, a.top, +1));  // normal → b
        }
        List<WallRange> out = new ArrayList<>(2);
        // Top discrepancy: whichever cell has solid above the other.
        if (a.top != b.top) {
            if (a.top > b.top) out.add(new WallRange(b.top, a.top, +1));  // normal → b (emptier above)
            else out.add(new WallRange(a.top, b.top, -1));                 // normal → a
        }
        // Bottom discrepancy: whichever has solid below the other.
        if (a.bottom != b.bottom) {
            if (a.bottom < b.bottom) out.add(new WallRange(a.bottom, b.bottom, +1));  // normal → b
            else out.add(new WallRange(b.bottom, a.bottom, -1));
        }
        return out;
    }

    private record WallRange(float zLow, float zHigh, int normalSign) {}

    /** Per-cell solid material band in mesh Z. {@code null} = no material. */
    private record Band(float bottom, float top) {}

    // ---- Grid + classification helpers ----

    private static Band bandAt(Band[][] bands, int i, int j, int nx, int ny) {
        if (i < 0 || j < 0 || i >= nx || j >= ny) return null;
        return bands[i][j];
    }

    private static boolean rectContains(Cutout.Rect r, float x, float y) {
        return x >= r.xMm() && x <= r.xMm() + r.widthMm()
                && y >= r.yMm() && y <= r.yMm() + r.heightMm();
    }

    private static boolean pointInside(float x, float y, List<Cutout.Rect> rects) {
        for (Cutout.Rect r : rects) {
            if (x >= r.xMm() && x <= r.xMm() + r.widthMm()
                    && y >= r.yMm() && y <= r.yMm() + r.heightMm()) {
                return true;
            }
        }
        return false;
    }

    /** Clip a cutout rect to the part's bounds. Returns null if nothing remains. */
    private static Cutout.Rect clip(Cutout.Rect r, float widthMm, float heightMm) {
        float x0 = Math.max(0, r.xMm());
        float y0 = Math.max(0, r.yMm());
        float x1 = Math.min(widthMm, r.xMm() + r.widthMm());
        float y1 = Math.min(heightMm, r.yMm() + r.heightMm());
        if (x1 <= x0 || y1 <= y0) return null;
        return new Cutout.Rect(x0, y0, x1 - x0, y1 - y0, r.depthMm(), r.face());
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
