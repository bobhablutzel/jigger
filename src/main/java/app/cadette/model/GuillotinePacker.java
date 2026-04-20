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

import lombok.AccessLevel;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Guillotine bin packing for laying out parts on sheet goods.
 *
 * Every cut goes edge-to-edge (guillotine constraint), matching how
 * woodworkers actually cut sheet goods on a table saw or panel saw.
 * Parts are sorted largest-first and placed using best-area-fit.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class GuillotinePacker {

    public static final float DEFAULT_KERF_MM = 3.2f;

    /**
     * Input: a part to be placed on a sheet.
     */
    @Data
    public static class PackingPart {
        private final String name;
        private final float widthMm;
        private final float heightMm;
        private final GrainRequirement grainRequirement;
    }

    @Data
    static class FreeRect {
        final float x, y, w, h;
    }

    private final List<SheetLayout> sheets = new ArrayList<>();
    private final List<List<FreeRect>> sheetFreeRects = new ArrayList<>();
    private final Material material;
    private final float kerfMm;

    /**
     * Pack parts onto sheets of the given material, respecting grain and kerf.
     *
     * @param material the sheet material (provides sheet dimensions and grain direction)
     * @param parts    the parts to pack
     * @param kerfMm   saw blade kerf width in mm
     * @return list of sheet layouts, one per sheet needed
     */
    public static List<SheetLayout> pack(Material material, List<PackingPart> parts, float kerfMm) {
        if (material.getSheetWidthMm() == null || material.getSheetHeightMm() == null) {
            return List.of();
        }

        GuillotinePacker packer = new GuillotinePacker(material, kerfMm);

        // Sort largest area first for better packing
        List<PackingPart> sorted = new ArrayList<>(parts);
        sorted.sort(Comparator.comparingDouble((PackingPart p) -> p.widthMm * p.heightMm).reversed());

        for (PackingPart part : sorted) {
            packer.placePart(part);
        }

        return packer.sheets;
    }

    public static List<SheetLayout> pack(Material material, List<PackingPart> parts) {
        return pack(material, parts, DEFAULT_KERF_MM);
    }

    private void placePart(PackingPart part) {
        float pw = part.widthMm;
        float ph = part.heightMm;
        boolean canRot = canRotate(part, material);

        // Try fitting in existing sheets (best-area-fit)
        int bestSheet = -1;
        int bestRect = -1;
        float bestArea = Float.MAX_VALUE;
        boolean bestRotated = false;

        for (int s = 0; s < sheets.size(); s++) {
            List<FreeRect> freeRects = sheetFreeRects.get(s);
            for (int r = 0; r < freeRects.size(); r++) {
                FreeRect rect = freeRects.get(r);

                // Try unrotated
                if (fits(pw, ph, rect)) {
                    float area = rect.w * rect.h;
                    if (area < bestArea) {
                        bestArea = area;
                        bestSheet = s;
                        bestRect = r;
                        bestRotated = false;
                    }
                }

                // Try rotated
                if (canRot && fits(ph, pw, rect)) {
                    float area = rect.w * rect.h;
                    if (area < bestArea) {
                        bestArea = area;
                        bestSheet = s;
                        bestRect = r;
                        bestRotated = true;
                    }
                }
            }
        }

        if (bestSheet >= 0) {
            doPlace(bestSheet, bestRect, part, bestRotated);
        } else {
            // New sheet
            float sheetW = material.getSheetWidthMm();
            float sheetH = material.getSheetHeightMm();
            SheetLayout newSheet = new SheetLayout(material, sheetW, sheetH);
            sheets.add(newSheet);
            List<FreeRect> newFree = new ArrayList<>();
            newFree.add(new FreeRect(0, 0, sheetW, sheetH));
            sheetFreeRects.add(newFree);

            int s = sheets.size() - 1;
            if (fits(pw, ph, newFree.get(0))) {
                doPlace(s, 0, part, false);
            } else if (canRot && fits(ph, pw, newFree.get(0))) {
                doPlace(s, 0, part, true);
            }
            // Part too large for sheet — skip
        }
    }

    private boolean fits(float partW, float partH, FreeRect rect) {
        return partW <= rect.w + 0.01f && partH <= rect.h + 0.01f;
    }

    private void doPlace(int sheetIndex, int rectIndex, PackingPart part, boolean rotated) {
        List<FreeRect> freeRects = sheetFreeRects.get(sheetIndex);
        FreeRect rect = freeRects.remove(rectIndex);

        float pw = rotated ? part.heightMm : part.widthMm;
        float ph = rotated ? part.widthMm : part.heightMm;

        sheets.get(sheetIndex).getPlacements().add(new SheetLayout.PlacedPart(
                part.name, rect.x, rect.y, pw, ph, rotated, part.grainRequirement));

        // Guillotine split: choose the split that maximizes the larger remaining piece.
        //   Horizontal split: right = (x+pw+kerf, y, remainW, ph)
        //                     top   = (x, y+ph+kerf, rect.w, remainH)
        //   Vertical split:   right = (x+pw+kerf, y, remainW, rect.h)
        //                     top   = (x, y+ph+kerf, pw, remainH)
        float remainW = rect.w - pw - kerfMm;
        float remainH = rect.h - ph - kerfMm;

        float hMax = Math.max(Math.max(0, remainW) * ph, rect.w * Math.max(0, remainH));
        float vMax = Math.max(Math.max(0, remainW) * rect.h, pw * Math.max(0, remainH));

        if (hMax >= vMax) {
            if (remainW > 0.01f) {
                freeRects.add(new FreeRect(rect.x + pw + kerfMm, rect.y, remainW, ph));
            }
            if (remainH > 0.01f) {
                freeRects.add(new FreeRect(rect.x, rect.y + ph + kerfMm, rect.w, remainH));
            }
        } else {
            if (remainW > 0.01f) {
                freeRects.add(new FreeRect(rect.x + pw + kerfMm, rect.y, remainW, rect.h));
            }
            if (remainH > 0.01f) {
                freeRects.add(new FreeRect(rect.x, rect.y + ph + kerfMm, pw, remainH));
            }
        }
    }

    /**
     * Whether a part can be rotated on this material.
     * Grain-constrained parts on grain-bearing materials cannot rotate.
     */
    public static boolean canRotate(PackingPart part, Material material) {
        if (material.getGrainDirection() == GrainDirection.NONE) {
            return true;
        }
        // Material has grain (ALONG_LENGTH — runs along sheet height).
        // VERTICAL/HORIZONTAL grain parts are locked; ANY can rotate.
        return part.grainRequirement == GrainRequirement.ANY;
    }
}
