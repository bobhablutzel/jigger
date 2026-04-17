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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tracks the currently selected object or assembly.
 * Clicking a part that belongs to an assembly selects the assembly.
 * Notifies listeners on selection change.
 */
public class SelectionManager {

    private final SceneManager sceneManager;
    private String selectedName;       // assembly name or standalone part/object name
    private boolean isAssembly;
    private final List<Consumer<SelectionEvent>> listeners = new ArrayList<>();

    public record SelectionEvent(String name, boolean isAssembly, String templateName, int partCount) {
        /** Creates an empty (deselect) event. */
        static SelectionEvent empty() {
            return new SelectionEvent(null, false, null, 0);
        }
    }

    public SelectionManager(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    /**
     * Select by geometry name (as found by ray cast).
     * The geometry name matches the part/object name. If the part belongs
     * to an assembly, the assembly is selected instead.
     */
    public void selectByPartName(String partName) {
        if (partName == null) {
            deselect();
            return;
        }

        // Check if this part belongs to an assembly (name contains "/")
        String assemblyName = null;
        int slash = partName.indexOf('/');
        if (slash > 0) {
            assemblyName = partName.substring(0, slash);
        }

        // Verify the assembly exists
        if (assemblyName != null && sceneManager.getAssembly(assemblyName) != null) {
            select(assemblyName, true);
        } else {
            select(partName, false);
        }
    }

    private void select(String name, boolean assembly) {
        if (name.equals(selectedName)) return;  // already selected
        selectedName = name;
        isAssembly = assembly;
        fireEvent();
    }

    public void deselect() {
        if (selectedName == null) return;
        selectedName = null;
        isAssembly = false;
        fireEvent();
    }

    public String getSelectedName() {
        return selectedName;
    }

    public boolean isAssemblySelected() {
        return isAssembly;
    }

    /** Get all part names that are part of the current selection (for highlighting). */
    public List<String> getSelectedPartNames() {
        if (selectedName == null) return List.of();
        if (isAssembly) {
            Assembly assembly = sceneManager.getAssembly(selectedName);
            if (assembly != null) {
                return assembly.getParts().stream()
                        .map(com.jigger.model.Part::getName)
                        .toList();
            }
        }
        return List.of(selectedName);
    }

    public void addSelectionListener(Consumer<SelectionEvent> listener) {
        listeners.add(listener);
    }

    private void fireEvent() {
        SelectionEvent event;
        if (selectedName == null) {
            event = SelectionEvent.empty();
        } else if (isAssembly) {
            Assembly assembly = sceneManager.getAssembly(selectedName);
            event = new SelectionEvent(selectedName, true,
                    assembly != null ? assembly.getTemplateName() : null,
                    assembly != null ? assembly.getParts().size() : 0);
        } else {
            event = new SelectionEvent(selectedName, false, null, 1);
        }
        for (Consumer<SelectionEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
