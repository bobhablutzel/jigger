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
 * A reusable named 2D outline used by the {@code shape} declaration. Two
 * variants mirror the {@link Cutout} polygon/spline split: {@link Polygon}
 * stores vertices interpreted as straight-segment corners; {@link Spline}
 * stores control points interpreted as a periodic Catmull-Rom curve.
 *
 * <p>Shapes are defined in the part-local space at the origin (no anchor of
 * their own) and translated by an anchor at the {@code cut ... shape <name>
 * at x, y} call site. This separation lets the same shape be reused at
 * multiple positions — and, eventually, by a draw-mode tool that emits
 * shape declarations rather than per-cut vertex lists.
 */
public sealed interface Shape permits Shape.Polygon, Shape.Spline {

    /** Defining points in the shape's local 2D space. */
    List<Point2D> points();

    record Polygon(List<Point2D> points) implements Shape {}

    record Spline(List<Point2D> points) implements Shape {}
}
