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

import app.cadette.model.CutListGenerator;
import app.cadette.model.Material;
import app.cadette.model.Part;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CSV cut-list export: header row, per-part rows, unit conversion,
 * and RFC 4180 CSV quoting for fields containing commas or quotes.
 */
class CsvExportTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    private List<CutListGenerator.CutListEntry> cutList() {
        return CutListGenerator.generateCutList(sceneManager.getAllParts(), sceneManager.getJointRegistry());
    }

    @Test
    void headerAndDataRowsInCurrentUnits() throws IOException {
        exec("set units mm");
        exec("create part \"panel\" size 400, 600 at 0, 0, 0 grain vertical");

        Path out = Files.createTempFile("cadette-csv-", ".csv");
        out.toFile().deleteOnExit();
        CutListExporter.exportCsv(cutList(), UnitSystem.MILLIMETERS, out);

        List<String> lines = Files.readAllLines(out);
        assertEquals("Part,Material,Width (mm),Height (mm),Thickness (mm),Grain,Operations",
                lines.get(0));
        // Row 1 data
        String[] cols = lines.get(1).split(",", -1);
        assertEquals("panel", cols[0]);
        assertEquals("400.00", cols[2], "width in mm");
        assertEquals("600.00", cols[3], "height in mm");
        assertEquals("vertical", cols[5]);
    }

    @Test
    void imperialUnitsInHeaderAndValues() throws IOException {
        // In inches: "size 10, 20" means 10 in × 20 in — stored internally as
        // 254 mm × 508 mm. Output in inches should round-trip to 10.00 × 20.00.
        exec("set units inches");
        exec("create part \"wide\" size 10, 20 at 0, 0, 0");

        Path out = Files.createTempFile("cadette-csv-", ".csv");
        out.toFile().deleteOnExit();
        CutListExporter.exportCsv(cutList(), UnitSystem.INCHES, out);

        List<String> lines = Files.readAllLines(out);
        assertTrue(lines.get(0).contains("Width (in)"), "header should use 'in': " + lines.get(0));
        assertTrue(lines.get(1).contains("10.00"), "width in inches: " + lines.get(1));
        assertTrue(lines.get(1).contains("20.00"), "height in inches: " + lines.get(1));
    }

    @Test
    void quotingForNamesWithCommasAndQuotes() throws IOException {
        // We can't easily create a part with commas/quotes via the command grammar,
        // so build the entry directly to exercise the CSV escaper.
        Material mat = app.cadette.model.MaterialCatalog.instance().get("plywood-18mm");
        Part part = Part.builder()
                .name("tricky,name\"with\"quotes")
                .material(mat)
                .cutWidthMm(100f).cutHeightMm(200f)
                .position(new com.jme3.math.Vector3f(0, 0, 0))
                .grainRequirement(app.cadette.model.GrainRequirement.ANY)
                .build();
        var entry = new CutListGenerator.CutListEntry(
                part.getName(), mat, 100f, 200f, 18f,
                part.getGrainRequirement(), List.of("dado 9.0mm deep for \"bottom\""));

        Path out = Files.createTempFile("cadette-csv-", ".csv");
        out.toFile().deleteOnExit();
        CutListExporter.exportCsv(List.of(entry), UnitSystem.MILLIMETERS, out);

        String content = Files.readString(out);
        // Name field: commas and quotes force quoting, embedded quotes are doubled
        assertTrue(content.contains("\"tricky,name\"\"with\"\"quotes\""),
                "name should be CSV-quoted with doubled quotes:\n" + content);
        // Operations field has a quote → must be quoted
        assertTrue(content.contains("\"dado 9.0mm deep for \"\"bottom\"\"\""),
                "operations should be CSV-quoted:\n" + content);
    }

    @Test
    void exportCommandWritesFile() throws IOException {
        exec("set units mm");
        exec("create part \"p1\" size 100, 200 at 0, 0, 0");

        Path out = Files.createTempFile("cadette-export-", ".csv");
        out.toFile().deleteOnExit();
        String result = exec("export cutlist csv \"" + out.toString() + "\"");
        assertTrue(result.contains("Exported cut list"), "should confirm export: " + result);

        String content = Files.readString(out);
        assertTrue(content.startsWith("Part,Material,Width"), "CSV should have header: " + content);
        assertTrue(content.contains("p1"), "CSV should contain part name: " + content);
    }
}
