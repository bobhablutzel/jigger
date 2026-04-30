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

import java.util.ArrayList;
import java.util.List;

/**
 * Per-joint geometric predicates: given the parts' current world-frame
 * positions and orientations, do they actually engage in the way the joint
 * type implies? Answers via a list of {@link ValidationIssue}s — empty list
 * means "looks geometrically sensible".
 *
 * <p>Each predicate operates on the projected AABB of the inserted part in
 * the receiving's local frame ({@link JointCutoutInferrer#projectInsertedAABB}),
 * which is the same math used for cutout inference. Validation is therefore
 * a sibling concern, not a competing one.
 *
 * <p>Tolerance: {@value #EPSILON_MM}mm. Floating-point projection through
 * quaternion rotation can drift a few hundredths of a mm even on
 * axis-aligned cases, so we don't false-positive on that drift, but we
 * still catch user-scale misplacement.
 *
 * <p>Coverage as of v1: {@code Dado} and {@code Rabbet} have predicates.
 * {@code Butt} and {@code PocketScrew} return no issues — they need
 * face-touching tests that depend on identifying *which* surfaces are
 * intended to meet, which the joint record doesn't carry today.
 */
public final class JointValidator {

    private static final float EPSILON_MM = 0.1f;

    private JointValidator() {}

    static List<ValidationIssue> validateDado(Joint.Dado j, Part receiving, Part inserted,
                                              JointGeometryContext ctx) {
        List<ValidationIssue> issues = new ArrayList<>();
        JointCutoutInferrer.Projection p =
                JointCutoutInferrer.projectInsertedAABB(receiving, inserted, ctx);
        addBoundsIssue(issues, j, "dado", p, receiving);
        addEngagementIssue(issues, j, "dado", p, receiving);
        return issues;
    }

    static List<ValidationIssue> validateRabbet(Joint.Rabbet j, Part receiving, Part inserted,
                                                JointGeometryContext ctx) {
        List<ValidationIssue> issues = new ArrayList<>();
        JointCutoutInferrer.Projection p =
                JointCutoutInferrer.projectInsertedAABB(receiving, inserted, ctx);
        addBoundsIssue(issues, j, "rabbet", p, receiving);
        addEngagementIssue(issues, j, "rabbet", p, receiving);

        // Rabbet-specific: footprint should touch a receiver edge. A rabbet
        // strictly interior to the face is geometrically a dado-like pocket,
        // not an edge rebate, so flag it as a warning.
        float w = receiving.getCutWidthMm();
        float h = receiving.getCutHeightMm();
        boolean touchesEdge = p.minX() <= EPSILON_MM
                || p.maxX() >= w - EPSILON_MM
                || p.minY() <= EPSILON_MM
                || p.maxY() >= h - EPSILON_MM;
        if (!touchesEdge) {
            issues.add(new ValidationIssue(j, ValidationIssue.Severity.WARNING,
                    String.format("rabbet on '%s' ← '%s' sits interior to the receiver "
                                    + "(footprint x=%.1f..%.1f, y=%.1f..%.1f, receiver=%.1f×%.1f) "
                                    + "— a rabbet should land at an edge",
                            j.receivingPartName(), j.insertedPartName(),
                            p.minX(), p.maxX(), p.minY(), p.maxY(), w, h)));
        }
        return issues;
    }

    private static void addBoundsIssue(List<ValidationIssue> issues, Joint j, String label,
                                       JointCutoutInferrer.Projection p, Part receiver) {
        float w = receiver.getCutWidthMm();
        float h = receiver.getCutHeightMm();
        if (p.minX() < -EPSILON_MM || p.maxX() > w + EPSILON_MM
                || p.minY() < -EPSILON_MM || p.maxY() > h + EPSILON_MM) {
            issues.add(new ValidationIssue(j, ValidationIssue.Severity.ERROR,
                    String.format("%s on '%s' ← '%s' extends past receiver bounds: "
                                    + "footprint x=%.1f..%.1f, y=%.1f..%.1f, receiver=%.1f×%.1f",
                            label, j.receivingPartName(), j.insertedPartName(),
                            p.minX(), p.maxX(), p.minY(), p.maxY(), w, h)));
        }
    }

    private static void addEngagementIssue(List<ValidationIssue> issues, Joint j, String label,
                                           JointCutoutInferrer.Projection p, Part receiver) {
        float t = receiver.getThicknessMm();
        if (p.maxZ() < -EPSILON_MM || p.minZ() > t + EPSILON_MM) {
            issues.add(new ValidationIssue(j, ValidationIssue.Severity.ERROR,
                    String.format("%s on '%s' ← '%s' parts not engaged: inserted's Z range "
                                    + "%.1f..%.1f does not overlap receiver's 0..%.1f",
                            label, j.receivingPartName(), j.insertedPartName(),
                            p.minZ(), p.maxZ(), t)));
        }
    }
}
