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

import lombok.Getter;

import java.util.EnumSet;
import java.util.Set;

// Each joint type declares which MaterialType substances it can physically
// work in. Compatibility lives on the joint, not on the material, so adding
// a new joint (biscuit, domino, dovetail) is a one-line update here rather
// than a boolean-per-material sweep.
//
// Known future work (see memory `project_joint_future_work.md`):
//   - Thickness-dependent rules (pocket screws need ~1/2"+ stock, dados
//     can't exceed receiving thickness).
//   - Asymmetric joints: the receiving vs. inserted material may have
//     different requirements (pocket screw bites the receiver).
// Today's check is coarse — MaterialType only — and that's fine for the
// joints we currently support.
@Getter
public enum JointType {
    BUTT("Butt joint", false,
            EnumSet.allOf(MaterialType.class)),
    DADO("Dado", true,
            EnumSet.of(MaterialType.PLYWOOD, MaterialType.HARDWOOD,
                    MaterialType.SOFTWOOD, MaterialType.MDF)),
    RABBET("Rabbet", true,
            EnumSet.of(MaterialType.PLYWOOD, MaterialType.HARDBOARD,
                    MaterialType.HARDWOOD, MaterialType.SOFTWOOD, MaterialType.MDF)),
    POCKET_SCREW("Pocket screw", false,
            EnumSet.of(MaterialType.PLYWOOD, MaterialType.HARDWOOD,
                    MaterialType.SOFTWOOD, MaterialType.MDF));
    // Future: BISCUIT, DOWEL, DOVETAIL, MORTISE_TENON, BOX_JOINT

    private final String displayName;
    private final boolean affectsGeometry;
    private final Set<MaterialType> applicableTypes;

    JointType(String displayName, boolean affectsGeometry, Set<MaterialType> applicableTypes) {
        this.displayName = displayName;
        this.affectsGeometry = affectsGeometry;
        this.applicableTypes = applicableTypes;
    }

    public boolean supports(MaterialType materialType) {
        return materialType != null && applicableTypes.contains(materialType);
    }

    public static JointType fromString(String text) {
        String lower = text.toLowerCase();
        for (JointType jt : values()) {
            if (jt.name().toLowerCase().equals(lower)) return jt;
        }
        return null;
    }

    /** Comma-separated list of valid joint type names, for error messages. */
    public static String validNames() {
        StringBuilder sb = new StringBuilder();
        for (JointType jt : values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(jt.name().toLowerCase());
        }
        return sb.toString();
    }
}
