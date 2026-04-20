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

package app.cadette.command;

import app.cadette.SceneManager;
import app.cadette.model.Part;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Records all objects that existed before a "delete all" so they can be restored.
 */
@RequiredArgsConstructor
public class DeleteAllAction implements UndoableAction {

    private final SceneManager scene;
    private final List<ObjectSnapshot> snapshots;

    /** Snapshot of an object — includes the Part if it was a part-based object. */
    public record ObjectSnapshot(String name, String shapeType, Vector3f position,
                                 Vector3f size, ColorRGBA color, Part part) {
        /** Convenience constructor for primitives. */
        public ObjectSnapshot(String name, String shapeType, Vector3f position,
                              Vector3f size, ColorRGBA color) {
            this(name, shapeType, position, size, color, null);
        }
    }

    @Override
    public void undo() {
        for (ObjectSnapshot s : snapshots) {
            if (s.part() != null) {
                scene.createPart(s.part());
            } else {
                scene.createObject(s.name(), s.shapeType(), s.position(), s.size(), s.color());
            }
        }
    }

    @Override
    public void redo() {
        scene.deleteAllObjects();
    }

    @Override
    public String description() {
        return "delete all (" + snapshots.size() + " objects)";
    }
}
