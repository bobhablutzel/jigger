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

import app.cadette.model.Joint;
import app.cadette.model.JointGeometryContext;
import app.cadette.model.Material;
import app.cadette.model.MaterialKind;
import app.cadette.model.MaterialType;
import app.cadette.model.Part;
import app.cadette.model.ValidationIssue;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the joint geometric validators. Each case sets up a small
 * receiving + inserted pair, positions them deliberately to either pass
 * or trigger a specific predicate, and asserts on the resulting issue list.
 */
class JointValidationTest {

    static final class FakeContext implements JointGeometryContext {
        private final Map<String, Vector3f> positions = new HashMap<>();
        private final Map<String, Quaternion> rotations = new HashMap<>();

        FakeContext put(String name, Vector3f pos, Quaternion rot) {
            positions.put(name, pos);
            rotations.put(name, rot);
            return this;
        }

        @Override public Vector3f cornerPosition(String name) {
            return positions.getOrDefault(name, Vector3f.ZERO);
        }
        @Override public Quaternion rotation(String name) {
            return rotations.getOrDefault(name, new Quaternion());
        }
    }

    private static Material plywood18() {
        return Material.builder()
                .name("plywood-18mm")
                .type(MaterialType.PLYWOOD)
                .kind(MaterialKind.SHEET_GOOD)
                .thicknessMm(18f)
                .build();
    }

    private static Part part(String name, float w, float h) {
        return Part.builder()
                .name(name)
                .material(plywood18())
                .cutWidthMm(w)
                .cutHeightMm(h)
                .build();
    }

    @Test
    void dado_insertedSittingInGroove_noIssues() {
        // Same setup as the canonical inference test: 50×30 inserted lands
        // inside the 100×200 receiver and penetrates from above.
        Part r = part("R", 100, 200);
        Part i = part("I", 50, 30);
        FakeContext ctx = new FakeContext()
                .put("R", new Vector3f(0, 0, 0), new Quaternion())
                .put("I", new Vector3f(25, 100, 9), new Quaternion());

        Joint.Dado j = new Joint.Dado("R", "I", 9f);
        List<ValidationIssue> issues = j.validate(r, i, ctx);

        assertTrue(issues.isEmpty(), "engaged in-bounds dado should validate clean: " + issues);
    }

    @Test
    void dado_footprintExtendsPastEdge_reportsErrorWithBounds() {
        // Inserted's right edge runs off the receiver's right edge.
        Part r = part("R", 100, 200);
        Part i = part("I", 50, 30);
        FakeContext ctx = new FakeContext()
                .put("R", new Vector3f(0, 0, 0), new Quaternion())
                .put("I", new Vector3f(80, 100, 9), new Quaternion());

        Joint.Dado j = new Joint.Dado("R", "I", 9f);
        List<ValidationIssue> issues = j.validate(r, i, ctx);

        assertEquals(1, issues.size(), "expected one bounds error: " + issues);
        ValidationIssue issue = issues.get(0);
        assertEquals(ValidationIssue.Severity.ERROR, issue.severity());
        assertTrue(issue.message().contains("dado"), issue.message());
        assertTrue(issue.message().contains("extends past"), issue.message());
    }

    @Test
    void dado_partsFloatingApart_reportsEngagementError() {
        // Inserted is parked above the receiver in Z — no penetration at all.
        // (Receiver's body is Z=[0..18]; inserted sits at Z=[100..118].)
        Part r = part("R", 100, 200);
        Part i = part("I", 50, 30);
        FakeContext ctx = new FakeContext()
                .put("R", new Vector3f(0, 0, 0), new Quaternion())
                .put("I", new Vector3f(25, 100, 100), new Quaternion());

        Joint.Dado j = new Joint.Dado("R", "I", 9f);
        List<ValidationIssue> issues = j.validate(r, i, ctx);

        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(iss ->
                        iss.severity() == ValidationIssue.Severity.ERROR
                                && iss.message().contains("not engaged")),
                "expected engagement error: " + issues);
    }

    @Test
    void rabbet_atRearEdge_noIssues() {
        // Mirror of the cabinet rabbet inference test — the back panel rabbets
        // into the left side at the rear edge, so the footprint sits at
        // x = D - back_thickness with width = back_thickness.
        Part leftSide = part("K/left-side", 400, 600);
        Part back = Part.builder()
                .name("K/back")
                .material(Material.builder()
                        .name("hardboard-5.5mm")
                        .type(MaterialType.HARDBOARD)
                        .kind(MaterialKind.SHEET_GOOD)
                        .thicknessMm(5.5f)
                        .build())
                .cutWidthMm(500)
                .cutHeightMm(600)
                .build();

        Quaternion qLeft = new Quaternion().fromAngles(0, 90 * FastMath.DEG_TO_RAD, 0);
        FakeContext ctx = new FakeContext()
                .put("K/left-side", new Vector3f(0, 0, 0), qLeft)
                .put("K/back", new Vector3f(0, 0, -400), new Quaternion());

        Joint.Rabbet j = new Joint.Rabbet("K/left-side", "K/back", 9f);
        List<ValidationIssue> issues = j.validate(leftSide, back, ctx);

        assertTrue(issues.isEmpty(),
                "well-positioned rabbet at rear edge should validate clean: " + issues);
    }

    @Test
    void rabbet_strictlyInteriorFootprint_warnsNotAtEdge() {
        // Inserted lands strictly interior to the receiver's face — that's a
        // dado-shaped pocket, not a real rabbet. Should produce a WARNING
        // (the geometry is internally consistent, the joint type is wrong).
        Part r = part("R", 200, 200);
        Part i = part("I", 50, 50);
        FakeContext ctx = new FakeContext()
                .put("R", new Vector3f(0, 0, 0), new Quaternion())
                .put("I", new Vector3f(75, 75, 9), new Quaternion());

        Joint.Rabbet j = new Joint.Rabbet("R", "I", 9f);
        List<ValidationIssue> issues = j.validate(r, i, ctx);

        assertTrue(issues.stream().anyMatch(iss ->
                        iss.severity() == ValidationIssue.Severity.WARNING
                                && iss.message().contains("interior")),
                "expected 'interior' warning for non-edge rabbet: " + issues);
    }

    @Test
    void buttAndPocketScrew_returnNoIssuesForNow() {
        // V1 ships dado/rabbet predicates only — butt and pocket-screw need
        // face-touching tests not yet implemented. Confirm the default
        // behavior so the test fails loudly when those predicates are added.
        Part r = part("R", 100, 200);
        Part i = part("I", 50, 30);
        FakeContext ctx = new FakeContext()
                .put("R", new Vector3f(0, 0, 0), new Quaternion())
                .put("I", new Vector3f(25, 100, 18), new Quaternion());

        assertTrue(new Joint.Butt("R", "I").validate(r, i, ctx).isEmpty());
        assertTrue(new Joint.PocketScrew("R", "I", 2, 32f).validate(r, i, ctx).isEmpty());
    }
}
