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

package app.cadette.theme;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.math.ColorRGBA;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.style.Attributes;
import com.simsilica.lemur.style.Styles;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads {@code .cdt} (Cadette theme) YAML files from the bundled
 * resources and the user directory, resolves their {@code extends}
 * chains, and applies the active theme to Lemur's {@link Styles}.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Bundled built-ins under {@code resources/themes/*.cdt} —
 *       dark, light, dark-glass, light-glass, dark-high-contrast,
 *       light-high-contrast.</li>
 *   <li>User overlays at {@code ~/.cadette/themes/*.cdt} — a user
 *       file with the same name as a built-in wins.</li>
 * </ol>
 *
 * <p>{@code applyTheme(name)} writes attributes into the Lemur Styles
 * for the "glass" style group, then calls {@code styles.clearCache()}
 * so widgets re-read on next style query. New widgets created after
 * the apply pick up the new values automatically; live re-styling of
 * already-constructed widgets is a follow-up item (see
 * {@code project_lemur_theme_backlog.md}).
 *
 * <p>Supported per-element attributes today:
 * <ul>
 *   <li>{@code fontSize}: Number, applied as float.</li>
 *   <li>{@code color}: hex string like {@code "#ddeeff"} or
 *       {@code "#ddeeffcc"} (with alpha) → ColorRGBA.</li>
 *   <li>{@code highlightColor} / {@code focusColor} (Button state colors —
 *       hover and pressed/focused). Same hex format as {@code color}.
 *       Override the Lemur defaults (yellow, green respectively).</li>
 *   <li>{@code shadowColor} / {@code highlightShadowColor} /
 *       {@code focusShadowColor}: hex string → ColorRGBA. For text shadow
 *       on Buttons / Labels.</li>
 *   <li>{@code background}: hex string → flat
 *       QuadBackgroundComponent.</li>
 *   <li>{@code insets}: a single number for uniform padding, or a list
 *       {@code [top, left, bottom, right]} → Lemur {@code Insets3f}.
 *       Useful on the {@code button} selector to give tabs more
 *       visual weight.</li>
 *   <li>{@code font}: string path resolvable by jME3's AssetManager.
 *       Looked up in this order — {@code ~/.cadette/fonts/<value>},
 *       {@code Interface/Fonts/<value>}, then the value as a
 *       direct asset path. Falls back to the Lemur default font on
 *       miss with a warn-once.</li>
 * </ul>
 */
public class ThemeRegistry {

    private static final String BUILTIN_RESOURCE_DIR = "/themes/";
    private static final String[] BUILTINS = {
            "dark",
            "light",
            "dark-glass",
            "light-glass",
            "dark-high-contrast",
            "light-high-contrast"
    };
    private static final Path USER_THEME_DIR =
            Path.of(System.getProperty("user.home"), ".cadette", "themes");

    private final Map<String, Theme> themes = new TreeMap<>();
    private final AssetManager assetManager;
    /** Style group all theme attributes are applied to. Lemur applies
     *  this group automatically since {@code setDefaultStyle("glass")}
     *  is set on the Styles object in CadetteApp. */
    private static final String STYLE_GROUP = "glass";

    public ThemeRegistry(AssetManager assetManager) {
        this.assetManager = assetManager;
        loadBuiltins();
        loadUserOverlays();
    }

    /** All known theme names, alphabetical. Used for {@code show themes}
     *  and for autocomplete-eligible UIs later. */
    public List<String> listThemes() {
        return new ArrayList<>(themes.keySet());
    }

    public Theme getTheme(String name) {
        return themes.get(name);
    }

    /** Walk a scene-graph subtree and re-apply Lemur styles to every
     *  Panel-derived widget found. Needed after a theme switch — the
     *  Styles object's new attributes don't auto-propagate to widgets
     *  that were constructed before the switch, so we have to push
     *  them out explicitly via {@code styles.applyStyles(...)}.
     *
     *  <p>Only style-driven attributes get reset; programmatic setters
     *  (setPreferredSize, setText, manually-set background components,
     *  etc.) are left alone.
     *
     *  <p>ListBox subtrees are skipped entirely — calling applyStyles
     *  on the ListBox itself or any of its internal components
     *  (GridPanel, Slider, cell renderers) collapses the output area
     *  to a single visible row, even though programmatic sizing
     *  remains correct (verified by diagnostic — see
     *  {@code project_theme_listbox_collapse_backlog.md}). The
     *  ListBox's cell colors and backgrounds take effect on next
     *  launch instead.
     *
     *  <p>Live-restyle every other Panel including Labels, TextFields,
     *  Buttons, Containers, and our parts-row sub-containers. */
    /** Apply the named theme to {@code styles}. Throws
     *  IllegalArgumentException if the theme is unknown — callers
     *  should call {@link #listThemes()} for valid names. */
    public void applyTheme(String name, Styles styles) {
        Theme target = themes.get(name);
        if (target == null) {
            throw new IllegalArgumentException("Unknown theme: " + name
                    + " (known: " + listThemes() + ")");
        }
        Map<String, Map<String, Object>> resolved = resolveWithExtends(target);
        for (var entry : resolved.entrySet()) {
            String elementId = entry.getKey();
            Attributes attrs = styles.getSelector(elementId, STYLE_GROUP);
            for (var attr : entry.getValue().entrySet()) {
                Object converted = convertValue(attr.getKey(), attr.getValue());
                if (converted != null) {
                    attrs.set(attr.getKey(), converted);
                }
            }
        }
        styles.clearCache();
    }

    /** Flatten {@code theme.extends} chains by deep-merging parent
     *  element maps under the child's overrides. Cycle-protected. */
    private Map<String, Map<String, Object>> resolveWithExtends(Theme theme) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        List<Theme> chain = new ArrayList<>();
        Theme cursor = theme;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (cursor != null) {
            if (!seen.add(cursor.name())) break; // cycle
            chain.add(cursor);
            cursor = cursor.extendsFrom() != null ? themes.get(cursor.extendsFrom()) : null;
        }
        // Apply ancestors first (root → child), so child overrides win.
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (var entry : chain.get(i).elements().entrySet()) {
                merged.computeIfAbsent(entry.getKey(), k -> new LinkedHashMap<>())
                      .putAll(entry.getValue());
            }
        }
        return merged;
    }

    /** Convert a YAML-loaded value (String, Number, etc.) into the
     *  Lemur-typed object the style attribute expects. Returns null
     *  for unknown attributes so the apply loop just skips them. */
    private Object convertValue(String attrName, Object yamlValue) {
        switch (attrName) {
            case "fontSize" -> {
                if (yamlValue instanceof Number n) return n.floatValue();
                return null;
            }
            case "color", "highlightColor", "focusColor",
                    "shadowColor", "highlightShadowColor", "focusShadowColor",
                    "activeBackground", "activeColor" -> {
                // Color-shaped attributes — Lemur Button uses highlightColor
                // (hover) and focusColor (clicked/keyboard focus), defaulting
                // to yellow + green respectively. Themes need to override
                // those or the tabs flash technicolor on interaction.
                //
                // `activeBackground` / `activeColor` are custom keys read by
                // LemurTabHost — when a theme provides them, the active tab
                // uses these explicit values instead of the derived
                // `lighten(baseBg, 12%)` highlight. Necessary for HC themes
                // where lightening pure black or pure white is meaningless.
                if (yamlValue instanceof String s) return parseHexColor(s);
                return null;
            }
            case "background" -> {
                if (yamlValue instanceof String s) {
                    ColorRGBA c = parseHexColor(s);
                    return c != null ? new QuadBackgroundComponent(c) : null;
                }
                return null;
            }
            case "insets" -> {
                // [top, left, bottom, right]. A single number is shorthand
                // for uniform padding. Lemur's Insets3f takes (min1, min2,
                // max1, max2) which is (top, left, bottom, right) in the
                // GUI Y-up coord system.
                if (yamlValue instanceof Number n) {
                    float v = n.floatValue();
                    return new com.simsilica.lemur.Insets3f(v, v, v, v);
                }
                if (yamlValue instanceof List<?> list && list.size() == 4
                        && list.stream().allMatch(o -> o instanceof Number)) {
                    return new com.simsilica.lemur.Insets3f(
                            ((Number) list.get(0)).floatValue(),
                            ((Number) list.get(1)).floatValue(),
                            ((Number) list.get(2)).floatValue(),
                            ((Number) list.get(3)).floatValue());
                }
                return null;
            }
            case "font" -> {
                if (yamlValue instanceof String s) return loadFont(s);
                return null;
            }
            default -> {
                // Unknown attribute — Lemur's Attributes is permissive,
                // we could pass through but it's safer to skip and let
                // the user notice when their typo doesn't change anything.
                return null;
            }
        }
    }

    /** Parse {@code #rrggbb} or {@code #rrggbbaa} hex strings into a
     *  jME3 {@link ColorRGBA}. Returns null on malformed input.
     *
     *  <p>Constructs a raw ColorRGBA from {@code value/255f}. jME3's GUI
     *  framebuffer gamma-encodes linear → sRGB at output, so the visible
     *  pixel will be roughly {@code (hex/255)^(1/2.2) * 255}, i.e. lighter
     *  than the hex you picked. Empirically iterate the YAML hex darker
     *  until the visible color matches.
     *
     *  <p>(We tried routing through {@link GuiGlobals#srgbaColor} which
     *  does an sRGB → linear conversion intended to give 1:1 round-trip,
     *  but the visible result came out about half as bright as expected —
     *  likely a quirk of Lemur's Unshaded GUI material combined with how
     *  the framebuffer / screenshot pipeline reports pixel values. The
     *  raw-division approach is simpler and lets the user iterate
     *  visually.) */
    static ColorRGBA parseHexColor(String hex) {
        if (hex == null) return null;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6 && s.length() != 8) return null;
        try {
            float r = Integer.parseInt(s.substring(0, 2), 16) / 255f;
            float g = Integer.parseInt(s.substring(2, 4), 16) / 255f;
            float b = Integer.parseInt(s.substring(4, 6), 16) / 255f;
            float a = s.length() == 8
                    ? Integer.parseInt(s.substring(6, 8), 16) / 255f
                    : 1f;
            return new ColorRGBA(r, g, b, a);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private final java.util.Set<String> warnedMissingFonts = new java.util.HashSet<>();

    /** Resolve a font name through the documented path order. Returns
     *  null on miss (caller skips the attribute, falling back to the
     *  Lemur default font). */
    private BitmapFont loadFont(String fontName) {
        if (assetManager == null) return null;
        // Tried-paths order:
        //   user dir, well-known interface fonts, raw value as asset path
        List<String> attempts = List.of(
                System.getProperty("user.home") + "/.cadette/fonts/" + fontName,
                "Interface/Fonts/" + fontName,
                fontName);
        for (String path : attempts) {
            try {
                return assetManager.loadFont(path);
            } catch (Exception ignored) {
                // keep trying
            }
        }
        if (warnedMissingFonts.add(fontName)) {
            System.err.println("[theme] font not found: " + fontName
                    + " — falling back to default");
        }
        return null;
    }

    private void loadBuiltins() {
        Yaml yaml = new Yaml();
        for (String name : BUILTINS) {
            String path = BUILTIN_RESOURCE_DIR + name + ".cdt";
            try (InputStream in = getClass().getResourceAsStream(path)) {
                if (in == null) {
                    System.err.println("[theme] built-in missing: " + path);
                    continue;
                }
                Map<String, Object> raw = yaml.load(in);
                Theme t = themeFromYaml(raw);
                if (t != null) themes.put(t.name(), t);
            } catch (IOException | RuntimeException e) {
                System.err.println("[theme] failed to load " + path + ": " + e.getMessage());
            }
        }
    }

    private void loadUserOverlays() {
        if (!Files.isDirectory(USER_THEME_DIR)) return;
        Yaml yaml = new Yaml();
        try (var stream = Files.list(USER_THEME_DIR)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".cdt"))
                  .forEach(p -> {
                      try (InputStream in = Files.newInputStream(p)) {
                          Map<String, Object> raw = yaml.load(in);
                          Theme t = themeFromYaml(raw);
                          if (t != null) themes.put(t.name(), t);
                      } catch (IOException | RuntimeException e) {
                          System.err.println("[theme] failed to load " + p + ": "
                                  + e.getMessage());
                      }
                  });
        } catch (IOException e) {
            System.err.println("[theme] couldn't enumerate " + USER_THEME_DIR
                    + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Theme themeFromYaml(Map<String, Object> raw) {
        if (raw == null) return null;
        Object nameObj = raw.get("name");
        if (!(nameObj instanceof String name)) return null;
        String description = raw.get("description") instanceof String d ? d : null;
        String extendsFrom = raw.get("extends") instanceof String x ? x : null;
        Map<String, Map<String, Object>> elements = new HashMap<>();
        Object e = raw.get("elements");
        if (e instanceof Map<?, ?> em) {
            for (var entry : em.entrySet()) {
                if (entry.getKey() instanceof String k
                        && entry.getValue() instanceof Map<?, ?> v) {
                    elements.put(k, (Map<String, Object>) v);
                }
            }
        }
        return new Theme(name, description, extendsFrom, elements);
    }
}
