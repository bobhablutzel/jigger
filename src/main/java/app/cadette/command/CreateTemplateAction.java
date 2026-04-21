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

import java.util.List;

/**
 * Undoable action for template instantiation.
 * Undo removes all parts created by the template. Redo re-creates them.
 */
public class CreateTemplateAction implements UndoableAction {

    private final SceneManager scene;
    private final String assemblyName;
    private final String templateName;
    private final List<Part> createdParts;

    // Hand-coded: defensive List.copyOf so the caller can't mutate the captured
    // parts after construction. @RequiredArgsConstructor would store the
    // caller's list reference directly.
    public CreateTemplateAction(SceneManager scene, String assemblyName,
                                String templateName, List<Part> createdParts) {
        this.scene = scene;
        this.assemblyName = assemblyName;
        this.templateName = templateName;
        this.createdParts = List.copyOf(createdParts);
    }

    @Override
    public void undo() {
        for (Part part : createdParts.reversed()) {
            scene.deleteObject(part.getName());
        }
        scene.removeAssembly(assemblyName);
    }

    @Override
    public void redo() {
        for (Part part : createdParts) {
            scene.createPart(part);
        }
        app.cadette.model.Assembly assembly = new app.cadette.model.Assembly(assemblyName);
        assembly.setTemplateName(templateName);
        for (Part part : createdParts) {
            assembly.addPart(part);
        }
        scene.registerAssembly(assembly);
    }

    @Override
    public String description() {
        return "create " + templateName + " \"" + assemblyName + "\" (" + createdParts.size() + " parts)";
    }
}
