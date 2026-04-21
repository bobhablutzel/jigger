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
import app.cadette.model.Assembly;
import app.cadette.model.Joint;
import app.cadette.model.Part;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;

import java.util.List;

/**
 * Undoable action for deleting an entire assembly.
 * Captures all part data, positions, and joints for restoration.
 */
public class DeleteAssemblyAction implements UndoableAction {

    private final SceneManager scene;
    private final String assemblyName;
    private final String templateName;
    private final List<PartSnapshot> snapshots;
    private final List<Joint> joints;

    public record PartSnapshot(Part part, Vector3f position, Vector3f size,
                               ColorRGBA color, Vector3f rotation) {}

    // Hand-coded: defensive List.copyOf on both collection fields so the
    // captured undo state is insulated from post-construction mutation.
    // @RequiredArgsConstructor would store the caller's references directly.
    public DeleteAssemblyAction(SceneManager scene, String assemblyName, String templateName,
                                List<PartSnapshot> snapshots, List<Joint> joints) {
        this.scene = scene;
        this.assemblyName = assemblyName;
        this.templateName = templateName;
        this.snapshots = List.copyOf(snapshots);
        this.joints = List.copyOf(joints);
    }

    @Override
    public void undo() {
        Assembly assembly = new Assembly(assemblyName);
        assembly.setTemplateName(templateName);
        for (PartSnapshot snap : snapshots) {
            scene.createPart(snap.part());
            if (snap.rotation() != null && !snap.rotation().equals(Vector3f.ZERO)) {
                scene.rotateObject(snap.part().getName(), snap.rotation());
            }
            assembly.addPart(snap.part());
        }
        scene.registerAssembly(assembly);
        for (Joint j : joints) {
            scene.getJointRegistry().addJoint(j);
        }
        scene.markCutSheetDirty();
    }

    @Override
    public void redo() {
        for (PartSnapshot snap : snapshots.reversed()) {
            scene.deleteObject(snap.part().getName());
        }
        scene.removeAssembly(assemblyName);
    }

    @Override
    public String description() {
        return "delete assembly \"" + assemblyName + "\" (" + snapshots.size() + " parts)";
    }
}
