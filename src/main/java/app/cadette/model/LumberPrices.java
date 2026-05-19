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

import app.cadette.prefs.Preferences;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-board price lookup for dimensional lumber. The packer uses these
 * to optimize for total dollars rather than total linear feet, so the
 * "right" stock-length mix matches what a woodworker actually pays.
 *
 * <p>Prices are keyed by stock length in mm (matching the catalog's
 * {@code standardLengthsMm}). Engine-internal callers can divide by
 * length to get cost-per-mm, but the user-facing unit is "price per
 * piece" — that's how lumberyards sell.
 *
 * <p><b>Lookup order:</b> user preferences first
 * ({@code materials.<slug>.prices.<lengthMm>} in
 * {@code preferences.yaml}), then the baked-in defaults below, then
 * a falls-back-to-length-only heuristic if a material has no entry
 * at all. Metric lumber resolves to its Imperial canonical slug via
 * {@link MaterialCatalog#costKey} before either lookup.
 *
 * <p>The defaults are <b>rough estimates</b> meant to give the
 * optimizer a sensible cost-curve shape. Real prices vary by region,
 * vendor, and season — users should override per their actual store
 * in {@code preferences.yaml}.
 */
public final class LumberPrices {

    private LumberPrices() { }

    /** Default piece prices ($USD) by material slug → length mm → price.
     *  Sourced from one user's PTL data point (2x4 PT: 8'=$4.51, 10'=$7.80,
     *  12'=$9.36, 16'=$13.82) extrapolated by cross-section area for
     *  the other dimensions and by treatment multiplier (SPF ≈ 0.65× PT,
     *  GC ≈ 1.1× PT). Treat as ballpark only. */
    private static final Map<String, Map<Integer, Double>> DEFAULTS = new LinkedHashMap<>();

    static {
        // 2x4 family (cross-section reference; others scale from this)
        register("lumber-2x4-spf",  Map.of(2438, 2.93, 3048, 5.07, 3658, 6.08, 4877, 8.98));
        register("lumber-2x4-pt",   Map.of(2438, 4.51, 3048, 7.80, 3658, 9.36, 4877, 13.82));
        register("lumber-2x4-gc",   Map.of(2438, 4.96, 3048, 8.58, 3658, 10.30, 4877, 15.20));

        // 2x6 (cross-section ≈ 1.57× 2x4)
        register("lumber-2x6-spf",  scaled("lumber-2x4-spf", 1.57));
        register("lumber-2x6-pt",   scaled("lumber-2x4-pt",  1.57));
        register("lumber-2x6-gc",   scaled("lumber-2x4-gc",  1.57));

        // 2x8 (cross-section ≈ 2.07× 2x4)
        register("lumber-2x8-spf",  scaled("lumber-2x4-spf", 2.07));
        register("lumber-2x8-pt",   scaled("lumber-2x4-pt",  2.07));
        register("lumber-2x8-gc",   scaled("lumber-2x4-gc",  2.07));

        // 2x10 (cross-section ≈ 2.64× 2x4; not commonly stocked in 8')
        register("lumber-2x10-spf", scaledLengths("lumber-2x4-spf", 2.64, 3048, 3658, 4877));
        register("lumber-2x10-pt",  scaledLengths("lumber-2x4-pt",  2.64, 3048, 3658, 4877));
        register("lumber-2x10-gc",  scaledLengths("lumber-2x4-gc",  2.64, 3048, 3658, 4877));

        // 2x12 (cross-section ≈ 3.21× 2x4)
        register("lumber-2x12-spf", scaledLengths("lumber-2x4-spf", 3.21, 3048, 3658, 4877));
        register("lumber-2x12-pt",  scaledLengths("lumber-2x4-pt",  3.21, 3048, 3658, 4877));
        register("lumber-2x12-gc",  scaledLengths("lumber-2x4-gc",  3.21, 3048, 3658, 4877));

        // 4x4 (caps at 12'). Premium over linear-area would predict
        // because timber that thick takes longer to grow.
        register("lumber-4x4-spf", scaledLengths("lumber-2x4-spf", 2.40, 2438, 3048, 3658));
        register("lumber-4x4-pt",  scaledLengths("lumber-2x4-pt",  2.40, 2438, 3048, 3658));
        register("lumber-4x4-gc",  scaledLengths("lumber-2x4-gc",  2.40, 2438, 3048, 3658));

        // 1x4 pine (board stock)
        register("lumber-1x4-pine", Map.of(1830, 4.50, 2438, 5.95, 3048, 7.95, 3658, 9.85));
    }

    private static void register(String slug, Map<Integer, Double> prices) {
        DEFAULTS.put(slug, new LinkedHashMap<>(prices));
    }

    private static Map<Integer, Double> scaled(String fromSlug, double factor) {
        Map<Integer, Double> src = DEFAULTS.get(fromSlug);
        if (src == null) return Map.of();
        Map<Integer, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Double> e : src.entrySet()) {
            out.put(e.getKey(), round2(e.getValue() * factor));
        }
        return out;
    }

    private static Map<Integer, Double> scaledLengths(
            String fromSlug, double factor, int... lengths) {
        Map<Integer, Double> src = DEFAULTS.get(fromSlug);
        if (src == null) return Map.of();
        Map<Integer, Double> out = new LinkedHashMap<>();
        for (int len : lengths) {
            Double base = src.get(len);
            if (base == null) {
                // Linearly interpolate from the two nearest known lengths
                // — only happens when target wants a length the reference
                // family didn't ship (e.g. 4x4 caps shorter than 2x4).
                base = interpolate(src, len);
            }
            if (base != null) out.put(len, round2(base * factor));
        }
        return out;
    }

    private static Double interpolate(Map<Integer, Double> table, int len) {
        Integer below = null, above = null;
        for (int k : table.keySet()) {
            if (k <= len && (below == null || k > below)) below = k;
            if (k >= len && (above == null || k < above)) above = k;
        }
        if (below != null && below.equals(above)) return table.get(below);
        if (below == null && above != null) return table.get(above);
        if (above == null && below != null) return table.get(below);
        if (below == null) return null;
        double bp = table.get(below);
        double ap = table.get(above);
        double t = (len - below) / (double) (above - below);
        return bp + t * (ap - bp);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Price ($USD) of a single board of {@code lengthMm}. Checks user
     *  preferences first; falls back to the built-in defaults; returns
     *  {@code null} if neither source has an entry for this combination. */
    public static Double priceFor(String materialSlug, int lengthMm) {
        String canonical = MaterialCatalog.costKey(materialSlug);

        // Preferences override: materials.<slug>.prices.<lengthMm>
        Object pref = Preferences.instance().get(
                "materials", canonical, "prices", String.valueOf(lengthMm));
        if (pref instanceof Number n) return n.doubleValue();

        // Preferences may also store the prices block as a YAML map
        // with integer keys (the natural shape from hand-editing). The
        // string-key path above misses that case — also check the map.
        Map<Object, Object> userPrices = Preferences.instance().getMap(
                "materials", canonical, "prices");
        if (!userPrices.isEmpty()) {
            for (Map.Entry<Object, Object> e : userPrices.entrySet()) {
                if (e.getKey() instanceof Number n
                        && n.intValue() == lengthMm
                        && e.getValue() instanceof Number p) {
                    return p.doubleValue();
                }
            }
        }

        Map<Integer, Double> defaults = DEFAULTS.get(canonical);
        if (defaults != null) {
            Double v = defaults.get(lengthMm);
            if (v != null) return v;
        }
        return null;
    }

    /** All known stock lengths with prices for a material, defaults
     *  merged with user overrides. Used by callers that want to render
     *  the full price table (e.g. the BOM cost feature). */
    public static Map<Integer, Double> allPricesFor(String materialSlug) {
        String canonical = MaterialCatalog.costKey(materialSlug);
        Map<Integer, Double> merged = new LinkedHashMap<>();
        Map<Integer, Double> defaults = DEFAULTS.get(canonical);
        if (defaults != null) merged.putAll(defaults);
        Map<Object, Object> overrides = Preferences.instance().getMap(
                "materials", canonical, "prices");
        for (Map.Entry<Object, Object> e : overrides.entrySet()) {
            Integer len = null;
            if (e.getKey() instanceof Number n) len = n.intValue();
            else try {
                len = Integer.parseInt(e.getKey().toString());
            } catch (NumberFormatException ignored) { /* skip */ }
            if (len != null && e.getValue() instanceof Number p) {
                merged.put(len, p.doubleValue());
            }
        }
        return merged;
    }

    /** Test hook — exposes the built-in defaults for verification. */
    static Map<String, Map<Integer, Double>> defaultsForTests() {
        Map<String, Map<Integer, Double>> snapshot = new HashMap<>();
        for (Map.Entry<String, Map<Integer, Double>> e : DEFAULTS.entrySet()) {
            snapshot.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return snapshot;
    }
}
