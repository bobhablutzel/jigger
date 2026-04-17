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

package com.jigger.command;

import com.jigger.SceneManager;
import com.jme3.math.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Undoable action for rotating an entire assembly.
 * Captures old rotations AND positions since assembly rotation moves parts
 * around the pivot point. Undo restores the exact prior state rather than
 * trying to compute a reverse rotation (which can accumulate floating-point error).
 */
public class RotateAssemblyAction implements UndoableAction {

    private final SceneManager scene;
    private final String assemblyName;
    private final Map<String, Vector3f> oldRotations;
    private final Map<String, Vector3f> oldPositions;
    private final List<String> partNames;
    // Snapshot of new state for redo
    private Map<String, Vector3f> newRotations;
    private Map<String, Vector3f> newPositions;

    public RotateAssemblyAction(SceneManager scene, String assemblyName,
                                Map<String, Vector3f> oldRotations,
                                Map<String, Vector3f> oldPositions,
                                List<String> partNames) {
        this.scene = scene;
        this.assemblyName = assemblyName;
        this.oldRotations = Map.copyOf(oldRotations);
        this.oldPositions = Map.copyOf(oldPositions);
        this.partNames = List.copyOf(partNames);

        // Capture current (post-rotation) state for redo
        this.newRotations = new java.util.LinkedHashMap<>();
        this.newPositions = new java.util.LinkedHashMap<>();
        for (String name : partNames) {
            newRotations.put(name, scene.getRotation(name).clone());
            SceneManager.ObjectRecord rec = scene.getObjectRecord(name);
            if (rec != null) {
                newPositions.put(name, rec.position().clone());
            }
        }
    }

    @Override
    public void undo() {
        for (String name : partNames) {
            Vector3f oldPos = oldPositions.get(name);
            if (oldPos != null) {
                scene.moveObject(name, oldPos);
            }
            Vector3f oldRot = oldRotations.get(name);
            if (oldRot != null) {
                scene.rotateObject(name, oldRot);
            }
        }
    }

    @Override
    public void redo() {
        for (String name : partNames) {
            Vector3f newPos = newPositions.get(name);
            if (newPos != null) {
                scene.moveObject(name, newPos);
            }
            Vector3f newRot = newRotations.get(name);
            if (newRot != null) {
                scene.rotateObject(name, newRot);
            }
        }
    }

    @Override
    public String description() {
        return "rotate assembly \"" + assemblyName + "\" (" + partNames.size() + " parts)";
    }
}
