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

import com.jme3.math.ColorRGBA;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A material that parts can be made from.
 * Thickness is always stored in mm. Sheet dimensions are null for non-sheet goods.
 *
 * <p>Dimensional lumber (2x4, 2x6, etc.) is modeled as SHEET_GOOD with a fixed
 * cross-section: {@code widthMm} captures the actual cross-section width
 * (e.g. 38mm for a "2x4"), and {@code standardLengthsMm} lists the stock
 * lengths the lumber comes in. The packer treats one of those lengths as the
 * sheet, and parts whose width matches naturally lay end-to-end.
 * For plywood and other 2D-cut sheet goods, {@code widthMm} stays null —
 * users specify both dimensions explicitly via {@code size W, L}.
 */
@Data
@Builder
public class Material {
    private final String name;          // slug, e.g. "plywood-3/4"
    private final String displayName;   // human-readable, e.g. "3/4\" Cabinet Plywood"
    private final MaterialType type;    // substance (PLYWOOD, HARDWOOD, STONE, ...)
    private final MaterialKind kind;    // handling (SHEET_GOOD, SOLID_LUMBER, SLAB, HARDWARE)
    private final float thicknessMm;
    private final Float sheetWidthMm;   // populated for SHEET_GOOD; null otherwise
    private final Float sheetHeightMm;  // populated for SHEET_GOOD; null otherwise
    // Cross-section width for dimensional lumber — non-null signals "fixed
    // cross-section, defaulted in part creation." Null for plywood (user
    // specifies width via `size`) and for hardware/slab/per-piece materials.
    private final Float widthMm;
    // Stock lengths the lumber is sold in (mm). Empty/null for non-lumber.
    // For v1 the packer uses the longest entry as the sheet height — multi-
    // length optimization is a follow-up.
    private final List<Float> standardLengthsMm;
    private final GrainDirection grainDirection;
    private final MeasurementSystem measurementSystem;
    private final ColorRGBA displayColor;

    /** True iff this is dimensional lumber (fixed cross-section, 1D-packed). */
    public boolean isDimensionalLumber() {
        return widthMm != null;
    }
}
