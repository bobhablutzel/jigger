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

import java.util.List;

/**
 * A region removed from a {@link Part}. Cutouts are defined in the part's
 * local cut-face space (one corner is the origin, +x / +y across the face)
 * and apply before any scene rotation.
 *
 * <p>The interface is sealed — each concrete shape knows its own bounds and
 * its own machining characteristics. Consumers that only care about extent
 * (sheet-layout packing, collision checks, BOM operation lines) call
 * {@link #bounds()}; consumers that need the shape (3D mesh generation,
 * dashed outlines on the cut sheet) pattern-match on the variant.
 *
 * <p>{@code depthMm} distinguishes through-cuts from partial-depth pockets:
 * {@code null} means the cutout goes the full thickness of the part (sink
 * notch, toe-kick recess, etc.); a positive value means a pocket of that
 * depth (shelf-pin hole, mortise, etc.).
 *
 * <p>As of Phase E1 only {@link Rect} is reachable through the grammar and
 * rendering pipeline. {@link Circle}, {@link Polygon}, and {@link Spline}
 * variants carry valid {@code bounds()} implementations so model-layer
 * consumers already work for them, but the {@code cut} command and the 3D
 * mesh generator don't yet produce or consume them — see
 * project_phase_e_cutouts.md for the staging plan.
 */
public sealed interface Cutout
        permits Cutout.Rect, Cutout.Circle, Cutout.Polygon, Cutout.Spline {

    /** Axis-aligned 2D extent of this cutout in the part's local cut-face space. */
    BoundingBox bounds();

    /** Null = through-cut (full thickness). Positive = partial-depth pocket. */
    Float depthMm();

    // ----- Rect — the v1 variant, fully wired through grammar + rendering -----

    /**
     * Axis-aligned rectangular cutout. {@code xMm} / {@code yMm} locate the
     * origin corner; {@code widthMm} / {@code heightMm} are positive extents.
     */
    record Rect(float xMm, float yMm, float widthMm, float heightMm, Float depthMm)
            implements Cutout {
        @Override
        public BoundingBox bounds() {
            return new BoundingBox(xMm, yMm, widthMm, heightMm);
        }
    }

    // ----- Future variants: model is ready, grammar + rendering come later -----

    /**
     * TODO Phase E: circular cutout — shelf-pin holes are the motivating case
     * (partial-depth circles drilled into a side panel). Needs a
     * {@code cut "…" circle at …} grammar alternative and a circular-arc
     * mesh contribution in the renderer.
     */
    record Circle(float cxMm, float cyMm, float radiusMm, Float depthMm)
            implements Cutout {
        @Override
        public BoundingBox bounds() {
            float d = radiusMm * 2f;
            return new BoundingBox(cxMm - radiusMm, cyMm - radiusMm, d, d);
        }
    }

    /**
     * TODO Phase E: straight-edged polygon cutout — handles odd-shaped sink
     * notches, L-shaped counter corners, etc. Vertices are in traversal
     * order; the shape closes implicitly from the last vertex back to the
     * first. Needs a grammar alternative and earcut-style triangulation
     * for the mesh contribution.
     */
    record Polygon(List<Point2D> vertices, Float depthMm) implements Cutout {
        @Override
        public BoundingBox bounds() {
            if (vertices.isEmpty()) return new BoundingBox(0, 0, 0, 0);
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (Point2D p : vertices) {
                if (p.xMm() < minX) minX = p.xMm();
                if (p.yMm() < minY) minY = p.yMm();
                if (p.xMm() > maxX) maxX = p.xMm();
                if (p.yMm() > maxY) maxY = p.yMm();
            }
            return new BoundingBox(minX, minY, maxX - minX, maxY - minY);
        }
    }

    /**
     * TODO Phase E: smooth-curve cutout — the crosscut-sled handle was the
     * motivating case. Control points are stored; curve type (cubic Bezier,
     * B-spline, Catmull-Rom, …) will be decided when we implement the
     * grammar alternative. The control-point hull is a conservative upper
     * bound for most common spline types — real tight bounds need per-type
     * extent math, which is a renderer concern.
     */
    record Spline(List<Point2D> controlPoints, Float depthMm) implements Cutout {
        @Override
        public BoundingBox bounds() {
            if (controlPoints.isEmpty()) return new BoundingBox(0, 0, 0, 0);
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (Point2D p : controlPoints) {
                if (p.xMm() < minX) minX = p.xMm();
                if (p.yMm() < minY) minY = p.yMm();
                if (p.xMm() > maxX) maxX = p.xMm();
                if (p.yMm() > maxY) maxY = p.yMm();
            }
            return new BoundingBox(minX, minY, maxX - minX, maxY - minY);
        }
    }
}
