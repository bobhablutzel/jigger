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

import java.util.ArrayList;
import java.util.List;

/**
 * Tessellation helpers for the curve primitives Cadette supports.
 *
 * <p>Shared by {@link PartMeshBuilder} (which feeds JTS polygons for the
 * 3D mesh boolean ops) and the cut-sheet renderers (which need vertex
 * lists to stroke dashed outlines on the printed sheet).
 *
 * <p>Centripetal Catmull-Rom is used for splines because it never
 * self-intersects on control polygons with reasonable spacing, which
 * matters when the curve becomes a cutout boundary.
 */
public final class SplineTessellator {

    /** Samples per Catmull-Rom segment. 16 keeps chord error well under
     *  a pixel at any cabinet-rendering scale; matches PartMeshBuilder. */
    public static final int CATMULL_ROM_SAMPLES_PER_SEGMENT = 16;

    private SplineTessellator() { }

    /**
     * Tessellate a closed periodic centripetal Catmull-Rom spline through
     * {@code control} into a polygon vertex list. With N control points
     * and K samples per segment, returns N × K points traversing the
     * curve once. The curve passes exactly through each control point
     * (Catmull-Rom is interpolating) and wraps cyclically so the result
     * is naturally closed.
     */
    public static List<Point2D> tessellateCatmullRom(List<Point2D> control) {
        int n = control.size();
        if (n < 3) return control;
        List<Point2D> result = new ArrayList<>(n * CATMULL_ROM_SAMPLES_PER_SEGMENT);
        for (int i = 0; i < n; i++) {
            Point2D p0 = control.get((i - 1 + n) % n);
            Point2D p1 = control.get(i);
            Point2D p2 = control.get((i + 1) % n);
            Point2D p3 = control.get((i + 2) % n);
            float t0 = 0;
            float t1 = t0 + alphaDistance(p0, p1);
            float t2 = t1 + alphaDistance(p1, p2);
            float t3 = t2 + alphaDistance(p2, p3);
            for (int j = 0; j < CATMULL_ROM_SAMPLES_PER_SEGMENT; j++) {
                float u = (float) j / CATMULL_ROM_SAMPLES_PER_SEGMENT;
                float t = t1 + u * (t2 - t1);
                result.add(barryGoldman(p0, p1, p2, p3, t0, t1, t2, t3, t));
            }
        }
        return result;
    }

    private static float alphaDistance(Point2D a, Point2D b) {
        float dx = b.xMm() - a.xMm();
        float dy = b.yMm() - a.yMm();
        return (float) Math.pow(dx * dx + dy * dy, 0.25);
    }

    private static Point2D barryGoldman(Point2D p0, Point2D p1, Point2D p2, Point2D p3,
                                        float t0, float t1, float t2, float t3, float t) {
        Point2D a1 = lerp(p0, p1, (t - t0) / (t1 - t0));
        Point2D a2 = lerp(p1, p2, (t - t1) / (t2 - t1));
        Point2D a3 = lerp(p2, p3, (t - t2) / (t3 - t2));
        Point2D b1 = lerp(a1, a2, (t - t0) / (t2 - t0));
        Point2D b2 = lerp(a2, a3, (t - t1) / (t3 - t1));
        return lerp(b1, b2, (t - t1) / (t2 - t1));
    }

    private static Point2D lerp(Point2D a, Point2D b, float t) {
        return new Point2D(
                a.xMm() + (b.xMm() - a.xMm()) * t,
                a.yMm() + (b.yMm() - a.yMm()) * t);
    }
}
