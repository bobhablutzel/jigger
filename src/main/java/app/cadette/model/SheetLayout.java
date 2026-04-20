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

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * The layout of parts on a single sheet of material.
 * Contains the placement positions for each part, supporting future
 * export to PDF, image, or CNC G-code.
 */
@Data
@RequiredArgsConstructor
public class SheetLayout {
    private final Material material;
    private final float sheetWidthMm;
    private final float sheetHeightMm;
    private final List<PlacedPart> placements = new ArrayList<>();

    /** Total area used by placed parts (excluding kerf). */
    public float getUsedAreaMm2() {
        float area = 0;
        for (PlacedPart p : placements) {
            area += p.widthOnSheet * p.heightOnSheet;
        }
        return area;
    }

    /** Percentage of sheet area that is offcut (unused material after cutting). */
    public float getOffcutPercent() {
        float sheetArea = sheetWidthMm * sheetHeightMm;
        if (sheetArea <= 0) return 0;
        return (1f - getUsedAreaMm2() / sheetArea) * 100f;
    }

    /**
     * A single part placed on a sheet.
     * Coordinates are from the bottom-left corner of the sheet.
     */
    @Data
    public static class PlacedPart {
        private final String partName;
        private final float x;
        private final float y;
        private final float widthOnSheet;
        private final float heightOnSheet;
        private final boolean rotated;
        private final GrainRequirement grainRequirement;
    }
}
