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

import app.cadette.UnitSystem;

import java.util.List;
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
        permits Joint.Butt, Joint.Dado, Joint.Rabbet, Joint.PocketScrew,
                Joint.CountersunkScrew, Joint.Glue {

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
     * <p>Position and cross-section are derived from the parts' world-frame
     * geometry — passed in via {@link JointGeometryContext} — rather than
     * stored on the joint, so the cutout follows the parts when they move.
     */
    default Optional<Cutout> inferReceivingCutout(Part receiving, Part inserted,
                                                  JointGeometryContext ctx) {
        return Optional.empty();
    }

    /**
     * Geometric sanity check for this joint given the parts' current
     * world-frame positions. Empty list = looks correct. See
     * {@link JointValidator} for the per-type predicates and the open
     * question of butt / pocket-screw face-touching tests.
     */
    default List<ValidationIssue> validate(Part receiving, Part inserted,
                                           JointGeometryContext ctx) {
        return List.of();
    }

    /**
     * BOM fastener entries this joint contributes (e.g., screws, glue-ups).
     * Default: empty for joints with no hardware. Overrides return one or
     * more {@link CutListGenerator.FastenerEntry} items; the cut-list
     * aggregates same-labelled entries across all joints.
     *
     * <p>Per-variant placement keeps {@link CutListGenerator} from needing
     * to know about every joint kind — adding a new joint with a new
     * fastener is a one-line override here, no edit to the generator.
     */
    default List<CutListGenerator.FastenerEntry> bomFasteners() {
        return List.of();
    }

    /**
     * Per-part operation note for the cut-list (e.g., "dado 9mm deep for
     * 'shelf'"). Default: empty for joints with no machining or hardware
     * operation. Each variant formats its own description; the generator
     * just calls this and emits.
     */
    default Optional<String> describeOperation(UnitSystem units) {
        return Optional.empty();
    }

    /**
     * Where this joint was defined in script. Null when the joint was
     * constructed programmatically (most tests). The visitor populates
     * this from the parser context's start token + the executor's
     * effective source string. Validation messages attribute issues back
     * to this location when present.
     */
    SourceLocation source();

    record Butt(String receivingPartName, String insertedPartName, SourceLocation source) implements Joint {
        public Butt(String receivingPartName, String insertedPartName) {
            this(receivingPartName, insertedPartName, null);
        }
        @Override public JointType type() { return JointType.BUTT; }
    }

    record Dado(String receivingPartName, String insertedPartName, float depthMm, SourceLocation source)
            implements Joint {
        public Dado(String receivingPartName, String insertedPartName, float depthMm) {
            this(receivingPartName, insertedPartName, depthMm, null);
        }
        @Override public JointType type() { return JointType.DADO; }

        @Override
        public Optional<Cutout> inferReceivingCutout(Part receiving, Part inserted,
                                                    JointGeometryContext ctx) {
            JointCutoutInferrer.Footprint fp =
                    JointCutoutInferrer.projectInsertedFootprint(receiving, inserted, ctx);
            return Optional.of(new Cutout.Rect(fp.xMm(), fp.yMm(), fp.widthMm(), fp.heightMm(),
                    depthMm, fp.face()));
        }

        @Override
        public List<ValidationIssue> validate(Part receiving, Part inserted,
                                              JointGeometryContext ctx) {
            return JointValidator.validateDado(this, receiving, inserted, ctx);
        }

        @Override
        public Optional<String> describeOperation(UnitSystem units) {
            return Optional.of(String.format("dado %.1f %s deep for \"%s\"",
                    units.fromMm(depthMm), units.getAbbreviation(), insertedPartName));
        }
    }

    record Rabbet(String receivingPartName, String insertedPartName, float depthMm, SourceLocation source)
            implements Joint {
        public Rabbet(String receivingPartName, String insertedPartName, float depthMm) {
            this(receivingPartName, insertedPartName, depthMm, null);
        }
        @Override public JointType type() { return JointType.RABBET; }

        @Override
        public Optional<Cutout> inferReceivingCutout(Part receiving, Part inserted,
                                                    JointGeometryContext ctx) {
            // Same projection as Dado — "edge rebate" vs. "mid-face groove" is a
            // semantic distinction, not a geometric one. The cutout lands at
            // the edge naturally when the inserted is at the receiving's edge.
            JointCutoutInferrer.Footprint fp =
                    JointCutoutInferrer.projectInsertedFootprint(receiving, inserted, ctx);
            return Optional.of(new Cutout.Rect(fp.xMm(), fp.yMm(), fp.widthMm(), fp.heightMm(),
                    depthMm, fp.face()));
        }

        @Override
        public List<ValidationIssue> validate(Part receiving, Part inserted,
                                              JointGeometryContext ctx) {
            return JointValidator.validateRabbet(this, receiving, inserted, ctx);
        }

        @Override
        public Optional<String> describeOperation(UnitSystem units) {
            return Optional.of(String.format("rabbet %.1f %s deep for \"%s\"",
                    units.fromMm(depthMm), units.getAbbreviation(), insertedPartName));
        }
    }

    record PocketScrew(String receivingPartName, String insertedPartName,
                       int screwCount, float screwSpacingMm, SourceLocation source) implements Joint {
        public PocketScrew(String receivingPartName, String insertedPartName,
                           int screwCount, float screwSpacingMm) {
            this(receivingPartName, insertedPartName, screwCount, screwSpacingMm, null);
        }
        @Override public JointType type() { return JointType.POCKET_SCREW; }

        @Override
        public List<CutListGenerator.FastenerEntry> bomFasteners() {
            return screwCount > 0
                    ? List.of(new CutListGenerator.FastenerEntry("Pocket screws", screwCount))
                    : List.of();
        }

        @Override
        public Optional<String> describeOperation(UnitSystem units) {
            return screwCount > 0
                    ? Optional.of(String.format("%d pocket screw hole(s) for \"%s\"",
                            screwCount, insertedPartName))
                    : Optional.empty();
        }
    }

    /**
     * Perpendicular wood screws driven through a clearance hole in the
     * receiving part into the inserted part, with the head sitting flush in
     * a countersunk recess. Distinct from {@link PocketScrew} in mechanics
     * (perpendicular vs angled, countersunk recess vs hidden pocket) and
     * shop operation (countersink bit vs Kreg jig).
     *
     * <p>v1 carries no cutout inference — explicit countersunk recess and
     * clearance hole live as separate {@code cut circle ... depth} commands
     * if the user wants them visible. The joint contributes a fastener line
     * to the BOM and a per-part operation note.
     */
    record CountersunkScrew(String receivingPartName, String insertedPartName,
                            int screwCount, float screwSpacingMm, SourceLocation source) implements Joint {
        public CountersunkScrew(String receivingPartName, String insertedPartName,
                                int screwCount, float screwSpacingMm) {
            this(receivingPartName, insertedPartName, screwCount, screwSpacingMm, null);
        }
        @Override public JointType type() { return JointType.COUNTERSUNK_SCREW; }

        @Override
        public List<CutListGenerator.FastenerEntry> bomFasteners() {
            return screwCount > 0
                    ? List.of(new CutListGenerator.FastenerEntry("Countersunk screws", screwCount))
                    : List.of();
        }

        @Override
        public Optional<String> describeOperation(UnitSystem units) {
            return screwCount > 0
                    ? Optional.of(String.format("%d countersunk hole(s) for \"%s\"",
                            screwCount, insertedPartName))
                    : Optional.empty();
        }
    }

    /**
     * Adhesive-only joint between two parts' adjoining faces. No cutout, no
     * hardware. Used for laminating doubled stock, glue-ups of multi-piece
     * assemblies, and any case where the parts attach via glue alone. The
     * joint contributes an "Adhesive" line to the BOM as a glue-up step
     * count; future work can compute glue-surface area from face overlap.
     */
    record Glue(String receivingPartName, String insertedPartName, SourceLocation source) implements Joint {
        public Glue(String receivingPartName, String insertedPartName) {
            this(receivingPartName, insertedPartName, null);
        }
        @Override public JointType type() { return JointType.GLUE; }

        @Override
        public List<CutListGenerator.FastenerEntry> bomFasteners() {
            return List.of(new CutListGenerator.FastenerEntry("Glue-ups", 1));
        }

        @Override
        public Optional<String> describeOperation(UnitSystem units) {
            return Optional.of(String.format("glue-up to \"%s\"", insertedPartName));
        }
    }
}
