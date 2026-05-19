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

package app.cadette;

import app.cadette.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferencesTest {

    @Test
    void roundTripsNestedSetGet(@TempDir Path tmp) {
        Preferences p = new Preferences(tmp);
        p.load();
        p.set("dark", "ui", "theme");
        p.set(4.51, "materials", "lumber-2x4-ptl", "prices", "2438");
        p.set(List.of(0.25f, 0.7f), "layout", "splitters");

        // Reload from disk
        Preferences q = new Preferences(tmp);
        q.load();
        assertEquals("dark", q.getString(null, "ui", "theme"));
        assertEquals(4.51f, q.getFloat(0f, "materials",
                "lumber-2x4-ptl", "prices", "2438"), 0.001f);
        List<Object> splitters = q.getList("layout", "splitters");
        assertEquals(2, splitters.size());
        assertEquals(0.25f, ((Number) splitters.get(0)).floatValue(), 0.001f);
    }

    @Test
    void missingPathReturnsDefault(@TempDir Path tmp) {
        Preferences p = new Preferences(tmp);
        p.load();
        assertNull(p.get("nonexistent", "path"));
        assertEquals("fallback", p.getString("fallback", "missing"));
        assertEquals(42, p.getInt(42, "also", "missing"));
    }

    @Test
    void migratesLayoutPropertiesAndActiveTheme(@TempDir Path tmp) throws IOException {
        // Seed legacy files
        Files.writeString(tmp.resolve("layout.properties"),
                "splitter.0=0.25\nsplitter.1=0.7\ntab.0=2\ntab.1=0\n");
        Files.writeString(tmp.resolve("active_theme"), "dark-glass\n");

        Preferences p = new Preferences(tmp);
        p.load();

        assertEquals("dark-glass", p.getString(null, "ui", "theme"));
        List<Object> splitters = p.getList("layout", "splitters");
        assertEquals(2, splitters.size());
        assertEquals(0.25, ((Number) splitters.get(0)).doubleValue(), 0.001);
        assertEquals(0.7, ((Number) splitters.get(1)).doubleValue(), 0.001);
        List<Object> tabs = p.getList("layout", "tabs");
        assertEquals(List.of(2, 0), tabs);

        // Migration writes preferences.yaml; legacy files left alone.
        assertTrue(Files.exists(tmp.resolve("preferences.yaml")));
        assertTrue(Files.exists(tmp.resolve("layout.properties")));
        assertTrue(Files.exists(tmp.resolve("active_theme")));
    }

    @Test
    void preferencesFileWinsOverLegacy(@TempDir Path tmp) throws IOException {
        // Both present — preferences.yaml is canonical.
        Files.writeString(tmp.resolve("preferences.yaml"),
                "ui:\n  theme: light\n");
        Files.writeString(tmp.resolve("active_theme"), "dark\n");

        Preferences p = new Preferences(tmp);
        p.load();
        assertEquals("light", p.getString(null, "ui", "theme"));
    }

    @Test
    void getMapReturnsLumberPriceTable(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("preferences.yaml"),
                "materials:\n"
              + "  lumber-2x4-ptl:\n"
              + "    prices:\n"
              + "      2438: 4.51\n"
              + "      3048: 6.24\n");

        Preferences p = new Preferences(tmp);
        p.load();
        Map<Object, Object> prices = p.getMap(
                "materials", "lumber-2x4-ptl", "prices");
        assertNotNull(prices);
        assertEquals(2, prices.size());
        // YAML integer keys land as Integer; doubles as Double.
        assertEquals(4.51, ((Number) prices.get(2438)).doubleValue(), 0.001);
    }
}
