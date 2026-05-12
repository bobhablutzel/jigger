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

import app.cadette.UnitSystem;
import lombok.Data;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates cut lists and BOMs from parts and joints.
 *
 * Effective dimensions account for joinery:
 * - A part inserted into a dado is wider by the dado depth on each side it's dadoed into
 *   (the dado allows it to sit deeper, so the cut size stays the same — but the
 *    effective overlap changes). Actually, for a standard dado: the cut dimension
 *    of the inserted part doesn't change, but the position shifts. The receiving
 *    part gets a groove cut into it (a machining operation).
 *
 * For the cut list, what matters is:
 * - The cut dimensions of each part (unchanged by joinery)
 * - The machining operations on each part (dados, rabbets to be cut)
 * - The fasteners needed (pocket screws, etc.)
 */
public class CutListGenerator {

    @Data
    public static class CutListEntry {
        private final String partName;
        private final Material material;
        private final float cutWidthMm;
        private final float cutHeightMm;
        private final float thicknessMm;
        private final GrainRequirement grainRequirement;
        private final List<String> operations;  // machining operations (e.g., "dado 9mm deep")
    }

    @Data
    public static class BomEntry {
        private final Material material;
        private final int partCount;
        private final float totalAreaMm2;  // total area of all parts in this material
        private final Integer sheetCount;  // null if not a sheet good
        private final Float offcutPercent;  // null if not a sheet good
        // Actual stock dimensions used (from the packer), not the catalog
        // defaults. For lumber this is the picked stock length, which may
        // differ from material.sheetHeightMm if a shorter length fit better.
        // Null when no layouts were produced.
        private final Float actualSheetHeightMm;
    }

    @Data
    public static class FastenerEntry {
        private final String type;
        private final int count;
    }

    /**
     * Generate the cut list from all parts in the scene.
     * Groups by material, includes machining operations from joints.
     *
     * <p>Operation dimensions (dado depth, cutout size, etc.) are formatted
     * in the caller's display units. Part dimensions are returned in mm
     * and converted at display time by the command visitor.
     */
    public static List<CutListEntry> generateCutList(
            Map<String, Part> parts, JointRegistry joints, UnitSystem units) {
        return parts.values().stream()
                .map(part -> new CutListEntry(
                        part.getName(),
                        part.getMaterial(),
                        part.getCutWidthMm(),
                        part.getCutHeightMm(),
                        part.getThicknessMm(),
                        part.getGrainRequirement(),
                        operationsFor(part, joints, units)))
                .sorted(Comparator
                        .comparing((CutListEntry e) -> e.getMaterial().getName())
                        .thenComparing(CutListEntry::getPartName))
                .toList();
    }

    /**
     * Machining operations applied to a part — joints where this part is
     * the receiving side, plus any cutouts. Joints are listed first
     * (they're the traditional cut-list operations); cutouts follow in
     * the order they were added.
     */
    private static List<String> operationsFor(Part part, JointRegistry joints, UnitSystem units) {
        return Stream.concat(
                joints.getJointsForPart(part.getName()).stream()
                        .filter(j -> j.receivingPartName().equals(part.getName()))
                        .map(j -> describeOperation(j, units))
                        .flatMap(Optional::stream),
                part.getCutouts().stream()
                        .map(c -> describeCutout(c, units))
        ).toList();
    }

    private static Optional<String> describeOperation(Joint j, UnitSystem units) {
        return j.describeOperation(units);
    }

    private static String describeCutout(Cutout cutout, UnitSystem units) {
        String abbr = units.getAbbreviation();
        if (cutout instanceof Cutout.Rect r) {
            String depthStr = r.depthMm() != null
                    ? String.format(" %.1f %s deep", units.fromMm(r.depthMm()), abbr)
                    : " through";
            return String.format("cutout rect %.1f×%.1f %s at (%.1f, %.1f)%s",
                    units.fromMm(r.widthMm()), units.fromMm(r.heightMm()), abbr,
                    units.fromMm(r.xMm()), units.fromMm(r.yMm()), depthStr);
        }
        if (cutout instanceof Cutout.Circle c) {
            String depthStr = c.depthMm() != null
                    ? String.format(" %.1f %s deep", units.fromMm(c.depthMm()), abbr)
                    : " through";
            return String.format("cutout circle ⌀%.1f %s at (%.1f, %.1f)%s",
                    units.fromMm(c.radiusMm() * 2f), abbr,
                    units.fromMm(c.cxMm()), units.fromMm(c.cyMm()), depthStr);
        }
        // Polygon / Spline variants not yet reachable through the grammar.
        return "cutout " + cutout.getClass().getSimpleName().toLowerCase();
    }

    /**
     * Generate the BOM using actual sheet layout results from the packer.
     */
    public static List<BomEntry> generateBom(Map<String, Part> parts, List<SheetLayout> layouts) {
        Map<String, List<Part>> byMaterial = parts.values().stream()
                .collect(Collectors.groupingBy(
                        p -> p.getMaterial().getName(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, List<SheetLayout>> layoutsByMaterial = layouts.stream()
                .collect(Collectors.groupingBy(
                        l -> l.getMaterial().getName(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        return byMaterial.values().stream()
                .map(materialParts -> toBomEntry(materialParts,
                        layoutsByMaterial.get(materialParts.get(0).getMaterial().getName())))
                .toList();
    }

    private static BomEntry toBomEntry(List<Part> materialParts, List<SheetLayout> matLayouts) {
        Material mat = materialParts.get(0).getMaterial();
        float totalArea = (float) materialParts.stream()
                .mapToDouble(p -> p.getCutWidthMm() * p.getCutHeightMm())
                .sum();
        boolean haveLayouts = matLayouts != null && !matLayouts.isEmpty();
        Integer sheetCount = haveLayouts ? matLayouts.size() : null;
        Float offcutPercent = haveLayouts
                ? (float) matLayouts.stream()
                        .mapToDouble(SheetLayout::getOffcutPercent)
                        .average()
                        .orElse(0)
                : null;
        Float actualHeight = haveLayouts ? matLayouts.get(0).getSheetHeightMm() : null;
        return new BomEntry(mat, materialParts.size(), totalArea, sheetCount, offcutPercent, actualHeight);
    }

    /**
     * Generate fastener summary from joints.
     */
    /**
     * Aggregate fastener entries from every joint's {@code bomFasteners()}
     * contribution, summed by type label. Adding a new joint type that
     * carries hardware is a one-line override on the variant — this method
     * doesn't need to change.
     */
    public static List<FastenerEntry> generateFasteners(JointRegistry joints) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        joints.getAllJoints().stream()
                .flatMap(j -> j.bomFasteners().stream())
                .forEach(fe -> totals.merge(fe.getType(), fe.getCount(), Integer::sum));
        return totals.entrySet().stream()
                .map(e -> new FastenerEntry(e.getKey(), e.getValue()))
                .toList();
    }
}
