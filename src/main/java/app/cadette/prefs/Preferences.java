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

package app.cadette.prefs;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Hand-editable user preferences backed by {@code ~/.cadette/preferences.yaml}.
 *
 * <p>Singleton; first call to {@link #instance()} loads the file (creating
 * an empty in-memory tree if missing) and runs a one-shot migration from
 * the older {@code layout.properties} and {@code active_theme} files when
 * {@code preferences.yaml} doesn't yet exist. Mutations auto-save.
 *
 * <p>Read paths use a varargs-of-strings convention to navigate nested
 * maps: {@code getString("ui", "theme")} pulls {@code root["ui"]["theme"]}.
 * Final-segment keys that are numbers in YAML (e.g. {@code prices: { 2438:
 * 4.51 }}) are returned as the YAML parser sees them — use
 * {@link #getMap(String...)} and iterate when keys are numeric.
 */
public final class Preferences {

    private static final Path DEFAULT_DIR =
            Path.of(System.getProperty("user.home"), ".cadette");

    private static Preferences instance;

    /** Process-wide handle pointed at {@code ~/.cadette/preferences.yaml}. */
    public static synchronized Preferences instance() {
        if (instance == null) {
            instance = new Preferences(DEFAULT_DIR);
            instance.load();
        }
        return instance;
    }

    private final Path prefsDir;
    private final Path prefsFile;
    private final Path legacyLayout;
    private final Path legacyTheme;

    private Map<String, Object> root = new LinkedHashMap<>();

    /** Tests construct their own instance over a tmpdir; production code
     *  should call {@link #instance()}. */
    public Preferences(Path prefsDir) {
        this.prefsDir = prefsDir;
        this.prefsFile = prefsDir.resolve("preferences.yaml");
        this.legacyLayout = prefsDir.resolve("layout.properties");
        this.legacyTheme = prefsDir.resolve("active_theme");
    }

    // ---------- generic accessors --------------------------------------

    /** Walk {@code root} along {@code path}; return the final value or
     *  {@code null} if any segment is missing or isn't a map. */
    public Object get(String... path) {
        Object cur = root;
        for (String seg : path) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(seg);
            if (cur == null) return null;
        }
        return cur;
    }

    public String getString(String defaultValue, String... path) {
        Object v = get(path);
        return v == null ? defaultValue : v.toString();
    }

    public Float getFloat(Float defaultValue, String... path) {
        Object v = get(path);
        if (v instanceof Number n) return n.floatValue();
        if (v == null) return defaultValue;
        try {
            return Float.parseFloat(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public Integer getInt(Integer defaultValue, String... path) {
        Object v = get(path);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Walk to {@code path} and return the value as a Map, or an empty
     *  unmodifiable map if missing / wrong type. */
    @SuppressWarnings("unchecked")
    public Map<Object, Object> getMap(String... path) {
        Object v = get(path);
        if (v instanceof Map<?, ?> m) return (Map<Object, Object>) m;
        return Collections.emptyMap();
    }

    /** Walk to {@code path} and return the value as a List, or an empty
     *  unmodifiable list if missing / wrong type. Used by layout state
     *  where order is dense (splitter ratios, tab indices). */
    @SuppressWarnings("unchecked")
    public List<Object> getList(String... path) {
        Object v = get(path);
        if (v instanceof List<?> l) return (List<Object>) l;
        return Collections.emptyList();
    }

    /** Write {@code value} at {@code path}, creating intermediate maps as
     *  needed, and persist. */
    @SuppressWarnings("unchecked")
    public void set(Object value, String... path) {
        if (path.length == 0) throw new IllegalArgumentException("empty path");
        Map<String, Object> cur = root;
        for (int i = 0; i < path.length - 1; i++) {
            Object next = cur.get(path[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(path[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(path[path.length - 1], value);
        save();
    }

    // ---------- persistence --------------------------------------------

    @SuppressWarnings("unchecked")
    public void load() {
        boolean userFileExists = Files.isRegularFile(prefsFile);
        if (userFileExists) {
            try (BufferedReader in = Files.newBufferedReader(prefsFile)) {
                Object parsed = new Yaml().load(in);
                if (parsed instanceof Map<?, ?> m) {
                    root = new LinkedHashMap<>((Map<String, Object>) m);
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("[prefs] couldn't load preferences.yaml: "
                        + e.getMessage() + " — starting fresh");
            }
        } else {
            // Fresh install — pull anything from the legacy files first.
            migrateLegacyFiles();
        }
        // Always overlay bundled defaults: user values win where present,
        // defaults fill missing keys. Catches both fresh installs (file
        // missing entirely) and existing installs that predate a new
        // section in the bundle (e.g. user had layout prefs but no
        // materials section yet).
        boolean filledFromBundle = mergeBundledDefaults();
        if (filledFromBundle || !userFileExists) save();
    }

    /** Read {@code /default-preferences.yaml} from the classpath and
     *  merge it under the current root: any key absent in user file gets
     *  the bundled value; existing user keys are untouched. Returns
     *  {@code true} if anything actually changed (i.e. a save is warranted). */
    @SuppressWarnings("unchecked")
    private boolean mergeBundledDefaults() {
        try (var in = Preferences.class.getResourceAsStream(
                "/default-preferences.yaml")) {
            if (in == null) return false;
            Object parsed = new Yaml().load(in);
            if (!(parsed instanceof Map<?, ?> bundle)) return false;
            return mergeUnder((Map<Object, Object>) bundle,
                              (Map<Object, Object>) (Map<?, ?>) root);
        } catch (IOException | RuntimeException e) {
            System.err.println("[prefs] couldn't load bundled defaults: "
                    + e.getMessage());
            return false;
        }
    }

    /** Deep-merge: for every key in {@code source}, copy into {@code target}
     *  only if the key is absent. Where both have a Map value, recurse.
     *  User values (in {@code target}) always win.
     *
     *  <p>Uses {@code Map<Object, Object>} because YAML mixes key types
     *  freely — integer mm length keys at one level, string slug keys at
     *  another. */
    @SuppressWarnings("unchecked")
    private static boolean mergeUnder(Map<Object, Object> source,
                                      Map<Object, Object> target) {
        boolean changed = false;
        for (Map.Entry<Object, Object> e : source.entrySet()) {
            Object existing = target.get(e.getKey());
            Object incoming = e.getValue();
            if (existing == null) {
                target.put(e.getKey(), incoming);
                changed = true;
            } else if (existing instanceof Map<?, ?>
                    && incoming instanceof Map<?, ?>) {
                if (mergeUnder((Map<Object, Object>) incoming,
                               (Map<Object, Object>) existing)) {
                    changed = true;
                }
            }
            // else: existing scalar/list/etc — user wins, no change.
        }
        return changed;
    }

    /** Pull settings from the pre-YAML files written by earlier sessions.
     *  Legacy files are left in place so the user can verify the result
     *  before deleting them manually. */
    private void migrateLegacyFiles() {
        if (Files.isRegularFile(legacyLayout)) {
            Properties p = new Properties();
            try (BufferedReader in = Files.newBufferedReader(legacyLayout)) {
                p.load(in);
                List<Object> splitters = sparseIndexedList(p, "splitter.");
                List<Object> tabs = sparseIndexedList(p, "tab.");
                Map<String, Object> layout = new LinkedHashMap<>();
                if (!splitters.isEmpty()) layout.put("splitters", splitters);
                if (!tabs.isEmpty()) layout.put("tabs", tabs);
                if (!layout.isEmpty()) root.put("layout", layout);
            } catch (IOException e) {
                System.err.println("[prefs] couldn't migrate layout.properties: "
                        + e.getMessage());
            }
        }
        if (Files.isRegularFile(legacyTheme)) {
            try {
                String name = Files.readString(legacyTheme).trim();
                if (!name.isEmpty()) {
                    Map<String, Object> ui = new LinkedHashMap<>();
                    ui.put("theme", name);
                    root.put("ui", ui);
                }
            } catch (IOException e) {
                System.err.println("[prefs] couldn't migrate active_theme: "
                        + e.getMessage());
            }
        }
    }

    /** Pull keys like {@code prefix0}, {@code prefix1}, ... from a flat
     *  Properties bag into a dense List, padding gaps with {@code null}. */
    private static List<Object> sparseIndexedList(Properties p, String prefix) {
        int max = -1;
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(prefix)) continue;
            try {
                int i = Integer.parseInt(key.substring(prefix.length()));
                if (i > max) max = i;
            } catch (NumberFormatException ignored) { /* skip */ }
        }
        if (max < 0) return List.of();
        List<Object> out = new ArrayList<>(max + 1);
        for (int i = 0; i <= max; i++) out.add(null);
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(prefix)) continue;
            try {
                int i = Integer.parseInt(key.substring(prefix.length()));
                if (i >= 0) out.set(i, parseNumber(p.getProperty(key)));
            } catch (NumberFormatException ignored) { /* skip */ }
        }
        return out;
    }

    private static Object parseNumber(String s) {
        try {
            if (s.contains(".") || s.contains("e") || s.contains("E")) {
                return Double.parseDouble(s);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    /** Write the current tree to {@code preferences.yaml}. Errors are
     *  reported but non-fatal — losing a save shouldn't crash the app. */
    public void save() {
        try {
            Files.createDirectories(prefsDir);
            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setIndent(2);
            opts.setPrettyFlow(true);
            Yaml yaml = new Yaml(opts);
            try (BufferedWriter out = Files.newBufferedWriter(prefsFile)) {
                out.write("# Cadette preferences — hand-editable.\n");
                out.write("# Changes apply on next launch (or save inside the app).\n");
                out.write("\n");
                yaml.dump(root, out);
            }
        } catch (IOException e) {
            System.err.println("[prefs] couldn't save preferences.yaml: "
                    + e.getMessage());
        }
    }

    /** Test hook — drop the in-memory state and reload from disk. Used
     *  by tests that need to reset between runs; production code should
     *  never need this. */
    public static synchronized void resetForTests() {
        instance = null;
    }

}
