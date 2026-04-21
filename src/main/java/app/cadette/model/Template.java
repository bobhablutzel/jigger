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

package app.cadette.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parametric template that can be instantiated to create an assembly of parts.
 * Body lines are raw command strings with $variable references that are
 * evaluated at instantiation time.
 *
 * Parameters can have aliases: define "cab" params width(w), height(h), depth(d)
 * Both the full name and alias can be used at instantiation time.
 */
@Data
public class Template {
    private final String name;
    private final List<String> paramNames;          // canonical names in order
    private final Map<String, String> paramAliases;  // alias → canonical name
    private final List<String> bodyLines;
    private final boolean builtIn;

    // Hand-coded: convenience ctor that delegates to the 5-arg form with
    // no aliases and builtIn=false. @RequiredArgsConstructor can't express
    // the default values.
    public Template(String name, List<String> paramNames, List<String> bodyLines) {
        this(name, paramNames, Map.of(), bodyLines, false);
    }

    // Hand-coded: defensive List.copyOf / Map.copyOf so template bodies and
    // param metadata are insulated from post-construction mutation.
    // @RequiredArgsConstructor / @AllArgsConstructor would store the caller's
    // references directly.
    public Template(String name, List<String> paramNames, Map<String, String> paramAliases,
                    List<String> bodyLines, boolean builtIn) {
        this.name = name;
        this.paramNames = List.copyOf(paramNames);
        this.paramAliases = Map.copyOf(paramAliases);
        this.bodyLines = List.copyOf(bodyLines);
        this.builtIn = builtIn;
    }

    /** Resolve a param name or alias to its canonical name. Returns null if not recognized. */
    public String resolveParam(String nameOrAlias) {
        String lower = nameOrAlias.toLowerCase();
        if (paramNames.contains(lower)) return lower;
        return paramAliases.get(lower);
    }
}
