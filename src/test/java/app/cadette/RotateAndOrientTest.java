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

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rotation semantics changed 2026-05-11: bare {@code rotate} composes onto
 * the current orientation; {@code rotate ... to ...} replaces it. Plus
 * {@code orient} as a named-orientation shortcut for dimensional lumber.
 */
class RotateAndOrientTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    /** Compose two rotations the same way the visitor does — quaternion mult. */
    private static Vector3f compose(Vector3f a, Vector3f b) {
        Quaternion qa = new Quaternion().fromAngles(
                a.x * FastMath.DEG_TO_RAD, a.y * FastMath.DEG_TO_RAD, a.z * FastMath.DEG_TO_RAD);
        Quaternion qb = new Quaternion().fromAngles(
                b.x * FastMath.DEG_TO_RAD, b.y * FastMath.DEG_TO_RAD, b.z * FastMath.DEG_TO_RAD);
        float[] angles = qb.mult(qa).toAngles(null);
        return new Vector3f(
                angles[0] * FastMath.RAD_TO_DEG,
                angles[1] * FastMath.RAD_TO_DEG,
                angles[2] * FastMath.RAD_TO_DEG);
    }

    private static void assertAnglesEqual(Vector3f expected, Vector3f actual, float tol) {
        // Compare via quaternion to avoid Euler-decomposition false negatives
        // (e.g. (180, 0, 180) ≡ (0, 180, 0)).
        Quaternion qe = new Quaternion().fromAngles(
                expected.x * FastMath.DEG_TO_RAD, expected.y * FastMath.DEG_TO_RAD, expected.z * FastMath.DEG_TO_RAD);
        Quaternion qa = new Quaternion().fromAngles(
                actual.x * FastMath.DEG_TO_RAD, actual.y * FastMath.DEG_TO_RAD, actual.z * FastMath.DEG_TO_RAD);
        // Same rotation if dot product magnitude is near 1.
        float dot = Math.abs(qe.dot(qa));
        assertTrue(dot > 1 - tol, "Expected ~" + expected + " got " + actual + " (dot=" + dot + ")");
    }

    @Test
    void singleBareRotateMatchesAbsoluteFromIdentity() {
        // Composing 90° around Y with the identity = just 90° around Y.
        exec("create part \"p\" size 100, 100 material \"plywood-18mm\"");
        exec("rotate p 0, 90, 0");
        Vector3f rot = sceneManager.getRotation("p");
        assertAnglesEqual(new Vector3f(0, 90, 0), rot, 0.001f);
    }

    @Test
    void twoBareRotatesCompose() {
        // Two 90° turns around Y = 180° around Y.
        exec("create part \"p\" size 100, 100 material \"plywood-18mm\"");
        exec("rotate p 0, 90, 0");
        exec("rotate p 0, 90, 0");
        Vector3f rot = sceneManager.getRotation("p");
        assertAnglesEqual(new Vector3f(0, 180, 0), rot, 0.001f);
    }

    @Test
    void rotateByIsExplicitCompose() {
        exec("create part \"p\" size 100, 100 material \"plywood-18mm\"");
        exec("rotate p by 0, 90, 0");
        exec("rotate p by 90, 0, 0");
        // Should equal the quaternion compose of those two deltas.
        Vector3f expected = compose(new Vector3f(0, 90, 0), new Vector3f(90, 0, 0));
        Vector3f rot = sceneManager.getRotation("p");
        assertAnglesEqual(expected, rot, 0.001f);
    }

    @Test
    void rotateToIsAbsolute() {
        exec("create part \"p\" size 100, 100 material \"plywood-18mm\"");
        exec("rotate p 0, 45, 0");      // compose
        exec("rotate p 0, 45, 0");      // compose → 90 total
        exec("rotate p to 0, 0, 0");    // absolute reset
        Vector3f rot = sceneManager.getRotation("p");
        assertAnglesEqual(new Vector3f(0, 0, 0), rot, 0.001f);
    }

    @Test
    void orientKeepsAabbMinStable() {
        // Place a 2x4 at a known position; AABB min should be at that position
        // (since the box starts at the origin in local coords). After
        // `orient on-edge` (a 2-axis rotation), the visible box has swung
        // around the origin corner, but orient auto-translates so the AABB
        // min stays put.
        exec("create part \"rail\" length 800 material \"lumber-2x4-spf\" at 100, 200, 300");
        Vector3f[] before = sceneManager.computeObjectAABB("rail");
        assertNotNull(before);
        exec("orient rail on-edge");
        Vector3f[] after = sceneManager.computeObjectAABB("rail");
        assertNotNull(after);
        // Min corner should be at the same world position within tolerance.
        assertEquals(before[0].x, after[0].x, 0.01f, "AABB min X should be stable");
        assertEquals(before[0].y, after[0].y, 0.01f, "AABB min Y should be stable");
        assertEquals(before[0].z, after[0].z, 0.01f, "AABB min Z should be stable");
    }

    @Test
    void orientClauseAtCreateKeepsAabbMinStable() {
        // The orient-on-create path also goes through AABB-stable rotation,
        // so the AABB min lands at the position specified by `at`.
        exec("create part \"rail\" length 800 material \"lumber-2x4-spf\" at 100, 200, 300 orient on-edge");
        Vector3f[] aabb = sceneManager.computeObjectAABB("rail");
        assertNotNull(aabb);
        assertEquals(100f, aabb[0].x, 0.01f);
        assertEquals(200f, aabb[0].y, 0.01f);
        assertEquals(300f, aabb[0].z, 0.01f);
    }

    @Test
    void orientFlatLaysLumberWideFaceUp() {
        // Default 2x4: X=38, Y=length, Z=89. `orient flat` = laid on a
        // workbench: length horizontal (along X), wide-face normal vertical
        // (along Y). Wide-face normal in local space is ±X (since wide face
        // is the length × 89 face, perpendicular to local X).
        exec("create part \"rail\" length 800 material \"lumber-2x4-spf\"");
        exec("orient rail flat");
        Vector3f rot = sceneManager.getRotation("rail");
        Quaternion q = new Quaternion().fromAngles(
                rot.x * FastMath.DEG_TO_RAD, rot.y * FastMath.DEG_TO_RAD, rot.z * FastMath.DEG_TO_RAD);
        // Length (local Y) should point along ±X (horizontal).
        Vector3f worldLength = q.mult(Vector3f.UNIT_Y);
        assertEquals(1f, Math.abs(worldLength.x), 0.01f, "length should run along ±X, got " + worldLength);
        // Wide-face normal (local X) should point along ±Y (up/down).
        Vector3f wideFaceNormal = q.mult(Vector3f.UNIT_X);
        assertEquals(1f, Math.abs(wideFaceNormal.y), 0.01f,
                "wide face should face up/down, got normal " + wideFaceNormal);
    }

    @Test
    void orientOnEdgePutsWideFaceTowardViewer() {
        // Joist / gate-rail stance: length horizontal, narrow edge up,
        // wide face vertical (toward viewer along Z).
        exec("create part \"joist\" length 1200 material \"lumber-2x4-spf\"");
        exec("orient joist on-edge");
        Vector3f rot = sceneManager.getRotation("joist");
        Quaternion q = new Quaternion().fromAngles(
                rot.x * FastMath.DEG_TO_RAD, rot.y * FastMath.DEG_TO_RAD, rot.z * FastMath.DEG_TO_RAD);
        // Length along ±X.
        Vector3f worldLength = q.mult(Vector3f.UNIT_Y);
        assertEquals(1f, Math.abs(worldLength.x), 0.01f, "length should run along ±X");
        // Wide-face normal (local X) along ±Z (visible to front viewer).
        Vector3f wideFaceNormal = q.mult(Vector3f.UNIT_X);
        assertEquals(1f, Math.abs(wideFaceNormal.z), 0.01f,
                "wide face should face viewer (±Z), got normal " + wideFaceNormal);
    }

    @Test
    void orientOnEndStandsLumberVertical() {
        exec("create part \"post\" length 2000 material \"lumber-4x4-spf\"");
        exec("orient post on-end");
        Vector3f rot = sceneManager.getRotation("post");
        Quaternion q = new Quaternion().fromAngles(
                rot.x * FastMath.DEG_TO_RAD, rot.y * FastMath.DEG_TO_RAD, rot.z * FastMath.DEG_TO_RAD);
        // Length (local Y) should point along ±Y in world (still vertical).
        Vector3f worldLength = q.mult(Vector3f.UNIT_Y);
        assertEquals(1f, Math.abs(worldLength.y), 0.01f, "length should stay vertical");
    }

    @Test
    void orientRejectsNonLumber() {
        exec("create part \"panel\" size 600, 900 material \"plywood-18mm\"");
        String result = exec("orient panel flat");
        assertTrue(result.toLowerCase().contains("dimensional lumber"), result);
    }

    @Test
    void orientClauseOnCreate() {
        // `orient on-edge` works as a clause at creation time — gate-rail
        // case: length horizontal, wide face toward viewer.
        exec("create part \"rail\" length 900 material \"lumber-2x4-spf\" orient on-edge");
        Vector3f rot = sceneManager.getRotation("rail");
        Quaternion q = new Quaternion().fromAngles(
                rot.x * FastMath.DEG_TO_RAD, rot.y * FastMath.DEG_TO_RAD, rot.z * FastMath.DEG_TO_RAD);
        Vector3f worldLength = q.mult(Vector3f.UNIT_Y);
        assertEquals(1f, Math.abs(worldLength.x), 0.01f, "length should be horizontal");
        Vector3f wideFaceNormal = q.mult(Vector3f.UNIT_X);
        assertEquals(1f, Math.abs(wideFaceNormal.z), 0.01f, "wide face toward viewer");
    }

    @Test
    void orientClauseRejectsNonLumberAtCreate() {
        String result = exec("create part \"p\" size 100, 200 material \"plywood-18mm\" orient flat");
        assertTrue(result.toLowerCase().contains("dimensional lumber"), result);
        assertNull(sceneManager.getPart("p"));
    }

    @Test
    void orientThenRotateByComposes() {
        // orient sets a goal state; subsequent `rotate by` adjusts from there.
        exec("create part \"r\" length 800 material \"lumber-2x4-spf\" orient on-edge");
        Vector3f afterOrient = sceneManager.getRotation("r").clone();
        exec("rotate r by 0, 0, 45");
        Vector3f afterRotate = sceneManager.getRotation("r");
        Vector3f expected = compose(afterOrient, new Vector3f(0, 0, 45));
        assertAnglesEqual(expected, afterRotate, 0.001f);
    }
}
