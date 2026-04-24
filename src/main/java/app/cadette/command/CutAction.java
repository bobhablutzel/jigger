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
import app.cadette.model.Cutout;
import app.cadette.model.Part;
import lombok.RequiredArgsConstructor;

/**
 * Undoable `cut` operation. Adds a {@link Cutout} to a part on {@code redo},
 * removes the same instance on {@code undo}. Cut-sheet is marked dirty on
 * both sides since cutouts show up as operations in the BOM.
 *
 * The cutout reference is held directly (not by index), so undo/redo work
 * correctly even if other cutouts were added or removed between operations.
 */
@RequiredArgsConstructor
public class CutAction implements UndoableAction {

    private final SceneManager scene;
    private final String partName;
    private final Cutout cutout;

    @Override
    public void undo() {
        Part part = scene.getPart(partName);
        if (part != null) part.removeCutout(cutout);
        scene.rebuildPartMesh(partName);
    }

    @Override
    public void redo() {
        Part part = scene.getPart(partName);
        if (part != null) part.addCutout(cutout);
        scene.rebuildPartMesh(partName);
    }

    @Override
    public String description() {
        return "cut on '" + partName + "'";
    }
}
