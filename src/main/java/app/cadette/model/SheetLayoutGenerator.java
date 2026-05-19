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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Groups parts by material and runs the guillotine packer on each group.
 * Non-sheet goods (hardwood, metal) are excluded from layout.
 */
public class SheetLayoutGenerator {

    /**
     * Generate sheet layouts for all parts, grouped by material.
     *
     * @param parts  all parts in the scene
     * @param kerfMm saw blade kerf width in mm
     * @return list of sheet layouts across all materials
     */
    public static List<SheetLayout> generateLayouts(Map<String, Part> parts, float kerfMm) {
        // Group parts by material name
        Map<String, List<Part>> byMaterial = parts.values().stream()
                .collect(Collectors.groupingBy(
                        p -> p.getMaterial().getName(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<SheetLayout> allLayouts = new ArrayList<>();

        for (var entry : byMaterial.entrySet()) {
            List<Part> materialParts = entry.getValue();
            Material mat = materialParts.get(0).getMaterial();

            // Only sheet goods go through the guillotine packer. Solid lumber
            // is packed elsewhere (or not at all yet); slab and hardware are
            // counted per-piece in the BOM.
            if (mat.getKind() != MaterialKind.SHEET_GOOD) {
                continue;
            }

            // Convert to packing parts
            List<GuillotinePacker.PackingPart> packingParts = materialParts.stream()
                    .map(p -> new GuillotinePacker.PackingPart(
                            p.getName(),
                            p.getCutWidthMm(),
                            p.getCutHeightMm(),
                            p.getGrainRequirement()))
                    .toList();

            if (mat.isDimensionalLumber()) {
                allLayouts.addAll(packLumber(mat, packingParts, kerfMm));
            } else {
                allLayouts.addAll(GuillotinePacker.pack(mat, packingParts, kerfMm));
            }
        }

        return allLayouts;
    }

    /**
     * Pack lumber by trying each standard stock length and picking the choice
     * that minimizes total dollar cost — shorter stock is cheaper per linear
     * foot at the big-box yards most users buy from, so the cheapest mix
     * isn't always the one minimizing linear feet. Falls back to linear-feet
     * minimization (the older heuristic) when prices aren't known for every
     * candidate length, so unconfigured materials still pack reasonably.
     * Stock lengths shorter than the longest part are skipped. If no stock
     * length fits, the longest stock is used and the packer's per-part skip
     * handles the oversize.
     */
    private static List<SheetLayout> packLumber(Material mat,
                                                List<GuillotinePacker.PackingPart> parts,
                                                float kerfMm) {
        List<Float> stockLengths = mat.getStandardLengthsMm();
        if (stockLengths == null || stockLengths.isEmpty()) {
            // No catalog — fall back to whatever sheetHeightMm carries.
            return GuillotinePacker.pack(mat, parts, kerfMm);
        }
        float longestPart = (float) parts.stream()
                .mapToDouble(GuillotinePacker.PackingPart::getHeightMm)
                .max().orElse(0);
        float longestStock = stockLengths.stream()
                .max(Float::compare).orElse(0f);
        // Surface oversize parts up-front. The packer drops them
        // silently later (see "Part too large for sheet — skip" in
        // GuillotinePacker), which is a real shop-floor footgun —
        // the part vanishes from the cut sheet with no signal.
        // stderr is the current convention; routing these into the
        // command output panel is backlogged.
        for (GuillotinePacker.PackingPart pp : parts) {
            if (pp.getHeightMm() > longestStock + 0.5f) {
                System.err.println(String.format(
                        "[packer] part '%s' is %.0fmm long; longest %s stock is %.0fmm "
                      + "— part will be dropped from the cut sheet. "
                      + "Consider splitting the part or adding a longer stock length.",
                        pp.getName(), pp.getHeightMm(),
                        mat.getDisplayName(), longestStock));
            }
        }
        float binW = mat.getWidthMm();

        // Sorted ascending; shortest viable length wins on ties.
        List<Float> sorted = stockLengths.stream().sorted().toList();

        // Probe whether we have a price for every candidate length up
        // front. If yes, optimize in dollars; if no, the old linear-feet
        // metric is still meaningful (it just isn't aware of per-length
        // pricing). Mixed-mode comparison would be apples-to-oranges.
        boolean haveFullPriceTable = true;
        for (float L : sorted) {
            if (L < longestPart) continue;
            if (LumberPrices.priceFor(mat.getName(), Math.round(L)) == null) {
                haveFullPriceTable = false;
                break;
            }
        }

        List<SheetLayout> bestLayouts = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (float L : sorted) {
            if (L < longestPart) continue;
            var layouts = GuillotinePacker.pack(mat, parts, kerfMm, binW, L);
            if (layouts.isEmpty()) continue;
            double cost = haveFullPriceTable
                    ? layouts.size() * LumberPrices.priceFor(mat.getName(), Math.round(L))
                    : layouts.size() * L;
            if (cost < bestCost - 0.001) {
                bestCost = cost;
                bestLayouts = layouts;
            }
        }
        if (bestLayouts != null) return bestLayouts;
        // Some part is longer than every stock length — pack on the longest
        // and let the per-part skip happen there.
        float L = sorted.get(sorted.size() - 1);
        return GuillotinePacker.pack(mat, parts, kerfMm, binW, L);
    }

    public static List<SheetLayout> generateLayouts(Map<String, Part> parts) {
        return generateLayouts(parts, GuillotinePacker.DEFAULT_KERF_MM);
    }
}
