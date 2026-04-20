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

import app.cadette.model.Assembly;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.function.Consumer;

/**
 * Tracks selected objects and assemblies.
 * <p>
 * Click behavior:
 *   - Click a part in an assembly → selects the assembly
 *   - Click again on a part in the already-selected assembly → drills down to that part
 *   - Shift+click → toggle add/remove from selection
 *   - Click empty space → deselect all
 */
@RequiredArgsConstructor
public class SelectionManager {

    private final SceneManager sceneManager;
    private final LinkedHashMap<String, Boolean> selected = new LinkedHashMap<>(); // name → isAssembly
    private final List<Consumer<SelectionChange>> listeners = new ArrayList<>();

    public record SelectionChange(List<String> selectedNames, String lastChanged) {}

    /**
     * Select by geometry name, with optional shift for multi-select.
     * <p>
     * Rules:
     *   - An assembly and its individual parts cannot be selected simultaneously.
     *   - If the assembly is selected as a whole, clicking a part in it drills down
     *     (replaces assembly selection with that part). Shift-click does nothing.
     *   - If individual parts of an assembly are selected, shift-click on another
     *     part in the same assembly toggles that part (stays at part level).
     */
    public void selectByPartName(String partName, boolean shiftDown) {
        if (partName == null) {
            if (!shiftDown) deselect();
            return;
        }

        // Resolve assembly membership
        String assemblyName = null;
        int slash = partName.indexOf('/');
        if (slash > 0) {
            String candidate = partName.substring(0, slash);
            if (sceneManager.getAssembly(candidate) != null) {
                assemblyName = candidate;
            }
        }

        if (assemblyName != null) {
            // Clicked part belongs to an assembly
            if (selected.containsKey(assemblyName) && Boolean.TRUE.equals(selected.get(assemblyName))) {
                // Assembly is selected as a whole
                if (shiftDown) {
                    // Shift-click on a part of an already-selected assembly → do nothing
                    return;
                }
                // Drill down: replace assembly selection with this individual part
                selected.clear();
                selected.put(partName, false);
            } else if (hasPartsOfAssembly(assemblyName)) {
                // Individual parts of this assembly are already selected
                if (shiftDown) {
                    // Toggle this specific part
                    if (selected.containsKey(partName)) {
                        selected.remove(partName);
                    } else {
                        selected.put(partName, false);
                    }
                } else {
                    // Replace with just this part
                    selected.clear();
                    selected.put(partName, false);
                }
            } else {
                // No parts of this assembly are selected — select the assembly
                if (shiftDown) {
                    selected.put(assemblyName, true);
                } else {
                    selected.clear();
                    selected.put(assemblyName, true);
                }
            }
        } else {
            // Standalone object (not in an assembly)
            if (shiftDown) {
                if (selected.containsKey(partName)) {
                    selected.remove(partName);
                } else {
                    selected.put(partName, false);
                }
            } else {
                selected.clear();
                selected.put(partName, false);
            }
        }

        fireEvent(partName);
    }

    /** Check if any individually-selected parts belong to the given assembly. */
    private boolean hasPartsOfAssembly(String assemblyName) {
        String prefix = assemblyName + "/";
        return selected.keySet().stream()
                .anyMatch(name -> name.startsWith(prefix));
    }

    public void deselect() {
        if (selected.isEmpty()) return;
        selected.clear();
        fireEvent(null);
    }

    /** Get all selected names. */
    public List<String> getSelectedNames() {
        return List.copyOf(selected.keySet());
    }

    /** Check if a specific name is selected. */
    public boolean isSelected(String name) {
        return selected.containsKey(name);
    }

    /** Get all part names that should be highlighted (expanding assemblies to their parts). */
    public List<String> getSelectedPartNames() {
        List<String> parts = new ArrayList<>();
        for (var entry : selected.entrySet()) {
            if (entry.getValue()) {
                // Assembly — expand to all parts
                Assembly assembly = sceneManager.getAssembly(entry.getKey());
                if (assembly != null) {
                    assembly.getParts().forEach(p -> parts.add(p.getName()));
                }
            } else {
                parts.add(entry.getKey());
            }
        }
        return parts;
    }

    public void addSelectionListener(Consumer<SelectionChange> listener) {
        listeners.add(listener);
    }

    private void fireEvent(String lastChanged) {
        SelectionChange event = new SelectionChange(getSelectedNames(), lastChanged);
        for (Consumer<SelectionChange> listener : listeners) {
            listener.accept(event);
        }
    }
}
