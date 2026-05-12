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

import app.cadette.model.Material;
import app.cadette.model.MaterialCatalog;
import app.cadette.model.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for dimensional lumber: catalog entries, `length` clause on
 * create-part, and BOM rendering as boards rather than sheets.
 */
class DimensionalLumberTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    @Test
    void catalogHasDimensionalLumberEntries() {
        Material twoByFour = MaterialCatalog.instance().get("lumber-2x4-spf");
        assertNotNull(twoByFour);
        assertTrue(twoByFour.isDimensionalLumber());
        assertEquals(38f, twoByFour.getWidthMm());
        assertEquals(89f, twoByFour.getThicknessMm());
        assertFalse(twoByFour.getStandardLengthsMm().isEmpty());
        // Sheet dims mirror the lumber dims so the existing packer can place
        // it without modification.
        assertEquals(38f, twoByFour.getSheetWidthMm());
        assertNotNull(twoByFour.getSheetHeightMm());
    }

    @Test
    void plywoodIsNotDimensionalLumber() {
        Material plywood = MaterialCatalog.instance().get("plywood-18mm");
        assertNotNull(plywood);
        assertFalse(plywood.isDimensionalLumber());
        assertNull(plywood.getWidthMm());
    }

    @Test
    void lengthClauseDefaultsWidthFromMaterial() {
        String result = exec("create part \"rail\" length 600 material \"lumber-2x4-spf\"");
        assertTrue(result.contains("Created"), result);
        Part p = sceneManager.getPart("rail");
        assertNotNull(p);
        assertEquals(38f, p.getCutWidthMm(), 0.01f);
        assertEquals(600f, p.getCutHeightMm(), 0.01f);
    }

    @Test
    void lengthRejectsSheetGoodMaterial() {
        String result = exec("create part \"oops\" length 600 material \"plywood-18mm\"");
        assertTrue(result.toLowerCase().contains("dimensional-lumber") ||
                   result.toLowerCase().contains("dimensional lumber"), result);
        assertNull(sceneManager.getPart("oops"));
    }

    @Test
    void sizeOnLumberValidatesWidthMatches() {
        // Wrong width on a lumber material is an error, not a silent override.
        String result = exec("create part \"oops\" size 100, 600 material \"lumber-2x4-spf\"");
        assertTrue(result.toLowerCase().contains("cross-section")
                || result.toLowerCase().contains("doesn't match"), result);
        assertNull(sceneManager.getPart("oops"));
    }

    @Test
    void sizeOnLumberWithMatchingWidthAccepted() {
        String result = exec("create part \"ok\" size 38, 600 material \"lumber-2x4-spf\"");
        assertTrue(result.contains("Created"), result);
        assertEquals(38f, sceneManager.getPart("ok").getCutWidthMm(), 0.01f);
    }

    @Test
    void lengthOnSheetGoodIsRejected() {
        // Sheets need size W, L — `length` alone doesn't apply.
        String result = exec("create part \"p\" length 600 material \"plywood-18mm\"");
        assertTrue(result.toLowerCase().contains("dimensional"), result);
    }

    @Test
    void cannotCombineSizeAndLength() {
        String result = exec("create part \"oops\" size 38, 600 length 600 material \"lumber-2x4-spf\"");
        assertTrue(result.toLowerCase().contains("either")
                || result.toLowerCase().contains("not both"), result);
    }

    @Test
    void bomRendersLumberAsBoardsNotSheets() {
        exec("create part \"rail1\" length 600 material \"lumber-2x4-spf\"");
        exec("create part \"rail2\" length 800 material \"lumber-2x4-spf\"");
        String bom = exec("show bom");
        assertTrue(bom.toLowerCase().contains("board"), bom);
        assertFalse(bom.contains("sheet"), "Lumber should not render as 'sheet': " + bom);
    }

    @Test
    void bomRendersPlywoodAsSheetsNotBoards() {
        exec("create part \"top\" size 600, 900 material \"plywood-18mm\"");
        String bom = exec("show bom");
        assertTrue(bom.toLowerCase().contains("sheet"), bom);
    }

    @Test
    void packerPicksShortestStockLengthThatFits() {
        // 3 × 24" parts on a 2x4 should pick an 8' board, not 16'.
        // 24" = 609.6mm; 8' = 2438mm. Three parts + kerfs ≈ 1834mm, fits.
        exec("create part \"a\" length 610 material \"lumber-2x4-spf\"");
        exec("create part \"b\" length 610 material \"lumber-2x4-spf\"");
        exec("create part \"c\" length 610 material \"lumber-2x4-spf\"");
        String bom = exec("show bom");
        assertTrue(bom.contains("8 ft"), "Expected 8 ft board, got: " + bom);
        assertFalse(bom.contains("16 ft"), "Should not pick 16 ft: " + bom);
        assertTrue(bom.contains("1 board"), bom);
    }

    @Test
    void packerPicksLongerLengthWhenShortDoesntFit() {
        // 100" parts can't fit on 8' (96") — must use 10' or longer.
        // 100" = 2540mm; smallest stock >= 2540 is 10' (3048mm).
        exec("create part \"long1\" length 2540 material \"lumber-2x4-spf\"");
        exec("create part \"long2\" length 2540 material \"lumber-2x4-spf\"");
        String bom = exec("show bom");
        assertTrue(bom.contains("10 ft"), "Expected 10 ft board, got: " + bom);
        assertFalse(bom.contains("8 ft"), "Should not pick 8 ft: " + bom);
    }

    @Test
    void lumberLengthDisplaysInFeetForImperialEvenIfUnitsAreMm() {
        exec("set units mm");
        exec("create part \"r\" length 610 material \"lumber-2x4-spf\"");
        String bom = exec("show bom");
        assertTrue(bom.contains("ft"), "Lumber length should be in feet: " + bom);
        assertFalse(bom.contains(" mm long"), bom);
    }

    @Test
    void lumberLengthDisplaysInMetersForMetricSlug() {
        exec("create part \"r\" length 600 material \"lumber-38x89-spf\"");
        String bom = exec("show bom");
        assertTrue(bom.contains(" m long") || bom.contains(" m,"),
                "Lumber length should be in meters: " + bom);
        assertFalse(bom.contains("ft"), bom);
    }

    @Test
    void multipleLumberPartsPackOntoFewerBoards() {
        // Several short pieces should fit on a single 8' (or longer) board
        // rather than one board per part.
        for (int i = 1; i <= 4; i++) {
            exec("create part \"r" + i + "\" length 500 material \"lumber-2x4-spf\"");
        }
        String bom = exec("show bom");
        // 4 × 500 = 2000mm, comfortably under any standard stock length.
        assertTrue(bom.contains("1 board") || bom.contains("2 board"), bom);
        // Not, say, "4 boards".
        assertFalse(bom.contains("4 board"), bom);
    }
}
