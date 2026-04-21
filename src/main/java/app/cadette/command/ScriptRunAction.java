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

import java.util.List;

/**
 * Composite undoable action for a script run.
 * Undo reverses all actions in reverse order. Redo replays them in order.
 */
public class ScriptRunAction implements UndoableAction {

    private final String scriptName;
    private final List<UndoableAction> actions;

    // Hand-coded: defensive List.copyOf on the actions list. @RequiredArgsConstructor
    // would store the caller's reference directly.
    public ScriptRunAction(String scriptName, List<UndoableAction> actions) {
        this.scriptName = scriptName;
        this.actions = List.copyOf(actions);
    }

    @Override
    public void undo() {
        for (int i = actions.size() - 1; i >= 0; i--) {
            actions.get(i).undo();
        }
    }

    @Override
    public void redo() {
        for (UndoableAction action : actions) {
            action.redo();
        }
    }

    @Override
    public String description() {
        return "run " + scriptName + " (" + actions.size() + " actions)";
    }
}
