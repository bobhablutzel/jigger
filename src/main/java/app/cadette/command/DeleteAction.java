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

@RequiredArgsConstructor
public class DeleteAction implements UndoableAction {

    private final SceneManager scene;
    private final String name;
    private final String shapeType;
    private final Vector3f position;
    private final Vector3f size;
    private final ColorRGBA color;
    private final Part part;  // non-null if this was a part, null for primitives

    // Hand-coded: delegating convenience ctor for primitives (no Part to
    // restore). @RequiredArgsConstructor generates the full 7-arg ctor;
    // this 6-arg overload supplies part=null so callers don't have to.
    public DeleteAction(SceneManager scene, String name, String shapeType,
                        Vector3f position, Vector3f size, ColorRGBA color) {
        this(scene, name, shapeType, position, size, color, null);
    }

    @Override
    public void undo() {
        if (part != null) {
            scene.createPart(part);
        } else {
            scene.createObject(name, shapeType, position, size, color);
        }
    }

    @Override
    public void redo() {
        scene.deleteObject(name);
    }

    @Override
    public String description() {
        return "delete \"" + name + "\"";
    }
}
