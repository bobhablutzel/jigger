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

import app.cadette.SceneManager;
import app.cadette.UnitSystem;
import app.cadette.ViewLayoutMode;
import app.cadette.model.*;
import com.jme3.math.Vector3f;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.antlr.v4.runtime.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Parses command text via the ANTLR grammar and delegates execution
 * to a {@link CommandVisitor}.
 */
public class CommandExecutor {

    private final SceneManager scene;
    @Setter private Runnable onExit;
    @Getter private UnitSystem units = UnitSystem.MILLIMETERS;
    @Getter private Material defaultMaterial = MaterialCatalog.instance().getDefaultFor(
            UnitSystem.MILLIMETERS.getMeasurementSystem());
    private final List<Consumer<UnitSystem>> unitChangeListeners = new ArrayList<>();
    private final List<Consumer<Material>> materialChangeListeners = new ArrayList<>();
    private final List<Consumer<ViewLayoutMode>> layoutChangeListeners = new ArrayList<>();
    @Getter private ViewLayoutMode layoutMode = ViewLayoutMode.TABBED;

    private final Deque<UndoableAction> undoStack = new ArrayDeque<>();
    private final Deque<UndoableAction> redoStack = new ArrayDeque<>();

    /** Callback to open a file chooser dialog (set by CadetteApp). Returns null if cancelled. */
    @Setter private Supplier<Path> fileChooser;

    /** Callback to open a save-file dialog. Accepts a description and extensions. Returns null if cancelled. */
    @Setter private BiFunction<String, String[], Path> saveFileChooser;

    // -- Template recording state --
    private boolean definingTemplate = false;
    private String definingTemplateName = null;
    private List<String> definingParamNames = null;
    private Map<String, String> definingParamAliases = null;
    private List<String> definingBodyLines = null;

    // Suppress individual undo pushes during template instantiation or script runs
    private boolean suppressUndo = false;
    // Collect actions during script run (null when not collecting)
    private List<UndoableAction> collectingActions = null;

    // Template-expansion context: when non-null, the visitor prefixes object-name
    // references with "<prefix>/" so a body-line "left-side" becomes "K/left-side".
    @Getter(AccessLevel.PACKAGE) private String currentInstancePrefix = null;
    // Set by visitor's visitCreatePartCommand after each part create, consumed by
    // instantiateTemplate to collect the parts belonging to the new assembly.
    @Setter(AccessLevel.PACKAGE) private String lastCreatedPartName = null;

    public CommandExecutor(SceneManager scene) {
        this.scene = scene;
    }

    // Hand-coded: fires change listeners on write. @Setter can't express dispatch.
    public void setUnits(UnitSystem units) {
        this.units = units;
        for (Consumer<UnitSystem> listener : unitChangeListeners) {
            listener.accept(units);
        }
    }

    public void addUnitChangeListener(Consumer<UnitSystem> listener) {
        unitChangeListeners.add(listener);
    }

    // Hand-coded: fires change listeners on write. @Setter can't express dispatch.
    public void setDefaultMaterial(Material material) {
        this.defaultMaterial = material;
        for (Consumer<Material> listener : materialChangeListeners) {
            listener.accept(material);
        }
    }

    public void addMaterialChangeListener(Consumer<Material> listener) {
        materialChangeListeners.add(listener);
    }

    // Hand-coded: fires change listeners on write. @Setter can't express dispatch.
    public void setLayoutMode(ViewLayoutMode mode) {
        this.layoutMode = mode;
        for (Consumer<ViewLayoutMode> listener : layoutChangeListeners) {
            listener.accept(mode);
        }
    }

    public void addLayoutChangeListener(Consumer<ViewLayoutMode> listener) {
        layoutChangeListeners.add(listener);
    }

    /**
     * Open a save-file dialog. Returns null if cancelled or no chooser is available.
     */
    public Path chooseSaveFile(String description, String... extensions) {
        if (saveFileChooser == null) return null;
        return saveFileChooser.apply(description, extensions);
    }

    /** Clear the undo/redo stacks. */
    public void clearUndoHistory() {
        undoStack.clear();
        redoStack.clear();
    }

    /** Called by the visitor to fire the exit callback. */
    void fireExit() {
        if (onExit != null) {
            onExit.run();
        }
    }

    /** Undo the last action. Returns a feedback message. */
    public String undo() {
        if (undoStack.isEmpty()) {
            return "Nothing to undo.";
        }
        UndoableAction action = undoStack.pop();
        action.undo();
        redoStack.push(action);
        return "Undone: " + action.description();
    }

    /** Redo the last undone action. Returns a feedback message. */
    public String redo() {
        if (redoStack.isEmpty()) {
            return "Nothing to redo.";
        }
        UndoableAction action = redoStack.pop();
        action.redo();
        undoStack.push(action);
        return "Redone: " + action.description();
    }

    /** Called by the visitor to record an undoable action. */
    void pushAction(UndoableAction action) {
        if (suppressUndo) return;
        if (collectingActions != null) {
            collectingActions.add(action);
        } else {
            undoStack.push(action);
            redoStack.clear();
        }
    }

    public String execute(String input) {
        try {
            String trimmed = input.trim();
            if (trimmed.isEmpty()) return "";
            String lower = trimmed.toLowerCase();

            // -- Template recording mode --
            if (definingTemplate && lower.equals("end define")) {
                return finishDefine();
            }
            if (definingTemplate) {
                definingBodyLines.add(trimmed);
                return "  (recorded)";
            }

            // -- Normal ANTLR parsing --
            CharStream chars = CharStreams.fromString(trimmed);
            CadetteCommandLexer lexer = new CadetteCommandLexer(chars);
            lexer.removeErrorListeners();

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            CadetteCommandParser parser = new CadetteCommandParser(tokens);
            parser.removeErrorListeners();

            StringBuilder errors = new StringBuilder();
            parser.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                        int line, int charPos, String msg, RecognitionException e) {
                    errors.append("Parse error: ").append(msg);
                }
            });

            CadetteCommandParser.InputContext inputCtx = parser.input();

            if (!errors.isEmpty()) {
                return errors + "\nType 'help' for usage.";
            }

            // Pure-comment or whitespace-only lines produce no command.
            if (inputCtx.command() == null) return "";

            CommandVisitor visitor = new CommandVisitor(this, scene);
            return visitor.visit(inputCtx.command());

        } catch (Exception e) {
            return "Error: " + e.getMessage() + "\nType 'help' for usage.";
        }
    }

    // ======================== Template Define ========================

    /**
     * Enter template-recording mode. Called by the visitor after ANTLR parses
     * the define header. Subsequent lines are collected until "end define".
     */
    String beginDefine(String name, List<String> paramNames, Map<String, String> paramAliases) {
        definingTemplateName = name;
        definingParamNames = new ArrayList<>(paramNames);
        definingParamAliases = new LinkedHashMap<>(paramAliases);
        definingBodyLines = new ArrayList<>();
        definingTemplate = true;

        StringBuilder paramDesc = new StringBuilder();
        for (int i = 0; i < definingParamNames.size(); i++) {
            if (i > 0) paramDesc.append(", ");
            String pName = definingParamNames.get(i);
            paramDesc.append(pName);
            definingParamAliases.entrySet().stream()
                    .filter(e -> e.getValue().equals(pName))
                    .findFirst()
                    .ifPresent(e -> paramDesc.append("(").append(e.getKey()).append(")"));
        }

        return "Defining template '" + definingTemplateName + "'"
                + (definingParamNames.isEmpty() ? "..." : " (params: " + paramDesc + ")...");
    }

    private String finishDefine() {
        Template template = new Template(definingTemplateName, definingParamNames,
                definingParamAliases, definingBodyLines, false);
        TemplateRegistry.instance().register(template);

        String msg = "Template '" + definingTemplateName + "' defined ("
                + definingBodyLines.size() + " lines, "
                + definingParamNames.size() + " params).";

        definingTemplate = false;
        definingTemplateName = null;
        definingParamNames = null;
        definingParamAliases = null;
        definingBodyLines = null;

        return msg;
    }

    // ======================== Template Instantiation ========================

    /** Relative placement info for a template instantiation. */
    record RelativePlacement(String direction, String referenceName, float gapUnits) {}

    /**
     * Instantiate a template. Called by the visitor after ANTLR parses the command.
     * placement and relativePlacement are mutually exclusive — either or both may be null.
     */
    String instantiateTemplate(String templateName, String instanceName,
                                Vector3f placement, RelativePlacement relativePlacement,
                                Map<String, String> rawParamValues) {
        Template template = TemplateRegistry.instance().get(templateName);
        if (template == null) {
            return "Template '" + templateName + "' not found.";
        }

        if (scene.getAssembly(instanceName) != null) {
            return "Assembly '" + instanceName + "' already exists.";
        }

        Map<String, Double> vars = resolveParamValues(template, rawParamValues);
        if (vars == null) {
            return "Usage: create " + templateName + " \"name\" "
                    + String.join(" ", template.getParamNames().stream()
                        .map(p -> p + " <value>").toList());
        }

        // Implicit variables (all in current display units)
        // $mm = 1 millimeter in current units (e.g., 0.1 if units are cm, 1.0 if mm)
        vars.put("mm", (double) units.fromMm(1f));

        // $thickness = default material thickness
        float thicknessMm = defaultMaterial.getThicknessMm();
        vars.put("thickness", (double) units.fromMm(thicknessMm));

        // $back_thickness = hardboard thickness
        Material hardboard = MaterialCatalog.instance().get(
                units.getMeasurementSystem() == MeasurementSystem.IMPERIAL ? "hardboard-1/4" : "hardboard-5.5mm");
        if (hardboard != null) {
            vars.put("back_thickness", (double) units.fromMm(hardboard.getThicknessMm()));
        }

        // Instantiate: expand each body line, substitute vars, execute
        Assembly assembly = new Assembly(instanceName);
        assembly.setTemplateName(templateName);
        List<Part> createdParts = new ArrayList<>();
        StringBuilder output = new StringBuilder();

        suppressUndo = true;
        String previousPrefix = currentInstancePrefix;
        currentInstancePrefix = instanceName;
        try {
            for (String bodyLine : template.getBodyLines()) {
                if (bodyLine.trim().isEmpty()) continue;

                String resolved = ExpressionEvaluator.substituteInLine(bodyLine, vars);
                lastCreatedPartName = null;
                String result = execute(resolved);
                if (result.isEmpty()) continue;  // comment line
                output.append("  ").append(result).append("\n");

                if (lastCreatedPartName != null) {
                    Part part = scene.getPart(lastCreatedPartName);
                    if (part != null) {
                        assembly.addPart(part);
                        createdParts.add(part);
                    }
                }
            }
        } finally {
            suppressUndo = false;
            currentInstancePrefix = previousPrefix;
            lastCreatedPartName = null;
        }

        // Normalize assembly position: shift all parts so the AABB min
        // is at the target placement (default 0,0,0). Uses rotation-aware bounds.
        Vector3f targetMm = placement != null
                ? new Vector3f(units.toMm(placement.x), units.toMm(placement.y), units.toMm(placement.z))
                : Vector3f.ZERO.clone();
        {
            com.jme3.math.Vector3f[] aabb = computeAssemblyAABB(createdParts);
            com.jme3.math.Vector3f delta = targetMm.subtract(aabb[0]);
            if (delta.lengthSquared() > 0.001f) {
                for (Part part : createdParts) {
                    SceneManager.ObjectRecord rec = scene.getObjectRecord(part.getName());
                    if (rec != null) {
                        scene.moveObject(part.getName(), rec.position().add(delta));
                    }
                }
            }
        }

        scene.registerAssembly(assembly);

        // Apply relative placement if specified (after registration so we can use assembly bbox)
        String posStr = "";
        if (relativePlacement != null) {
            String refName = relativePlacement.referenceName();
            Assembly refAssembly = scene.getAssembly(refName);
            SceneManager.ObjectRecord refRec = scene.getObjectRecord(refName);
            if (refAssembly == null && refRec == null) {
                // Undo the creation — clean up parts and assembly
                for (Part part : createdParts.reversed()) {
                    scene.deleteObject(part.getName());
                }
                scene.removeAssembly(instanceName);
                return "Reference '" + refName + "' not found. Assembly not created.";
            } else {
                com.jme3.math.Vector3f[] refBBox;
                if (refAssembly != null) {
                    refBBox = computeAssemblyAABB(refAssembly.getParts());
                } else {
                    refBBox = scene.computeObjectAABB(refName);
                }

                com.jme3.math.Vector3f[] srcBBox = computeAssemblyAABB(createdParts);

                com.jme3.math.Vector3f refMin = refBBox[0], refMax = refBBox[1];
                com.jme3.math.Vector3f srcSize = srcBBox[1].subtract(srcBBox[0]);
                float gapMm = units.toMm(relativePlacement.gapUnits());

                com.jme3.math.Vector3f targetPos = switch (relativePlacement.direction()) {
                    case "left" -> new com.jme3.math.Vector3f(refMin.x - srcSize.x - gapMm, refMin.y, refMin.z);
                    case "right" -> new com.jme3.math.Vector3f(refMax.x + gapMm, refMin.y, refMin.z);
                    case "behind" -> new com.jme3.math.Vector3f(refMin.x, refMin.y, refMin.z - srcSize.z - gapMm);
                    case "in front", "in-front" -> new com.jme3.math.Vector3f(refMin.x, refMin.y, refMax.z + gapMm);
                    case "above" -> new com.jme3.math.Vector3f(refMin.x, refMax.y + gapMm, refMin.z);
                    case "below" -> new com.jme3.math.Vector3f(refMin.x, refMin.y - srcSize.y - gapMm, refMin.z);
                    default -> srcBBox[0];
                };

                com.jme3.math.Vector3f moveDelta = targetPos.subtract(srcBBox[0]);
                if (moveDelta.lengthSquared() > 0.001f) {
                    for (Part part : createdParts) {
                        SceneManager.ObjectRecord rec = scene.getObjectRecord(part.getName());
                        if (rec != null) {
                            scene.moveObject(part.getName(), rec.position().add(moveDelta));
                        }
                    }
                }
                posStr = " " + relativePlacement.direction() + " '" + refName + "'";
                if (relativePlacement.gapUnits() != 0) {
                    posStr += String.format(" (gap %.2f %s)", relativePlacement.gapUnits(), units.getAbbreviation());
                }
            }
        } else if (placement != null) {
            posStr = String.format(" at (%.2f, %.2f, %.2f)", placement.x, placement.y, placement.z);
        }

        pushAction(new CreateTemplateAction(scene, instanceName, templateName, createdParts));

        return "Created " + templateName + " '" + instanceName + "' (" + createdParts.size() + " parts)" + posStr + ":\n"
                + output.toString().stripTrailing();
    }

    /** Common parameter name aliases (global fallback when template has none). */
    private static final Map<String, String> PARAM_ALIASES = Map.of(
            "w", "width",
            "h", "height",
            "d", "depth",
            "l", "length",
            "mat", "material",
            "gr", "grain",
            "sz", "size"
    );

    /**
     * Resolve raw parameter key-value pairs against a template's declared params.
     * Keys are resolved via template-specific aliases first, then global aliases.
     * Returns null if any declared param is missing or non-numeric.
     */
    private Map<String, Double> resolveParamValues(Template template, Map<String, String> rawValues) {
        List<String> paramNames = template.getParamNames();
        if (rawValues.isEmpty() && !paramNames.isEmpty()) {
            return null;
        }

        Map<String, String> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rawValues.entrySet()) {
            String key = entry.getKey().toLowerCase();
            String resolved = template.resolveParam(key);
            if (resolved == null) {
                resolved = PARAM_ALIASES.getOrDefault(key, key);
            }
            canonical.put(resolved, entry.getValue());
        }

        Map<String, Double> vars = new LinkedHashMap<>();
        for (String param : paramNames) {
            String val = canonical.get(param);
            if (val == null) return null;
            try {
                vars.put(param, Double.parseDouble(val));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return vars;
    }

    // ======================== Run / Script ========================

    /** Called by the visitor when `run` has no path — prompt the user via the file chooser. */
    String runWithFileChooser() {
        if (fileChooser == null) return "No file chooser available.";
        Path file = fileChooser.get();
        if (file == null) return "Cancelled.";
        if (!Files.exists(file)) return "File not found: " + file;
        return runScript(file);
    }

    /** Called by the visitor with an explicit path (already variable-expanded). */
    String runScriptPath(String path) {
        if (path.isEmpty()) return "Empty path.";
        Path file = Path.of(path);
        if (!Files.exists(file)) return "File not found: " + file;
        return runScript(file);
    }

    public String runScript(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }

        StringBuilder output = new StringBuilder();
        output.append("Running ").append(file.getFileName()).append("...\n");

        // Collect all individual actions into a single composite undo action
        List<UndoableAction> previousCollecting = collectingActions;
        collectingActions = new ArrayList<>();

        try {
            int lineNum = 0;
            for (String line : lines) {
                lineNum++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String result = execute(trimmed);
                if (result.isEmpty()) continue;  // comment line
                output.append(String.format("  [%d] %s → %s%n", lineNum, trimmed, result));
            }
        } finally {
            List<UndoableAction> collected = collectingActions;
            collectingActions = previousCollecting;

            // Push composite action (unless we're nested inside another collector)
            if (!collected.isEmpty()) {
                ScriptRunAction composite = new ScriptRunAction(
                        file.getFileName().toString(), collected);
                if (collectingActions != null) {
                    collectingActions.add(composite);
                } else {
                    undoStack.push(composite);
                    redoStack.clear();
                }
            }
        }

        output.append("Done. (").append(file.getFileName()).append(")");
        return output.toString();
    }

    /**
     * Run the startup script if it exists. Undo is fully suppressed —
     * startup commands don't appear in the undo history.
     */
    public String runStartupScript() {
        Path startupDir = Path.of(System.getProperty("user.home"), ".cadette");
        Path startupFile = startupDir.resolve("startup.cds");
        if (Files.exists(startupFile)) {
            suppressUndo = true;
            try {
                return runScript(startupFile);
            } finally {
                suppressUndo = false;
            }
        }
        return null;
    }

    // ======================== Utilities ========================

    /** Compute rotation-aware AABB for a list of parts. */
    private com.jme3.math.Vector3f[] computeAssemblyAABB(List<Part> parts) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Part p : parts) {
            com.jme3.math.Vector3f[] aabb = scene.computeObjectAABB(p.getName());
            if (aabb != null) {
                minX = Math.min(minX, aabb[0].x);
                minY = Math.min(minY, aabb[0].y);
                minZ = Math.min(minZ, aabb[0].z);
                maxX = Math.max(maxX, aabb[1].x);
                maxY = Math.max(maxY, aabb[1].y);
                maxZ = Math.max(maxZ, aabb[1].z);
            }
        }
        return new com.jme3.math.Vector3f[]{
                new com.jme3.math.Vector3f(minX, minY, minZ),
                new com.jme3.math.Vector3f(maxX, maxY, maxZ)
        };
    }

}
