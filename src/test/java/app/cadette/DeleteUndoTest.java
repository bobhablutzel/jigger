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

import com.jme3.math.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for delete + undo: the restored object must keep the
 * rotation it had at delete time (not the default zero rotation).
 */
class DeleteUndoTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    @Test
    void deleteAndUndoRestoresPrimitiveRotation() {
        exec("create box rotated size 1");
        exec("rotate rotated 0, 90, 0");
        Vector3f before = sceneManager.getRotation("rotated").clone();
        assertEquals(90f, before.y, 0.01f);

        exec("delete rotated");
        assertNull(sceneManager.getObjectRecord("rotated"));
        exec("undo");

        assertNotNull(sceneManager.getObjectRecord("rotated"),
                "object should be restored by undo");
        Vector3f after = sceneManager.getRotation("rotated");
        assertEquals(before.x, after.x, 0.01f, "X rotation");
        assertEquals(before.y, after.y, 0.01f, "Y rotation");
        assertEquals(before.z, after.z, 0.01f, "Z rotation");
    }

    @Test
    void deleteAndUndoRestoresPartRotation() {
        exec("create part \"panel\" size 400, 600 at 0, 0, 0 grain vertical");
        exec("rotate \"panel\" -45, 0, 30");
        Vector3f before = sceneManager.getRotation("panel").clone();

        exec("delete \"panel\"");
        exec("undo");

        assertNotNull(sceneManager.getObjectRecord("panel"));
        Vector3f after = sceneManager.getRotation("panel");
        assertEquals(before.x, after.x, 0.01f);
        assertEquals(before.y, after.y, 0.01f);
        assertEquals(before.z, after.z, 0.01f);
    }

    @Test
    void deleteAllAndUndoRestoresRotations() {
        exec("create box a size 1");
        exec("rotate a 0, 90, 0");
        exec("create box b size 1");
        exec("rotate b -45, 0, 22.5");

        exec("delete all");
        exec("undo");

        assertEquals(90f, sceneManager.getRotation("a").y, 0.01f, "a Y rotation");
        assertEquals(-45f, sceneManager.getRotation("b").x, 0.01f, "b X rotation");
        assertEquals(22.5f, sceneManager.getRotation("b").z, 0.01f, "b Z rotation");
    }
}
