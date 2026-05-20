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

package app.cadette.command;

import app.cadette.CutListExporter;
import app.cadette.CutSheetExporter;
import app.cadette.SceneManager;
import app.cadette.UnitSystem;
import app.cadette.model.*;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ANTLR visitor that executes parsed commands against the SceneManager.
 * Each visit method returns a feedback string for the console.
 */
@RequiredArgsConstructor
public class CommandVisitor extends CadetteCommandParserBaseVisitor<String> {

    private final CommandExecutor executor;
    private final SceneManager scene;

    @Override
    public String visitCommand(CadetteCommandParser.CommandContext ctx) {
        // Delegate to whichever child command rule matched
        return visitChildren(ctx);
    }

    @Override
    public String visitCreateCommand(CadetteCommandParser.CreateCommandContext ctx) {
        String shape = ctx.shape().getText();
        String name = extractName(ctx.objectName());

        String collision = nameCollisionError(name);
        if (collision != null) return collision;

        Vector3f position = Vector3f.ZERO;
        Vector3f size = defaultSize();
        ColorRGBA color = ColorRGBA.White;

        for (var arg : ctx.createArg()) {
            if (arg.atPlacement() != null) {
                position = parsePosition(arg.atPlacement().position());
            } else if (arg.sizeSpec() != null) {
                size = parseSizeSpec(arg.sizeSpec());
            } else if (arg.color() != null) {
                color = parseColor(arg.color());
            }
        }

        String id = scene.createObject(name, shape, position, size, color);
        executor.pushAction(new CreateAction(scene, name, shape, position, size, color));

        String abbr = executor.getUnits().getAbbreviation();
        return String.format("Created %s '%s' at (%.2f, %.2f, %.2f) %s",
                shape, id,
                fromMm(position.x), fromMm(position.y), fromMm(position.z), abbr);
    }

    @Override
    public String visitCreateTemplateCommand(CadetteCommandParser.CreateTemplateCommandContext ctx) {
        // Template name is a registry lookup, never a scene reference — never prefix it.
        // Instance name is the new object; extractName applies any outer template's prefix.
        String templateName = templateRefText(ctx.templateRef());
        String instanceName = extractName(ctx.objectName());

        Vector3f placement = null;
        CommandExecutor.RelativePlacement relPlacement = null;
        Map<String, Double> rawParams = new LinkedHashMap<>();

        for (var arg : ctx.templateArg()) {
            if (arg.atPlacement() != null) {
                var exprs = arg.atPlacement().position().expression();
                placement = new Vector3f(
                        evalFloat(exprs.get(0)),
                        evalFloat(exprs.get(1)),
                        evalFloat(exprs.get(2)));
            } else if (arg.relativePosition() != null) {
                var relCtx = arg.relativePosition();
                String dir = directionText(relCtx.direction());
                String refName = extractName(relCtx.objectName());
                float gap = relCtx.GAP() != null
                        ? evalFloat(relCtx.expression())
                        : 0;
                relPlacement = new CommandExecutor.RelativePlacement(dir, refName, gap);
            } else if (arg.paramValuePair() != null) {
                var pvp = arg.paramValuePair();
                rawParams.put(pvp.paramName().getText().toLowerCase(),
                        evaluateExpression(pvp.expression()));
            }
        }

        String result = executor.instantiateTemplate(templateName, instanceName, placement, relPlacement, rawParams);

        // Auto-validate the new assembly's joints. Silent on the happy path —
        // we only append output when there are actual issues, so well-formed
        // templates don't grow noise. Skips entirely if instantiation didn't
        // produce an assembly (e.g. error path, name collision).
        Assembly assembly = scene.getAssembly(instanceName);
        if (assembly != null) {
            List<Joint> joints = scene.getJointRegistry().getJointsForAssembly(assembly);
            if (!joints.isEmpty()) {
                List<ValidationIssue> issues = collectJointIssues(joints);
                if (!issues.isEmpty()) {
                    result += "\n\n" + formatValidationReport(issues, joints.size(),
                            " in '" + instanceName + "'");
                }
            }
        }
        return result;
    }

    @Override
    public String visitCreatePartCommand(CadetteCommandParser.CreatePartCommandContext ctx) {
        String name = extractName(ctx.objectName());

        String collision = nameCollisionError(name);
        if (collision != null) return collision;

        app.cadette.model.Material material = executor.getDefaultMaterial();
        CadetteCommandParser.PartSizeContext partSizeCtx = null;
        CadetteCommandParser.ExpressionContext lengthExpr = null;
        CadetteCommandParser.OrientationContext orientCtx = null;
        Vector3f position = Vector3f.ZERO;
        GrainRequirement grain = GrainRequirement.ANY;

        for (var arg : ctx.partArg()) {
            if (arg.materialName() != null) {
                String materialSlug = extractMaterialName(arg.materialName());
                material = MaterialCatalog.instance().get(materialSlug);
                if (material == null) {
                    return "Unknown material '" + materialSlug + "'.\n"
                            + "Available materials: use 'show materials' to list.";
                }
            } else if (arg.partSize() != null) {
                partSizeCtx = arg.partSize();
            } else if (arg.LENGTH() != null) {
                lengthExpr = arg.expression();
            } else if (arg.orientation() != null) {
                orientCtx = arg.orientation();
            } else if (arg.atPlacement() != null) {
                position = parsePosition(arg.atPlacement().position());
            } else if (arg.grainReq() != null) {
                if (arg.grainReq().VERTICAL() != null) grain = GrainRequirement.VERTICAL;
                else if (arg.grainReq().HORIZONTAL() != null) grain = GrainRequirement.HORIZONTAL;
            }
        }
        if (orientCtx != null && !material.isDimensionalLumber()) {
            return "Part '" + name + "': 'orient' applies to dimensional lumber only. "
                    + "Use 'rotate' for general rotation on " + material.getDisplayName() + ".";
        }

        if (partSizeCtx != null && lengthExpr != null) {
            return "Part '" + name + "': use either 'size W, L' or 'length L', not both.";
        }
        float cutWidth;
        float cutHeight;
        if (lengthExpr != null) {
            if (!material.isDimensionalLumber()) {
                return "Part '" + name + "': 'length' requires a dimensional-lumber material "
                        + "(e.g. lumber-2x4-spf). Use 'size W, L' for "
                        + material.getDisplayName() + ".";
            }
            cutWidth = material.getWidthMm();
            cutHeight = toMm(evalFloat(lengthExpr));
        } else if (partSizeCtx != null) {
            var exprs = partSizeCtx.expression();
            cutWidth = toMm(evalFloat(exprs.get(0)));
            cutHeight = toMm(evalFloat(exprs.get(1)));
            if (material.isDimensionalLumber()
                    && Math.abs(cutWidth - material.getWidthMm()) > 0.1f) {
                return String.format("Part '%s': width %.1f %s doesn't match %s cross-section (%.1f %s). "
                                + "Use 'length L' to default the width from the material.",
                        name, fromMm(cutWidth), executor.getUnits().getAbbreviation(),
                        material.getDisplayName(),
                        fromMm(material.getWidthMm()), executor.getUnits().getAbbreviation());
            }
        } else {
            return "Missing 'size' or 'length' for part '" + name + "'.";
        }

        Part part = Part.builder()
                .name(name)
                .material(material)
                .cutWidthMm(cutWidth)
                .cutHeightMm(cutHeight)
                .position(position)
                .grainRequirement(grain)
                .build();

        scene.createPart(part);
        executor.pushAction(new CreatePartAction(scene, part));
        executor.setLastCreatedPartName(name);

        if (orientCtx != null) {
            applyRotationKeepingAabbMinStable(name, orientationToEuler(orientCtx));
        }

        String abbr = executor.getUnits().getAbbreviation();
        return String.format("Created part '%s' — %s, %.2f x %.2f %s (%.2f %s thick) at (%.2f, %.2f, %.2f)%s",
                name, material.getDisplayName(),
                fromMm(cutWidth), fromMm(cutHeight), abbr,
                fromMm(material.getThicknessMm()), abbr,
                fromMm(position.x), fromMm(position.y), fromMm(position.z),
                orientCtx != null ? " oriented " + orientationLabel(orientCtx) : "");
    }

    @Override
    public String visitDeleteCommand(CadetteCommandParser.DeleteCommandContext ctx) {
        if (ctx.ALL() != null) {
            Map<String, SceneManager.ObjectRecord> recs = scene.getObjectRecords();
            List<DeleteAllAction.ObjectSnapshot> snapshots = recs.values().stream()
                    .map(r -> new DeleteAllAction.ObjectSnapshot(
                            r.name(), r.shapeType(), r.position(), r.size(), r.color(),
                            scene.getRotation(r.name()).clone(),
                            scene.getPart(r.name())))
                    .toList();
            scene.deleteAllObjects();
            executor.pushAction(new DeleteAllAction(scene, snapshots));
            return "Deleted all objects.";
        }
        String name = extractName(ctx.objectName());

        // Check assembly first
        Assembly assembly = scene.getAssembly(name);
        if (assembly != null) {
            return deleteAssembly(assembly);
        }

        SceneManager.ObjectRecord rec = scene.getObjectRecord(name);
        if (rec == null) {
            return "Object or assembly '" + name + "' not found.";
        }
        Part part = scene.getPart(name);
        Vector3f rotation = scene.getRotation(name).clone();
        scene.deleteObject(name);
        executor.pushAction(new DeleteAction(scene, rec.name(), rec.shapeType(),
                rec.position(), rec.size(), rec.color(), rotation, part));
        return "Deleted '" + name + "'.";
    }

    private String deleteAssembly(Assembly assembly) {
        String name = assembly.getName();
        String templateName = assembly.getTemplateName();

        // Joints touching any assembly part, deduplicated.
        List<Joint> assemblyJoints = assembly.getParts().stream()
                .flatMap(p -> scene.getJointRegistry().getJointsForPart(p.getName()).stream())
                .distinct()
                .toList();

        // Snapshots for undo — skip parts whose scene record is gone.
        List<DeleteAssemblyAction.PartSnapshot> snapshots = assembly.getParts().stream()
                .map(p -> {
                    SceneManager.ObjectRecord rec = scene.getObjectRecord(p.getName());
                    return rec == null ? null : new DeleteAssemblyAction.PartSnapshot(
                            p, rec.position(), rec.size(), rec.color(),
                            scene.getRotation(p.getName()));
                })
                .filter(Objects::nonNull)
                .toList();

        assembly.getParts().reversed().forEach(p -> scene.deleteObject(p.getName()));
        scene.removeAssembly(name);

        executor.pushAction(new DeleteAssemblyAction(scene, name, templateName,
                snapshots, assemblyJoints));
        return String.format("Deleted assembly '%s' (%d parts).", name, snapshots.size());
    }

    @Override
    public String visitMoveCommand(CadetteCommandParser.MoveCommandContext ctx) {
        String name = extractName(ctx.objectName());

        // Relative positioning: move b to left of "a" [gap N]
        if (ctx.relativePosition() != null) {
            return moveRelative(name, ctx.relativePosition());
        }

        // Absolute positioning: move b to x,y,z
        Assembly assembly = scene.getAssembly(name);
        if (assembly != null) {
            return moveAssembly(assembly, parsePosition(ctx.position()));
        }

        SceneManager.ObjectRecord rec = scene.getObjectRecord(name);
        if (rec == null) {
            return "Object or assembly '" + name + "' not found.";
        }
        Vector3f newPos = parsePosition(ctx.position());
        Vector3f oldPos = rec.position();
        scene.moveObject(name, newPos);
        executor.pushAction(new MoveAction(scene, name, oldPos, newPos));
        String abbr = executor.getUnits().getAbbreviation();
        return String.format("Moved '%s' to (%.2f, %.2f, %.2f) %s",
                name, fromMm(newPos.x), fromMm(newPos.y), fromMm(newPos.z), abbr);
    }

    private String moveRelative(String name, CadetteCommandParser.RelativePositionContext relCtx) {
        String refName = extractName(relCtx.objectName());
        String dir = directionText(relCtx.direction());
        float gapMm = 0;
        if (relCtx.GAP() != null) {
            gapMm = toMm(evalFloat(relCtx.expression()));
        }

        Vector3f[] srcBBox = getBBox(name);
        Vector3f[] refBBox = getBBox(refName);
        if (srcBBox == null) return "Object or assembly '" + name + "' not found.";
        if (refBBox == null) return "Reference '" + refName + "' not found.";

        Vector3f targetPos = computeRelativePosition(dir, refBBox, srcBBox, gapMm);
        String abbr = executor.getUnits().getAbbreviation();

        // Delegate to assembly or single-object move
        Assembly assembly = scene.getAssembly(name);
        if (assembly != null) {
            return moveAssembly(assembly, targetPos);
        }

        SceneManager.ObjectRecord rec = scene.getObjectRecord(name);
        Vector3f oldPos = rec.position();
        scene.moveObject(name, targetPos);
        executor.pushAction(new MoveAction(scene, name, oldPos, targetPos));
        return String.format("Moved '%s' %s '%s' to (%.2f, %.2f, %.2f) %s",
                name, dir, refName,
                fromMm(targetPos.x), fromMm(targetPos.y), fromMm(targetPos.z), abbr);
    }

    private String moveAssembly(Assembly assembly, Vector3f targetPos) {
        String name = assembly.getName();
        String abbr = executor.getUnits().getAbbreviation();

        Vector3f currentOrigin = getBBox(name)[0];

        Vector3f delta = targetPos.subtract(currentOrigin);
        List<String> partNames = assembly.getParts().stream()
                .map(Part::getName).toList();

        shiftByName(partNames, delta);
        executor.pushAction(new MoveAssemblyAction(scene, name, delta, partNames));
        return String.format("Moved assembly '%s' (%d parts) to (%.2f, %.2f, %.2f) %s",
                name, partNames.size(),
                fromMm(targetPos.x), fromMm(targetPos.y), fromMm(targetPos.z), abbr);
    }

    @Override
    public String visitAlignCommand(CadetteCommandParser.AlignCommandContext ctx) {
        String faceStr = faceText(ctx.face());

        // Reference object (the one to align WITH)
        String refName = extractName(ctx.objectName());
        Vector3f[] refBBox = getBBox(refName);
        if (refBBox == null) {
            return "Reference '" + refName + "' not found.";
        }

        // Get the face coordinate from the reference
        float refFaceCoord = switch (faceStr) {
            case "front" -> refBBox[1].z;   // max Z
            case "back" -> refBBox[0].z;    // min Z
            case "left" -> refBBox[0].x;    // min X
            case "right" -> refBBox[1].x;   // max X
            case "top" -> refBBox[1].y;     // max Y
            case "bottom" -> refBBox[0].y;  // min Y
            default -> 0;
        };

        // Targets to align
        var targetNames = ctx.objectNameList().objectName();
        String abbr = executor.getUnits().getAbbreviation();
        StringBuilder result = new StringBuilder();
        int aligned = 0;

        for (var targetCtx : targetNames) {
            String targetName = extractName(targetCtx);
            Vector3f[] targetBBox = getBBox(targetName);
            if (targetBBox == null) {
                result.append("  '").append(targetName).append("' not found.\n");
                continue;
            }

            // Compute how far to shift the target so its face matches the reference face
            float delta = switch (faceStr) {
                case "front" -> refFaceCoord - targetBBox[1].z;   // align max Z
                case "back" -> refFaceCoord - targetBBox[0].z;    // align min Z
                case "left" -> refFaceCoord - targetBBox[0].x;    // align min X
                case "right" -> refFaceCoord - targetBBox[1].x;   // align max X
                case "top" -> refFaceCoord - targetBBox[1].y;     // align max Y
                case "bottom" -> refFaceCoord - targetBBox[0].y;  // align min Y
                default -> 0;
            };

            if (Math.abs(delta) < 0.01f) {
                result.append("  '").append(targetName).append("' already aligned.\n");
                aligned++;
                continue;
            }

            // Build the movement vector (only one axis moves)
            Vector3f moveVec = switch (faceStr) {
                case "front", "back" -> new Vector3f(0, 0, delta);
                case "left", "right" -> new Vector3f(delta, 0, 0);
                case "top", "bottom" -> new Vector3f(0, delta, 0);
                default -> Vector3f.ZERO;
            };

            // Apply to assembly or individual object
            Assembly assembly = scene.getAssembly(targetName);
            if (assembly != null) {
                List<String> partNames = assembly.getParts().stream()
                        .map(Part::getName).toList();
                shiftByName(partNames, moveVec);
                executor.pushAction(new MoveAssemblyAction(scene, targetName, moveVec, partNames));
            } else {
                SceneManager.ObjectRecord rec = scene.getObjectRecord(targetName);
                if (rec != null) {
                    Vector3f oldPos = rec.position();
                    scene.moveObject(targetName, oldPos.add(moveVec));
                    executor.pushAction(new MoveAction(scene, targetName, oldPos, oldPos.add(moveVec)));
                }
            }
            aligned++;
        }

        return String.format("Aligned %s of %d object(s) with '%s'.",
                faceStr, aligned, refName);
    }

    @Override
    public String visitRotateCommand(CadetteCommandParser.RotateCommandContext ctx) {
        String name = extractName(ctx.objectName());
        var exprs = ctx.rotation().expression();
        Vector3f delta = new Vector3f(
                evalFloat(exprs.get(0)),
                evalFloat(exprs.get(1)),
                evalFloat(exprs.get(2)));
        boolean absolute = ctx.rotationMode() != null
                && ctx.rotationMode() instanceof CadetteCommandParser.RotateToModeContext;

        // Check assembly first
        Assembly assembly = scene.getAssembly(name);
        if (assembly != null) {
            // Assemblies always compose (their semantics already meant
            // "rotate the whole thing by this delta"); `to` rotates from
            // the identity, i.e. resets each part's rotation first.
            if (absolute) {
                for (Part p : assembly.getParts()) {
                    scene.rotateObject(p.getName(), Vector3f.ZERO);
                }
            }
            return rotateAssembly(assembly, delta);
        }

        SceneManager.ObjectRecord rec = scene.getObjectRecord(name);
        if (rec == null) {
            return "Object or assembly '" + name + "' not found.";
        }
        Vector3f oldDegrees = scene.getRotation(name).clone();
        Vector3f newDegrees = absolute ? delta : composeRotation(oldDegrees, delta);
        // `rotate` pivots around the part's local origin (existing semantics).
        // Templates depend on this; `orient` is the AABB-stable alternative.
        scene.rotateObject(name, newDegrees);
        executor.pushAction(new RotateAction(scene, name, oldDegrees, newDegrees));
        return String.format("Rotated '%s' %s (%.1f, %.1f, %.1f) → (%.1f, %.1f, %.1f) degrees",
                name, absolute ? "to" : "by",
                delta.x, delta.y, delta.z,
                newDegrees.x, newDegrees.y, newDegrees.z);
    }

    /**
     * Rotate a part and translate it so the AABB min corner stays where it
     * was before the rotation — the visible box doesn't fly off when you
     * change orientation. Returns the new position.
     */
    private Vector3f applyRotationKeepingAabbMinStable(String name, Vector3f newDegrees) {
        com.jme3.math.Vector3f[] oldAabb = scene.computeObjectAABB(name);
        scene.rotateObject(name, newDegrees);
        com.jme3.math.Vector3f[] newAabb = scene.computeObjectAABB(name);
        if (oldAabb == null || newAabb == null) {
            return scene.getObjectRecord(name).position().clone();
        }
        Vector3f delta = oldAabb[0].subtract(newAabb[0]);
        Vector3f current = scene.getObjectRecord(name).position();
        Vector3f target = current.add(delta);
        scene.moveObject(name, target);
        return target;
    }

    /** Compose two Euler-angle rotations as quaternion multiplication (delta
     *  applied after current). Returns the combined rotation as XYZ Euler. */
    private static Vector3f composeRotation(Vector3f currentDegrees, Vector3f deltaDegrees) {
        com.jme3.math.Quaternion current = eulerToQuat(currentDegrees);
        com.jme3.math.Quaternion delta = eulerToQuat(deltaDegrees);
        com.jme3.math.Quaternion combined = delta.mult(current);
        float[] angles = combined.toAngles(null);
        return new Vector3f(
                angles[0] * com.jme3.math.FastMath.RAD_TO_DEG,
                angles[1] * com.jme3.math.FastMath.RAD_TO_DEG,
                angles[2] * com.jme3.math.FastMath.RAD_TO_DEG);
    }

    private static com.jme3.math.Quaternion eulerToQuat(Vector3f degrees) {
        return new com.jme3.math.Quaternion().fromAngles(
                degrees.x * com.jme3.math.FastMath.DEG_TO_RAD,
                degrees.y * com.jme3.math.FastMath.DEG_TO_RAD,
                degrees.z * com.jme3.math.FastMath.DEG_TO_RAD);
    }

    @Override
    public String visitOrientCommand(CadetteCommandParser.OrientCommandContext ctx) {
        String name = extractName(ctx.objectName());
        SceneManager.ObjectRecord rec = scene.getObjectRecord(name);
        if (rec == null) {
            return "Object '" + name + "' not found.";
        }
        Part part = scene.getPart(name);
        if (part == null || !part.getMaterial().isDimensionalLumber()) {
            return "Orient '" + name + "': named orientations apply to dimensional lumber parts only. "
                    + "Use 'rotate' for general rotation.";
        }
        Vector3f target = orientationToEuler(ctx.orientation());
        Vector3f oldDegrees = scene.getRotation(name).clone();
        Vector3f oldPosition = rec.position().clone();
        Vector3f newPosition = applyRotationKeepingAabbMinStable(name, target);
        executor.pushAction(new RotateAction(scene, name, oldDegrees, target, oldPosition, newPosition));
        return String.format("Oriented '%s' %s — (%.1f, %.1f, %.1f) degrees",
                name, orientationLabel(ctx.orientation()),
                target.x, target.y, target.z);
    }

    /** Map an orientation keyword to its target Euler angles (XYZ degrees).
     *  Carpentry conventions: a board's "wide face" is the broader of its
     *  two long faces; the "narrow edge" is the other.
     *
     *  <p>For lumber with default part-local axes X = cross-section width,
     *  Y = length, Z = thickness (wide dimension):
     *  <ul>
     *    <li>{@code flat}: length horizontal (along X), wide face up/down
     *        (faces ±Y) — laid flat on a workbench, low profile from the side
     *    <li>{@code on-edge}: length horizontal (along X), narrow edge up
     *        (faces ±Y), wide face vertical (faces ±Z) — joist or gate-rail
     *        stance, full 89mm visible to a front viewer
     *    <li>{@code on-end}: length vertical (along Y), wide face toward +Z
     *        — fence post, vertical gate stile
     *  </ul>
     */
    private static Vector3f orientationToEuler(CadetteCommandParser.OrientationContext ctx) {
        com.jme3.math.Quaternion q;
        if (ctx instanceof CadetteCommandParser.OrientFlatContext) {
            // Single 90° around Z: length swings from local Y to world X.
            // Wide-face normal swings from local X to world Y (up).
            q = axisAngle(0, 0, 1, 90);
        } else if (ctx instanceof CadetteCommandParser.OrientOnEdgeContext) {
            // First lay flat (90° around Z), then tip onto narrow edge by
            // rotating 90° around the (now horizontal) length axis = world X.
            // Wide face swings from up to facing the viewer.
            q = axisAngle(1, 0, 0, 90).mult(axisAngle(0, 0, 1, 90));
        } else /* OrientOnEndContext */ {
            // Length stays vertical (local Y). Just rotate 90° around Y so
            // the wide-face normal (local X) swings to world Z, facing viewer.
            q = axisAngle(0, 1, 0, 90);
        }
        float[] angles = q.toAngles(null);
        return new Vector3f(
                angles[0] * com.jme3.math.FastMath.RAD_TO_DEG,
                angles[1] * com.jme3.math.FastMath.RAD_TO_DEG,
                angles[2] * com.jme3.math.FastMath.RAD_TO_DEG);
    }

    private static com.jme3.math.Quaternion axisAngle(float ax, float ay, float az, float degrees) {
        return new com.jme3.math.Quaternion().fromAngleAxis(
                degrees * com.jme3.math.FastMath.DEG_TO_RAD,
                new Vector3f(ax, ay, az));
    }

    private static String orientationLabel(CadetteCommandParser.OrientationContext ctx) {
        if (ctx instanceof CadetteCommandParser.OrientFlatContext) return "flat";
        if (ctx instanceof CadetteCommandParser.OrientOnEdgeContext) return "on-edge";
        return "on-end";
    }

    private String rotateAssembly(Assembly assembly, Vector3f newDegrees) {
        String name = assembly.getName();
        List<String> partNames = assembly.getParts().stream()
                .map(Part::getName).toList();

        // Capture old state for undo
        Map<String, Vector3f> oldRotations = new LinkedHashMap<>();
        Map<String, Vector3f> oldPositions = new LinkedHashMap<>();
        for (String partName : partNames) {
            oldRotations.put(partName, scene.getRotation(partName).clone());
            SceneManager.ObjectRecord rec = scene.getObjectRecord(partName);
            if (rec != null) {
                oldPositions.put(partName, rec.position().clone());
            }
        }

        // Compute the assembly's pivot (bounding box min)
        Vector3f pivot = assembly.getBoundingBoxMin(posLookup(), sizeLookup());

        // Build a quaternion for the assembly rotation
        com.jme3.math.Quaternion assemblyQuat = new com.jme3.math.Quaternion().fromAngles(
                newDegrees.x * com.jme3.math.FastMath.DEG_TO_RAD,
                newDegrees.y * com.jme3.math.FastMath.DEG_TO_RAD,
                newDegrees.z * com.jme3.math.FastMath.DEG_TO_RAD);

        // Rotate each part's position around the pivot and combine rotations
        for (String partName : partNames) {
            SceneManager.ObjectRecord rec = scene.getObjectRecord(partName);
            if (rec == null) continue;

            // Rotate position around pivot
            Vector3f relPos = rec.position().subtract(pivot);
            Vector3f rotatedPos = assemblyQuat.mult(relPos).add(pivot);
            scene.moveObject(partName, rotatedPos);

            // Combine: apply the assembly rotation on top of the part's existing rotation
            Vector3f partDegrees = scene.getRotation(partName);
            com.jme3.math.Quaternion partQuat = new com.jme3.math.Quaternion().fromAngles(
                    partDegrees.x * com.jme3.math.FastMath.DEG_TO_RAD,
                    partDegrees.y * com.jme3.math.FastMath.DEG_TO_RAD,
                    partDegrees.z * com.jme3.math.FastMath.DEG_TO_RAD);
            com.jme3.math.Quaternion combined = assemblyQuat.mult(partQuat);
            float[] angles = combined.toAngles(null);
            Vector3f combinedDegrees = new Vector3f(
                    angles[0] * com.jme3.math.FastMath.RAD_TO_DEG,
                    angles[1] * com.jme3.math.FastMath.RAD_TO_DEG,
                    angles[2] * com.jme3.math.FastMath.RAD_TO_DEG);
            scene.rotateObject(partName, combinedDegrees);
        }

        executor.pushAction(new RotateAssemblyAction(scene, name, oldRotations,
                oldPositions, partNames));
        return String.format("Rotated assembly '%s' (%d parts) by (%.1f, %.1f, %.1f) degrees",
                name, partNames.size(), newDegrees.x, newDegrees.y, newDegrees.z);
    }

    @Override
    public String visitResizeCommand(CadetteCommandParser.ResizeCommandContext ctx) {
        String name = extractName(ctx.objectName());
        // Resize doesn't apply to assemblies — only individual parts
        SceneManager.ObjectRecord rec = scene.getObjectRecord(name);
        if (rec == null) {
            Assembly assembly = scene.getAssembly(name);
            if (assembly != null) {
                return "Cannot resize an assembly. Resize individual parts with '" + name + "/part-name'.";
            }
            return "Object '" + name + "' not found.";
        }
        Vector3f newSize = parseSizeSpec(ctx.sizeSpec());
        Vector3f oldSize = rec.size();
        scene.resizeObject(name, newSize);
        executor.pushAction(new ResizeAction(scene, name, oldSize, newSize));
        String abbr = executor.getUnits().getAbbreviation();
        return String.format("Resized '%s' to (%.2f, %.2f, %.2f) %s",
                name, fromMm(newSize.x), fromMm(newSize.y), fromMm(newSize.z), abbr);
    }

    @Override
    public String visitJoinCommand(CadetteCommandParser.JoinCommandContext ctx) {
        var names = ctx.objectName();
        String receivingName = extractName(names.get(0));
        String insertedName = extractName(names.get(1));

        // Validate both parts exist
        if (scene.getObjectRecord(receivingName) == null) {
            return "Object '" + receivingName + "' not found.";
        }
        if (scene.getObjectRecord(insertedName) == null) {
            return "Object '" + insertedName + "' not found.";
        }

        // Parse joint type — looked up by name so adding a joint kind is a
        // Java-only change (new record + JointType entry).
        String jointTypeName = ctx.jointType().getText();
        JointType type = JointType.fromString(jointTypeName);
        if (type == null) {
            return "Unknown joint type '" + jointTypeName + "'. Valid: " + JointType.validNames();
        }

        // Validate that the joint can work in both materials. Today this is
        // a MaterialType-only check; thickness and receiver/inserted asymmetry
        // are parked as future work (see memory project_joint_future_work).
        Part receivingPart = scene.getPart(receivingName);
        Part insertedPart = scene.getPart(insertedName);
        String incompatibility = checkJointMaterialCompatibility(type, receivingPart, insertedPart);
        if (incompatibility != null) return incompatibility;

        // Extract optional modifiers (depth/screws/spacing) from joinArgs.
        Float requestedDepthUnits = null;
        int screwCount = 0;
        float screwSpacingMm = 0;
        for (var arg : ctx.joinArg()) {
            float num = evalFloat(arg.expression());
            if (arg.DEPTH() != null) requestedDepthUnits = num;
            else if (arg.SCREWS() != null) screwCount = (int) num;
            else if (arg.SPACING() != null) screwSpacingMm = toMm(num);
        }

        // For dado/rabbet: validate materials and compute depth
        String abbr = executor.getUnits().getAbbreviation();
        StringBuilder warnings = new StringBuilder();
        float depthMm = 0;

        if (type.isAffectsGeometry()) {
            // Look up the receiving part's material thickness
            float receivingThicknessMm = receivingPart != null
                    ? receivingPart.getThicknessMm()
                    : scene.getObjectRecord(receivingName).size().z; // fallback for primitives

            // Warn if the receiving part is too thin (< 10mm) for a dado/rabbet
            if (receivingThicknessMm < 10f) {
                warnings.append(String.format(
                        "Warning: '%s' is only %.1f %s thick — consider reversing the joint direction " +
                        "(the thicker part should receive the %s).\n",
                        receivingName, fromMm(receivingThicknessMm), abbr, type.getDisplayName()));
            }

            if (requestedDepthUnits != null) {
                depthMm = toMm(requestedDepthUnits);
            } else {
                // Default to half the receiving material's thickness
                depthMm = receivingThicknessMm / 2f;
            }

            // Cap depth at the receiving material's thickness
            if (depthMm > receivingThicknessMm) {
                warnings.append(String.format(
                        "Warning: depth %.2f %s exceeds material thickness %.2f %s — capped.\n",
                        fromMm(depthMm), abbr, fromMm(receivingThicknessMm), abbr));
                depthMm = receivingThicknessMm;
            }
        }

        SourceLocation src = new SourceLocation(executor.effectiveSource(),
                ctx.getStart().getLine());
        Joint joint = switch (type) {
            case BUTT -> new Joint.Butt(receivingName, insertedName, src);
            case DADO -> new Joint.Dado(receivingName, insertedName, depthMm, src);
            case RABBET -> new Joint.Rabbet(receivingName, insertedName, depthMm, src);
            case POCKET_SCREW -> new Joint.PocketScrew(receivingName, insertedName,
                    screwCount, screwSpacingMm, src);
            case COUNTERSUNK_SCREW -> new Joint.CountersunkScrew(receivingName, insertedName,
                    screwCount, screwSpacingMm, src);
            case GLUE -> new Joint.Glue(receivingName, insertedName, src);
        };

        scene.getJointRegistry().addJoint(joint);
        scene.markCutSheetDirty();
        executor.pushAction(new JoinAction(scene, joint));

        StringBuilder msg = new StringBuilder();
        if (!warnings.isEmpty()) {
            msg.append(warnings);
        }
        msg.append(String.format("Joined '%s' to '%s' with %s",
                receivingName, insertedName, type.getDisplayName()));
        if (depthMm > 0) {
            msg.append(String.format(" (depth: %.2f %s)", fromMm(depthMm), abbr));
        }
        if (screwCount > 0) {
            msg.append(String.format(" (%d screws", screwCount));
            if (screwSpacingMm > 0) {
                msg.append(String.format(", %.2f %s spacing", fromMm(screwSpacingMm), abbr));
            }
            msg.append(")");
        }
        return msg.toString();
    }

    @Override
    public String visitShapeDefPolygon(CadetteCommandParser.ShapeDefPolygonContext ctx) {
        String name = extractName(ctx.objectName());
        List<Point2D> verts = ctx.vertexPair().stream()
                .map(v -> new Point2D(
                        toMm(evalFloat(v.expression(0))),
                        toMm(evalFloat(v.expression(1)))))
                .toList();
        executor.registerShape(name, new Shape.Polygon(verts));
        return "Defined polygon shape '" + name + "' with " + verts.size() + " vertices.";
    }

    @Override
    public String visitShapeDefSpline(CadetteCommandParser.ShapeDefSplineContext ctx) {
        String name = extractName(ctx.objectName());
        List<Point2D> pts = ctx.vertexPair().stream()
                .map(v -> new Point2D(
                        toMm(evalFloat(v.expression(0))),
                        toMm(evalFloat(v.expression(1)))))
                .toList();
        executor.registerShape(name, new Shape.Spline(pts));
        return "Defined spline shape '" + name + "' with " + pts.size() + " control points.";
    }

    @Override
    public String visitPrintCommand(CadetteCommandParser.PrintCommandContext ctx) {
        return messageBody(ctx.STRING().getText());
    }

    @Override
    public String visitWarnCommand(CadetteCommandParser.WarnCommandContext ctx) {
        return "Warning: " + messageBody(ctx.STRING().getText());
    }

    @Override
    public String visitErrorCommand(CadetteCommandParser.ErrorCommandContext ctx) {
        // v1: error doesn't terminate the script — same emit mechanism as
        // print/warn with a stronger prefix. Script-termination via a
        // separate `fail` command is backlogged.
        return "Error: " + messageBody(ctx.STRING().getText());
    }

    /** Strip the surrounding quotes and run ${var} interpolation against the
     *  active scope. Shared helper for print/warn/error. */
    private String messageBody(String quoted) {
        return interpolateString(stripStringQuotes(quoted));
    }

    @Override
    public String visitLetCommand(CadetteCommandParser.LetCommandContext ctx) {
        String name = ctx.VAR_REF().getText().substring(1);  // strip $
        double value = evaluateExpression(ctx.expression());
        executor.setInCurrentScope(name, value);
        return String.format("$%s = %s", name, formatNumber(value));
    }

    /** Drop trailing .0 on whole numbers; otherwise show enough precision to
     *  round-trip the value sensibly. Keeps `let` echo readable. */
    private static String formatNumber(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return String.format("%.4g", v);
    }

    @Override
    public String visitCutCommand(CadetteCommandParser.CutCommandContext ctx) {
        String partName = extractName(ctx.objectName());
        Part part = scene.getPart(partName);
        if (part == null) {
            return "Part '" + partName + "' not found.";
        }
        Cutout cutout = buildCutout(ctx.cutShape(), part);
        String abbr = executor.getUnits().getAbbreviation();
        // PartMeshBuilder silently drops cutouts that clip to nothing —
        // catch that here so the user gets feedback at command time. The
        // check is shape-accurate (polygonizes the cutout and asks JTS),
        // not a bbox approximation, so polygons/splines whose vertices
        // straddle the panel but whose actual shape misses it are caught.
        if (!PartMeshBuilder.intersectsPanelFace(cutout,
                part.getCutWidthMm(), part.getCutHeightMm())) {
            return String.format("Cutout %s falls outside '%s' (%.1f × %.1f %s); ignored.",
                    describeCutout(cutout, abbr), partName,
                    fromMm(part.getCutWidthMm()), fromMm(part.getCutHeightMm()), abbr);
        }
        part.addCutout(cutout);
        executor.pushAction(new CutAction(scene, partName, cutout));
        // rebuildPartMesh handles mesh regeneration + markCutSheetDirty.
        scene.rebuildPartMesh(partName);
        StringBuilder result = new StringBuilder("Added cutout to '")
                .append(partName).append("': ").append(describeCutout(cutout, abbr));
        if (cutoutExtendsPastPanel(cutout, part)) {
            result.append("\n  Note: cutout extends past the panel edge; clipped at the boundary.");
        }
        return result.toString();
    }

    @Override
    public String visitFilletCommand(CadetteCommandParser.FilletCommandContext ctx) {
        String partName = extractName(ctx.objectName());
        Part part = scene.getPart(partName);
        if (part == null) {
            return "Part '" + partName + "' not found.";
        }

        var exprs = ctx.expression();
        float cx = toMm(evalFloat(exprs.get(0)));
        float cy = toMm(evalFloat(exprs.get(1)));
        float radius = toMm(evalFloat(exprs.get(2)));
        Float depth = ctx.DEPTH() != null ? toMm(evalFloat(exprs.get(3))) : null;

        String facingName = ctx.nameLike().getText().toUpperCase();
        Float facingDeg = parseFacingCardinal(facingName);
        if (facingDeg == null) {
            return "Unknown facing direction '" + facingName + "'. Valid: NE, NW, SE, SW.";
        }

        List<Point2D> verts = filletWedgeVertices(cx, cy, radius, facingDeg);
        Cutout cutout = new Cutout.Polygon(verts, depth, Cutout.Face.FRONT);

        String abbr = executor.getUnits().getAbbreviation();
        if (!PartMeshBuilder.intersectsPanelFace(cutout,
                part.getCutWidthMm(), part.getCutHeightMm())) {
            return String.format("Fillet at (%.1f, %.1f) %s falls outside '%s' (%.1f × %.1f %s); ignored.",
                    fromMm(cx), fromMm(cy), abbr, partName,
                    fromMm(part.getCutWidthMm()), fromMm(part.getCutHeightMm()), abbr);
        }
        part.addCutout(cutout);
        executor.pushAction(new CutAction(scene, partName, cutout));
        scene.rebuildPartMesh(partName);

        String depthStr = depth != null
                ? String.format(" %.1f %s deep", fromMm(depth), abbr)
                : " through";
        return String.format("Filleted '%s' at (%.1f, %.1f) %s, radius %.1f %s, facing %s%s",
                partName, fromMm(cx), fromMm(cy), abbr,
                fromMm(radius), abbr, facingName, depthStr);
    }

    /**
     * Triangle vertices for a miter wedge at the named corner. The cut line
     * runs across the part's width (W) starting at the corner, tilted by
     * {@code angleDeg} from the end edge. The wedge removed has the corner
     * itself as one vertex, the adjacent end-edge corner as another, and a
     * point on the side edge as the third — depth W·tan(angle) along the
     * length axis.
     *
     * <p>For a brace installed at angle α from horizontal, the miter at
     * each end uses angle = α: the cut surface ends up parallel to the
     * surface the brace lands on. Same number drives both rotate and miter.
     *
     * <p>Returns null for unrecognised facing names.
     */
    private static List<Point2D> miterWedgeVertices(String facing, float angleDeg,
                                                     float widthMm, float heightMm) {
        double depth = widthMm * Math.tan(Math.toRadians(angleDeg));
        return switch (facing) {
            case "SE" -> List.of(  // bottom-right wedge: cut from (0,0) up to (W, depth)
                    new Point2D(0, 0),
                    new Point2D(widthMm, 0),
                    new Point2D(widthMm, (float) depth));
            case "SW" -> List.of(  // bottom-left wedge: cut from (W,0) up to (0, depth)
                    new Point2D(widthMm, 0),
                    new Point2D(0, 0),
                    new Point2D(0, (float) depth));
            case "NE" -> List.of(  // top-right wedge: cut from (0,h) down to (W, h-depth)
                    new Point2D(0, heightMm),
                    new Point2D(widthMm, heightMm),
                    new Point2D(widthMm, heightMm - (float) depth));
            case "NW" -> List.of(  // top-left wedge: cut from (W,h) down to (0, h-depth)
                    new Point2D(widthMm, heightMm),
                    new Point2D(0, heightMm),
                    new Point2D(0, heightMm - (float) depth));
            default -> null;
        };
    }

    /** Cardinal facing → degrees. {@code null} for unrecognised names. */
    private static Float parseFacingCardinal(String name) {
        return switch (name) {
            case "NE" -> 45f;
            case "NW" -> 135f;
            case "SW" -> 225f;
            case "SE" -> 315f;
            default -> null;
        };
    }

    /**
     * Polygon vertices for a fillet wedge: corner point, tangent point on
     * edge 1, arc samples, tangent point on edge 2. Auto-closes back to
     * the corner. The wedge is the small region between the L-shape corner
     * and the quarter-arc tangent to both edges.
     */
    private static List<Point2D> filletWedgeVertices(float cx, float cy, float r, float facingDeg) {
        int arcSamples = 12;
        double facingRad = Math.toRadians(facingDeg);
        // Edges of material adjacent to the corner, 135° from facing each side.
        double e1Rad = facingRad - Math.toRadians(135);
        double e2Rad = facingRad + Math.toRadians(135);
        // Tangent points B (on edge 1), D (on edge 2).
        float bx = cx + r * (float) Math.cos(e1Rad);
        float by = cy + r * (float) Math.sin(e1Rad);
        float dxv = cx + r * (float) Math.cos(e2Rad);
        float dyv = cy + r * (float) Math.sin(e2Rad);
        // Arc centre: into the material along the bisector, distance r·√2.
        double matRad = facingRad + Math.PI;
        double diag = r * Math.sqrt(2);
        float ax = cx + (float) (diag * Math.cos(matRad));
        float ay = cy + (float) (diag * Math.sin(matRad));
        // Arc spans 90°; pick the short way around.
        double thetaB = Math.atan2(by - ay, bx - ax);
        double thetaD = Math.atan2(dyv - ay, dxv - ax);
        double delta = thetaD - thetaB;
        if (delta > Math.PI)  delta -= 2 * Math.PI;
        if (delta < -Math.PI) delta += 2 * Math.PI;

        List<Point2D> verts = new ArrayList<>();
        verts.add(new Point2D(cx, cy));
        verts.add(new Point2D(bx, by));
        for (int i = 1; i < arcSamples; i++) {
            double t = (double) i / arcSamples;
            double theta = thetaB + t * delta;
            verts.add(new Point2D(
                    ax + r * (float) Math.cos(theta),
                    ay + r * (float) Math.sin(theta)));
        }
        verts.add(new Point2D(dxv, dyv));
        return verts;
    }

    @Override
    public String visitKeepCommand(CadetteCommandParser.KeepCommandContext ctx) {
        String partName = extractName(ctx.objectName());
        Part part = scene.getPart(partName);
        if (part == null) {
            return "Part '" + partName + "' not found.";
        }
        Cutout keep = buildCutout(ctx.cutShape(), part);
        String abbr = executor.getUnits().getAbbreviation();
        // A keep region with no overlap with the panel would erase the panel
        // entirely. Reject as a probable user error rather than silently
        // produce an empty mesh.
        if (!PartMeshBuilder.intersectsPanelFace(keep,
                part.getCutWidthMm(), part.getCutHeightMm())) {
            return String.format("Keep region %s falls outside '%s' (%.1f × %.1f %s); rejected "
                    + "(would erase the panel).",
                    describeCutout(keep, abbr), partName,
                    fromMm(part.getCutWidthMm()), fromMm(part.getCutHeightMm()), abbr);
        }
        part.addKeep(keep);
        executor.pushAction(new KeepAction(scene, partName, keep));
        scene.rebuildPartMesh(partName);
        StringBuilder result = new StringBuilder("Added keep region to '")
                .append(partName).append("': ").append(describeCutout(keep, abbr));
        if (cutoutExtendsPastPanel(keep, part)) {
            result.append("\n  Note: keep region extends past the panel edge; clipped at the boundary.");
        }
        return result.toString();
    }

    /** True if the cutout's bbox crosses any panel edge. Triggers an
     *  informational note so the user can spot typos that produce wildly
     *  off-panel coordinates (e.g. polygon vertex at 1500 instead of 150). */
    private static boolean cutoutExtendsPastPanel(Cutout c, Part p) {
        BoundingBox b = c.bounds();
        return b.xMm() < 0
                || b.maxXMm() > p.getCutWidthMm()
                || b.yMm() < 0
                || b.maxYMm() > p.getCutHeightMm();
    }

    /** Dispatch on the cutShape alternative to build the appropriate Cutout variant. */
    private Cutout buildCutout(CadetteCommandParser.CutShapeContext ctx, Part part) {
        if (ctx instanceof CadetteCommandParser.RectCutShapeContext r) {
            // Args (AT/SIZE/DEPTH/FACE) in any order; visitor enforces required set.
            ShapeArgs args = parseShapeArgs(r.shapeArg());
            // Reject mismatches first so the user gets a useful "wrong clause"
            // message rather than a "missing required clause" one.
            if (args.radius != null) throw new IllegalArgumentException("rect cut does not take `radius`");
            if (args.at == null) throw new IllegalArgumentException("rect cut requires `at <x>, <y>`");
            if (args.size == null) throw new IllegalArgumentException("rect cut requires `size <w>, <h>`");
            return new Cutout.Rect(args.at[0], args.at[1], args.size[0], args.size[1],
                    args.depth, args.face);
        }
        if (ctx instanceof CadetteCommandParser.CircleCutShapeContext c) {
            ShapeArgs args = parseShapeArgs(c.shapeArg());
            if (args.size != null) throw new IllegalArgumentException("circle cut does not take `size`; use `radius`");
            if (args.at == null) throw new IllegalArgumentException("circle cut requires `at <cx>, <cy>`");
            if (args.radius == null) throw new IllegalArgumentException("circle cut requires `radius <r>`");
            return new Cutout.Circle(args.at[0], args.at[1], args.radius, args.depth, args.face);
        }
        if (ctx instanceof CadetteCommandParser.PolygonCutShapeContext p) {
            List<Point2D> vertices = p.vertexPair().stream()
                    .map(v -> new Point2D(
                            toMm(evalFloat(v.expression(0))),
                            toMm(evalFloat(v.expression(1)))))
                    .toList();
            ShapeArgs args = parseShapeArgs(p.shapeArg());
            rejectVertexShapeMismatches("polygon", args);
            return new Cutout.Polygon(vertices, args.depth, args.face);
        }
        if (ctx instanceof CadetteCommandParser.SplineCutShapeContext s) {
            // Catmull-Rom — interpolating, control points lie on the curve.
            List<Point2D> controls = s.vertexPair().stream()
                    .map(v -> new Point2D(
                            toMm(evalFloat(v.expression(0))),
                            toMm(evalFloat(v.expression(1)))))
                    .toList();
            ShapeArgs args = parseShapeArgs(s.shapeArg());
            rejectVertexShapeMismatches("spline", args);
            return new Cutout.Spline(controls, args.depth, args.face);
        }
        if (ctx instanceof CadetteCommandParser.CurveCutShapeContext cv) {
            // Cubic Bezier — control points in groups of 3, periodic closure.
            List<Point2D> controls = cv.vertexPair().stream()
                    .map(v -> new Point2D(
                            toMm(evalFloat(v.expression(0))),
                            toMm(evalFloat(v.expression(1)))))
                    .toList();
            if (controls.size() < 6 || controls.size() % 3 != 0) {
                throw new IllegalArgumentException(
                        "curve needs a multiple of 3 control points (≥6 for ≥2 segments); got "
                        + controls.size());
            }
            ShapeArgs args = parseShapeArgs(cv.shapeArg());
            rejectVertexShapeMismatches("curve", args);
            return new Cutout.Curve(controls, args.depth, args.face);
        }
        if (ctx instanceof CadetteCommandParser.MiterCutShapeContext m) {
            // miter facing <NE|NW|SE|SW> angle <θ> — triangular wedge at the
            // named corner of the part. θ is measured from the end edge.
            // Reusable for diagonal braces, picture-frame miters, hip rafters.
            String facingName = m.nameLike().getText().toUpperCase();
            float angleDeg = evalFloat(m.expression());
            ShapeArgs args = parseShapeArgs(m.shapeArg());
            if (args.at != null) throw new IllegalArgumentException(
                    "miter cut does not take `at` — position is the named corner");
            if (args.size != null) throw new IllegalArgumentException(
                    "miter cut does not take `size`");
            if (args.radius != null) throw new IllegalArgumentException(
                    "miter cut does not take `radius`");
            List<Point2D> verts = miterWedgeVertices(
                    facingName, angleDeg,
                    part.getCutWidthMm(), part.getCutHeightMm());
            if (verts == null) {
                throw new IllegalArgumentException(
                        "Unknown miter facing '" + facingName + "'. Valid: NE, NW, SE, SW.");
            }
            return new Cutout.Polygon(verts, args.depth, args.face);
        }
        if (ctx instanceof CadetteCommandParser.NamedShapeCutShapeContext n) {
            // SHAPE <name> with AT/DEPTH args in any order.
            String shapeName = extractName(n.objectName());
            Shape shape = executor.lookupShape(shapeName);
            if (shape == null) {
                throw new IllegalArgumentException("Unknown shape: '" + shapeName + "'");
            }
            ShapeArgs args = parseShapeArgs(n.shapeArg());
            if (args.size != null || args.radius != null) throw new IllegalArgumentException(
                    "shape reference does not take `size` or `radius`");
            if (args.at == null) throw new IllegalArgumentException(
                    "shape reference requires `at <ax>, <ay>`");
            float ax = args.at[0];
            float ay = args.at[1];
            Float depth = args.depth;
            Cutout.Face face = args.face;
            List<Point2D> translated = shape.points().stream()
                    .map(p -> new Point2D(p.xMm() + ax, p.yMm() + ay))
                    .toList();
            return switch (shape) {
                case Shape.Polygon ignored -> new Cutout.Polygon(translated, depth, face);
                case Shape.Spline ignored  -> new Cutout.Spline(translated, depth, face);
            };
        }
        throw new IllegalStateException("Unhandled cutShape alternative: "
                + ctx.getClass().getSimpleName());
    }

    /**
     * Parsed bag of shape clauses. Each field is null if the corresponding
     * clause wasn't present. Caller validates required clauses for its shape.
     */
    private static final class ShapeArgs {
        float[] at;             // [x, y] in mm
        float[] size;           // [w, h] in mm
        Float radius;           // mm
        Float depth;            // mm
        Cutout.Face face = Cutout.Face.FRONT;  // defaults to front
    }

    /** Reject AT/SIZE/RADIUS clauses on shapes whose geometry is defined by
     *  vertex lists (polygon, spline, curve). Only DEPTH and FACE make sense
     *  there. */
    private static void rejectVertexShapeMismatches(String shapeName, ShapeArgs args) {
        if (args.at != null) throw new IllegalArgumentException(
                shapeName + " cut does not take `at` — coordinates come from the vertex list");
        if (args.size != null) throw new IllegalArgumentException(
                shapeName + " cut does not take `size`");
        if (args.radius != null) throw new IllegalArgumentException(
                shapeName + " cut does not take `radius`");
    }

    /** Walk shape-arg clauses and collect them into a {@link ShapeArgs} bag,
     *  rejecting duplicates of the same clause. */
    private ShapeArgs parseShapeArgs(List<CadetteCommandParser.ShapeArgContext> args) {
        ShapeArgs out = new ShapeArgs();
        boolean faceSeen = false;
        for (var arg : args) {
            if (arg instanceof CadetteCommandParser.ShapeArgAtContext at) {
                if (out.at != null) throw new IllegalArgumentException("`at` specified twice");
                out.at = new float[] {
                        toMm(evalFloat(at.expression(0))),
                        toMm(evalFloat(at.expression(1))) };
            } else if (arg instanceof CadetteCommandParser.ShapeArgSizeContext sz) {
                if (out.size != null) throw new IllegalArgumentException("`size` specified twice");
                out.size = new float[] {
                        toMm(evalFloat(sz.expression(0))),
                        toMm(evalFloat(sz.expression(1))) };
            } else if (arg instanceof CadetteCommandParser.ShapeArgRadiusContext rd) {
                if (out.radius != null) throw new IllegalArgumentException("`radius` specified twice");
                out.radius = toMm(evalFloat(rd.expression()));
            } else if (arg instanceof CadetteCommandParser.ShapeArgDepthContext dp) {
                if (out.depth != null) throw new IllegalArgumentException("`depth` specified twice");
                out.depth = toMm(evalFloat(dp.expression()));
            } else if (arg instanceof CadetteCommandParser.ShapeArgFaceContext fc) {
                if (faceSeen) throw new IllegalArgumentException("`face` specified twice");
                faceSeen = true;
                String name = fc.nameLike().getText().toLowerCase();
                out.face = switch (name) {
                    case "front" -> Cutout.Face.FRONT;
                    case "back" -> Cutout.Face.BACK;
                    default -> throw new IllegalArgumentException(
                            "Unknown face '" + name + "'. Valid: front, back.");
                };
            }
        }
        return out;
    }

    /** Human-readable cutout description for command output. */
    private String describeCutout(Cutout cutout, String abbr) {
        if (cutout instanceof Cutout.Rect r) {
            String depth = r.depthMm() != null
                    ? String.format(" %.1f %s deep", fromMm(r.depthMm()), abbr)
                    : " through";
            return String.format("rect at (%.1f, %.1f) %s size %.1f × %.1f %s%s",
                    fromMm(r.xMm()), fromMm(r.yMm()), abbr,
                    fromMm(r.widthMm()), fromMm(r.heightMm()), abbr,
                    depth);
        }
        if (cutout instanceof Cutout.Circle c) {
            String depth = c.depthMm() != null
                    ? String.format(" %.1f %s deep", fromMm(c.depthMm()), abbr)
                    : " through";
            return String.format("circle at (%.1f, %.1f) %s radius %.1f %s%s",
                    fromMm(c.cxMm()), fromMm(c.cyMm()), abbr,
                    fromMm(c.radiusMm()), abbr, depth);
        }
        if (cutout instanceof Cutout.Polygon p) {
            String depth = p.depthMm() != null
                    ? String.format(" %.1f %s deep", fromMm(p.depthMm()), abbr)
                    : " through";
            return String.format("polygon (%d vertices)%s",
                    p.vertices().size(), depth);
        }
        if (cutout instanceof Cutout.Spline s) {
            String depth = s.depthMm() != null
                    ? String.format(" %.1f %s deep", fromMm(s.depthMm()), abbr)
                    : " through";
            return String.format("spline (%d control points)%s",
                    s.controlPoints().size(), depth);
        }
        if (cutout instanceof Cutout.Curve cv) {
            String depth = cv.depthMm() != null
                    ? String.format(" %.1f %s deep", fromMm(cv.depthMm()), abbr)
                    : " through";
            int segments = cv.controlPoints().size() / 3;
            return String.format("curve (%d Bezier segments)%s", segments, depth);
        }
        return cutout.toString();  // variants not yet user-facing
    }

    @Override
    public String visitDisplayCommand(CadetteCommandParser.DisplayCommandContext ctx) {
        if (ctx.objectName() != null) {
            String name = extractName(ctx.objectName());
            // Check assembly first
            Assembly assembly = scene.getAssembly(name);
            if (assembly != null) {
                int count = 0;
                for (Part p : assembly.getParts()) {
                    if (!scene.isNameDisplayed(p.getName())) {
                        scene.displayName(p.getName());
                        count++;
                    }
                }
                return count > 0
                        ? "Displaying names for " + count + " part(s) in assembly '" + name + "'."
                        : "All names already displayed in assembly '" + name + "'.";
            }
            if (scene.getObjectRecord(name) == null) {
                return "Object or assembly '" + name + "' not found.";
            }
            boolean wasDisplayed = scene.isNameDisplayed(name);
            scene.displayName(name);
            executor.pushAction(new DisplayNameAction(scene, name, wasDisplayed));
            return wasDisplayed
                    ? "Name already displayed for '" + name + "'."
                    : "Displaying name for '" + name + "'.";
        }
        // All objects
        int count = scene.displayAllNames();
        return count > 0
                ? "Displaying names for " + count + " object(s)."
                : "All names already displayed.";
    }

    @Override
    public String visitHideCommand(CadetteCommandParser.HideCommandContext ctx) {
        if (ctx.objectName() != null) {
            String name = extractName(ctx.objectName());
            // Check assembly first
            Assembly assembly = scene.getAssembly(name);
            if (assembly != null) {
                int count = 0;
                for (Part p : assembly.getParts()) {
                    if (scene.isNameDisplayed(p.getName())) {
                        scene.hideName(p.getName());
                        count++;
                    }
                }
                return count > 0
                        ? "Hidden names for " + count + " part(s) in assembly '" + name + "'."
                        : "No names were displayed in assembly '" + name + "'.";
            }
            if (scene.getObjectRecord(name) == null) {
                return "Object or assembly '" + name + "' not found.";
            }
            boolean wasDisplayed = scene.isNameDisplayed(name);
            scene.hideName(name);
            executor.pushAction(new HideNameAction(scene, name, wasDisplayed));
            return wasDisplayed
                    ? "Hidden name for '" + name + "'."
                    : "Name was not displayed for '" + name + "'.";
        }
        // All objects
        int count = scene.hideAllNames();
        return count > 0
                ? "Hidden names for " + count + " object(s)."
                : "No names were displayed.";
    }

    @Override
    public String visitListCommand(CadetteCommandParser.ListCommandContext ctx) {
        return listObjects();
    }

    @Override
    public String visitShowCommand(CadetteCommandParser.ShowCommandContext ctx) {
        // show info <name>
        if (ctx.INFO() != null && ctx.objectName() != null) {
            return showInfo(extractName(ctx.objectName()));
        }
        // show template <name>
        if (ctx.TEMPLATE() != null && ctx.templateRef() != null) {
            return showTemplateDefinition(templateRefText(ctx.templateRef()));
        }

        var target = ctx.showTarget();
        if (target.UNITS() != null) {
            UnitSystem u = executor.getUnits();
            return "Current units: " + u.name().toLowerCase()
                    + " (" + u.getAbbreviation() + ")"
                    + "\nAvailable: " + UnitSystem.allNames();
        }
        if (target.OBJECTS() != null) {
            return listObjects();
        }
        if (target.MATERIALS() != null) {
            return listMaterials();
        }
        if (target.TEMPLATES() != null) {
            return listTemplates();
        }
        if (target.JOINTS() != null) {
            return listJoints();
        }
        if (target.CUTLIST() != null) {
            return showCutList();
        }
        if (target.BOM() != null) {
            return showBom();
        }
        if (target.LAYOUT() != null) {
            return showLayout();
        }
        if (target.SCRIPT_PATH() != null) {
            return showScriptPath();
        }
        if (target.THEMES() != null) {
            return showThemes();
        }
        if (target.THEME() != null) {
            return showActiveTheme();
        }
        if (target.ABOUT() != null) {
            return showAbout();
        }
        return "Unknown show target. Try: show units, show objects, show materials, show templates, show joints, show cutlist, show bom, show layout, show script_path, show theme, show themes, show about";
    }

    private String showAbout() {
        app.cadette.BuildInfo bi = app.cadette.BuildInfo.instance();
        return "Cadette " + bi.getVersion()
            + "\nBuild commit:  " + bi.getCommit() + " (" + bi.getBranch() + ")"
            + "\nBuilt:         " + bi.getBuildTime()
            + "\nLicense:       Apache 2.0"
            + "\nGitHub:        https://github.com/bobhablutzel/cadette";
    }

    /** Brief "what's active right now". Use {@code show themes} to list. */
    private String showActiveTheme() {
        app.cadette.theme.ThemeRegistry reg = executor.getThemeRegistry();
        String active = executor.getThemeName();
        if (reg == null) return "Active theme: " + active + " (registry not initialised).";
        app.cadette.theme.Theme t = reg.getTheme(active);
        if (t == null) return "Active theme: " + active + " (NOT FOUND in registry).";
        StringBuilder sb = new StringBuilder("Active theme: ").append(active);
        if (t.description() != null) sb.append(" — ").append(t.description());
        if (t.extendsFrom() != null) sb.append(" (extends ").append(t.extendsFrom()).append(")");
        return sb.toString();
    }

    private String showThemes() {
        app.cadette.theme.ThemeRegistry reg = executor.getThemeRegistry();
        if (reg == null) return "Themes: registry not initialised.";
        java.util.List<String> names = reg.listThemes();
        if (names.isEmpty()) return "No themes loaded.";
        StringBuilder sb = new StringBuilder("Themes (").append(names.size()).append(", * = active):\n");
        String active = executor.getThemeName();
        for (String n : names) {
            sb.append("  ");
            sb.append(n.equals(active) ? "* " : "  ");
            sb.append(n);
            app.cadette.theme.Theme t = reg.getTheme(n);
            if (t != null && t.description() != null) {
                sb.append("  — ").append(t.description());
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String showScriptPath() {
        List<Path> userEntries = executor.getUserScriptPath();
        StringBuilder sb = new StringBuilder();
        if (userEntries.isEmpty()) {
            sb.append("Script search path: defaults only (no user entries set).");
        } else {
            sb.append("Script search path (user entries): ");
            sb.append(userEntries.stream().map(Path::toString).collect(Collectors.joining(", ")));
            sb.append(".");
        }
        sb.append("\nEffective search order:");
        for (Path p : executor.effectiveScriptSearchPath()) {
            sb.append("\n  ").append(p);
        }
        return sb.toString();
    }

    @Override
    public String visitSetCommand(CadetteCommandParser.SetCommandContext ctx) {
        if (ctx.unitName() != null) {
            String unitText = ctx.unitName().getText();
            UnitSystem newUnit = UnitSystem.fromString(unitText);
            if (newUnit == null) {
                return "Unknown unit '" + unitText + "'. Available: " + UnitSystem.allNames();
            }
            UnitSystem oldUnit = executor.getUnits();
            executor.pushAction(new SetUnitsAction(executor::setUnits, oldUnit, newUnit));
            executor.setUnits(newUnit);
            return "Units set to " + newUnit.name().toLowerCase() + " (" + newUnit.getAbbreviation() + ").";
        }
        if (ctx.materialName() != null) {
            String slug = extractMaterialName(ctx.materialName());
            var mat = MaterialCatalog.instance().get(slug);
            if (mat == null) {
                return "Unknown material '" + slug + "'. Use 'show materials' to list.";
            }
            executor.setDefaultMaterial(mat);
            return "Default material set to " + mat.getDisplayName() + " (" + mat.getName() + ").";
        }
        if (ctx.KERF() != null) {
            float value = toMm(evalFloat(ctx.expression()));
            if (value < 0) {
                return "Kerf must be non-negative.";
            }
            scene.setKerfMm(value);
            String abbr = executor.getUnits().getAbbreviation();
            return String.format("Kerf set to %.1f %s (%.1f mm).", fromMm(value), abbr, value);
        }
        if (ctx.scriptPathSpec() != null) {
            var spec = ctx.scriptPathSpec();
            List<Path> entries;
            if (spec.NONE() != null) {
                entries = List.of();
            } else {
                entries = spec.STRING().stream()
                        .map(t -> stripQuotes(t.getText()))
                        .map(CommandVisitor::expandUserHome)
                        .toList();
            }
            List<Path> oldPath = executor.getUserScriptPath();
            executor.pushAction(new SetScriptPathAction(executor::setUserScriptPath, oldPath, entries));
            executor.setUserScriptPath(entries);
            return formatScriptPathStatus(entries);
        }
        if (ctx.themeName() != null) {
            String name;
            if (ctx.themeName().ID() != null) {
                name = ctx.themeName().ID().getText();
            } else {
                name = stripQuotes(ctx.themeName().STRING().getText());
            }
            app.cadette.theme.ThemeRegistry reg = executor.getThemeRegistry();
            if (reg == null || reg.getTheme(name) == null) {
                String known = reg == null ? "(registry not initialised)"
                                            : String.join(", ", reg.listThemes());
                return "Unknown theme '" + name + "'. Known: " + known + ".";
            }
            executor.setThemeName(name);
            return "Theme set to " + name + ". Restart to apply.";
        }
        return "Unknown set target.";
    }

    /**
     * Tilde-expand a user-supplied path string. {@code Path.of("~/foo")}
     * does not expand the tilde — it would create a literal directory
     * named "~" — so we handle the {@code ~} and {@code ~/} prefixes here.
     */
    private static Path expandUserHome(String s) {
        if (s.equals("~")) return Path.of(System.getProperty("user.home"));
        if (s.startsWith("~/")) return Path.of(System.getProperty("user.home"), s.substring(2));
        return Path.of(s);
    }

    private String formatScriptPathStatus(List<Path> userEntries) {
        StringBuilder sb = new StringBuilder();
        if (userEntries.isEmpty()) {
            sb.append("Script search path cleared (defaults only).");
        } else {
            sb.append("Script search path set to: ");
            sb.append(userEntries.stream().map(Path::toString).collect(Collectors.joining(", ")));
            sb.append(".");
        }
        sb.append("\nEffective search order:");
        for (Path p : executor.effectiveScriptSearchPath()) {
            sb.append("\n  ").append(p);
        }
        return sb.toString();
    }

    private static String stripQuotes(String s) {
        return (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
                ? s.substring(1, s.length() - 1) : s;
    }

    @Override
    public String visitExportCommand(CadetteCommandParser.ExportCommandContext ctx) {
        var parts = scene.getAllParts();
        if (parts.isEmpty()) {
            return "No parts in scene — nothing to export.";
        }

        var fmt = ctx.exportFormat();
        boolean isCsv = fmt.CSV() != null;
        boolean isPdf = fmt.PDF() != null;
        boolean isPng = fmt.PNG() != null;

        String extension = isCsv ? "csv" : isPdf ? "pdf" : isPng ? "png" : "jpeg";
        String description = isCsv ? "CSV files"
                : isPdf ? "PDF files"
                : isPng ? "PNG images"
                : "JPEG images";

        // Determine output path
        Path outputPath;
        if (ctx.STRING() != null) {
            String path = ctx.STRING().getText();
            path = path.substring(1, path.length() - 1); // strip quotes
            outputPath = Path.of(path);
            if (!path.contains(".")) {
                outputPath = Path.of(path + "." + extension);
            }
        } else {
            outputPath = executor.chooseSaveFile(description, extension);
            if (outputPath == null) {
                return "Export cancelled.";
            }
        }

        try {
            UnitSystem units = executor.getUnits();
            if (isCsv) {
                var entries = CutListGenerator.generateCutList(parts, scene.getJointRegistry(), executor.getUnits());
                CutListExporter.exportCsv(entries, units, outputPath);
                return "Exported cut list to " + outputPath.toAbsolutePath();
            }

            // Sheet-layout exports (PDF/PNG/JPEG) need actual sheet goods.
            var layouts = SheetLayoutGenerator.generateLayouts(parts, scene.getKerfMm());
            if (layouts.isEmpty()) {
                return "No sheet goods in scene (only hardwood/metal parts) — nothing to export.";
            }
            var cutouts = scene.getEffectiveCutouts();
            var keeps = scene.getEffectiveKeeps();
            if (isPdf) {
                CutSheetExporter.exportPdf(layouts, units, outputPath, cutouts, keeps);
            } else {
                CutSheetExporter.exportImage(layouts, units, outputPath, cutouts, keeps);
            }
            return "Exported cut sheets to " + outputPath.toAbsolutePath();
        } catch (Exception e) {
            return "Export failed: " + e.getMessage();
        }
    }

    @Override
    public String visitUndoCommand(CadetteCommandParser.UndoCommandContext ctx) {
        return executor.undo();
    }

    @Override
    public String visitRedoCommand(CadetteCommandParser.RedoCommandContext ctx) {
        return executor.redo();
    }

    @Override
    public String visitHelpCommand(CadetteCommandParser.HelpCommandContext ctx) {
        return helpText();
    }

    @Override
    public String visitExitCommand(CadetteCommandParser.ExitCommandContext ctx) {
        executor.fireExit();
        return "Exiting...";
    }

    @Override
    public String visitStatsCommand(CadetteCommandParser.StatsCommandContext ctx) {
        boolean visible = scene.toggleStats();
        return "Stats display " + (visible ? "on" : "off") + ".";
    }

    @Override
    public String visitValidateCommand(CadetteCommandParser.ValidateCommandContext ctx) {
        List<Joint> joints;
        String scopeLabel;
        if (ctx.objectName() != null) {
            String name = extractName(ctx.objectName());
            Assembly assembly = scene.getAssembly(name);
            if (assembly == null) {
                return "No assembly named '" + name + "'.";
            }
            joints = scene.getJointRegistry().getJointsForAssembly(assembly);
            scopeLabel = " in '" + name + "'";
        } else {
            joints = scene.getJointRegistry().getAllJoints();
            scopeLabel = "";
        }
        if (joints.isEmpty()) return "No joints to validate" + scopeLabel + ".";

        List<ValidationIssue> issues = collectJointIssues(joints);
        return formatValidationReport(issues, joints.size(), scopeLabel);
    }

    /**
     * Run every joint's geometric predicate and gather the issues. Joints
     * referencing missing parts (mid-undo, partially-built scene) are
     * skipped silently.
     */
    private List<ValidationIssue> collectJointIssues(List<Joint> joints) {
        Map<String, Part> parts = scene.getAllParts();
        List<ValidationIssue> issues = new ArrayList<>();
        for (Joint j : joints) {
            Part receiving = parts.get(j.receivingPartName());
            Part inserted = parts.get(j.insertedPartName());
            if (receiving == null || inserted == null) continue;
            issues.addAll(j.validate(receiving, inserted, scene));
        }
        return issues;
    }

    private String formatValidationReport(List<ValidationIssue> issues, int totalJoints, String scopeLabel) {
        if (issues.isEmpty()) {
            return String.format("Validation%s: all %d joint%s OK.",
                    scopeLabel, totalJoints, totalJoints == 1 ? "" : "s");
        }
        long errors = issues.stream()
                .filter(i -> i.severity() == ValidationIssue.Severity.ERROR).count();
        long warnings = issues.size() - errors;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Validation%s: %d issue%s found (%d error%s, %d warning%s).",
                scopeLabel,
                issues.size(), issues.size() == 1 ? "" : "s",
                errors, errors == 1 ? "" : "s",
                warnings, warnings == 1 ? "" : "s"));
        for (ValidationIssue i : issues) {
            sb.append("\n  [").append(i.severity().name()).append("] ").append(i.message());
        }
        return sb.toString();
    }

    @Override
    public String visitRunCommand(CadetteCommandParser.RunCommandContext ctx) {
        if (ctx.pathExpr() == null) {
            return executor.runWithFileChooser();
        }
        String path = ctx.pathExpr().pathSegment().stream()
                .map(CommandVisitor::pathSegmentText)
                .collect(Collectors.joining());
        return executor.runScriptPath(path);
    }

    private static String pathSegmentText(CadetteCommandParser.PathSegmentContext seg) {
        if (seg.PATH_LITERAL() != null) return seg.PATH_LITERAL().getText();
        if (seg.PATH_VAR() != null) return resolvePathVar(seg.PATH_VAR().getText().substring(1));
        if (seg.PATH_QUOTED() != null) {
            String q = seg.PATH_QUOTED().getText();
            return q.substring(1, q.length() - 1); // strip surrounding quotes
        }
        return "";
    }

    /**
     * Check whether the given joint type can physically work in both parts'
     * materials. Returns null if compatible, or an error message naming the
     * first incompatible part if not. Only substance (MaterialType) is checked;
     * thickness and asymmetric rules are future work.
     */
    private static String checkJointMaterialCompatibility(JointType type, Part receiving, Part inserted) {
        return Stream.of(receiving, inserted)
                .filter(Objects::nonNull)
                .map(p -> incompatibilityMessage(type, p))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** Null if the part's material supports this joint type; error message otherwise. */
    private static String incompatibilityMessage(JointType type, Part p) {
        var mat = p.getMaterial();
        if (mat == null || type.supports(mat.getType())) return null;
        return String.format(
                "Cannot make a %s joint in '%s' (%s). %s not applicable to %s.",
                type.getDisplayName().toLowerCase(),
                p.getName(),
                mat.getDisplayName(),
                type.getDisplayName(),
                mat.getType().name().toLowerCase());
    }

    /** Resolve a path variable — home/user aliases first, then env vars. */
    private static String resolvePathVar(String name) {
        return switch (name.toLowerCase()) {
            case "home" -> System.getProperty("user.home", "");
            case "user" -> System.getProperty("user.name", "");
            default -> {
                String env = System.getenv(name);
                yield env != null ? env : "$" + name;
            }
        };
    }

    @Override
    public String visitUsingAdd(CadetteCommandParser.UsingAddContext ctx) {
        String ns = templateRefText(ctx.templateRef());
        return executor.addUsingNamespace(ns);
    }

    @Override
    public String visitUsingClear(CadetteCommandParser.UsingClearContext ctx) {
        executor.clearUsingNamespaces();
        return "Cleared `using` namespaces.";
    }

    @Override
    public String visitWhichCommand(CadetteCommandParser.WhichCommandContext ctx) {
        String ref = templateRefText(ctx.templateRef());
        CommandExecutor.TemplateResolution res = executor.resolveTemplate(ref);
        if (res.template() == null) return res.errorMessage();
        Template t = res.template();
        String src = t.getSource() != null ? t.getSource() : "(unknown source)";
        return ref + " → " + t.getName() + "\n  source: " + src;
    }

    // -- Parsing helpers --

    private Vector3f parsePosition(CadetteCommandParser.PositionContext ctx) {
        var exprs = ctx.expression();
        return new Vector3f(
                toMm(evalFloat(exprs.get(0))),
                toMm(evalFloat(exprs.get(1))),
                toMm(evalFloat(exprs.get(2))));
    }

    private Vector3f parseSizeSpec(CadetteCommandParser.SizeSpecContext ctx) {
        if (ctx instanceof CadetteCommandParser.SizeByDimensionsContext dimCtx) {
            var exprs = dimCtx.dimensions().expression();
            float first = toMm(evalFloat(exprs.get(0)));
            if (exprs.size() == 3) {
                return new Vector3f(
                        first,
                        toMm(evalFloat(exprs.get(1))),
                        toMm(evalFloat(exprs.get(2))));
            }
            return new Vector3f(first, first, first);
        }
        if (ctx instanceof CadetteCommandParser.SizeByComponentsContext compCtx) {
            float w = toMm(1);
            float h = toMm(1);
            float d = toMm(1);
            for (var ws : compCtx.widthSpec()) {
                w = toMm(evalFloat(ws.expression()));
            }
            for (var hs : compCtx.heightSpec()) {
                h = toMm(evalFloat(hs.expression()));
            }
            for (var ds : compCtx.depthSpec()) {
                d = toMm(evalFloat(ds.expression()));
            }
            return new Vector3f(w, h, d);
        }
        return defaultSize();
    }

    private ColorRGBA parseColor(CadetteCommandParser.ColorContext ctx) {
        if (ctx.RED() != null) return ColorRGBA.Red;
        if (ctx.GREEN() != null) return ColorRGBA.Green;
        if (ctx.BLUE() != null) return ColorRGBA.Blue;
        if (ctx.YELLOW() != null) return ColorRGBA.Yellow;
        if (ctx.WHITE() != null) return ColorRGBA.White;
        if (ctx.HEX_COLOR() != null) {
            String hex = ctx.HEX_COLOR().getText().substring(1);
            int rgb = Integer.parseInt(hex, 16);
            return new ColorRGBA(
                    ((rgb >> 16) & 0xFF) / 255f,
                    ((rgb >> 8) & 0xFF) / 255f,
                    (rgb & 0xFF) / 255f,
                    1f);
        }
        return ColorRGBA.White;
    }

    private String listObjects() {
        Map<String, SceneManager.ObjectRecord> recs = scene.getObjectRecords();
        if (recs.isEmpty()) return "No objects in scene.";
        String abbr = executor.getUnits().getAbbreviation();
        StringBuilder sb = new StringBuilder("Objects in scene (units: " + abbr + "):\n");

        Map<String, Assembly> assemblies = scene.getAllAssemblies();
        // Pre-compute part names owned by any assembly so standalone filtering
        // below is independent of the listing pass.
        java.util.Set<String> assemblyPartNames = assemblies.values().stream()
                .flatMap(a -> a.getParts().stream())
                .map(Part::getName)
                .collect(Collectors.toSet());

        // List assemblies first
        assemblies.values().forEach(assembly -> {
            sb.append(String.format("\n  %-20s assembly%s (%d parts)%n",
                    assembly.getName(), assemblyTemplateLabel(assembly), assembly.getParts().size()));
            assembly.getParts().forEach(p -> {
                SceneManager.ObjectRecord rec = recs.get(p.getName());
                if (rec != null) {
                    Vector3f pos = rec.position();
                    sb.append(String.format("    %-20s [%s] %.2f x %.2f %s  at (%.2f, %.2f, %.2f)%s%n",
                            p.getName(), p.getMaterial().getName(),
                            fromMm(p.getCutWidthMm()), fromMm(p.getCutHeightMm()), abbr,
                            fromMm(pos.x), fromMm(pos.y), fromMm(pos.z),
                            cutoutSummary(p)));
                }
            });
        });

        // List standalone objects (not in any assembly)
        List<SceneManager.ObjectRecord> standalone = recs.values().stream()
                .filter(rec -> !assemblyPartNames.contains(rec.name()))
                .toList();
        if (!standalone.isEmpty()) {
            if (!assemblies.isEmpty()) sb.append("\n  Standalone:\n");
            standalone.forEach(rec -> sb.append(standaloneLine(rec, abbr)).append("\n"));
        }
        return sb.toString().stripTrailing();
    }

    /** Compact " [N cutouts]" suffix for list output; empty string if none. */
    private static String cutoutSummary(Part part) {
        int n = part.getCutouts().size();
        return n == 0 ? "" : "  [" + n + " cutout" + (n == 1 ? "" : "s") + "]";
    }

    private static String assemblyTemplateLabel(Assembly assembly) {
        String tmplName = assembly.getTemplateName();
        if (tmplName == null) return "";
        Template tmpl = TemplateRegistry.instance().get(tmplName);
        String src = tmpl != null ? tmpl.getSource() : null;
        return src != null
                ? " [" + tmplName + " from " + src + "]"
                : " [" + tmplName + "]";
    }

    private String standaloneLine(SceneManager.ObjectRecord rec, String abbr) {
        Vector3f pos = rec.position();
        Part part = scene.getPart(rec.name());
        if (part != null) {
            return String.format("  %-20s [%s] %.2f x %.2f %s (%.2f thick)  at (%.2f, %.2f, %.2f)  grain: %s%s",
                    part.getName(), part.getMaterial().getName(),
                    fromMm(part.getCutWidthMm()), fromMm(part.getCutHeightMm()), abbr,
                    fromMm(part.getThicknessMm()),
                    fromMm(pos.x), fromMm(pos.y), fromMm(pos.z),
                    part.getGrainRequirement().name().toLowerCase(),
                    cutoutSummary(part));
        }
        Vector3f size = rec.size();
        return String.format("  %-20s %-10s at (%.2f, %.2f, %.2f)  size (%.2f, %.2f, %.2f) %s",
                rec.name(), rec.shapeType(),
                fromMm(pos.x), fromMm(pos.y), fromMm(pos.z),
                fromMm(size.x), fromMm(size.y), fromMm(size.z),
                abbr);
    }

    private String listMaterials() {
        String abbr = executor.getUnits().getAbbreviation();
        String body = MaterialCatalog.instance().getAll().stream()
                .map(mat -> materialLine(mat, abbr))
                .collect(Collectors.joining("\n"));
        return "Available materials:\n" + body;
    }

    private String materialLine(app.cadette.model.Material mat, String abbr) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-22s %-30s  thickness: %.2f %s  type: %s  kind: %s",
                mat.getName(), mat.getDisplayName(),
                fromMm(mat.getThicknessMm()), abbr,
                mat.getType().name().toLowerCase(),
                mat.getKind().name().toLowerCase()));
        if (mat.getKind() == app.cadette.model.MaterialKind.SHEET_GOOD
                && mat.getSheetWidthMm() != null) {
            sb.append(String.format("  sheet: %.0f x %.0f %s",
                    fromMm(mat.getSheetWidthMm()), fromMm(mat.getSheetHeightMm()), abbr));
        }
        if (mat.getGrainDirection() != app.cadette.model.GrainDirection.NONE) {
            sb.append("  grain: yes");
        }
        return sb.toString();
    }

    private String showInfo(String name) {
        // Check assembly first
        Assembly assembly = scene.getAssembly(name);
        if (assembly != null) {
            return showAssemblyInfo(assembly);
        }

        SceneManager.ObjectRecord rec = scene.getObjectRecord(name);
        if (rec == null) {
            return "Object or assembly '" + name + "' not found.";
        }
        String abbr = executor.getUnits().getAbbreviation();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Info: ").append(name).append(" ===\n");

        // Record data
        sb.append(String.format("  Shape:    %s%n", rec.shapeType()));
        sb.append(String.format("  Position: (%.2f, %.2f, %.2f) %s%n",
                fromMm(rec.position().x), fromMm(rec.position().y), fromMm(rec.position().z), abbr));
        sb.append(String.format("  Size:     (%.2f, %.2f, %.2f) %s%n",
                fromMm(rec.size().x), fromMm(rec.size().y), fromMm(rec.size().z), abbr));

        // Rotation
        Vector3f rot = scene.getRotation(name);
        sb.append(String.format("  Rotation: (%.1f, %.1f, %.1f) degrees%n", rot.x, rot.y, rot.z));

        // Part info
        app.cadette.model.Part part = scene.getPart(name);
        if (part != null) {
            sb.append(String.format("  Material: %s (%s, %.2f %s thick)%n",
                    part.getMaterial().getName(), part.getMaterial().getDisplayName(),
                    fromMm(part.getThicknessMm()), abbr));
            sb.append(String.format("  Cut size: %.2f x %.2f %s%n",
                    fromMm(part.getCutWidthMm()), fromMm(part.getCutHeightMm()), abbr));
            sb.append(String.format("  Grain:    %s%n", part.getGrainRequirement().name().toLowerCase()));
        }

        // Scene graph data
        Vector3f nodeWorld = scene.getNodeWorldTranslation(name);
        Vector3f geomLocal = scene.getGeomLocalTranslation(name);
        if (nodeWorld != null) {
            sb.append(String.format("  [Scene] Node world pos:  (%.2f, %.2f, %.2f)%n",
                    fromMm(nodeWorld.x), fromMm(nodeWorld.y), fromMm(nodeWorld.z)));
        }
        if (geomLocal != null) {
            sb.append(String.format("  [Scene] Geom local pos:  (%.2f, %.2f, %.2f)%n",
                    fromMm(geomLocal.x), fromMm(geomLocal.y), fromMm(geomLocal.z)));
        }
        Vector3f[] bounds = scene.getWorldBounds(name);
        if (bounds != null) {
            sb.append(String.format("  [Scene] World bounds min: (%.2f, %.2f, %.2f)%n",
                    fromMm(bounds[0].x), fromMm(bounds[0].y), fromMm(bounds[0].z)));
            sb.append(String.format("  [Scene] World bounds max: (%.2f, %.2f, %.2f)%n",
                    fromMm(bounds[1].x), fromMm(bounds[1].y), fromMm(bounds[1].z)));
        }

        // Joints involving this part
        var joints = scene.getJointRegistry().getJointsForPart(name);
        if (!joints.isEmpty()) {
            sb.append("  Joints:\n");
            String lines = joints.stream()
                    .map(j -> jointInfoLine(j, name, abbr))
                    .collect(Collectors.joining("\n"));
            sb.append(lines).append("\n");
        }

        // Cutouts (toe-kick notches, shelf-pin holes, sink cutouts, etc.)
        if (part != null && !part.getCutouts().isEmpty()) {
            sb.append("  Cutouts:\n");
            String lines = part.getCutouts().stream()
                    .map(c -> cutoutInfoLine(c, abbr))
                    .collect(Collectors.joining("\n"));
            sb.append(lines).append("\n");
        }

        return sb.toString().stripTrailing();
    }

    /** Info-panel formatting for a single cutout. */
    private String cutoutInfoLine(app.cadette.model.Cutout c, String abbr) {
        if (c instanceof app.cadette.model.Cutout.Rect r) {
            String depth = r.depthMm() != null
                    ? String.format(" %.1f %s deep", fromMm(r.depthMm()), abbr)
                    : " through";
            return String.format("    rect at (%.1f, %.1f) %s size %.1f × %.1f %s%s",
                    fromMm(r.xMm()), fromMm(r.yMm()), abbr,
                    fromMm(r.widthMm()), fromMm(r.heightMm()), abbr, depth);
        }
        return "    " + c.getClass().getSimpleName().toLowerCase();
    }

    /** Info-panel formatting: names the role (receives/inserted into) from the part's POV. */
    private String jointInfoLine(Joint j, String partName, String abbr) {
        boolean isReceiving = j.receivingPartName().equals(partName);
        String role = isReceiving ? "receives" : "inserted into";
        String other = isReceiving ? j.insertedPartName() : j.receivingPartName();
        String depth = switch (j) {
            case Joint.Dado d -> String.format(", depth %.2f %s", fromMm(d.depthMm()), abbr);
            case Joint.Rabbet r -> String.format(", depth %.2f %s", fromMm(r.depthMm()), abbr);
            default -> "";
        };
        return String.format("    %s \"%s\" (%s%s)", role, other, j.type().getDisplayName(), depth);
    }

    private String showAssemblyInfo(Assembly assembly) {
        String abbr = executor.getUnits().getAbbreviation();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Assembly: ").append(assembly.getName()).append(" ===\n");
        if (assembly.getTemplateName() != null) {
            sb.append(String.format("  Template:  %s%n", assembly.getTemplateName()));
        }
        sb.append(String.format("  Parts:     %d%n", assembly.getParts().size()));

        // Bounding box (rotation-aware)
        Vector3f[] aabb = getBBox(assembly.getName());
        sb.append(String.format("  Origin:    (%.2f, %.2f, %.2f) %s%n",
                fromMm(aabb[0].x), fromMm(aabb[0].y), fromMm(aabb[0].z), abbr));
        sb.append(String.format("  Size:      (%.2f, %.2f, %.2f) %s%n",
                fromMm(aabb[1].x - aabb[0].x), fromMm(aabb[1].y - aabb[0].y),
                fromMm(aabb[1].z - aabb[0].z), abbr));

        // List parts
        sb.append("\n  Parts:\n");
        String partsBody = assembly.getParts().stream()
                .map(p -> {
                    SceneManager.ObjectRecord rec = scene.getObjectRecord(p.getName());
                    if (rec == null) return null;
                    Vector3f pos = rec.position();
                    return String.format("    %-25s [%s] %.2f x %.2f %s  at (%.2f, %.2f, %.2f)",
                            p.getName(), p.getMaterial().getName(),
                            fromMm(p.getCutWidthMm()), fromMm(p.getCutHeightMm()), abbr,
                            fromMm(pos.x), fromMm(pos.y), fromMm(pos.z));
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        sb.append(partsBody).append("\n");

        // Assembly joints
        var joints = scene.getJointRegistry().getJointsForAssembly(assembly);
        if (!joints.isEmpty()) {
            sb.append("\n  Joints:\n");
            String lines = joints.stream()
                    .map(j -> jointSummaryLine(j, abbr, "    ", false))
                    .collect(Collectors.joining("\n"));
            sb.append(lines).append("\n");
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Summary formatting: receiving ← inserted, optional depth, and (if
     * {@code includeScrews}) pocket-screw count/spacing. Indent varies —
     * assembly-info uses 4 spaces, `show joints` uses 2.
     */
    private String jointSummaryLine(Joint j, String abbr, String indent, boolean includeScrews) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s%-15s  \"%s\" ← \"%s\"",
                indent, j.type().getDisplayName(),
                j.receivingPartName(), j.insertedPartName()));
        switch (j) {
            case Joint.Dado d -> sb.append(String.format("  depth: %.2f %s", fromMm(d.depthMm()), abbr));
            case Joint.Rabbet r -> sb.append(String.format("  depth: %.2f %s", fromMm(r.depthMm()), abbr));
            case Joint.PocketScrew ps when includeScrews && ps.screwCount() > 0 -> {
                sb.append(String.format("  screws: %d", ps.screwCount()));
                if (ps.screwSpacingMm() > 0) {
                    sb.append(String.format(" @ %.2f %s", fromMm(ps.screwSpacingMm()), abbr));
                }
            }
            default -> { }
        }
        return sb.toString();
    }

    private String listTemplates() {
        var templates = TemplateRegistry.instance().getAll();
        if (templates.isEmpty()) return "No templates defined.";
        String body = templates.stream()
                .map(t -> String.format("  %-36s params: %-36s  %d lines%n      source: %s",
                        t.getName(),
                        paramDescription(t),
                        t.getBodyLines().size(),
                        t.getSource() != null ? t.getSource() : "unknown"))
                .collect(Collectors.joining("\n"));
        return "Available templates:\n" + body
                + "\n\nUsage: create <template> \"name\" param1 value1 ..."
                + "\n       Aliases work: create base_cabinet K w 600 h 900 d 400";
    }

    /** Format template parameters as "width(w), height(h), depth(d)". */
    private static String paramDescription(Template t) {
        return t.getParamNames().stream()
                .map(p -> p + t.getParamAliases().entrySet().stream()
                        .filter(e -> e.getValue().equals(p))
                        .findFirst()
                        .map(e -> "(" + e.getKey() + ")")
                        .orElse(""))
                .collect(Collectors.joining(", "));
    }

    private String showTemplateDefinition(String name) {
        Template t = TemplateRegistry.instance().get(name);
        if (t == null) {
            return "Template '" + name + "' not found. Use 'show templates' to list.";
        }

        String banner = t.isStandard()
                ? "# Standard template — copy and modify to customize.\n"
                + "# Drop an override into ~/.cadette/templates/ (filesystem beats classpath).\n\n"
                : "";

        String header = "define \"" + t.getName() + "\""
                + (t.getParamNames().isEmpty() ? "" : " params " + paramDescription(t));

        String body = t.getBodyLines().stream()
                .map(line -> "  " + line)
                .collect(Collectors.joining("\n"));

        return banner + header + "\n" + body + "\nend define";
    }

    private String listJoints() {
        var joints = scene.getJointRegistry().getAllJoints();
        if (joints.isEmpty()) return "No joints defined.";
        String abbr = executor.getUnits().getAbbreviation();

        String body = joints.stream()
                .map(j -> jointSummaryLine(j, abbr, "  ", true))
                .collect(Collectors.joining("\n"));

        String summary = scene.getJointRegistry().getSummary().entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey().getDisplayName())
                .collect(Collectors.joining(", "));
        if (summary.isEmpty()) summary = "none";

        // Quick health check — same predicates as `validate`, but just the
        // count, since a list view shouldn't dump full issue text.
        List<ValidationIssue> issues = collectJointIssues(joints);
        String validation;
        if (issues.isEmpty()) {
            validation = "all OK.";
        } else {
            long errors = issues.stream()
                    .filter(i -> i.severity() == ValidationIssue.Severity.ERROR).count();
            long warnings = issues.size() - errors;
            validation = String.format("%d issue%s (%d error%s, %d warning%s) — run `validate` for details.",
                    issues.size(), issues.size() == 1 ? "" : "s",
                    errors, errors == 1 ? "" : "s",
                    warnings, warnings == 1 ? "" : "s");
        }

        return "Joints:\n" + body
                + "\n\nSummary: " + summary
                + "\nValidation: " + validation;
    }

    private String showCutList() {
        var parts = scene.getAllParts();
        if (parts.isEmpty()) return "No parts in scene. (Cut list only includes parts, not primitives.)";

        var entries = CutListGenerator.generateCutList(parts, scene.getJointRegistry(), executor.getUnits());
        String abbr = executor.getUnits().getAbbreviation();

        // Entries arrive sorted by material name, so groupingBy on a
        // LinkedHashMap gives the same visual ordering as the old state-
        // tracking loop — each material gets one section.
        Map<String, List<CutListGenerator.CutListEntry>> byMaterial = entries.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getMaterial().getName(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        String sections = byMaterial.values().stream()
                .map(group -> cutListSection(group, abbr))
                .collect(Collectors.joining("\n"));

        return String.format("Cut List (units: %s):%n%n", abbr)
                + sections
                + String.format("%n  Total: %d parts", entries.size());
    }

    private String cutListSection(List<CutListGenerator.CutListEntry> group, String abbr) {
        CutListGenerator.CutListEntry first = group.get(0);
        String header = String.format("  %s (%s, %.2f %s thick):",
                first.getMaterial().getDisplayName(),
                first.getMaterial().getName(),
                fromMm(first.getThicknessMm()), abbr);
        String body = group.stream()
                .map(e -> cutListEntryLines(e, abbr))
                .collect(Collectors.joining("\n"));
        return header + "\n" + body + "\n";
    }

    private String cutListEntryLines(CutListGenerator.CutListEntry entry, String abbr) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("    %-25s %8.2f x %-8.2f %s",
                entry.getPartName(),
                fromMm(entry.getCutWidthMm()),
                fromMm(entry.getCutHeightMm()),
                abbr));
        if (entry.getGrainRequirement() != GrainRequirement.ANY) {
            sb.append("  grain: ").append(entry.getGrainRequirement().name().toLowerCase());
        }
        if (!entry.getOperations().isEmpty()) {
            sb.append("\n").append(entry.getOperations().stream()
                    .map(op -> "      → " + op)
                    .collect(Collectors.joining("\n")));
        }
        return sb.toString();
    }

    private String showBom() {
        var parts = scene.getAllParts();
        if (parts.isEmpty()) return "No parts in scene.";

        String abbr = executor.getUnits().getAbbreviation();
        StringBuilder sb = new StringBuilder();
        sb.append("Bill of Materials:\n\n");

        // Materials — use actual packing for sheet counts
        sb.append("  Materials:\n");
        var layouts = SheetLayoutGenerator.generateLayouts(parts, scene.getKerfMm());
        var bomEntries = CutListGenerator.generateBom(parts, layouts);
        sb.append(bomEntries.stream()
                .map(entry -> bomLine(entry, abbr))
                .collect(Collectors.joining("\n")));
        sb.append("\n");

        // Fasteners
        var fasteners = CutListGenerator.generateFasteners(scene.getJointRegistry());
        if (!fasteners.isEmpty()) {
            sb.append("\n  Fasteners:\n");
            sb.append(fasteners.stream()
                    .map(f -> String.format("    %d x %s", f.getCount(), f.getType()))
                    .collect(Collectors.joining("\n")));
            sb.append("\n");
        }

        // Joint summary
        var jointSummary = scene.getJointRegistry().getSummary();
        if (!jointSummary.isEmpty()) {
            sb.append("\n  Joinery operations:\n");
            sb.append(jointSummary.entrySet().stream()
                    .map(e -> String.format("    %d x %s", e.getValue(), e.getKey().getDisplayName()))
                    .collect(Collectors.joining("\n")));
            sb.append("\n");
        }

        return sb.toString().stripTrailing();
    }

    private String bomLine(CutListGenerator.BomEntry entry, String abbr) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("    %d pc  %-30s  (%.2f %s thick)",
                entry.getPartCount(),
                entry.getMaterial().getDisplayName(),
                fromMm(entry.getMaterial().getThicknessMm()), abbr));
        if (entry.getSheetCount() != null) {
            if (entry.getMaterial().isDimensionalLumber()) {
                // Lumber displays in the trade unit (ft for imperial, m for
                // metric), regardless of the user's `set units` choice —
                // matches how lumber is actually sold and labeled.
                float lengthMm = entry.getActualSheetHeightMm() != null
                        ? entry.getActualSheetHeightMm()
                        : entry.getMaterial().getSheetHeightMm();
                sb.append(String.format("  %d board%s (%s long, %.1f%% offcut)",
                        entry.getSheetCount(),
                        entry.getSheetCount() == 1 ? "" : "s",
                        formatLumberLength(lengthMm, entry.getMaterial().getMeasurementSystem()),
                        entry.getOffcutPercent()));
            } else {
                sb.append(String.format("  %d sheet%s (%.0f x %.0f %s, %.1f%% offcut)",
                        entry.getSheetCount(),
                        entry.getSheetCount() == 1 ? "" : "s",
                        fromMm(entry.getMaterial().getSheetWidthMm()),
                        fromMm(entry.getMaterial().getSheetHeightMm()),
                        abbr,
                        entry.getOffcutPercent()));
            }
        }
        return sb.toString();
    }

    /** Format lumber length in trade units: feet for imperial, meters for
     *  metric. Independent of the user's current display units. */
    private static String formatLumberLength(float mm, app.cadette.model.MeasurementSystem system) {
        if (system == app.cadette.model.MeasurementSystem.IMPERIAL) {
            float ft = mm / 304.8f;
            // 8.00 ft → "8 ft"; 9.5 ft → "9.5 ft". Avoid spurious decimals.
            if (Math.abs(ft - Math.round(ft)) < 0.05f) {
                return String.format("%d ft", Math.round(ft));
            }
            return String.format("%.1f ft", ft);
        }
        float m = mm / 1000f;
        if (Math.abs(m - Math.round(m)) < 0.05f) {
            return String.format("%d m", Math.round(m));
        }
        return String.format("%.1f m", m);
    }

    private String showLayout() {
        var parts = scene.getAllParts();
        if (parts.isEmpty()) return "No parts in scene.";

        float kerfMm = scene.getKerfMm();
        var layouts = SheetLayoutGenerator.generateLayouts(parts, kerfMm);
        if (layouts.isEmpty()) return "No sheet goods in scene (only hardwood/metal parts).";

        String abbr = executor.getUnits().getAbbreviation();

        // Group by display name so each material's sheets get "Sheet i of N" numbering.
        Map<String, List<SheetLayout>> byMaterial = layouts.stream()
                .collect(Collectors.groupingBy(
                        l -> l.getMaterial().getDisplayName(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        String sections = byMaterial.entrySet().stream()
                .map(e -> layoutSection(e.getKey(), e.getValue(), abbr))
                .collect(Collectors.joining("\n"));

        String summaryLines = byMaterial.entrySet().stream()
                .map(e -> String.format("    %s: %d sheet%s",
                        e.getKey(), e.getValue().size(),
                        e.getValue().size() == 1 ? "" : "s"))
                .collect(Collectors.joining("\n"));

        int totalSheets = layouts.size();
        return String.format("Sheet Layouts (%s, kerf: %.1f %s):\n", abbr, fromMm(kerfMm), abbr)
                + sections
                + "\n  Summary:\n"
                + summaryLines
                + String.format("\n    Total: %d sheet%s", totalSheets, totalSheets == 1 ? "" : "s");
    }

    /** Render all sheets for one material, numbered 1..N of the group. */
    private String layoutSection(String matName, List<SheetLayout> groupLayouts, String abbr) {
        int total = groupLayouts.size();
        StringBuilder sb = new StringBuilder("\n");
        for (int i = 0; i < groupLayouts.size(); i++) {
            SheetLayout layout = groupLayouts.get(i);
            sb.append(String.format("  %s — Sheet %d of %d (%.0f x %.0f %s):\n",
                    matName, i + 1, total,
                    fromMm(layout.getSheetWidthMm()), fromMm(layout.getSheetHeightMm()), abbr));
            sb.append(layout.getPlacements().stream()
                    .map(p -> layoutPartLine(p, abbr))
                    .collect(Collectors.joining("\n")));
            sb.append(String.format("%n    Used: %.1f%%   Offcut: %.1f%%%n",
                    100f - layout.getOffcutPercent(), layout.getOffcutPercent()));
        }
        return sb.toString();
    }

    private String layoutPartLine(SheetLayout.PlacedPart p, String abbr) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("    %-25s %6.1f x %6.1f %s  at (%6.1f, %6.1f)",
                p.getPartName(),
                fromMm(p.getWidthOnSheet()), fromMm(p.getHeightOnSheet()), abbr,
                fromMm(p.getX()), fromMm(p.getY())));
        if (p.isRotated()) sb.append("  rotated");
        if (p.getGrainRequirement() != GrainRequirement.ANY) {
            sb.append("  grain: ").append(p.getGrainRequirement().name().toLowerCase());
        }
        return sb.toString();
    }

    // -- Scene helpers --

    /** Translate every named part by {@code delta}; parts missing from the scene are skipped. */
    private void shiftByName(List<String> partNames, Vector3f delta) {
        partNames.forEach(pn -> {
            SceneManager.ObjectRecord rec = scene.getObjectRecord(pn);
            if (rec != null) scene.moveObject(pn, rec.position().add(delta));
        });
    }

    // -- Bounding box helpers (work for assemblies or individual objects) --

    private java.util.function.Function<String, Vector3f> posLookup() {
        return pn -> { var r = scene.getObjectRecord(pn); return r != null ? r.position() : null; };
    }

    private java.util.function.Function<String, Vector3f> sizeLookup() {
        return pn -> { var r = scene.getObjectRecord(pn); return r != null ? r.size() : null; };
    }

    /** Get bounding box [min, max] for a name — assembly or individual object. Returns null if not found.
     *  Uses rotation-aware AABB computation. */
    private Vector3f[] getBBox(String name) {
        Assembly assembly = scene.getAssembly(name);
        if (assembly != null) {
            // Compute assembly AABB from the union of all part AABBs
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (Part p : assembly.getParts()) {
                Vector3f[] partBBox = scene.computeObjectAABB(p.getName());
                if (partBBox != null) {
                    minX = Math.min(minX, partBBox[0].x);
                    minY = Math.min(minY, partBBox[0].y);
                    minZ = Math.min(minZ, partBBox[0].z);
                    maxX = Math.max(maxX, partBBox[1].x);
                    maxY = Math.max(maxY, partBBox[1].y);
                    maxZ = Math.max(maxZ, partBBox[1].z);
                }
            }
            return new Vector3f[]{
                    new Vector3f(minX, minY, minZ),
                    new Vector3f(maxX, maxY, maxZ)
            };
        }
        // Individual object
        return scene.computeObjectAABB(name);
    }

    /**
     * Compute the target position for placing 'source' relative to 'reference'.
     * Returns the position for source's bounding box min corner.
     */
    private Vector3f computeRelativePosition(String directionText, Vector3f[] refBBox,
                                              Vector3f[] srcBBox, float gapMm) {
        Vector3f refMin = refBBox[0], refMax = refBBox[1];
        Vector3f srcMin = srcBBox[0], srcMax = srcBBox[1];
        Vector3f srcSize = srcMax.subtract(srcMin);

        return switch (directionText) {
            case "left" -> new Vector3f(refMin.x - srcSize.x - gapMm, refMin.y, refMin.z);
            case "right" -> new Vector3f(refMax.x + gapMm, refMin.y, refMin.z);
            case "behind" -> new Vector3f(refMin.x, refMin.y, refMin.z - srcSize.z - gapMm);
            case "in front", "in-front" -> new Vector3f(refMin.x, refMin.y, refMax.z + gapMm);
            case "above" -> new Vector3f(refMin.x, refMax.y + gapMm, refMin.z);
            case "below" -> new Vector3f(refMin.x, refMin.y - srcSize.y - gapMm, refMin.z);
            default -> refMin.clone();
        };
    }

    /** Normalize a direction context to a lowercase string. */
    private String directionText(CadetteCommandParser.DirectionContext ctx) {
        if (ctx.LEFT_KW() != null) return "left";
        if (ctx.RIGHT_KW() != null) return "right";
        if (ctx.BEHIND() != null) return "behind";
        if (ctx.IN_FRONT() != null) return "in front";
        if (ctx.ABOVE() != null) return "above";
        if (ctx.BELOW() != null) return "below";
        return "";
    }

    /** Normalize a face context to a lowercase string. */
    private String faceText(CadetteCommandParser.FaceContext ctx) {
        if (ctx.FRONT() != null) return "front";
        if (ctx.BACK() != null) return "back";
        if (ctx.LEFT_KW() != null) return "left";
        if (ctx.RIGHT_KW() != null) return "right";
        if (ctx.TOP() != null) return "top";
        if (ctx.BOTTOM() != null) return "bottom";
        return "";
    }

    // -- Template body instantiation --

    /**
     * Walk a template's parsed body, collecting created parts into the given
     * assembly and appending each statement's output. Supports nested if/for
     * blocks; loop variables are pushed onto the executor's scope stack.
     */
    public void instantiateTemplateBody(CadetteCommandParser.DefineBlockContext body,
                                        Assembly assembly,
                                        List<Part> createdParts,
                                        StringBuilder output) {
        for (var stmt : body.statement()) {
            executeStatement(stmt, assembly, createdParts, output);
        }
    }

    /**
     * Execute one top-level statement (script or REPL) — a command, or an
     * `if`/`for` block. No assembly to collect parts into, so the command
     * branch skips that bookkeeping. Returns the statement's output.
     */
    public String executeStatement(CadetteCommandParser.StatementContext stmt) {
        StringBuilder output = new StringBuilder();
        executeStatement(stmt, null, null, output);
        return output.toString().stripTrailing();
    }

    /**
     * Dispatch one statement. Null assembly/createdParts signal top-level
     * execution — the command branch then skips the add-to-assembly step.
     * Output indentation also differs between the two contexts.
     */
    private void executeStatement(CadetteCommandParser.StatementContext stmt,
                                  Assembly assembly,
                                  List<Part> createdParts,
                                  StringBuilder output) {
        if (stmt.ifBlock() != null) {
            executeIfBlock(stmt.ifBlock(), assembly, createdParts, output);
        } else if (stmt.forBlock() != null) {
            executeForBlock(stmt.forBlock(), assembly, createdParts, output);
        } else if (stmt.command() != null) {
            executor.setLastCreatedPartName(null);
            String result = visit(stmt.command());
            if (result != null && !result.isEmpty()) {
                String indent = assembly != null ? "  " : "";
                output.append(indent).append(result).append("\n");
            }
            if (assembly != null) {
                String created = executor.getLastCreatedPartName();
                if (created != null) {
                    Part part = scene.getPart(created);
                    if (part != null) {
                        assembly.addPart(part);
                        createdParts.add(part);
                    }
                }
            }
        }
    }

    private void executeIfBlock(CadetteCommandParser.IfBlockContext ctx,
                                Assembly assembly,
                                List<Part> createdParts,
                                StringBuilder output) {
        double cond = evaluateExpression(ctx.expression());
        List<CadetteCommandParser.StatementContext> branch =
                cond != 0 ? ctx.thenBody : ctx.elseBody;
        if (branch == null) return;
        for (var s : branch) {
            executeStatement(s, assembly, createdParts, output);
        }
    }

    private void executeForBlock(CadetteCommandParser.ForBlockContext ctx,
                                 Assembly assembly,
                                 List<Part> createdParts,
                                 StringBuilder output) {
        String varName = ctx.VAR_REF().getText().substring(1);  // strip $
        long from = (long) evaluateExpression(ctx.expression(0));
        long to = (long) evaluateExpression(ctx.expression(1));
        // Loop variable shadows any outer scope. `for i in 5..3` runs zero times
        // (no implicit reverse — users can say so explicitly when we add `step`).
        Map<String, Double> loopScope = new LinkedHashMap<>();
        loopScope.put(varName, (double) from);
        executor.pushVarScope(loopScope);
        try {
            for (long i = from; i <= to; i++) {
                executor.setInCurrentScope(varName, (double) i);
                for (var s : ctx.statement()) {
                    executeStatement(s, assembly, createdParts, output);
                }
            }
        } finally {
            executor.popVarScope();
        }
    }

    // -- Expression evaluation --

    /**
     * Evaluate an expression parse tree to a double, using the executor's
     * active variable scope for VAR_REF nodes. Comparison and logical ops
     * return 1.0 / 0.0 under numeric truthiness. Throws a clear error on an
     * unresolved VAR_REF or divide-by-zero.
     */
    /** Math function dispatch for IdFuncCallExpr. Trig takes degrees so it
     *  composes cleanly with rotate/orient (which also use degrees). */
    private static double callMathFunction(String name, double[] args) {
        return switch (name) {
            case "sin"     -> requireArity(name, args, 1, () -> Math.sin(Math.toRadians(args[0])));
            case "cos"     -> requireArity(name, args, 1, () -> Math.cos(Math.toRadians(args[0])));
            case "tan"     -> requireArity(name, args, 1, () -> Math.tan(Math.toRadians(args[0])));
            case "asin"    -> requireArity(name, args, 1, () -> Math.toDegrees(Math.asin(args[0])));
            case "acos"    -> requireArity(name, args, 1, () -> Math.toDegrees(Math.acos(args[0])));
            case "atan"    -> requireArity(name, args, 1, () -> Math.toDegrees(Math.atan(args[0])));
            case "atan2"   -> requireArity(name, args, 2, () -> Math.toDegrees(Math.atan2(args[0], args[1])));
            case "sqrt"    -> requireArity(name, args, 1, () -> Math.sqrt(args[0]));
            case "hypot"   -> requireArity(name, args, 2, () -> Math.hypot(args[0], args[1]));
            case "abs"     -> requireArity(name, args, 1, () -> Math.abs(args[0]));
            case "pow"     -> requireArity(name, args, 2, () -> Math.pow(args[0], args[1]));
            case "floor"   -> requireArity(name, args, 1, () -> Math.floor(args[0]));
            case "ceil"    -> requireArity(name, args, 1, () -> Math.ceil(args[0]));
            case "round"   -> requireArity(name, args, 1, () -> (double) Math.round(args[0]));
            case "log"     -> requireArity(name, args, 1, () -> Math.log(args[0]));
            case "exp"     -> requireArity(name, args, 1, () -> Math.exp(args[0]));
            case "radians" -> requireArity(name, args, 1, () -> Math.toRadians(args[0]));
            case "degrees" -> requireArity(name, args, 1, () -> Math.toDegrees(args[0]));
            default -> throw new RuntimeException("Unknown function '" + name + "'");
        };
    }

    private static double requireArity(String name, double[] args, int expected,
                                        java.util.function.DoubleSupplier compute) {
        if (args.length != expected) {
            throw new RuntimeException(name + "() takes " + expected
                    + " argument" + (expected == 1 ? "" : "s") + ", got " + args.length);
        }
        return compute.getAsDouble();
    }

    /** Math constants resolvable as bare identifiers: pi, e. Returns null for
     *  anything else so the caller falls back to scope lookup. */
    private static Double mathConstant(String name) {
        return switch (name.toLowerCase()) {
            case "pi" -> Math.PI;
            case "e"  -> Math.E;
            default   -> null;
        };
    }

    double evaluateExpression(CadetteCommandParser.ExpressionContext ctx) {
        return switch (ctx) {
            case CadetteCommandParser.ParenExprContext c ->
                    evaluateExpression(c.expression());
            case CadetteCommandParser.FuncCallExprContext c -> {
                var args = c.expression().stream()
                        .mapToDouble(this::evaluateExpression)
                        .toArray();
                yield c.MIN() != null
                        ? java.util.Arrays.stream(args).min().orElseThrow()
                        : java.util.Arrays.stream(args).max().orElseThrow();
            }
            case CadetteCommandParser.IdFuncCallExprContext c -> {
                String fname = c.ID().getText().toLowerCase();
                double[] args = c.expression().stream()
                        .mapToDouble(this::evaluateExpression)
                        .toArray();
                yield callMathFunction(fname, args);
            }
            case CadetteCommandParser.NegExprContext c ->
                    -evaluateExpression(c.expression());
            case CadetteCommandParser.NotExprContext c ->
                    evaluateExpression(c.expression()) == 0 ? 1.0 : 0.0;
            case CadetteCommandParser.MulExprContext c -> {
                double a = evaluateExpression(c.expression(0));
                double b = evaluateExpression(c.expression(1));
                yield c.op.getType() == CadetteCommandLexer.STAR ? a * b : a / b;
            }
            case CadetteCommandParser.AddExprContext c -> {
                double a = evaluateExpression(c.expression(0));
                double b = evaluateExpression(c.expression(1));
                yield c.op.getType() == CadetteCommandLexer.PLUS ? a + b : a - b;
            }
            case CadetteCommandParser.RelExprContext c -> {
                double a = evaluateExpression(c.expression(0));
                double b = evaluateExpression(c.expression(1));
                yield switch (c.op.getType()) {
                    case CadetteCommandLexer.LT -> a < b ? 1.0 : 0.0;
                    case CadetteCommandLexer.LTE -> a <= b ? 1.0 : 0.0;
                    case CadetteCommandLexer.GT -> a > b ? 1.0 : 0.0;
                    case CadetteCommandLexer.GTE -> a >= b ? 1.0 : 0.0;
                    default -> throw new AssertionError("unreachable");
                };
            }
            case CadetteCommandParser.EqExprContext c -> {
                double a = evaluateExpression(c.expression(0));
                double b = evaluateExpression(c.expression(1));
                boolean eq = Math.abs(a - b) < 1e-9;
                yield c.op.getType() == CadetteCommandLexer.EQ
                        ? (eq ? 1.0 : 0.0)
                        : (eq ? 0.0 : 1.0);
            }
            // Short-circuit: don't evaluate RHS if LHS determines the answer.
            case CadetteCommandParser.AndExprContext c -> {
                double a = evaluateExpression(c.expression(0));
                yield a == 0 ? 0.0 : (evaluateExpression(c.expression(1)) == 0 ? 0.0 : 1.0);
            }
            case CadetteCommandParser.OrExprContext c -> {
                double a = evaluateExpression(c.expression(0));
                yield a != 0 ? 1.0 : (evaluateExpression(c.expression(1)) == 0 ? 0.0 : 1.0);
            }
            case CadetteCommandParser.UnitSuffixExprContext c -> {
                // Literal with explicit unit, e.g. `100mm`, `5cm`, `2.5in`.
                // Semantic: "N <unit>s expressed in the current display unit."
                // Implementation: convert N via the literal's unit to mm,
                // then back out to current units.
                double raw = evaluateExpression(c.expression());
                UnitSystem literalUnit = unitForSuffix(c.unitSuffix());
                UnitSystem current = executor.getUnits();
                yield current.fromMm(literalUnit.toMm((float) raw));
            }
            case CadetteCommandParser.NumberExprContext c ->
                    Double.parseDouble(c.NUMBER().getText());
            case CadetteCommandParser.VarRefExprContext c -> {
                String name = c.VAR_REF().getText().substring(1);
                Double v = executor.resolveVar(name);
                if (v == null) {
                    throw new RuntimeException("Undefined variable $" + name);
                }
                yield v;
            }
            case CadetteCommandParser.IdRefExprContext c -> {
                // Bare identifier: same resolution as $-prefixed VAR_REF.
                // Reached when the user writes `${i - 1}` (no $) and in
                // other expression contexts where an unprefixed name
                // happens to lex as ID rather than a keyword. Falls back
                // to math constants (pi, e) if no variable binds the name —
                // so user vars shadow constants, not the other way around.
                String name = c.ID().getText();
                Double v = executor.resolveVar(name);
                if (v != null) yield v;
                Double constant = mathConstant(name);
                if (constant != null) yield constant;
                throw new RuntimeException("Undefined variable " + name);
            }
            default -> throw new AssertionError(
                    "Unhandled expression node: " + ctx.getClass().getSimpleName());
        };
    }

    /** Convenience: evaluate and cast to float (most command args are floats, not doubles). */
    private float evalFloat(CadetteCommandParser.ExpressionContext ctx) {
        return (float) evaluateExpression(ctx);
    }

    /** Map a parsed unit-suffix token to its {@link UnitSystem} value. */
    private static UnitSystem unitForSuffix(CadetteCommandParser.UnitSuffixContext ctx) {
        if (ctx.MM() != null) return UnitSystem.MILLIMETERS;
        if (ctx.CM() != null) return UnitSystem.CENTIMETERS;
        if (ctx.M() != null) return UnitSystem.METERS;
        if (ctx.IN() != null) return UnitSystem.INCHES;
        if (ctx.FT() != null) return UnitSystem.FEET;
        if (ctx.YD() != null) return UnitSystem.YARDS;
        throw new AssertionError("Unhandled unit suffix: " + ctx.getText());
    }

    // -- Unit conversion shortcuts --

    private float toMm(float value) {
        return executor.getUnits().toMm(value);
    }

    private float fromMm(float mm) {
        return executor.getUnits().fromMm(mm);
    }

    private Vector3f defaultSize() {
        float d = toMm(1);
        return new Vector3f(d, d, d);
    }

    private String extractMaterialName(CadetteCommandParser.MaterialNameContext ctx) {
        return interpolateString(stripStringQuotes(
                ctx.STRING() != null ? ctx.STRING().getText() : ctx.nameLike().getText()));
    }

    /**
     * Extract an object name, applying the current template-instance prefix if set.
     * E.g. during expansion of template instance "K", a body line's "left-side" returns "K/left-side".
     * String literals with {@code $var} / {@code ${var}} references are interpolated
     * against the active scope — needed for dynamic part names inside for-loops
     * (e.g. {@code create part "shelf_$i" ...}).
     */
    private String extractName(CadetteCommandParser.ObjectNameContext ctx) {
        String raw = interpolateString(rawObjectName(ctx));
        String prefix = executor.getCurrentInstancePrefix();
        return prefix != null ? prefix + "/" + raw : raw;
    }

    /**
     * Returns an error message if a scene object already exists with the given
     * name — covers both parts and primitive shapes (box / sphere / cylinder)
     * since they share the SceneManager's name registry. Returns {@code null}
     * if the name is free.
     *
     * <p>Without this guard, a duplicate {@code create} silently overwrites
     * the registry entry while leaving the previous wrapper attached to the
     * scene graph. The previous object becomes a "ghost": still rendered in
     * 3D but invisible to {@code list}, {@code cut}, {@code move}, etc.
     */
    private String nameCollisionError(String name) {
        if (scene.getObjectRecord(name) == null) return null;
        return "An object named '" + name + "' already exists. "
                + "Use a different name, or delete the existing object first.";
    }

    private static String rawObjectName(CadetteCommandParser.ObjectNameContext ctx) {
        return stripStringQuotes(
                ctx.STRING() != null ? ctx.STRING().getText() : ctx.nameLike().getText());
    }

    private static String stripStringQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // Two forms of interpolation inside a string literal:
    //   $name      — simple var lookup against the active scope
    //   ${expr}    — arbitrary expression parsed and evaluated via the same
    //                grammar/evaluator used everywhere else
    // The `${...}` form lets names compose with arithmetic: "b${$i - 1}" for
    // "the previous cabinet". Content stops at the first '}' — nested braces
    // aren't supported (no use case yet).
    private static final java.util.regex.Pattern STRING_VAR_PATTERN =
            java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}|\\$([a-zA-Z_][a-zA-Z0-9_]*)");

    /**
     * Replace {@code $name} / {@code ${expression}} occurrences in a string
     * literal with values from the active scope. Unresolved simple refs are
     * left as-is so top-level commands without a scope don't throw.
     * Expressions inside {@code ${...}} surface parse/eval errors — those
     * are real user mistakes.
     */
    private String interpolateString(String raw) {
        if (raw == null || raw.indexOf('$') < 0) return raw;
        var m = STRING_VAR_PATTERN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            if (m.group(1) != null) {
                // ${expression} — parse and evaluate via the grammar.
                replacement = formatInterpolatedNumber(evaluateExpressionText(m.group(1)));
            } else {
                // $name — simple lookup; leave untouched if unresolved.
                String name = m.group(2);
                Double v = executor.resolveVar(name);
                replacement = v == null ? m.group() : formatInterpolatedNumber(v);
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Parse the given text through the ANTLR `expression` entry rule and
     * evaluate the resulting tree against the active scope. The grammar
     * treats bare identifiers as variable references (via the `idRefExpr`
     * alternative), so {@code ${i - 1}} and {@code ${$i - 1}} both work
     * without any text preprocessing.
     */
    private double evaluateExpressionText(String exprText) {
        CadetteCommandLexer lexer = new CadetteCommandLexer(
                org.antlr.v4.runtime.CharStreams.fromString(exprText));
        lexer.removeErrorListeners();
        var tokens = new org.antlr.v4.runtime.CommonTokenStream(lexer);
        CadetteCommandParser parser = new CadetteCommandParser(tokens);
        parser.removeErrorListeners();
        StringBuilder errors = new StringBuilder();
        parser.addErrorListener(new org.antlr.v4.runtime.BaseErrorListener() {
            @Override
            public void syntaxError(org.antlr.v4.runtime.Recognizer<?, ?> recognizer,
                                    Object offendingSymbol, int line, int charPos, String msg,
                                    org.antlr.v4.runtime.RecognitionException e) {
                if (!errors.isEmpty()) errors.append("; ");
                errors.append("at column ").append(charPos + 1).append(": ").append(msg)
                        .append(CommandExecutor.pointerLine(exprText, charPos));
            }
        });
        var ctx = parser.expression();
        if (!errors.isEmpty()) {
            // exprText itself is shown by the pointer line embedded in `errors`,
            // so don't repeat it in the wrap.
            throw new RuntimeException("Invalid expression in ${…} " + errors);
        }
        return evaluateExpression(ctx);
    }

    /** Format a loop-var or param value for embedding in an identifier: whole numbers render without ".0". */
    private static String formatInterpolatedNumber(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    /**
     * Extract a template name from a templateRef (QUALIFIED_NAME, STRING, or nameLike).
     * Template names are never prefixed with the current instance prefix — they're
     * registry lookups, not scene-local references.
     */
    static String templateRefText(CadetteCommandParser.TemplateRefContext ctx) {
        if (ctx.QUALIFIED_NAME() != null) return ctx.QUALIFIED_NAME().getText();
        if (ctx.STRING() != null) {
            String s = ctx.STRING().getText();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }
        return ctx.nameLike().getText();
    }

    private String helpText() {
        return """
                Available commands:

                Parts (woodworking):
                  create part <name> [material <mat>] size w,h [at x,y,z] [grain vertical|horizontal|any]
                      Thickness comes from the material. Size is the cut face (width, height).
                      Omit material to use the default (set via toolbar or 'set material').
                      Use 'show materials' to list available materials.

                Primitives:
                  create <shape> <name> [at x,y,z] [<size>] [color <color>]
                      shape:  box, sphere, cylinder
                      size:   size s | size w,h,d | width W height H depth D
                      color:  red, green, blue, yellow, white, #rrggbb

                Common:
                  move <name> to x,y,z             — move an object or assembly
                  move <name> to left|right|behind|in front|above|below of <ref> [gap N]
                  align front|back|left|right|top|bottom of <name>[,<name>,...] with <ref>
                  rotate <name> rx,ry,rz           — rotate in degrees (absolute)
                  resize <name> <size>             — resize an object
                  join <part1> to <part2> with <type> [depth N] [screws N] [spacing N]
                      types: butt, dado, rabbet, pocket_screw (or pocket)
                      depth required for dado/rabbet
                  cut <part> rect at x,y size w,h [depth N]
                                                     — remove a rectangular region from the cut face
                  cut <part> circle at cx,cy radius r [depth N]
                                                     — remove a circular region (e.g. 35mm hinge cup)
                                                     — non-rect cutouts not yet rendered in 3D view

                  display names                    — show name labels on all objects
                  display name <name>              — show name labels on one object
                  hide names                       — hide all name labels
                  hide name <name>                 — hide name labels on one object
                  delete <name>                    — remove an object by name
                  delete all                       — remove all objects
                  list                             — list all objects in the scene
                  show units                       — display current unit setting
                  show objects                     — display all objects and positions
                  show materials                   — list available materials
                  show joints                      — list all joints
                  show template <name>             — show full template definition (copiable)
                  show cutlist                     — cut list grouped by material
                  show bom                         — bill of materials + fasteners
                  show layout                      — sheet layout optimization (bin packing)
                  show script_path                 — current script search path + effective order
                  set units <unit>                 — change display/input units
                  set material <mat>               — change default material
                  set kerf <value>                 — saw blade kerf width (default 3.2mm)
                  set script_path "<dir>"[,...]   — script search prefix; 'none' clears
                                                     (defaults ~/.cadette/scripts/ and ./scripts/ always tried after)
                  export cutsheet pdf [file]      — export cut sheets to PDF (opens save dialog if no file)
                  export cutsheet png [file]      — export cut sheets to PNG image
                  export cutsheet jpg [file]      — export cut sheets to JPEG image
                  export cutlist csv [file]       — export cut list as CSV (one row per part)
                      units: """ + UnitSystem.allNames() + """

                  run [file]                       — run a .cds script (opens file dialog if omitted)
                                                     Scripts may start with '#! cadette' as a file identifier.
                  validate [<assembly>]            — geometric sanity check across joints
                                                     (omit name for whole scene; supply an assembly to scope)
                  undo                             — undo last action (also Ctrl+Z)
                  redo                             — redo last undone action (also Ctrl+Shift+Z)
                  help                             — show this help text
                  exit                             — quit the application

                Startup script: ~/.cadette/startup.cds (auto-runs on launch if present)

                Templates:
                  define <name|ns/.../name> params p1, p2, ...   — start template definition
                    <commands with $variable references and arithmetic>
                  end define                         — finish definition
                  create <template> "name" p1 v1 p2 v2 ...  — instantiate a template
                      <template> may be bare (base_cabinet) or qualified (standard/cabinets/base_cabinet).
                      Bare names resolve via `using` namespaces, then registry-wide uniqueness.
                  using <namespace>                  — prefer templates under this namespace for bare lookups
                      Scope: script-local for `run`-invoked scripts; session-wide in ~/.cadette/startup.cds.
                  using none                         — clear all `using` namespaces (useful at the top of a script)
                  which <template>                   — show the resolved fully-qualified name and source file
                  show templates                     — list available templates (with source files)

                Coordinates and sizes are in current units (default: mm).
                $thickness is implicit (from default material).

                Examples:
                  create base_cabinet "K1" width 600 height 900 depth 400
                  create part "left side" material "plywood-3/4" size 600,900 at 0,0,0 grain vertical
                  create part "back panel" material "hardboard-5.5mm" size 800,900
                  create part rail material "aluminum-1/8" size 600,19
                  show materials
                  join "left-side" to "bottom" with dado depth 9
                  join "left-side" to "top-stretcher" with pocket depth 0 screws 3 spacing 150
                  move "left side" to 100,0,0
                  rotate "left side" 0,90,0
                  set units inches
                  list""";
    }
}
