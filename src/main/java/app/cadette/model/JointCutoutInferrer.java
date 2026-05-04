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

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Iterates the joints where a given part is the receiving side and asks each
 * joint to compute its implicit cutout. Dispatch happens via the joint's own
 * {@link Joint#inferReceivingCutout} override — no switch on joint type.
 */
public final class JointCutoutInferrer {

    private JointCutoutInferrer() {}

    /**
     * Cutouts implied by joints where {@code part} is the receiving side.
     * Joints whose inserted part is missing from {@code parts} are skipped
     * silently (templates-in-progress, partial scene state during undo).
     *
     * <p>If a joint's projected cutout falls entirely outside the receiver's
     * cut-face rectangle, it would otherwise be silently dropped by
     * {@link PartMeshBuilder}'s clipping pass — a common symptom of a
     * misconfigured joint where the parts aren't actually engaged. We surface
     * those cases through {@code warnSink} and omit the cutout from the
     * returned list. This is a coarse, joint-by-joint check; the broader
     * "is this joint geometrically sensical" question is the validate
     * command's job.
     */
    public static List<Cutout> inferFor(Part part,
                                        JointRegistry joints,
                                        Map<String, Part> parts,
                                        JointGeometryContext ctx,
                                        Consumer<String> warnSink) {
        List<Cutout> out = new ArrayList<>();
        for (Joint j : joints.getJointsForPart(part.getName())) {
            if (!j.receivingPartName().equals(part.getName())) continue;
            Part inserted = parts.get(j.insertedPartName());
            if (inserted == null) continue;
            Optional<Cutout> opt = j.inferReceivingCutout(part, inserted, ctx);
            if (opt.isEmpty()) continue;
            Cutout c = opt.get();
            if (!intersectsPartFace(c.bounds(), part)) {
                warnSink.accept(formatOutOfBoundsWarning(j, part, c));
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private static boolean intersectsPartFace(BoundingBox b, Part receiver) {
        return b.maxXMm() > 0
                && b.xMm() < receiver.getCutWidthMm()
                && b.maxYMm() > 0
                && b.yMm() < receiver.getCutHeightMm();
    }

    private static String formatOutOfBoundsWarning(Joint j, Part receiver, Cutout c) {
        BoundingBox b = c.bounds();
        String src = j.source() == null ? "" : "(" + j.source() + ") ";
        return String.format(
                "%sjoint cutout warning: %s on '%s' ← '%s' — projected cutout "
                        + "(x=%.1f, y=%.1f, %.1f×%.1f) falls entirely outside "
                        + "receiver (%.1f×%.1f); silently dropping. Check that "
                        + "the parts are positioned to engage the joint.",
                src,
                j.type().name().toLowerCase(),
                receiver.getName(), j.insertedPartName(),
                b.xMm(), b.yMm(), b.widthMm(), b.heightMm(),
                receiver.getCutWidthMm(), receiver.getCutHeightMm());
    }

    /**
     * Project the inserted part's volume into the receiving part's local
     * cut-face frame. Returns the X/Y bounding rect (cut-face local coords)
     * plus the face the cutout should open on — determined by which side of
     * receiving's mid-thickness the inserted's projected Z midpoint lies.
     *
     * <p>The result rect may extend outside receiving's bounds; PartMeshBuilder
     * clips cutouts to the part rectangle, which is the right behavior here.
     */
    public record Footprint(float xMm, float yMm, float widthMm, float heightMm,
                            Cutout.Face face) {}

    /**
     * Full axis-aligned bounding box of the inserted part in the receiving
     * part's local frame — same projection as {@link Footprint} but with the
     * Z extents preserved. Used by validators that need to check "are the
     * parts actually engaged?" (Z-overlap with receiver's body).
     */
    public record Projection(float minX, float maxX, float minY, float maxY,
                             float minZ, float maxZ) {
        public float widthMm() { return maxX - minX; }
        public float heightMm() { return maxY - minY; }
    }

    /**
     * Compute the inserted's eight-corner AABB in the receiving's local frame.
     * Each part has a wrapper origin at its (0,0,0) cut-face corner with
     * rotation around that point; a corner transforms via
     * <pre>
     *   worldP   = qI · cornerI + posI
     *   localR_P = qR^(-1) · (worldP - posR)
     * </pre>
     */
    public static Projection projectInsertedAABB(Part receiving, Part inserted,
                                                 JointGeometryContext ctx) {
        Vector3f posR = ctx.cornerPosition(receiving.getName());
        Vector3f posI = ctx.cornerPosition(inserted.getName());
        Quaternion qR = ctx.rotation(receiving.getName());
        Quaternion qI = ctx.rotation(inserted.getName());
        Quaternion qRinv = qR.inverse();

        float wI = inserted.getCutWidthMm();
        float hI = inserted.getCutHeightMm();
        float tI = inserted.getThicknessMm();

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        for (int corner = 0; corner < 8; corner++) {
            Vector3f local = new Vector3f(
                    (corner & 1) == 0 ? 0 : wI,
                    (corner & 2) == 0 ? 0 : hI,
                    (corner & 4) == 0 ? 0 : tI);
            Vector3f world = qI.mult(local).addLocal(posI);
            Vector3f localR = qRinv.mult(world.subtractLocal(posR));
            if (localR.x < minX) minX = localR.x;
            if (localR.x > maxX) maxX = localR.x;
            if (localR.y < minY) minY = localR.y;
            if (localR.y > maxY) maxY = localR.y;
            if (localR.z < minZ) minZ = localR.z;
            if (localR.z > maxZ) maxZ = localR.z;
        }

        return new Projection(minX, maxX, minY, maxY, minZ, maxZ);
    }

    static Footprint projectInsertedFootprint(Part receiving, Part inserted,
                                              JointGeometryContext ctx) {
        Projection p = projectInsertedAABB(receiving, inserted, ctx);
        // Receiving's local Z range is [0, thickness]. If the inserted's Z
        // midpoint is below halfT, it's seated against (or coming through)
        // the back face; otherwise the front face.
        float halfT = receiving.getThicknessMm() * 0.5f;
        float zMid = (p.minZ() + p.maxZ()) * 0.5f;
        Cutout.Face face = zMid < halfT ? Cutout.Face.BACK : Cutout.Face.FRONT;
        return new Footprint(p.minX(), p.minY(), p.widthMm(), p.heightMm(), face);
    }
}
