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

import java.util.Optional;

/**
 * A joint between two parts. The receiving part hosts the joint (e.g., the
 * dado groove); the inserted part sits in/against it.
 *
 * <p>Each joint kind is its own record under this sealed interface so kind-
 * specific data (dado depth, screw count) lives only on the variant that
 * needs it. Per-instance behaviour — geometry inference for mesh generation,
 * future BOM contributions — dispatches via overrides on the variants rather
 * than a switch on a type tag.
 *
 * <p>{@link JointType} is retained as the vocabulary tag: it drives parser
 * lookups, display names, and per-kind material compatibility (which is a
 * type-level question, not a per-instance one).
 */
public sealed interface Joint
        permits Joint.Butt, Joint.Dado, Joint.Rabbet, Joint.PocketScrew {

    String receivingPartName();

    String insertedPartName();

    JointType type();

    default String id() {
        return receivingPartName() + "->" + insertedPartName() + ":" + type().name();
    }

    /**
     * Implicit cutout this joint contributes to the receiving part's mesh.
     * Empty for joints with no machined geometry (butt, pocket screw).
     *
     * <p>Position and cross-section are derived from the parts' positions and
     * orientations, which is why the parts are passed in rather than stored
     * on the joint itself. Implementations stay {@code Optional.empty()} until
     * Pass B of the joint/cutout unification work fills in the math.
     */
    default Optional<Cutout> inferReceivingCutout(Part receiving, Part inserted) {
        return Optional.empty();
    }

    record Butt(String receivingPartName, String insertedPartName) implements Joint {
        @Override public JointType type() { return JointType.BUTT; }
    }

    record Dado(String receivingPartName, String insertedPartName, float depthMm)
            implements Joint {
        @Override public JointType type() { return JointType.DADO; }

        @Override
        public Optional<Cutout> inferReceivingCutout(Part receiving, Part inserted) {
            // TODO Pass B: derive groove rect from receiving/inserted geometry.
            return Optional.empty();
        }
    }

    record Rabbet(String receivingPartName, String insertedPartName, float depthMm)
            implements Joint {
        @Override public JointType type() { return JointType.RABBET; }

        @Override
        public Optional<Cutout> inferReceivingCutout(Part receiving, Part inserted) {
            // TODO Pass B: derive edge-rebate rect from receiving/inserted geometry.
            return Optional.empty();
        }
    }

    record PocketScrew(String receivingPartName, String insertedPartName,
                       int screwCount, float screwSpacingMm) implements Joint {
        @Override public JointType type() { return JointType.POCKET_SCREW; }
        // No cutout. Future: hardware contribution to BOM.
    }
}
