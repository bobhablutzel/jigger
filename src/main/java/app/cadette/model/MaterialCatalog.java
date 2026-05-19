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

/* Dimensional lumber sizing. Nominal naming (e.g. "2x4") refers to a board
 * dressed to ~38 × 89 mm. Both nominal and actual appear in displayName so
 * imperial users recognise it; the model uses actual mm throughout. */

/**
 * Registry of available materials. Pre-loaded with common woodworking materials
 * in both imperial and metric naming conventions.
 */
public class MaterialCatalog {

    // Treatment colors for the SPF / PT / GC lumber family. Declared
    // BEFORE INSTANCE — the singleton's constructor calls loadDefaults()
    // which references these, and static fields initialize in source
    // order. Moving these below INSTANCE makes them null at the time
    // loadDefaults runs.
    private static final ColorRGBA SPF_COLOR =
            new ColorRGBA(0.94f, 0.88f, 0.72f, 1f);
    private static final ColorRGBA PT_COLOR =
            new ColorRGBA(0.74f, 0.82f, 0.58f, 1f);
    private static final ColorRGBA GC_COLOR =
            new ColorRGBA(0.58f, 0.72f, 0.48f, 1f);

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

    // Metric-named entries describe the same physical product as their
    // Imperial counterpart (lumber-38x89-spf is just a metric label for
    // lumber-2x4-spf, etc.). Cost lookup uses the Imperial entry as
    // canonical so the user only enters prices in one place.
    private static final Map<String, String> COST_ALIAS = Map.of(
            "lumber-38x89-spf",  "lumber-2x4-spf",
            "lumber-38x140-spf", "lumber-2x6-spf",
            "lumber-89x89-spf",  "lumber-4x4-spf",
            "lumber-19x89-pine", "lumber-1x4-pine");

    /** Map a material slug to the canonical slug under which its cost
     *  is recorded. Metric lumber entries resolve to their Imperial
     *  equivalents; everything else passes through unchanged. */
    public static String costKey(String materialSlug) {
        if (materialSlug == null) return null;
        return COST_ALIAS.getOrDefault(normalize(materialSlug), normalize(materialSlug));
    }

    /**
     * Register a dimensional-lumber material. SHEET_GOOD kind so the existing
     * guillotine packer handles it; cross-section width becomes the sheet
     * width, longest standard length becomes the sheet height. Grain runs
     * along length, as it always does for lumber.
     */
    private void registerLumber(String slug, String displayName, MaterialType type,
                                 float widthMm, float thicknessMm,
                                 List<Float> standardLengthsMm,
                                 MeasurementSystem system, ColorRGBA color) {
        float longest = (float) standardLengthsMm.stream().mapToDouble(Float::doubleValue).max().orElseThrow();
        register(Material.builder()
                .name(slug)
                .displayName(displayName)
                .type(type)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(thicknessMm)
                .sheetWidthMm(widthMm)
                .sheetHeightMm(longest)
                .widthMm(widthMm)
                .standardLengthsMm(standardLengthsMm)
                .grainDirection(GrainDirection.ALONG_LENGTH)
                .measurementSystem(system)
                .displayColor(color)
                .build());
    }

    /** Register a softwood lumber cross-section in SPF (untreated),
     *  PT (pressure-treated, above-ground rated), and GC (ground-contact
     *  rated) variants. Same geometry/lengths across all three — they
     *  differ only in identity (color, BOM line, eventual price). */
    private void registerLumberFamily(String nominal,
                                      float widthMm, float thicknessMm,
                                      List<Float> standardLengthsMm) {
        String suffix = " (" + (int) widthMm + " × " + (int) thicknessMm + "mm)";
        registerLumber("lumber-" + nominal + "-spf",
                nominal + " SPF" + suffix,
                MaterialType.SOFTWOOD, widthMm, thicknessMm,
                standardLengthsMm, MeasurementSystem.IMPERIAL, SPF_COLOR);
        registerLumber("lumber-" + nominal + "-pt",
                nominal + " Pressure-Treated" + suffix,
                MaterialType.SOFTWOOD, widthMm, thicknessMm,
                standardLengthsMm, MeasurementSystem.IMPERIAL, PT_COLOR);
        registerLumber("lumber-" + nominal + "-gc",
                nominal + " Ground-Contact PT" + suffix,
                MaterialType.SOFTWOOD, widthMm, thicknessMm,
                standardLengthsMm, MeasurementSystem.IMPERIAL, GC_COLOR);
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

        // -- Dimensional lumber (Imperial-named) -----------------------------
        // Cross-sections are S4S/dressed dimensions (the actual mm). Stock
        // lengths are 8'/10'/12'/16' — 14' isn't reliably stocked at the
        // big-box stores most users buy from. Each cross-section ships
        // in SPF / PT / GC variants via registerLumberFamily.
        //
        // 8'/10'/12'/16' = 2438/3048/3658/4877 mm.
        // 2x10 and 2x12 typically aren't stocked in 8'; 4x4 typically
        // tops out at 12'.
        registerLumberFamily("2x4", 38f, 89f,
                List.of(2438f, 3048f, 3658f, 4877f));
        registerLumberFamily("2x6", 38f, 140f,
                List.of(2438f, 3048f, 3658f, 4877f));
        registerLumberFamily("2x8", 38f, 184f,
                List.of(2438f, 3048f, 3658f, 4877f));
        registerLumberFamily("2x10", 38f, 235f,
                List.of(3048f, 3658f, 4877f));
        registerLumberFamily("2x12", 38f, 286f,
                List.of(3048f, 3658f, 4877f));
        registerLumberFamily("4x4", 89f, 89f,
                List.of(2438f, 3048f, 3658f));
        // 1x4 pine: trim/board stock, not a structural product — no
        // PT/GC variants.
        registerLumber("lumber-1x4-pine", "1x4 Pine (19 × 89mm)",
                MaterialType.SOFTWOOD, 19f, 89f,
                List.of(1830f, 2438f, 3048f, 3658f),
                MeasurementSystem.IMPERIAL, new ColorRGBA(0.96f, 0.90f, 0.74f, 1f));

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

        // -- Dimensional lumber (Metric-named) -------------------------------
        // Common European-market metric softwood sections. Stock lengths
        // 2.4/3.0/3.6/4.8 m — the 4.2 m equivalent of 14' is omitted to
        // match the Imperial set above.
        registerLumber("lumber-38x89-spf", "38 × 89mm SPF (2x4 equiv.)",
                MaterialType.SOFTWOOD, 38f, 89f,
                List.of(2400f, 3000f, 3600f, 4800f),
                MeasurementSystem.METRIC, SPF_COLOR);
        registerLumber("lumber-38x140-spf", "38 × 140mm SPF (2x6 equiv.)",
                MaterialType.SOFTWOOD, 38f, 140f,
                List.of(2400f, 3000f, 3600f, 4800f),
                MeasurementSystem.METRIC, SPF_COLOR);
        registerLumber("lumber-89x89-spf", "89 × 89mm SPF (4x4 equiv.)",
                MaterialType.SOFTWOOD, 89f, 89f,
                List.of(2400f, 3000f, 3600f),
                MeasurementSystem.METRIC, SPF_COLOR);
        registerLumber("lumber-19x89-pine", "19 × 89mm Pine (1x4 equiv.)",
                MaterialType.SOFTWOOD, 19f, 89f,
                List.of(1800f, 2400f, 3000f, 3600f),
                MeasurementSystem.METRIC, new ColorRGBA(0.96f, 0.90f, 0.74f, 1f));

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
