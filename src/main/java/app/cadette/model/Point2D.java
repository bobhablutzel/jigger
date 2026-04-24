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

/**
 * Flat 2D point in millimetres. Kept deliberately lightweight — model-layer
 * code shouldn't pull in jME3's {@code Vector2f} if a simple pair of floats
 * will do. Used by {@link Cutout.Polygon} vertices and
 * {@link Cutout.Spline} control points.
 */
public record Point2D(float xMm, float yMm) {}
