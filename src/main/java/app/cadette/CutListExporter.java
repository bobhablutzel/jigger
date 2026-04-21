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

import app.cadette.model.CutListGenerator.CutListEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports cut-list data (one row per part) to a CSV file. Distinct from
 * {@link CutSheetExporter}, which renders graphical sheet layouts.
 *
 * The output uses the user's current display units. Part names and operation
 * text are CSV-escaped per RFC 4180: fields containing commas, quotes, or
 * newlines are wrapped in double quotes, with embedded quotes doubled.
 */
public class CutListExporter {

    private static final String NEWLINE = "\r\n"; // RFC 4180

    public static void exportCsv(List<CutListEntry> entries, UnitSystem units, Path outputPath)
            throws IOException {
        String abbr = units.getAbbreviation();
        StringBuilder sb = new StringBuilder();

        sb.append("Part,Material,")
                .append("Width (").append(abbr).append("),")
                .append("Height (").append(abbr).append("),")
                .append("Thickness (").append(abbr).append("),")
                .append("Grain,Operations").append(NEWLINE);

        for (CutListEntry e : entries) {
            sb.append(csv(e.getPartName())).append(',')
                    .append(csv(e.getMaterial().getDisplayName())).append(',')
                    .append(String.format("%.2f", units.fromMm(e.getCutWidthMm()))).append(',')
                    .append(String.format("%.2f", units.fromMm(e.getCutHeightMm()))).append(',')
                    .append(String.format("%.2f", units.fromMm(e.getThicknessMm()))).append(',')
                    .append(csv(e.getGrainRequirement().name().toLowerCase())).append(',')
                    .append(csv(String.join("; ", e.getOperations())))
                    .append(NEWLINE);
        }

        Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
    }

    /** RFC 4180 CSV escape: wrap in quotes if the value has a comma, quote, or newline. */
    private static String csv(String value) {
        if (value == null) return "";
        boolean needsQuoting = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuoting) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
