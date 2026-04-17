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

package com.jigger.model;

import com.jme3.math.Vector3f;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named collection of parts that form a single object (e.g., a cabinet).
 * Assemblies are created by template instantiation and can be operated on as a unit.
 */
@Data
public class Assembly {
    private final String name;
    private String templateName;  // the template that created this assembly
    private final List<Part> parts = new ArrayList<>();

    public void addPart(Part part) {
        parts.add(part);
    }

    public Part getPart(String partName) {
        return parts.stream()
                .filter(p -> p.getName().equals(partName))
                .findFirst()
                .orElse(null);
    }

    public boolean removePart(String partName) {
        return parts.removeIf(p -> p.getName().equals(partName));
    }

    public List<Part> getParts() {
        return Collections.unmodifiableList(parts);
    }

    /**
     * Compute the minimum corner of the assembly's bounding box
     * using the positions from the scene's object records.
     *
     * @param positionLookup function to get current position for a part name
     * @param sizeLookup     function to get current size for a part name
     * @return the min corner, or ZERO if no parts
     */
    public Vector3f getBoundingBoxMin(java.util.function.Function<String, Vector3f> positionLookup,
                                      java.util.function.Function<String, Vector3f> sizeLookup) {
        if (parts.isEmpty()) return Vector3f.ZERO;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        for (Part p : parts) {
            Vector3f pos = positionLookup.apply(p.getName());
            if (pos != null) {
                minX = Math.min(minX, pos.x);
                minY = Math.min(minY, pos.y);
                minZ = Math.min(minZ, pos.z);
            }
        }
        return new Vector3f(minX, minY, minZ);
    }
}
