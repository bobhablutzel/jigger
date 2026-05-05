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

import com.jme3.math.Vector3f;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import lombok.experimental.UtilityClass;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural invariants for a {@link Mesh}, used by {@code PartMeshBuilderTest}
 * to characterise geometry without pinning a specific triangulation. These
 * checks survive algorithm changes — they describe the <em>contract</em> the
 * mesh must satisfy (extent, enclosed volume, material presence at specific
 * points, face coverage at expected Z planes) rather than implementation
 * artifacts (triangle counts, vertex order, cell decomposition).
 *
 * <h2>Closure check via volume, not manifold edges</h2>
 *
 * <p>Both the rect-grid decomposer and a polygon-with-holes approach naturally
 * produce T-junctions where three regions of different Z heights meet (a
 * single tall wall edge opposed by two shorter wall edges on adjacent walls).
 * A strict closed-manifold check fails on these even though the surface is
 * geometrically correct. Instead we use {@link #assertVolume} — the divergence
 * theorem gives a positive, deterministic result for closed surfaces, and a
 * mismatch with the analytic volume catches missing walls, doubled triangles,
 * or wrong-side faces.
 */
@UtilityClass
public class MeshInvariants {

    /** Default linear tolerance for bbox/face extent comparisons. */
    private static final float DEFAULT_TOL = 0.01f;

    /** Z-plane match tolerance for {@link #faceExtentAtZ}. */
    private static final float Z_PLANE_TOL = 0.001f;

    /**
     * Sub-micron XY perturbation applied to ray origin so it never lands on a
     * triangulation diagonal — point (x, y) at the exact centre of an
     * axis-aligned quad would otherwise hit both child triangles along their
     * shared diagonal and double-count. Smaller than any meaningful feature,
     * irrational-ish so it doesn't align with any conceivable axis grid.
     */
    private static final float RAY_PERTURB_X = 0.000137f;
    private static final float RAY_PERTURB_Y = 0.000179f;

    // ---- Extent ----------------------------------------------------------

    /** Asserts the mesh's overall bbox matches declared part dimensions. */
    public static void assertExtent(Mesh mesh, float widthMm, float heightMm, float thicknessMm) {
        float[] mm = bbox(mesh);
        assertEquals(widthMm,     mm[3] - mm[0], DEFAULT_TOL, "extent X");
        assertEquals(heightMm,    mm[4] - mm[1], DEFAULT_TOL, "extent Y");
        assertEquals(thicknessMm, mm[5] - mm[2], DEFAULT_TOL, "extent Z");
    }

    // ---- Volume ----------------------------------------------------------

    /**
     * Signed volume via the divergence theorem: Σ (a · (b × c)) / 6 over all
     * triangles. Positive for a closed mesh with outward-facing CCW winding.
     * Negative volume indicates inverted normals (or unclosed mesh).
     */
    public static float signedVolume(Mesh mesh) {
        float[] sum = {0f};
        forEachTriangle(mesh, (a, b, c) -> {
            // (b × c)
            float bcx = b[1] * c[2] - b[2] * c[1];
            float bcy = b[2] * c[0] - b[0] * c[2];
            float bcz = b[0] * c[1] - b[1] * c[0];
            // a · (b × c)
            sum[0] += a[0] * bcx + a[1] * bcy + a[2] * bcz;
        });
        return sum[0] / 6f;
    }

    /** Asserts the mesh's signed volume equals an analytically computed expected value. */
    public static void assertVolume(Mesh mesh, float expectedMm3, float toleranceMm3) {
        assertEquals(expectedMm3, signedVolume(mesh), toleranceMm3, "signed volume (mm³)");
    }

    // ---- Material presence (point-in-mesh via ray casting) ---------------

    /**
     * Returns true if the point is inside the closed mesh. Implemented as a
     * Möller-Trumbore ray cast in +Z direction, counting triangle crossings;
     * odd count means inside.
     *
     * <p>Caller must avoid points exactly on a face plane — pick a point
     * clearly interior to the region under test, e.g. {@code z = halfT/2}
     * for a solid cell or {@code z = pocketFloor − 1} for a pocket region.
     */
    public static boolean hasMaterialAt(Mesh mesh, float x, float y, float z) {
        return rayCrossings(mesh, x, y, z) % 2 == 1;
    }

    public static void assertHasMaterialAt(Mesh m, float x, float y, float z) {
        assertTrue(hasMaterialAt(m, x, y, z),
                "expected material at (%.2f, %.2f, %.2f)".formatted(x, y, z));
    }

    public static void assertNoMaterialAt(Mesh m, float x, float y, float z) {
        assertFalse(hasMaterialAt(m, x, y, z),
                "expected no material at (%.2f, %.2f, %.2f)".formatted(x, y, z));
    }

    // ---- Face-extent-at-Z ------------------------------------------------

    /**
     * Bounding box of all triangles whose three vertices lie within
     * {@link #Z_PLANE_TOL} of the given Z plane. Returns null if no such face
     * exists. Used to verify that a panel face still reaches the panel's outer
     * corners after an interior cutout, or that a pocket floor exists at an
     * expected Z.
     */
    public static Vector3f[] faceExtentAtZ(Mesh mesh, float z) {
        float[] mm = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                       Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
        boolean[] any = { false };
        forEachTriangle(mesh, (a, b, c) -> {
            if (Math.abs(a[2] - z) < Z_PLANE_TOL
                    && Math.abs(b[2] - z) < Z_PLANE_TOL
                    && Math.abs(c[2] - z) < Z_PLANE_TOL) {
                any[0] = true;
                for (float[] v : new float[][] { a, b, c }) {
                    if (v[0] < mm[0]) mm[0] = v[0];
                    if (v[1] < mm[1]) mm[1] = v[1];
                    if (v[0] > mm[2]) mm[2] = v[0];
                    if (v[1] > mm[3]) mm[3] = v[1];
                }
            }
        });
        return any[0]
                ? new Vector3f[] { new Vector3f(mm[0], mm[1], z), new Vector3f(mm[2], mm[3], z) }
                : null;
    }

    /** Asserts a face exists at Z and spans the expected (x, y) rectangle. */
    public static void assertFaceCoversAtZ(Mesh m, float z,
                                           float minX, float maxX, float minY, float maxY,
                                           float tol) {
        Vector3f[] b = faceExtentAtZ(m, z);
        assertNotNull(b, "no face found at Z=" + z);
        assertEquals(minX, b[0].x, tol, "face minX at Z=" + z);
        assertEquals(maxX, b[1].x, tol, "face maxX at Z=" + z);
        assertEquals(minY, b[0].y, tol, "face minY at Z=" + z);
        assertEquals(maxY, b[1].y, tol, "face maxY at Z=" + z);
    }

    // ---- Implementation --------------------------------------------------

    @FunctionalInterface
    private interface TriangleConsumer {
        void accept(float[] a, float[] b, float[] c);
    }

    private static void forEachTriangle(Mesh mesh, TriangleConsumer fn) {
        FloatBuffer pos = (FloatBuffer) mesh.getBuffer(VertexBuffer.Type.Position).getData();
        IntBuffer idx = (IntBuffer) mesh.getBuffer(VertexBuffer.Type.Index).getData();
        int triCount = mesh.getTriangleCount();
        for (int t = 0; t < triCount; t++) {
            fn.accept(vert(pos, idx.get(t * 3)),
                      vert(pos, idx.get(t * 3 + 1)),
                      vert(pos, idx.get(t * 3 + 2)));
        }
    }

    private static float[] vert(FloatBuffer pos, int i) {
        return new float[] { pos.get(i * 3), pos.get(i * 3 + 1), pos.get(i * 3 + 2) };
    }

    private static float[] bbox(Mesh mesh) {
        float[] r = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                      Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
        FloatBuffer pos = (FloatBuffer) mesh.getBuffer(VertexBuffer.Type.Position).getData();
        int count = pos.capacity() / 3;
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            if (x < r[0]) r[0] = x;
            if (y < r[1]) r[1] = y;
            if (z < r[2]) r[2] = z;
            if (x > r[3]) r[3] = x;
            if (y > r[4]) r[4] = y;
            if (z > r[5]) r[5] = z;
        }
        return r;
    }

    /**
     * Möller-Trumbore ray-triangle intersection; ray = (ox', oy', oz) + t·(0,0,1)
     * where (ox', oy') is the caller's origin perturbed by a sub-micron offset
     * so the ray never lands on an axis-aligned triangulation diagonal.
     */
    private static int rayCrossings(Mesh mesh, float oxIn, float oyIn, float oz) {
        float ox = oxIn + RAY_PERTURB_X;
        float oy = oyIn + RAY_PERTURB_Y;
        int[] hits = {0};
        forEachTriangle(mesh, (a, b, c) -> {
            float e1x = b[0] - a[0], e1y = b[1] - a[1], e1z = b[2] - a[2];
            float e2x = c[0] - a[0], e2y = c[1] - a[1], e2z = c[2] - a[2];
            // h = d × e2 with d = (0, 0, 1) → h = (-e2.y, e2.x, 0)
            float hx = -e2y, hy = e2x;  // hz = 0
            float det = e1x * hx + e1y * hy;
            if (Math.abs(det) < 1e-7f) return;  // ray parallel to triangle
            float invDet = 1f / det;
            float sx = ox - a[0], sy = oy - a[1], sz = oz - a[2];
            float u = invDet * (sx * hx + sy * hy);
            if (u < 0f || u > 1f) return;
            // q = s × e1
            float qx = sy * e1z - sz * e1y;
            float qy = sz * e1x - sx * e1z;
            float qz = sx * e1y - sy * e1x;
            // d · q = qz (since d = (0,0,1))
            float v = invDet * qz;
            if (v < 0f || u + v > 1f) return;
            float tHit = invDet * (e2x * qx + e2y * qy + e2z * qz);
            if (tHit > 1e-5f) hits[0]++;
        });
        return hits[0];
    }

}
