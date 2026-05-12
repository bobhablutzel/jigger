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
import app.cadette.model.Point2D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code cut <part> miter facing <NE|NW|SE|SW> angle <θ>}
 * cut shape — triangular wedge at a named corner. Reusable for diagonal
 * braces, picture frames, hip rafters.
 */
class MiterCutTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    private static Cutout.Polygon onlyMiter(Part p) {
        List<Cutout> cs = p.getCutouts();
        assertEquals(1, cs.size(), "expected exactly one cutout");
        assertInstanceOf(Cutout.Polygon.class, cs.get(0));
        return (Cutout.Polygon) cs.get(0);
    }

    private static void assertVertex(Point2D v, float x, float y) {
        assertEquals(x, v.xMm(), 0.01f, "x");
        assertEquals(y, v.yMm(), 0.01f, "y");
    }

    @Test
    void miterSouthEastRemovesBottomRightTriangle() {
        // 100 × 500 panel, miter at SE corner, 30° from the bottom edge.
        // Triangle: (0, 0), (100, 0), (100, 100·tan(30°) ≈ 57.74).
        exec("create part \"p\" size 100, 500 material \"plywood-18mm\"");
        exec("cut p miter facing SE angle 30");
        Cutout.Polygon mp = onlyMiter(sceneManager.getPart("p"));
        assertEquals(3, mp.vertices().size());
        assertVertex(mp.vertices().get(0), 0, 0);
        assertVertex(mp.vertices().get(1), 100, 0);
        assertVertex(mp.vertices().get(2), 100, (float) (100 * Math.tan(Math.toRadians(30))));
    }

    @Test
    void miterSouthWestRemovesBottomLeftTriangle() {
        exec("create part \"p\" size 100, 500 material \"plywood-18mm\"");
        exec("cut p miter facing SW angle 30");
        Cutout.Polygon mp = onlyMiter(sceneManager.getPart("p"));
        assertVertex(mp.vertices().get(0), 100, 0);
        assertVertex(mp.vertices().get(1), 0, 0);
        assertVertex(mp.vertices().get(2), 0, (float) (100 * Math.tan(Math.toRadians(30))));
    }

    @Test
    void miterNorthEastRemovesTopRightTriangle() {
        exec("create part \"p\" size 100, 500 material \"plywood-18mm\"");
        exec("cut p miter facing NE angle 30");
        Cutout.Polygon mp = onlyMiter(sceneManager.getPart("p"));
        assertVertex(mp.vertices().get(0), 0, 500);
        assertVertex(mp.vertices().get(1), 100, 500);
        assertVertex(mp.vertices().get(2), 100, 500 - (float) (100 * Math.tan(Math.toRadians(30))));
    }

    @Test
    void miterNorthWestRemovesTopLeftTriangle() {
        exec("create part \"p\" size 100, 500 material \"plywood-18mm\"");
        exec("cut p miter facing NW angle 30");
        Cutout.Polygon mp = onlyMiter(sceneManager.getPart("p"));
        assertVertex(mp.vertices().get(0), 100, 500);
        assertVertex(mp.vertices().get(1), 0, 500);
        assertVertex(mp.vertices().get(2), 0, 500 - (float) (100 * Math.tan(Math.toRadians(30))));
    }

    @Test
    void miterAtFortyFiveDegreesGivesSymmetricTriangle() {
        // Classic picture-frame miter: depth equals part width.
        exec("create part \"p\" size 100, 500 material \"plywood-18mm\"");
        exec("cut p miter facing NE angle 45");
        Cutout.Polygon mp = onlyMiter(sceneManager.getPart("p"));
        assertVertex(mp.vertices().get(2), 100, 400);  // 500 - 100·tan(45°) = 400
    }

    @Test
    void miterRejectsUnknownFacing() {
        exec("create part \"p\" size 100, 500 material \"plywood-18mm\"");
        String result = exec("cut p miter facing UP angle 30");
        // Parses (UP is a nameLike-ish keyword) but visitor should reject.
        assertTrue(result.toLowerCase().contains("unknown miter facing")
                || result.toLowerCase().contains("error"), result);
    }

    @Test
    void miterAcceptsDepthClause() {
        // Pocket-depth miter — useful for half-lap-style notches.
        exec("create part \"p\" size 100, 500 material \"plywood-18mm\"");
        exec("cut p miter facing SE angle 30 depth 9");
        Cutout.Polygon mp = onlyMiter(sceneManager.getPart("p"));
        assertEquals(9f, mp.depthMm(), 0.01f);
    }

    @Test
    void miterWorksOnLumber() {
        // The motivating case: diagonal brace with both ends mitered to the
        // brace's installation angle. Brace length is computed; the user
        // provides the matching miter angle.
        exec("create part \"brace\" length 1000 material \"lumber-2x4-spf\"");
        exec("cut brace miter facing SE angle 34");   // bottom-right end
        exec("cut brace miter facing NW angle 34");   // top-left end (other end)
        Part p = sceneManager.getPart("brace");
        assertEquals(2, p.getCutouts().size());
    }
}
