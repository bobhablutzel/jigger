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
 * Axis-aligned 2D bounding box in millimetres. Used as the uniform "extent"
 * contract across {@link Cutout} shape variants so consumers that only care
 * about where a shape sits (sheet layout, collision checks, dashed-outline
 * rendering on the cut sheet) don't need to pattern-match on shape kind.
 *
 * Coordinates are in the containing part's local cut-face space — origin at
 * one corner of the part, +x / +y across the face — before any scene
 * rotation is applied.
 */
public record BoundingBox(float xMm, float yMm, float widthMm, float heightMm) {
    public float maxXMm() { return xMm + widthMm; }
    public float maxYMm() { return yMm + heightMm; }
}
