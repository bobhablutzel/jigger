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
 * Source: https://github.com/bobhablutzel/jigger
 */

package com.jigger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DrawerBoxTemplateTest extends HeadlessTestBase {

    @BeforeEach
    void clearScene() { resetScene();
    }

    @Test
    void testDrawerBoxGeometry() {
        String result = exec("create drawer-box D width 500 height 200 depth 400");
        System.out.println(result);

        System.out.println("\n=== Drawer Box Parts ===");
        for (String part : new String[]{
                "D/left-side", "D/right-side", "D/front",
                "D/back", "D/bottom"}) {
            debugPart(part);
        }

        // After normalization, the assembly bounding box min is at the origin.
        // The back panel should be near z=0 (the back of the box).
        var back = bounds("D/back");
        assertNotNull(back, "back should exist");

        assertTrue(back[0].z < 20f,
                "Back min Z (" + back[0].z + ") should be near 0 (assembly origin)");
    }
}
