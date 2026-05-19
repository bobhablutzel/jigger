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

import app.cadette.model.LumberPrices;
import app.cadette.model.MaterialCatalog;
import app.cadette.model.SheetLayout;
import app.cadette.model.SheetLayoutGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lumber packer used to minimize linear feet — which silently
 * preferred long boards because they're cheaper per inch only when
 * you ignore that big-box yards charge less for short stock. Cost
 * optimization picks the right mix.
 */
class LumberCostOptimizationTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    @Test
    void costAwareOptimizerPrefersShorterStockOver16Foot() {
        // 8 parts of 24" 2x4 SPF. Possible packings:
        //   3 × 8'  = 7314 mm linear, ~$8.79
        //   2 × 10' = 6096 mm linear, ~$10.14
        //   2 × 16' = 9754 mm linear, ~$17.96
        // The old linear-feet metric picks 2 × 10'. Cost picks 3 × 8'.
        for (int i = 1; i <= 8; i++) {
            exec("create part \"p" + i + "\" length 610 material \"lumber-2x4-spf\"");
        }
        List<SheetLayout> layouts = SheetLayoutGenerator.generateLayouts(
                sceneManager.getAllParts(), sceneManager.getKerfMm());
        assertFalse(layouts.isEmpty(), "expected at least one layout");
        // All layouts for this single material should use 2438mm (8') stock.
        for (SheetLayout layout : layouts) {
            assertEquals(2438f, layout.getSheetHeightMm(), 1f,
                    "cost optimizer should pick 8' stock; got " + layout.getSheetHeightMm());
        }
    }

    @Test
    void costFunctionRecognizesPiecePriceCurve() {
        // The defaults must encode the user's observation that shorter
        // stock is cheaper per inch. If this regresses, the optimizer
        // loses its bias toward shorter boards.
        Double p8  = LumberPrices.priceFor("lumber-2x4-spf", 2438);
        Double p16 = LumberPrices.priceFor("lumber-2x4-spf", 4877);
        assertNotNull(p8);
        assertNotNull(p16);
        double perIn8  = p8  / (2438 / 25.4);
        double perIn16 = p16 / (4877 / 25.4);
        assertTrue(perIn16 > perIn8,
                "16' should cost more per inch than 8' (got "
              + perIn8 + " vs " + perIn16 + ")");
    }

    @Test
    void metricLumberAliasesToImperialForCost() {
        Double imperial = LumberPrices.priceFor("lumber-2x4-spf", 2438);
        Double metric   = LumberPrices.priceFor("lumber-38x89-spf", 2438);
        assertNotNull(imperial);
        assertEquals(imperial, metric,
                "lumber-38x89-spf should pull its price from lumber-2x4-spf");
    }

    @Test
    void costKeyPassesUnknownSlugsThrough() {
        // Plywood / hardware / made-up materials should resolve to
        // themselves — only metric lumber aliases.
        assertEquals("plywood-3/4", MaterialCatalog.costKey("plywood-3/4"));
        assertEquals("does-not-exist", MaterialCatalog.costKey("does-not-exist"));
    }

    @Test
    void everyCatalogMaterialHasDisplayColor() {
        // Regression guard: shared color constants must be declared
        // before the singleton's INSTANCE field, otherwise static-init
        // order leaves them null at registration time and parts render
        // with a NPE downstream in SceneManager.
        for (var mat : MaterialCatalog.instance().getAll()) {
            assertNotNull(mat.getDisplayColor(),
                    "material '" + mat.getName() + "' has null displayColor");
        }
    }
}
