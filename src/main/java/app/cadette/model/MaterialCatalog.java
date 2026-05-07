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

import java.util.*;
import java.util.stream.Stream;

/**
 * Registry of available materials. Pre-loaded with common woodworking materials
 * in both imperial and metric naming conventions.
 */
public class MaterialCatalog {

    private static final MaterialCatalog INSTANCE = new MaterialCatalog();

    public static final String DEFAULT_IMPERIAL = "plywood-3/4";
    public static final String DEFAULT_METRIC = "plywood-18mm";

    private final Map<String, Material> materials = new LinkedHashMap<>();

    private MaterialCatalog() {
        loadDefaults();
    }

    public static MaterialCatalog instance() {
        return INSTANCE;
    }

    public Material get(String slug) {
        return materials.get(normalize(slug));
    }

    public Collection<Material> getAll() {
        return Collections.unmodifiableCollection(materials.values());
    }

    /** Materials sorted with the given measurement system first, then the rest. */
    public List<Material> getSortedFor(MeasurementSystem preferred) {
        List<Material> matching = new ArrayList<>();
        List<Material> other = new ArrayList<>();
        for (Material m : materials.values()) {
            if (m.getMeasurementSystem() == preferred) {
                matching.add(m);
            } else {
                other.add(m);
            }
        }
        List<Material> result = new ArrayList<>(matching.size() + other.size());
        result.addAll(matching);
        result.addAll(other);
        return result;
    }

    /** Index of the first non-preferred material in the sorted list. */
    public int preferredCount(MeasurementSystem preferred) {
        return (int) materials.values().stream()
                .filter(m -> m.getMeasurementSystem() == preferred)
                .count();
    }

    public Material getDefaultFor(MeasurementSystem system) {
        return system == MeasurementSystem.IMPERIAL
                ? get(DEFAULT_IMPERIAL)
                : get(DEFAULT_METRIC);
    }

    public void register(Material material) {
        materials.put(normalize(material.getName()), material);
    }

    private static String normalize(String slug) {
        return slug.toLowerCase().replace('_', '-').replace(' ', '-');
    }

    private void loadDefaults() {
        // ===================== IMPERIAL =====================

        // -- Plywood (Imperial) --
        register(Material.builder()
                .name("plywood-3/4")
                .displayName("3/4\" Cabinet Plywood")
                .type(MaterialType.PLYWOOD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(19.05f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.76f, 0.60f, 0.42f, 1f))
                .build());

        register(Material.builder()
                .name("plywood-1/2")
                .displayName("1/2\" Plywood")
                .type(MaterialType.PLYWOOD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(12.7f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.72f, 0.56f, 0.38f, 1f))
                .build());

        register(Material.builder()
                .name("plywood-1/4")
                .displayName("1/4\" Plywood")
                .type(MaterialType.PLYWOOD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(6.35f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.78f, 0.62f, 0.44f, 1f))
                .build());

        // -- Hardboard (Imperial) --
        register(Material.builder()
                .name("hardboard-1/4")
                .displayName("1/4\" Hardboard")
                .type(MaterialType.HARDBOARD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(6.35f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.35f, 0.22f, 0.12f, 1f))
                .build());

        // -- MDF (Imperial) --
        register(Material.builder()
                .name("mdf-3/4")
                .displayName("3/4\" MDF")
                .type(MaterialType.MDF)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(19.05f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.65f, 0.55f, 0.40f, 1f))
                .build());

        // -- Hardwood (Imperial) --
        register(Material.builder()
                .name("poplar-3/4")
                .displayName("3/4\" Poplar")
                .type(MaterialType.HARDWOOD)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(19.05f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.82f, 0.78f, 0.65f, 1f))
                .build());

        register(Material.builder()
                .name("oak-3/4")
                .displayName("3/4\" Red Oak")
                .type(MaterialType.HARDWOOD)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(19.05f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.72f, 0.52f, 0.32f, 1f))
                .build());

        register(Material.builder()
                .name("maple-3/4")
                .displayName("3/4\" Hard Maple")
                .type(MaterialType.HARDWOOD)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(19.05f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.88f, 0.80f, 0.68f, 1f))
                .build());

        register(Material.builder()
                .name("pine-3/4")
                .displayName("3/4\" Pine")
                .type(MaterialType.SOFTWOOD)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(19.05f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.90f, 0.82f, 0.62f, 1f))
                .build());

        // -- Metal (Imperial) --
        register(Material.builder()
                .name("aluminum-1/8")
                .displayName("1/8\" Aluminum Flat Bar")
                .type(MaterialType.METAL)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(3.175f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.75f, 0.75f, 0.78f, 1f))
                .build());

        register(Material.builder()
                .name("aluminum-3/4x3/8")
                .displayName("3/4\" x 3/8\" Aluminum Bar")
                .type(MaterialType.METAL)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(9.525f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.75f, 0.75f, 0.78f, 1f))
                .build());

        // ===================== METRIC =====================

        // -- Plywood (Metric) --
        register(Material.builder()
                .name("plywood-18mm")
                .displayName("18mm Cabinet Plywood")
                .type(MaterialType.PLYWOOD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(18f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.76f, 0.60f, 0.42f, 1f))
                .build());

        register(Material.builder()
                .name("baltic-birch-18mm")
                .displayName("18mm Baltic Birch Plywood (5x5 sheet)")
                .type(MaterialType.PLYWOOD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(18f)
                .sheetWidthMm(1524f).sheetHeightMm(1524f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.76f, 0.60f, 0.42f, 1f))
                .build());

        register(Material.builder()
                .name("plywood-12mm")
                .displayName("12mm Plywood")
                .type(MaterialType.PLYWOOD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(12f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.72f, 0.56f, 0.38f, 1f))
                .build());

        register(Material.builder()
                .name("baltic-birch-9mm")
                .displayName("9mm Baltic Birch Plywood (5x5 sheet)")
                .type(MaterialType.PLYWOOD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(9f)
                .sheetWidthMm(1524f).sheetHeightMm(1524f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.76f, 0.60f, 0.42f, 1f))
                .build());

        register(Material.builder()
                .name("plywood-6mm")
                .displayName("6mm Plywood")
                .type(MaterialType.PLYWOOD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(6f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.78f, 0.62f, 0.44f, 1f))
                .build());

        // -- Hardboard (Metric) --
        register(Material.builder()
                .name("hardboard-5.5mm")
                .displayName("5.5mm Hardboard")
                .type(MaterialType.HARDBOARD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(5.5f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.38f, 0.24f, 0.14f, 1f))
                .build());

        register(Material.builder()
                .name("hardboard-3mm")
                .displayName("3mm Hardboard")
                .type(MaterialType.HARDBOARD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(3f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.35f, 0.22f, 0.12f, 1f))
                .build());

        // -- MDF (Metric) --
        register(Material.builder()
                .name("mdf-18mm")
                .displayName("18mm MDF")
                .type(MaterialType.MDF)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(18f)
                .sheetWidthMm(1220f).sheetHeightMm(2440f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.65f, 0.55f, 0.40f, 1f))
                .build());

        // -- Hardwood (Metric) --
        register(Material.builder()
                .name("poplar-20mm")
                .displayName("20mm Poplar")
                .type(MaterialType.HARDWOOD)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(20f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.82f, 0.78f, 0.65f, 1f))
                .build());

        register(Material.builder()
                .name("oak-20mm")
                .displayName("20mm Red Oak")
                .type(MaterialType.HARDWOOD)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(20f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.72f, 0.52f, 0.32f, 1f))
                .build());

        register(Material.builder()
                .name("maple-20mm")
                .displayName("20mm Hard Maple")
                .type(MaterialType.HARDWOOD)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(20f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.88f, 0.80f, 0.68f, 1f))
                .build());

        register(Material.builder()
                .name("pine-20mm")
                .displayName("20mm Pine")
                .type(MaterialType.SOFTWOOD)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(20f)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.90f, 0.82f, 0.62f, 1f))
                .build());

        // -- Metal (Metric) --
        register(Material.builder()
                .name("aluminum-3mm")
                .displayName("3mm Aluminum Flat Bar")
                .type(MaterialType.METAL)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(3f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.75f, 0.75f, 0.78f, 1f))
                .build());

        register(Material.builder()
                .name("aluminum-20x10mm")
                .displayName("20mm x 10mm Aluminum Bar")
                .type(MaterialType.METAL)
                .kind(MaterialKind.SOLID_LUMBER)
                .thicknessMm(10f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.75f, 0.75f, 0.78f, 1f))
                .build());

        // ===================== STONE / SLAB =====================
        // Slabs are typically sourced per-project, not from standard sheet sizes,
        // so sheetWidthMm/sheetHeightMm stay null. BOM reports per-piece; the
        // guillotine packer skips SLAB kind entirely.

        register(Material.builder()
                .name("granite-3cm")
                .displayName("3cm Granite Slab")
                .type(MaterialType.STONE)
                .kind(MaterialKind.SLAB)
                .thicknessMm(30f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.40f, 0.38f, 0.36f, 1f))
                .build());

        register(Material.builder()
                .name("granite-1-1/4")
                .displayName("1-1/4\" Granite Slab")
                .type(MaterialType.STONE)
                .kind(MaterialKind.SLAB)
                .thicknessMm(31.75f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.IMPERIAL)
                .displayColor(new ColorRGBA(0.40f, 0.38f, 0.36f, 1f))
                .build());

        register(Material.builder()
                .name("quartz-2cm")
                .displayName("2cm Engineered Quartz")
                .type(MaterialType.STONE)
                .kind(MaterialKind.SLAB)
                .thicknessMm(20f)
                .grainDirection(GrainDirection.NONE)
                .measurementSystem(MeasurementSystem.METRIC)
                .displayColor(new ColorRGBA(0.82f, 0.80f, 0.76f, 1f))
                .build());
    }
}
