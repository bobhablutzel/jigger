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

import com.jigger.model.Assembly;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for assembly-level operations: move, rotate, delete, list, info.
 */
class AssemblyOperationsTest extends HeadlessTestBase {

    @BeforeEach
    void setup() {
        resetScene();
    }

    private void createTestCabinet() {
        exec("create base-cabinet \"b\" width 600 height 900 depth 400");
    }

    @Test
    void moveAssemblyShiftsAllParts() {
        createTestCabinet();

        // Get original positions of first and last parts
        var leftSideRec = sceneManager.getObjectRecord("b/left-side");
        var bottomRec = sceneManager.getObjectRecord("b/bottom");
        assertNotNull(leftSideRec, "b/left-side should exist");
        assertNotNull(bottomRec, "b/bottom should exist");
        float origLeftX = leftSideRec.position().x;
        float origBottomX = bottomRec.position().x;

        // Move assembly to 100,0,0
        String result = exec("move b to 100,0,0");
        assertTrue(result.contains("assembly"), "Should mention assembly in result");

        // All parts should have shifted by the same delta
        var newLeftRec = sceneManager.getObjectRecord("b/left-side");
        var newBottomRec = sceneManager.getObjectRecord("b/bottom");
        float delta = 100f - origLeftX;  // delta based on bounding box min
        assertEquals(origLeftX + delta, newLeftRec.position().x, 0.1f);
        assertEquals(origBottomX + delta, newBottomRec.position().x, 0.1f);
    }

    @Test
    void moveAssemblyUndoRestoresPositions() {
        createTestCabinet();

        var origRec = sceneManager.getObjectRecord("b/left-side");
        float origX = origRec.position().x;

        exec("move b to 500,0,0");
        assertNotEquals(origX, sceneManager.getObjectRecord("b/left-side").position().x, 0.1f);

        String undoResult = exec("undo");
        assertTrue(undoResult.contains("assembly"), "Undo should mention assembly");
        assertEquals(origX, sceneManager.getObjectRecord("b/left-side").position().x, 0.1f);
    }

    @Test
    void deleteAssemblyRemovesAllParts() {
        createTestCabinet();
        Assembly assembly = sceneManager.getAssembly("b");
        assertNotNull(assembly);
        int partCount = assembly.getParts().size();
        assertTrue(partCount > 0);

        String result = exec("delete b");
        assertTrue(result.contains("assembly"), "Should mention assembly");
        assertTrue(result.contains(String.valueOf(partCount)), "Should mention part count");

        // Assembly and all parts should be gone
        assertNull(sceneManager.getAssembly("b"));
        assertNull(sceneManager.getObjectRecord("b/left-side"));
        assertNull(sceneManager.getObjectRecord("b/bottom"));
    }

    @Test
    void deleteAssemblyUndoRestoresEverything() {
        createTestCabinet();
        int partCount = sceneManager.getAssembly("b").getParts().size();

        exec("delete b");
        assertNull(sceneManager.getAssembly("b"));

        exec("undo");
        Assembly restored = sceneManager.getAssembly("b");
        assertNotNull(restored, "Assembly should be restored");
        assertEquals(partCount, restored.getParts().size(), "All parts should be restored");
        assertNotNull(sceneManager.getObjectRecord("b/left-side"), "Parts should be in scene");
    }

    @Test
    void rotateAssemblyRotatesAllParts() {
        createTestCabinet();

        // Capture original rotations (parts have individual rotations from the template)
        Assembly assembly = sceneManager.getAssembly("b");
        java.util.Map<String, Float> origYRots = new java.util.HashMap<>();
        for (var part : assembly.getParts()) {
            origYRots.put(part.getName(), sceneManager.getRotation(part.getName()).y);
        }

        String result = exec("rotate b 0,45,0");
        assertTrue(result.contains("assembly"), "Should mention assembly");

        // Each part's Y rotation should have increased by 45° (combined with its template rotation)
        for (var part : assembly.getParts()) {
            var rot = sceneManager.getRotation(part.getName());
            float expected = origYRots.get(part.getName()) + 45f;
            assertEquals(expected, rot.y, 1f,
                    part.getName() + " Y rotation should combine template + assembly rotation");
        }
    }

    @Test
    void showInfoOnAssemblyReturnsAssemblyDetails() {
        createTestCabinet();

        String info = exec("show info b");
        assertTrue(info.contains("Assembly:"), "Should show assembly header");
        assertTrue(info.contains("base-cabinet"), "Should show template name");
        assertTrue(info.contains("b/left-side"), "Should list parts");
    }

    @Test
    void listGroupsByAssembly() {
        createTestCabinet();
        // Also create a standalone part
        exec("create part \"standalone\" size 100,100");

        String list = exec("list");
        assertTrue(list.contains("assembly"), "Should show assembly grouping");
        assertTrue(list.contains("base-cabinet"), "Should show template name");
        assertTrue(list.contains("Standalone"), "Should have standalone section");
        assertTrue(list.contains("standalone"), "Should list standalone part");
    }

    @Test
    void individualPartStillWorksWithSlashNotation() {
        createTestCabinet();

        var origRec = sceneManager.getObjectRecord("b/left-side");
        assertNotNull(origRec);
        float origX = origRec.position().x;

        // Move individual part (not the assembly)
        exec("move \"b/left-side\" to 999,0,0");
        assertEquals(999f, sceneManager.getObjectRecord("b/left-side").position().x, 0.1f);

        // Other parts should NOT have moved
        var bottomRec = sceneManager.getObjectRecord("b/bottom");
        assertNotNull(bottomRec);
        // bottom position shouldn't be 999
        assertNotEquals(999f, bottomRec.position().x, 0.1f);
    }

    @Test
    void resizeOnAssemblyGivesHelpfulError() {
        createTestCabinet();

        String result = exec("resize b size 100,100,100");
        assertTrue(result.contains("Cannot resize an assembly"), "Should explain resize doesn't apply");
    }

    @Test
    void displayAndHideNamesOnAssembly() {
        createTestCabinet();

        String result = exec("display name b");
        assertTrue(result.contains("assembly") || result.contains("part(s)"),
                "Should mention assembly or parts");

        result = exec("hide name b");
        assertTrue(result.contains("part(s)") || result.contains("Hidden"),
                "Should confirm hiding");
    }
}
