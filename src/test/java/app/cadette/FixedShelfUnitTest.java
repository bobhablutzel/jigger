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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the bundled `fixed_shelf_unit` template. Shelves sit
 * in dados, so they're permanently fixed once assembled — a true adjustable
 * variant (shelf pins) comes later when we have part cutouts. Exercises
 * for-loops and default-param values through a real shipped template.
 */
class FixedShelfUnitTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    @Test
    void defaultShelfCountCreatesThreeShelves() {
        // Omit shelf_count; default=3 should kick in.
        exec("create fixed_shelf_unit SU w 800 h 1800 d 300");

        // The five structural parts are always present.
        assertNotNull(sceneManager.getPart("SU/left-side"));
        assertNotNull(sceneManager.getPart("SU/right-side"));
        assertNotNull(sceneManager.getPart("SU/bottom"));
        assertNotNull(sceneManager.getPart("SU/top"));
        assertNotNull(sceneManager.getPart("SU/back"));

        // Three shelves by default.
        assertNotNull(sceneManager.getPart("SU/shelf_1"));
        assertNotNull(sceneManager.getPart("SU/shelf_2"));
        assertNotNull(sceneManager.getPart("SU/shelf_3"));
        assertNull(sceneManager.getPart("SU/shelf_4"),
                "default shelf_count=3 should stop the loop at 3");
    }

    @Test
    void explicitShelfCountOverridesDefault() {
        exec("create fixed_shelf_unit SU w 800 h 1800 d 300 shelf_count 5");

        assertNotNull(sceneManager.getPart("SU/shelf_1"));
        assertNotNull(sceneManager.getPart("SU/shelf_2"));
        assertNotNull(sceneManager.getPart("SU/shelf_3"));
        assertNotNull(sceneManager.getPart("SU/shelf_4"));
        assertNotNull(sceneManager.getPart("SU/shelf_5"));
        assertNull(sceneManager.getPart("SU/shelf_6"));
    }

    @Test
    void zeroShelfCountOmitsAllShelves() {
        // An empty bookcase — just the five-panel box.
        exec("create fixed_shelf_unit SU w 800 h 1800 d 300 shelf_count 0");

        assertNotNull(sceneManager.getPart("SU/left-side"));
        assertNotNull(sceneManager.getPart("SU/top"));
        assertNull(sceneManager.getPart("SU/shelf_1"),
                "shelf_count=0 should skip the for-loop entirely");
    }
}
