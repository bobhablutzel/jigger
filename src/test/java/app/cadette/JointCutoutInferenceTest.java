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

import app.cadette.model.Cutout;
import app.cadette.model.Joint;
import app.cadette.model.JointGeometryContext;
import app.cadette.model.Material;
import app.cadette.model.MaterialKind;
import app.cadette.model.MaterialType;
import app.cadette.model.Part;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Joint geometry-inference math — exercises
 * {@link Joint.Dado#inferReceivingCutout} directly with a hand-built
 * {@link JointGeometryContext} so we don't depend on SceneManager / jME3
 * runtime. Each case ends in a known cutout rect we can hand-check.
 */
class JointCutoutInferenceTest {

    /** Lightweight test fixture — explicit per-part position + rotation, no scene needed. */
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
    void axisAligned_insertedSitsOnReceivingsFace_producesCenteredFootprint() {
        // Receiving: 100×200×18 panel at world origin, no rotation.
        // Inserted: 50×30×18 panel at (25, 100, 18) — sitting on receiving's +Z face.
        // Expected rect: (25, 100, 50, 30, depth=9).
        Part r = part("R", 100, 200);
        Part i = part("I", 50, 30);
        FakeContext ctx = new FakeContext()
                .put("R", new Vector3f(0, 0, 0), new Quaternion())
                .put("I", new Vector3f(25, 100, 18), new Quaternion());

        Joint.Dado j = new Joint.Dado("R", "I", 9f);
        Optional<Cutout> opt = j.inferReceivingCutout(r, i, ctx);

        assertTrue(opt.isPresent());
        Cutout.Rect rect = (Cutout.Rect) opt.get();
        assertEquals(25f, rect.xMm(), 0.001f);
        assertEquals(100f, rect.yMm(), 0.001f);
        assertEquals(50f, rect.widthMm(), 0.001f);
        assertEquals(30f, rect.heightMm(), 0.001f);
        assertEquals(9f, rect.depthMm(), 0.001f);
    }

    @Test
    void cabinetRightSidePanel_dadoOnBackFace_mirrorOfLeftSide() {
        // Mirror of the left-side test: in real cabinets, both side panels
        // are rotated +90Y so they share grain orientation, but that puts
        // the right-side's wrapper +Z (cut face) facing OUTWARD. Inference
        // must detect the inserted is on the back side of the receiving
        // and put the cutout on the BACK face.
        //
        // base_cabinet at (W=800, H=600, D=400), no toe-kick, with the bottom
        // sized to engage the dadoes (cutWidth = W - thickness = 782mm).
        Part rightSide = part("K/right-side", 400, 600);
        Part bottom = part("K/bottom", 782, 400);

        Quaternion qRight = new Quaternion().fromAngles(0, 90 * FastMath.DEG_TO_RAD, 0);
        Quaternion qBottom = new Quaternion().fromAngles(-90 * FastMath.DEG_TO_RAD, 0, 0);

        // Right-side at world (782, 0, 0). Bottom at world (9, 0, 0) with
        // rotation -90X — its right edge ends inside the right side's dado.
        FakeContext ctx = new FakeContext()
                .put("K/right-side", new Vector3f(782, 0, 0), qRight)
                .put("K/bottom", new Vector3f(9, 0, 0), qBottom);

        Joint.Dado j = new Joint.Dado("K/right-side", "K/bottom", 9f);
        Optional<Cutout> opt = j.inferReceivingCutout(rightSide, bottom, ctx);

        assertTrue(opt.isPresent());
        Cutout.Rect rect = (Cutout.Rect) opt.get();
        assertEquals(Cutout.Face.BACK, rect.face(),
                "right-side has its wrapper +Z facing outward — dado must cut from BACK face");
        // Y range still matches the bottom (rotation about Y preserves Y).
        assertEquals(0f, rect.yMm(), 0.01f);
        assertEquals(18f, rect.heightMm(), 0.01f);
        assertEquals(9f, rect.depthMm(), 0.001f);
    }

    @Test
    void cabinetSidePanel_rabbetFromBackPanel_landsAtRearEdge() {
        // base_cabinet at (W=500, H=600, D=400). The back panel is rabbeted
        // into both sides along their rear edges.
        // left-side: cut size (D=400, H=600), at (0,0,0), rotated 0,90,0.
        // back:      cut size (W=500, H=600), thickness 5.5mm hardboard,
        //            at (0, 0, -D=-400), no rotation.
        //   (Lies in the XY plane at world z=-D, facing forward.)
        // Expected rabbet footprint on left-side's cut face:
        //   xMm = D - back_thickness = 400 - 5.5 = 394.5
        //   yMm = 0, widthMm = 5.5, heightMm = 600, depth = 9.
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
        Optional<Cutout> opt = j.inferReceivingCutout(leftSide, back, ctx);

        assertTrue(opt.isPresent());
        Cutout.Rect rect = (Cutout.Rect) opt.get();
        assertEquals(394.5f, rect.xMm(), 0.01f, "rabbet sits at rear edge of side");
        assertEquals(0f, rect.yMm(), 0.01f);
        assertEquals(5.5f, rect.widthMm(), 0.01f, "rabbet width = back thickness");
        assertEquals(600f, rect.heightMm(), 0.01f, "rabbet runs full height");
        assertEquals(9f, rect.depthMm(), 0.001f);
    }

    @Test
    void cabinetSidePanel_dadoFromBottom_runsAcrossFullDepthAtBottomEdge() {
        // Mirrors base_cabinet at (W=500, H=600, D=400), no toe-kick.
        // left-side: cut size (D=400, H=600), thickness 18, at (0,0,0), rotated 0,90,0
        //   (panel stands vertically; cut face is the inside face of the side).
        // bottom:    cut size (W-2T=464, D=400), thickness 18, at (T=18, 0, 0), rotated -90,0,0.
        //   (panel lies horizontally; one long edge meets left-side).
        // Expected dado footprint on left-side's cut face:
        //   xMm ≈ 0 .. 400 (full depth), yMm ≈ 0 .. 18 (thickness of bottom),
        //   depthMm = 9.
        Part leftSide = part("K/left-side", 400, 600);
        Part bottom = part("K/bottom", 464, 400);

        Quaternion qLeft = new Quaternion().fromAngles(0, 90 * FastMath.DEG_TO_RAD, 0);
        Quaternion qBottom = new Quaternion().fromAngles(-90 * FastMath.DEG_TO_RAD, 0, 0);

        FakeContext ctx = new FakeContext()
                .put("K/left-side", new Vector3f(0, 0, 0), qLeft)
                .put("K/bottom", new Vector3f(18, 0, 0), qBottom);

        Joint.Dado j = new Joint.Dado("K/left-side", "K/bottom", 9f);
        Optional<Cutout> opt = j.inferReceivingCutout(leftSide, bottom, ctx);

        assertTrue(opt.isPresent());
        Cutout.Rect rect = (Cutout.Rect) opt.get();
        // The dado runs along the local-X axis of the side (= depth direction)
        // and is one bottom-thickness tall in local-Y (= height-from-floor).
        assertEquals(0f, rect.xMm(), 0.01f, "dado starts at front of side panel (local X=0)");
        assertEquals(0f, rect.yMm(), 0.01f, "dado bottom edge at floor (local Y=0)");
        assertEquals(400f, rect.widthMm(), 0.01f, "dado runs full depth");
        assertEquals(18f, rect.heightMm(), 0.01f, "dado is one bottom-thickness tall");
        assertEquals(9f, rect.depthMm(), 0.001f);
    }
}
