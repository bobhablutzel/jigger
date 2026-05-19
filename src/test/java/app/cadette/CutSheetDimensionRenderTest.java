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

import app.cadette.model.SheetLayout;
import app.cadette.model.SheetLayoutGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Render-level verification that the cut-sheet dim-line work actually
 * paints pixels where it should. Catches regressions in colour, stroke
 * width, and threshold logic that visual inspection would miss.
 */
class CutSheetDimensionRenderTest extends HeadlessTestBase {

    @BeforeEach
    void clean() { resetScene(); }

    @Test
    void dimLinesRenderAtNormalPanelSizes() {
        exec("create base_cabinet K width 500 height 600 depth 400");
        for (int[] size : new int[][] {{800, 500}, {500, 350}, {300, 250}}) {
            int painted = countDimPixels(size[0], size[1]);
            assertTrue(painted > 0,
                    "no dim pixels at panel size " + size[0] + "x" + size[1]
                  + " — dim-line code didn't fire (or color drift)");
        }
    }

    @Test
    void toeKickDimsRenderAtHighResolution() {
        // The dim-line geometry for tiny cuts (a 75×100mm toe-kick at
        // default scale) is too small to read — by design the pixel
        // threshold suppresses them. At resolution-aware zoom levels
        // those cuts grow enough to dimension, and the new dim lines
        // should add noticeably to the dim-colour pixel count.
        exec("create base_cabinet K1 width 500 height 600 depth 400 toe_kick 1");
        int withToe = countDimPixels(2400, 1600);

        resetScene();
        exec("create base_cabinet K0 width 500 height 600 depth 400 toe_kick 0");
        int withoutToe = countDimPixels(2400, 1600);

        assertTrue(withToe > withoutToe + 50,
                "at high render resolution, toe-kick should contribute "
              + "noticeably more dim-color pixels. withToe=" + withToe
              + " withoutToe=" + withoutToe);
    }

    private int countDimPixels(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            List<SheetLayout> layouts = SheetLayoutGenerator.generateLayouts(
                    sceneManager.getAllParts(), sceneManager.getKerfMm());
            CutSheetRenderer.render(g2, w, h, layouts,
                    executor.getUnits(), false, Set.of(), null,
                    sceneManager.getEffectiveCutouts(),
                    sceneManager.getEffectiveKeeps());
        } finally {
            g2.dispose();
        }
        int dimColorRgb = DimensionLine.DEFAULT_DIM_COLOR.getRGB() & 0x00FFFFFF;
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0x00FFFFFF) == dimColorRgb) count++;
            }
        }
        return count;
    }
}
