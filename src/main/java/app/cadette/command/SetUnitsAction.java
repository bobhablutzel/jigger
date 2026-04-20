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

import app.cadette.UnitSystem;
import lombok.RequiredArgsConstructor;

import java.util.function.Consumer;

@RequiredArgsConstructor
public class SetUnitsAction implements UndoableAction {

    private final Consumer<UnitSystem> setter;
    private final UnitSystem oldUnits;
    private final UnitSystem newUnits;

    @Override
    public void undo() {
        setter.accept(oldUnits);
    }

    @Override
    public void redo() {
        setter.accept(newUnits);
    }

    @Override
    public String description() {
        return "set units " + newUnits.name().toLowerCase();
    }
}
