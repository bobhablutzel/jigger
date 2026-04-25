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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central store for all joints in the scene.
 */
public class JointRegistry {

    private final Map<String, Joint> joints = new LinkedHashMap<>();

    public void addJoint(Joint joint) {
        joints.put(joint.id(), joint);
    }

    public void removeJoint(String jointId) {
        joints.remove(jointId);
    }

    public Joint getJoint(String jointId) {
        return joints.get(jointId);
    }

    /** Get all joints where the given part is either receiver or inserted. */
    public List<Joint> getJointsForPart(String partName) {
        return joints.values().stream()
                .filter(j -> j.receivingPartName().equals(partName)
                        || j.insertedPartName().equals(partName))
                .toList();
    }

    /** Get all joints where both parts belong to the given assembly. */
    public List<Joint> getJointsForAssembly(Assembly assembly) {
        Set<String> partNames = assembly.getParts().stream()
                .map(Part::getName)
                .collect(Collectors.toSet());
        return joints.values().stream()
                .filter(j -> partNames.contains(j.receivingPartName())
                        && partNames.contains(j.insertedPartName()))
                .toList();
    }

    public List<Joint> getAllJoints() {
        return List.copyOf(joints.values());
    }

    /** Remove all joints involving the given part. Returns the removed joints. */
    public List<Joint> removeJointsForPart(String partName) {
        List<Joint> removed = getJointsForPart(partName);
        for (Joint j : removed) {
            joints.remove(j.id());
        }
        return removed;
    }

    /** Summary counts by joint type. */
    public Map<JointType, Long> getSummary() {
        return joints.values().stream()
                .collect(Collectors.groupingBy(Joint::type, Collectors.counting()));
    }

    public void clear() {
        joints.clear();
    }

    public boolean isEmpty() {
        return joints.isEmpty();
    }
}
