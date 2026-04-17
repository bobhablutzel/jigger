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

package com.jigger.command;

import com.jigger.CutSheetExporter;
import com.jigger.SceneManager;
import com.jigger.UnitSystem;
import com.jigger.ViewLayoutMode;
import com.jigger.model.*;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ANTLR visitor that executes parsed commands against the SceneManager.
 * Each visit method returns a feedback string for the console.
 */
public class CommandVisitor extends JiggerCommandBaseVisitor<String> {

    private final CommandExecutor executor;
    private final SceneManager scene;

    public CommandVisitor(CommandExecutor executor, SceneManager scene) {
        this.executor = executor;
        this.scene = scene;
    }

    @Override
    public String visitCommand(JiggerCommandParser.CommandContext ctx) {
        // Delegate to whichever child command rule matched
        return visitChildren(ctx);
    }

    @Override
    public String visitCreateCommand(JiggerCommandParser.CreateCommandContext ctx) {
        String shape = ctx.shape().getText();
        String name = extractName(ctx.objectName());

        Vector3f position = Vector3f.ZERO;
        if (ctx.position() != null) {
            position = parsePosition(ctx.position());
        }

        Vector3f size = defaultSize();
        if (ctx.sizeSpec() != null) {
            size = parseSizeSpec(ctx.sizeSpec());
        }

        ColorRGBA color = ColorRGBA.White;
        if (ctx.color() != null) {
            color = parseColor(ctx.color());
        }

        String id = scene.createObject(name, shape, position, size, color);
        executor.pushAction(new CreateAction(scene, name, shape, position, size, color));

        String abbr = executor.getUnits().getAbbreviation();
        return String.format("Created %s '%s' at (%.2f, %.2f, %.2f) %s",
                shape, id,
                fromMm(position.x), fromMm(position.y), fromMm(position.z), abbr);
    }

    @Override
    public String visitCreatePartCommand(JiggerCommandParser.CreatePartCommandContext ctx) {
        String name = extractName(ctx.objectName());

        com.jigger.model.Material material;
        if (ctx.materialName() != null) {
            String materialSlug = extractMaterialName(ctx.materialName());
            material = MaterialCatalog.instance().get(materialSlug);
            if (material == null) {
                return "Unknown material '" + materialSlug + "'.\n"
                        + "Available materials: use 'show materials' to list.";
            }
        } else {
            material = executor.getDefaultMaterial();
        }

        var nums = ctx.partSize().NUMBER();
        float cutWidth = toMm(Float.parseFloat(nums.get(0).getText()));
        float cutHeight = toMm(Float.parseFloat(nums.get(1).getText()));

        Vector3f position = Vector3f.ZERO;
        if (ctx.position() != null) {
            position = parsePosition(ctx.position());
        }

        GrainRequirement grain = GrainRequirement.ANY;
        if (ctx.grainReq() != null) {
            if (ctx.grainReq().VERTICAL() != null) grain = GrainRequirement.VERTICAL;
            else if (ctx.grainReq().HORIZONTAL() != null) grain = GrainRequirement.HORIZONTAL;
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

        String abbr = executor.getUnits().getAbbreviation();
        return String.format("Created part '%s' — %s, %.2f x %.2f %s (%.2f %s thick) at (%.2f, %.2f, %.2f)",
                name, material.getDisplayName(),
                fromMm(cutWidth), fromMm(cutHeight), abbr,
                fromMm(material.getThicknessMm()), abbr,
                fromMm(position.x), fromMm(position.y), fromMm(position.z));
    }

    @Override
    public String visitDeleteCommand(JiggerCommandParser.DeleteCommandContext ctx) {
        if (ctx.ALL() != null) {
            Map<String, SceneManager.ObjectRecord> recs = scene.getObjectRecords();
            List<DeleteAllAction.ObjectSnapshot> snapshots = recs.values().stream()
                    .map(r -> new DeleteAllAction.ObjectSnapshot(
                            r.name(), r.shapeType(), r.position(), r.size(), r.color(),
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
        scene.deleteObject(name);
        executor.pushAction(new DeleteAction(scene, rec.name(), rec.shapeType(),
                rec.position(), rec.size(), rec.color(), part));
        return "Deleted '" + name + "'.";
    }

    private String deleteAssembly(Assembly assembly) {
        String name = assembly.getName();
        String templateName = assembly.getTemplateName();

        // Capture all joints involving assembly parts
        List<Joint> assemblyJoints = new ArrayList<>();
        for (Part p : assembly.getParts()) {
            assemblyJoints.addAll(scene.getJointRegistry().getJointsForPart(p.getName()));
        }
        // Deduplicate
        assemblyJoints = assemblyJoints.stream().distinct().toList();

        // Capture snapshots for undo
        List<DeleteAssemblyAction.PartSnapshot> snapshots = new ArrayList<>();
        for (Part p : assembly.getParts()) {
            SceneManager.ObjectRecord rec = scene.getObjectRecord(p.getName());
            if (rec != null) {
                snapshots.add(new DeleteAssemblyAction.PartSnapshot(
                        p, rec.position(), rec.size(), rec.color(),
                        scene.getRotation(p.getName())));
            }
        }

        // Delete all parts
        for (Part p : assembly.getParts().reversed()) {
            scene.deleteObject(p.getName());
        }
        scene.removeAssembly(name);

        executor.pushAction(new DeleteAssemblyAction(scene, name, templateName,
                snapshots, assemblyJoints));
        return String.format("Deleted assembly '%s' (%d parts).", name, snapshots.size());
    }

    @Override
    public String visitMoveCommand(JiggerCommandParser.MoveCommandContext ctx) {
        String name = extractName(ctx.objectName());

        // Check assembly first
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

    private String moveAssembly(Assembly assembly, Vector3f targetPos) {
        String name = assembly.getName();
        String abbr = executor.getUnits().getAbbreviation();

        // Compute the assembly's current origin (bounding box min corner)
        Vector3f currentOrigin = assembly.getBoundingBoxMin(
                partName -> {
                    SceneManager.ObjectRecord r = scene.getObjectRecord(partName);
                    return r != null ? r.position() : null;
                },
                partName -> {
                    SceneManager.ObjectRecord r = scene.getObjectRecord(partName);
                    return r != null ? r.size() : null;
                });

        Vector3f delta = targetPos.subtract(currentOrigin);
        List<String> partNames = assembly.getParts().stream()
                .map(Part::getName).toList();

        // Apply delta to all parts
        for (String partName : partNames) {
            SceneManager.ObjectRecord rec = scene.getObjectRecord(partName);
            if (rec != null) {
                scene.moveObject(partName, rec.position().add(delta));
            }
        }

        executor.pushAction(new MoveAssemblyAction(scene, name, delta, partNames));
        return String.format("Moved assembly '%s' (%d parts) to (%.2f, %.2f, %.2f) %s",
                name, partNames.size(),
                fromMm(targetPos.x), fromMm(targetPos.y), fromMm(targetPos.z), abbr);
    }

    @Override
    public String visitRotateCommand(JiggerCommandParser.RotateCommandContext ctx) {
        String name = extractName(ctx.objectName());
        var nums = ctx.rotation().NUMBER();
        Vector3f newDegrees = new Vector3f(
                Float.parseFloat(nums.get(0).getText()),
                Float.parseFloat(nums.get(1).getText()),
                Float.parseFloat(nums.get(2).getText()));

        // Check assembly first
        Assembly assembly = scene.getAssembly(name);
        if (assembly != null) {
            return rotateAssembly(assembly, newDegrees);
        }

        SceneManager.ObjectRecord rec = scene.getObjectRecord(name);
        if (rec == null) {
            return "Object or assembly '" + name + "' not found.";
        }
        Vector3f oldDegrees = scene.getRotation(name);
        scene.rotateObject(name, newDegrees);
        executor.pushAction(new RotateAction(scene, name, oldDegrees, newDegrees));
        return String.format("Rotated '%s' to (%.1f, %.1f, %.1f) degrees",
                name, newDegrees.x, newDegrees.y, newDegrees.z);
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
        Vector3f pivot = assembly.getBoundingBoxMin(
                pn -> { var r = scene.getObjectRecord(pn); return r != null ? r.position() : null; },
                pn -> { var r = scene.getObjectRecord(pn); return r != null ? r.size() : null; });

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
    public String visitResizeCommand(JiggerCommandParser.ResizeCommandContext ctx) {
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
    public String visitJoinCommand(JiggerCommandParser.JoinCommandContext ctx) {
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

        // Parse joint type
        var jtCtx = ctx.jointType();
        JointType type;
        if (jtCtx.BUTT_JT() != null) type = JointType.BUTT;
        else if (jtCtx.DADO_JT() != null) type = JointType.DADO;
        else if (jtCtx.RABBET_JT() != null) type = JointType.RABBET;
        else if (jtCtx.POCKET_SCREW_JT() != null) type = JointType.POCKET_SCREW;
        else return "Unknown joint type.";

        // For dado/rabbet: validate materials and compute depth
        String abbr = executor.getUnits().getAbbreviation();
        StringBuilder warnings = new StringBuilder();
        float depthMm = 0;

        if (type.isAffectsGeometry()) {
            // Look up the receiving part's material thickness
            Part receivingPart = scene.getPart(receivingName);
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

            if (ctx.DEPTH() != null && ctx.NUMBER().size() > 0) {
                depthMm = toMm(Float.parseFloat(ctx.NUMBER(0).getText()));
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

        int screwCount = 0;
        float screwSpacingMm = 0;
        // Parse screws and spacing — they come after depth in the NUMBER list
        var numbers = ctx.NUMBER();
        int numIdx = (ctx.DEPTH() != null) ? 1 : 0;
        if (ctx.SCREWS() != null && numIdx < numbers.size()) {
            screwCount = Integer.parseInt(numbers.get(numIdx).getText());
            numIdx++;
        }
        if (ctx.SPACING() != null && numIdx < numbers.size()) {
            screwSpacingMm = toMm(Float.parseFloat(numbers.get(numIdx).getText()));
        }

        Joint joint = Joint.builder()
                .receivingPartName(receivingName)
                .insertedPartName(insertedName)
                .type(type)
                .depthMm(depthMm)
                .screwCount(screwCount)
                .screwSpacingMm(screwSpacingMm)
                .build();

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
    public String visitDisplayCommand(JiggerCommandParser.DisplayCommandContext ctx) {
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
    public String visitHideCommand(JiggerCommandParser.HideCommandContext ctx) {
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
    public String visitListCommand(JiggerCommandParser.ListCommandContext ctx) {
        return listObjects();
    }

    @Override
    public String visitShowCommand(JiggerCommandParser.ShowCommandContext ctx) {
        // show info <name>
        if (ctx.INFO() != null && ctx.objectName() != null) {
            return showInfo(extractName(ctx.objectName()));
        }
        // show template <name>
        if (ctx.TEMPLATE() != null && ctx.objectName() != null) {
            return showTemplateDefinition(extractName(ctx.objectName()));
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
        return "Unknown show target. Try: show units, show objects, show materials, show templates, show joints, show cutlist, show bom, show layout";
    }

    @Override
    public String visitSetCommand(JiggerCommandParser.SetCommandContext ctx) {
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
            float value = toMm(Float.parseFloat(ctx.NUMBER().getText()));
            if (value < 0) {
                return "Kerf must be non-negative.";
            }
            scene.setKerfMm(value);
            String abbr = executor.getUnits().getAbbreviation();
            return String.format("Kerf set to %.1f %s (%.1f mm).", fromMm(value), abbr, value);
        }
        if (ctx.layoutMode() != null) {
            ViewLayoutMode mode = ctx.layoutMode().TABS() != null
                    ? ViewLayoutMode.TABBED : ViewLayoutMode.SPLIT_PANE;
            executor.setLayoutMode(mode);
            return "Layout set to " + (mode == ViewLayoutMode.TABBED ? "tabbed" : "split pane") + ".";
        }
        return "Unknown set target.";
    }

    @Override
    public String visitExportCommand(JiggerCommandParser.ExportCommandContext ctx) {
        var parts = scene.getAllParts();
        if (parts.isEmpty()) {
            return "No parts in scene — nothing to export.";
        }

        var layouts = SheetLayoutGenerator.generateLayouts(parts, scene.getKerfMm());
        if (layouts.isEmpty()) {
            return "No sheet goods in scene (only hardwood/metal parts) — nothing to export.";
        }

        // Determine format
        var fmt = ctx.exportFormat();
        boolean isPdf = fmt.PDF() != null;
        boolean isPng = fmt.PNG() != null;
        String extension = isPdf ? "pdf" : isPng ? "png" : "jpeg";
        String description = isPdf ? "PDF files" : isPng ? "PNG images" : "JPEG images";

        // Determine output path
        Path outputPath;
        if (ctx.STRING() != null) {
            String path = ctx.STRING().getText();
            path = path.substring(1, path.length() - 1); // strip quotes
            outputPath = Path.of(path);
            // Add extension if missing
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
            if (isPdf) {
                CutSheetExporter.exportPdf(layouts, units, outputPath);
            } else {
                CutSheetExporter.exportImage(layouts, units, outputPath);
            }
            return "Exported cut sheets to " + outputPath.toAbsolutePath();
        } catch (Exception e) {
            return "Export failed: " + e.getMessage();
        }
    }

    @Override
    public String visitUndoCommand(JiggerCommandParser.UndoCommandContext ctx) {
        return executor.undo();
    }

    @Override
    public String visitRedoCommand(JiggerCommandParser.RedoCommandContext ctx) {
        return executor.redo();
    }

    @Override
    public String visitHelpCommand(JiggerCommandParser.HelpCommandContext ctx) {
        return helpText();
    }

    @Override
    public String visitExitCommand(JiggerCommandParser.ExitCommandContext ctx) {
        executor.fireExit();
        return "Exiting...";
    }

    // -- Parsing helpers --

    private Vector3f parsePosition(JiggerCommandParser.PositionContext ctx) {
        var nums = ctx.NUMBER();
        return new Vector3f(
                toMm(Float.parseFloat(nums.get(0).getText())),
                toMm(Float.parseFloat(nums.get(1).getText())),
                toMm(Float.parseFloat(nums.get(2).getText())));
    }

    private Vector3f parseSizeSpec(JiggerCommandParser.SizeSpecContext ctx) {
        if (ctx instanceof JiggerCommandParser.SizeByDimensionsContext dimCtx) {
            var nums = dimCtx.dimensions().NUMBER();
            float first = toMm(Float.parseFloat(nums.get(0).getText()));
            if (nums.size() == 3) {
                return new Vector3f(
                        first,
                        toMm(Float.parseFloat(nums.get(1).getText())),
                        toMm(Float.parseFloat(nums.get(2).getText())));
            }
            return new Vector3f(first, first, first);
        }
        if (ctx instanceof JiggerCommandParser.SizeByComponentsContext compCtx) {
            float w = toMm(1);
            float h = toMm(1);
            float d = toMm(1);
            for (var ws : compCtx.widthSpec()) {
                w = toMm(Float.parseFloat(ws.NUMBER().getText()));
            }
            for (var hs : compCtx.heightSpec()) {
                h = toMm(Float.parseFloat(hs.NUMBER().getText()));
            }
            for (var ds : compCtx.depthSpec()) {
                d = toMm(Float.parseFloat(ds.NUMBER().getText()));
            }
            return new Vector3f(w, h, d);
        }
        return defaultSize();
    }

    private ColorRGBA parseColor(JiggerCommandParser.ColorContext ctx) {
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

        // Track which parts belong to assemblies so we can list standalone objects separately
        java.util.Set<String> assemblyPartNames = new java.util.HashSet<>();
        Map<String, Assembly> assemblies = scene.getAllAssemblies();

        // List assemblies first
        for (Assembly assembly : assemblies.values()) {
            String templateLabel = assembly.getTemplateName() != null
                    ? " [" + assembly.getTemplateName() + "]" : "";
            sb.append(String.format("\n  %-20s assembly%s (%d parts)%n",
                    assembly.getName(), templateLabel, assembly.getParts().size()));
            for (Part p : assembly.getParts()) {
                assemblyPartNames.add(p.getName());
                SceneManager.ObjectRecord rec = recs.get(p.getName());
                if (rec != null) {
                    Vector3f pos = rec.position();
                    sb.append(String.format("    %-20s [%s] %.2f x %.2f %s  at (%.2f, %.2f, %.2f)%n",
                            p.getName(), p.getMaterial().getName(),
                            fromMm(p.getCutWidthMm()), fromMm(p.getCutHeightMm()), abbr,
                            fromMm(pos.x), fromMm(pos.y), fromMm(pos.z)));
                }
            }
        }

        // List standalone objects (not in any assembly)
        boolean hasStandalone = false;
        for (var rec : recs.values()) {
            if (assemblyPartNames.contains(rec.name())) continue;
            if (!hasStandalone) {
                if (!assemblies.isEmpty()) sb.append("\n  Standalone:\n");
                hasStandalone = true;
            }
            Vector3f pos = rec.position();
            Part part = scene.getPart(rec.name());
            if (part != null) {
                sb.append(String.format("  %-20s [%s] %.2f x %.2f %s (%.2f thick)  at (%.2f, %.2f, %.2f)  grain: %s%n",
                        part.getName(), part.getMaterial().getName(),
                        fromMm(part.getCutWidthMm()), fromMm(part.getCutHeightMm()), abbr,
                        fromMm(part.getThicknessMm()),
                        fromMm(pos.x), fromMm(pos.y), fromMm(pos.z),
                        part.getGrainRequirement().name().toLowerCase()));
            } else {
                Vector3f size = rec.size();
                sb.append(String.format("  %-20s %-10s at (%.2f, %.2f, %.2f)  size (%.2f, %.2f, %.2f) %s%n",
                        rec.name(), rec.shapeType(),
                        fromMm(pos.x), fromMm(pos.y), fromMm(pos.z),
                        fromMm(size.x), fromMm(size.y), fromMm(size.z),
                        abbr));
            }
        }
        return sb.toString().stripTrailing();
    }

    private String listMaterials() {
        StringBuilder sb = new StringBuilder("Available materials:\n");
        String abbr = executor.getUnits().getAbbreviation();
        for (var mat : MaterialCatalog.instance().getAll()) {
            sb.append(String.format("  %-22s %-30s  thickness: %.2f %s  type: %s",
                    mat.getName(), mat.getDisplayName(),
                    fromMm(mat.getThicknessMm()), abbr,
                    mat.getType().name().toLowerCase()));
            if (mat.getSheetWidthMm() != null) {
                sb.append(String.format("  sheet: %.0f x %.0f %s",
                        fromMm(mat.getSheetWidthMm()), fromMm(mat.getSheetHeightMm()), abbr));
            }
            if (mat.getGrainDirection() != com.jigger.model.GrainDirection.NONE) {
                sb.append("  grain: yes");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
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
        com.jigger.model.Part part = scene.getPart(name);
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
            for (Joint j : joints) {
                String role = j.getReceivingPartName().equals(name) ? "receives" : "inserted into";
                String other = j.getReceivingPartName().equals(name)
                        ? j.getInsertedPartName() : j.getReceivingPartName();
                sb.append(String.format("    %s \"%s\" (%s", role, other, j.getType().getDisplayName()));
                if (j.getDepthMm() > 0) {
                    sb.append(String.format(", depth %.2f %s", fromMm(j.getDepthMm()), abbr));
                }
                sb.append(")\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    private String showAssemblyInfo(Assembly assembly) {
        String abbr = executor.getUnits().getAbbreviation();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Assembly: ").append(assembly.getName()).append(" ===\n");
        if (assembly.getTemplateName() != null) {
            sb.append(String.format("  Template:  %s%n", assembly.getTemplateName()));
        }
        sb.append(String.format("  Parts:     %d%n", assembly.getParts().size()));

        // Bounding box
        Vector3f bbMin = assembly.getBoundingBoxMin(
                pn -> { var r = scene.getObjectRecord(pn); return r != null ? r.position() : null; },
                pn -> { var r = scene.getObjectRecord(pn); return r != null ? r.size() : null; });
        sb.append(String.format("  Origin:    (%.2f, %.2f, %.2f) %s%n",
                fromMm(bbMin.x), fromMm(bbMin.y), fromMm(bbMin.z), abbr));

        // List parts
        sb.append("\n  Parts:\n");
        for (Part p : assembly.getParts()) {
            SceneManager.ObjectRecord rec = scene.getObjectRecord(p.getName());
            if (rec != null) {
                Vector3f pos = rec.position();
                sb.append(String.format("    %-25s [%s] %.2f x %.2f %s  at (%.2f, %.2f, %.2f)%n",
                        p.getName(), p.getMaterial().getName(),
                        fromMm(p.getCutWidthMm()), fromMm(p.getCutHeightMm()), abbr,
                        fromMm(pos.x), fromMm(pos.y), fromMm(pos.z)));
            }
        }

        // Assembly joints
        var joints = scene.getJointRegistry().getJointsForAssembly(assembly);
        if (!joints.isEmpty()) {
            sb.append("\n  Joints:\n");
            for (Joint j : joints) {
                sb.append(String.format("    %-15s  \"%s\" ← \"%s\"",
                        j.getType().getDisplayName(), j.getReceivingPartName(), j.getInsertedPartName()));
                if (j.getDepthMm() > 0) {
                    sb.append(String.format("  depth: %.2f %s", fromMm(j.getDepthMm()), abbr));
                }
                sb.append('\n');
            }
        }

        return sb.toString().stripTrailing();
    }

    private String listTemplates() {
        var templates = TemplateRegistry.instance().getAll();
        if (templates.isEmpty()) return "No templates defined.";
        StringBuilder sb = new StringBuilder("Available templates:\n");
        for (Template t : templates) {
            // Build param string with aliases
            StringBuilder params = new StringBuilder();
            for (String p : t.getParamNames()) {
                if (!params.isEmpty()) params.append(", ");
                params.append(p);
                t.getParamAliases().entrySet().stream()
                        .filter(e -> e.getValue().equals(p))
                        .findFirst()
                        .ifPresent(e -> params.append("(").append(e.getKey()).append(")"));
            }
            sb.append(String.format("  %-20s params: %-40s  %d lines%s%n",
                    t.getName(), params,
                    t.getBodyLines().size(),
                    t.isBuiltIn() ? "  (built-in)" : ""));
        }
        sb.append("\nUsage: create <template> \"name\" param1 value1 ...");
        sb.append("\n       Aliases work: create base-cabinet K w 600 h 900 d 400");
        return sb.toString().stripTrailing();
    }

    private String showTemplateDefinition(String name) {
        Template t = TemplateRegistry.instance().get(name);
        if (t == null) {
            return "Template '" + name + "' not found. Use 'show templates' to list.";
        }

        StringBuilder sb = new StringBuilder();
        if (t.isBuiltIn()) {
            sb.append("# Built-in template — copy and modify to customize.\n");
            sb.append("# Re-running the define block will override the built-in.\n\n");
        }

        // Build params string with aliases
        StringBuilder params = new StringBuilder();
        for (String p : t.getParamNames()) {
            if (!params.isEmpty()) params.append(", ");
            params.append(p);
            t.getParamAliases().entrySet().stream()
                    .filter(e -> e.getValue().equals(p))
                    .findFirst()
                    .ifPresent(e -> params.append("(").append(e.getKey()).append(")"));
        }

        sb.append("define \"").append(t.getName()).append("\"");
        if (!t.getParamNames().isEmpty()) {
            sb.append(" params ").append(params);
        }
        sb.append('\n');

        for (String line : t.getBodyLines()) {
            sb.append("  ").append(line).append('\n');
        }

        sb.append("end define");
        return sb.toString();
    }

    private String listJoints() {
        var joints = scene.getJointRegistry().getAllJoints();
        if (joints.isEmpty()) return "No joints defined.";
        String abbr = executor.getUnits().getAbbreviation();
        StringBuilder sb = new StringBuilder("Joints:\n");
        for (Joint j : joints) {
            sb.append(String.format("  %-15s  \"%s\" ← \"%s\"",
                    j.getType().getDisplayName(), j.getReceivingPartName(), j.getInsertedPartName()));
            if (j.getDepthMm() > 0) {
                sb.append(String.format("  depth: %.2f %s", fromMm(j.getDepthMm()), abbr));
            }
            if (j.getScrewCount() > 0) {
                sb.append(String.format("  screws: %d", j.getScrewCount()));
                if (j.getScrewSpacingMm() > 0) {
                    sb.append(String.format(" @ %.2f %s", fromMm(j.getScrewSpacingMm()), abbr));
                }
            }
            sb.append('\n');
        }
        var summary = scene.getJointRegistry().getSummary();
        sb.append("\nSummary: ");
        sb.append(summary.entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey().getDisplayName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none"));
        return sb.toString().stripTrailing();
    }

    private String showCutList() {
        var parts = scene.getAllParts();
        if (parts.isEmpty()) return "No parts in scene. (Cut list only includes parts, not primitives.)";

        var entries = CutListGenerator.generateCutList(parts, scene.getJointRegistry());
        String abbr = executor.getUnits().getAbbreviation();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Cut List (units: %s):%n%n", abbr));

        String currentMaterial = "";
        for (var entry : entries) {
            // Print material header when it changes
            if (!entry.getMaterial().getName().equals(currentMaterial)) {
                currentMaterial = entry.getMaterial().getName();
                sb.append(String.format("  %s (%s, %.2f %s thick):%n",
                        entry.getMaterial().getDisplayName(),
                        entry.getMaterial().getName(),
                        fromMm(entry.getThicknessMm()), abbr));
            }

            // Part line
            sb.append(String.format("    %-25s %8.2f x %-8.2f %s",
                    entry.getPartName(),
                    fromMm(entry.getCutWidthMm()),
                    fromMm(entry.getCutHeightMm()),
                    abbr));

            if (entry.getGrainRequirement() != GrainRequirement.ANY) {
                sb.append("  grain: ").append(entry.getGrainRequirement().name().toLowerCase());
            }
            sb.append('\n');

            // Machining operations
            for (String op : entry.getOperations()) {
                sb.append("      → ").append(op).append('\n');
            }
        }

        sb.append(String.format("%n  Total: %d parts", entries.size()));
        return sb.toString().stripTrailing();
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
        for (var entry : bomEntries) {
            sb.append(String.format("    %d pc  %-30s  (%.2f %s thick)",
                    entry.getPartCount(),
                    entry.getMaterial().getDisplayName(),
                    fromMm(entry.getMaterial().getThicknessMm()), abbr));
            if (entry.getSheetCount() != null) {
                sb.append(String.format("  %d sheet%s (%.0f x %.0f %s, %.1f%% offcut)",
                        entry.getSheetCount(),
                        entry.getSheetCount() == 1 ? "" : "s",
                        fromMm(entry.getMaterial().getSheetWidthMm()),
                        fromMm(entry.getMaterial().getSheetHeightMm()),
                        abbr,
                        entry.getOffcutPercent()));
            }
            sb.append('\n');
        }

        // Fasteners
        var fasteners = CutListGenerator.generateFasteners(scene.getJointRegistry());
        if (!fasteners.isEmpty()) {
            sb.append("\n  Fasteners:\n");
            for (var f : fasteners) {
                sb.append(String.format("    %d x %s%n", f.getCount(), f.getType()));
            }
        }

        // Joint summary
        var jointSummary = scene.getJointRegistry().getSummary();
        if (!jointSummary.isEmpty()) {
            sb.append("\n  Joinery operations:\n");
            for (var entry : jointSummary.entrySet()) {
                sb.append(String.format("    %d x %s%n", entry.getValue(), entry.getKey().getDisplayName()));
            }
        }

        return sb.toString().stripTrailing();
    }

    private String showLayout() {
        var parts = scene.getAllParts();
        if (parts.isEmpty()) return "No parts in scene.";

        float kerfMm = scene.getKerfMm();
        var layouts = SheetLayoutGenerator.generateLayouts(parts, kerfMm);
        if (layouts.isEmpty()) return "No sheet goods in scene (only hardwood/metal parts).";

        String abbr = executor.getUnits().getAbbreviation();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Sheet Layouts (%s, kerf: %.1f %s):\n",
                abbr, fromMm(kerfMm), abbr));

        // Group layouts by material for numbering
        String currentMaterial = null;
        int sheetNum = 0;
        int materialSheetCount = 0;

        // Pre-count sheets per material
        java.util.Map<String, Integer> sheetCounts = new java.util.LinkedHashMap<>();
        for (var layout : layouts) {
            sheetCounts.merge(layout.getMaterial().getDisplayName(), 1, Integer::sum);
        }

        for (var layout : layouts) {
            String matName = layout.getMaterial().getDisplayName();
            if (!matName.equals(currentMaterial)) {
                currentMaterial = matName;
                sheetNum = 1;
                materialSheetCount = sheetCounts.get(matName);
                sb.append('\n');
            } else {
                sheetNum++;
            }

            sb.append(String.format("  %s — Sheet %d of %d (%.0f x %.0f %s):\n",
                    matName, sheetNum, materialSheetCount,
                    fromMm(layout.getSheetWidthMm()), fromMm(layout.getSheetHeightMm()), abbr));

            for (var p : layout.getPlacements()) {
                sb.append(String.format("    %-25s %6.1f x %6.1f %s  at (%6.1f, %6.1f)",
                        p.getPartName(),
                        fromMm(p.getWidthOnSheet()), fromMm(p.getHeightOnSheet()), abbr,
                        fromMm(p.getX()), fromMm(p.getY())));
                if (p.isRotated()) {
                    sb.append("  rotated");
                }
                if (p.getGrainRequirement() != GrainRequirement.ANY) {
                    sb.append("  grain: " + p.getGrainRequirement().name().toLowerCase());
                }
                sb.append('\n');
            }

            sb.append(String.format("    Used: %.1f%%   Offcut: %.1f%%\n",
                    100f - layout.getOffcutPercent(), layout.getOffcutPercent()));
        }

        // Summary
        sb.append("\n  Summary:\n");
        for (var entry : sheetCounts.entrySet()) {
            sb.append(String.format("    %s: %d sheet%s\n",
                    entry.getKey(), entry.getValue(),
                    entry.getValue() == 1 ? "" : "s"));
        }
        int totalSheets = sheetCounts.values().stream().mapToInt(Integer::intValue).sum();
        sb.append(String.format("    Total: %d sheet%s", totalSheets, totalSheets == 1 ? "" : "s"));

        return sb.toString();
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

    private static String extractMaterialName(JiggerCommandParser.MaterialNameContext ctx) {
        if (ctx.STRING() != null) {
            String s = ctx.STRING().getText();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }
        return ctx.ID().getText();
    }

    private static String extractName(JiggerCommandParser.ObjectNameContext ctx) {
        if (ctx.STRING() != null) {
            String s = ctx.STRING().getText();
            // Strip surrounding quotes
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }
        return ctx.ID().getText();
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
                  move <name> to x,y,z             — move an object
                  rotate <name> rx,ry,rz           — rotate in degrees (absolute)
                  resize <name> <size>             — resize an object
                  join <part1> to <part2> with <type> [depth N] [screws N] [spacing N]
                      types: butt, dado, rabbet, pocket_screw (or pocket)
                      depth required for dado/rabbet

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
                  set units <unit>                 — change display/input units
                  set material <mat>               — change default material
                  set kerf <value>                 — saw blade kerf width (default 3.2mm)
                  set layout tabs|split           — switch between tabbed and split-pane view
                  export cutsheet pdf [file]      — export cut sheets to PDF (opens save dialog if no file)
                  export cutsheet png [file]      — export cut sheets to PNG image
                  export cutsheet jpg [file]      — export cut sheets to JPEG image
                      units: """ + UnitSystem.allNames() + """

                  run [file]                       — run a .jigs script (opens file dialog if omitted)
                  undo                             — undo last action (also Ctrl+Z)
                  redo                             — redo last undone action (also Ctrl+Shift+Z)
                  help                             — show this help text
                  exit                             — quit the application

                Startup script: ~/.jigger/startup.jigs (auto-runs on launch if present)

                Templates:
                  define "name" params p1, p2, ...   — start template definition
                    <commands with $variable references and arithmetic>
                  end define                         — finish definition
                  create <template> "name" p1 v1 p2 v2 ...  — instantiate a template
                  show templates                     — list available templates

                Coordinates and sizes are in current units (default: mm).
                $thickness is implicit (from default material).

                Examples:
                  create base-cabinet "K1" width 600 height 900 depth 400
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
