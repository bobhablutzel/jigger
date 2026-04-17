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

import com.jigger.SceneManager;
import com.jigger.UnitSystem;
import com.jigger.ViewLayoutMode;
import com.jigger.model.*;
import com.jme3.math.Vector3f;
import org.antlr.v4.runtime.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses command text via the ANTLR grammar and delegates execution
 * to a {@link CommandVisitor}.
 */
public class CommandExecutor {

    private final SceneManager scene;
    private Runnable onExit;
    private UnitSystem units = UnitSystem.MILLIMETERS;
    private Material defaultMaterial = MaterialCatalog.instance().getDefaultFor(
            UnitSystem.MILLIMETERS.getMeasurementSystem());
    private final List<Consumer<UnitSystem>> unitChangeListeners = new ArrayList<>();
    private final List<Consumer<Material>> materialChangeListeners = new ArrayList<>();
    private final List<Consumer<ViewLayoutMode>> layoutChangeListeners = new ArrayList<>();
    private ViewLayoutMode layoutMode = ViewLayoutMode.TABBED;

    private final Deque<UndoableAction> undoStack = new ArrayDeque<>();
    private final Deque<UndoableAction> redoStack = new ArrayDeque<>();

    /** Callback to open a file chooser dialog (set by JiggerApp). Returns null if cancelled. */
    private Supplier<Path> fileChooser;

    /** Callback to open a save-file dialog. Accepts a description and extensions. Returns null if cancelled. */
    private java.util.function.BiFunction<String, String[], Path> saveFileChooser;

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

    public CommandExecutor(SceneManager scene) {
        this.scene = scene;
    }

    public void setOnExit(Runnable onExit) {
        this.onExit = onExit;
    }

    public UnitSystem getUnits() {
        return units;
    }

    public void setUnits(UnitSystem units) {
        this.units = units;
        for (Consumer<UnitSystem> listener : unitChangeListeners) {
            listener.accept(units);
        }
    }

    public void addUnitChangeListener(Consumer<UnitSystem> listener) {
        unitChangeListeners.add(listener);
    }

    public Material getDefaultMaterial() {
        return defaultMaterial;
    }

    public void setDefaultMaterial(Material material) {
        this.defaultMaterial = material;
        for (Consumer<Material> listener : materialChangeListeners) {
            listener.accept(material);
        }
    }

    public void addMaterialChangeListener(Consumer<Material> listener) {
        materialChangeListeners.add(listener);
    }

    public ViewLayoutMode getLayoutMode() {
        return layoutMode;
    }

    public void setLayoutMode(ViewLayoutMode mode) {
        this.layoutMode = mode;
        for (Consumer<ViewLayoutMode> listener : layoutChangeListeners) {
            listener.accept(mode);
        }
    }

    public void addLayoutChangeListener(Consumer<ViewLayoutMode> listener) {
        layoutChangeListeners.add(listener);
    }

    public void setFileChooser(Supplier<Path> fileChooser) {
        this.fileChooser = fileChooser;
    }

    public void setSaveFileChooser(java.util.function.BiFunction<String, String[], Path> saveFileChooser) {
        this.saveFileChooser = saveFileChooser;
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
                // Skip comments inside define blocks
                if (trimmed.startsWith("#")) return "  (comment)";
                definingBodyLines.add(trimmed);
                return "  (recorded)";
            }
            if (lower.startsWith("define ")) {
                return startDefine(trimmed);
            }

            // -- Toggle stats display --
            if (lower.equals("stats")) {
                boolean visible = scene.toggleStats();
                return "Stats display " + (visible ? "on" : "off") + ".";
            }

            // -- Run command --
            if (lower.startsWith("run")) {
                String rest = trimmed.substring(3).trim();
                return handleRun(rest);
            }

            // -- Template instantiation: create <template-name> "instance" ... --
            if (lower.startsWith("create ")) {
                String result = tryTemplateInstantiation(trimmed);
                if (result != null) return result;
            }

            // -- Normal ANTLR parsing --
            String normalized = lowercaseOutsideQuotes(trimmed);

            CharStream chars = CharStreams.fromString(normalized);
            JiggerCommandLexer lexer = new JiggerCommandLexer(chars);
            lexer.removeErrorListeners();

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            JiggerCommandParser parser = new JiggerCommandParser(tokens);
            parser.removeErrorListeners();

            StringBuilder errors = new StringBuilder();
            parser.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                        int line, int charPos, String msg, RecognitionException e) {
                    errors.append("Parse error: ").append(msg);
                }
            });

            JiggerCommandParser.CommandContext cmd = parser.command();

            if (!errors.isEmpty()) {
                return errors + "\nType 'help' for usage.";
            }

            CommandVisitor visitor = new CommandVisitor(this, scene);
            return visitor.visit(cmd);

        } catch (Exception e) {
            return "Error: " + e.getMessage() + "\nType 'help' for usage.";
        }
    }

    // ======================== Template Define ========================

    private static final Pattern DEFINE_PATTERN = Pattern.compile(
            "(?i)define\\s+(?:\"([^\"]+)\"|([a-zA-Z_][a-zA-Z0-9_-]*))" +
            "(?:\\s+params?\\s+(.+))?");

    // Matches "width(w)" or "width" — captures name and optional alias
    private static final Pattern PARAM_WITH_ALIAS = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_]*)(?:\\(([a-zA-Z_][a-zA-Z0-9_]*)\\))?");

    private String startDefine(String line) {
        Matcher m = DEFINE_PATTERN.matcher(line.trim());
        if (!m.matches()) {
            return "Invalid define syntax. Usage: define \"name\" params width(w), height(h), depth(d)";
        }

        definingTemplateName = m.group(1) != null ? m.group(1) : m.group(2);

        definingParamNames = new ArrayList<>();
        definingParamAliases = new LinkedHashMap<>();
        if (m.group(3) != null) {
            // Split on commas, then parse each param (possibly with alias)
            for (String token : m.group(3).split(",")) {
                token = token.trim();
                if (token.isEmpty()) continue;
                Matcher pm = PARAM_WITH_ALIAS.matcher(token);
                if (pm.matches()) {
                    String paramName = pm.group(1).toLowerCase();
                    definingParamNames.add(paramName);
                    if (pm.group(2) != null) {
                        definingParamAliases.put(pm.group(2).toLowerCase(), paramName);
                    }
                }
            }
        }

        definingBodyLines = new ArrayList<>();
        definingTemplate = true;

        StringBuilder paramDesc = new StringBuilder();
        for (int i = 0; i < definingParamNames.size(); i++) {
            if (i > 0) paramDesc.append(", ");
            String pName = definingParamNames.get(i);
            paramDesc.append(pName);
            // Find alias for this param
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

    // Pattern: create <template-name> "instance-name" param1 val1 param2 val2 ...
    // or:      create <template-name> instance-name param1 val1 ...
    private static final Pattern CREATE_TEMPLATE_PATTERN = Pattern.compile(
            "(?i)create\\s+(?:\"([^\"]+)\"|([a-zA-Z_][a-zA-Z0-9_-]*))" +  // template name
            "\\s+(?:\"([^\"]+)\"|([a-zA-Z_][a-zA-Z0-9_]*))" +              // instance name
            "(\\s+.+)?");                                                    // param-value pairs

    private String tryTemplateInstantiation(String line) {
        Matcher m = CREATE_TEMPLATE_PATTERN.matcher(line.trim());
        if (!m.matches()) return null;

        String templateName = m.group(1) != null ? m.group(1) : m.group(2);

        // Skip known non-template keywords
        String tl = templateName.toLowerCase();
        if (tl.equals("part") || tl.equals("box") || tl.equals("sphere") || tl.equals("cylinder")) {
            return null; // fall through to ANTLR
        }

        Template template = TemplateRegistry.instance().get(templateName);
        if (template == null) return null; // not a template, fall through

        String instanceName = m.group(3) != null ? m.group(3) : m.group(4);
        String paramsStr = m.group(5) != null ? m.group(5).trim() : "";

        // Check for name collision
        if (scene.getAssembly(instanceName) != null) {
            return "Assembly '" + instanceName + "' already exists.";
        }

        // Extract optional relative placement ("to left of a [gap N]")
        String[] remainingParams = {paramsStr};
        RelativePlacement relativePlacement = extractRelativePlacement(paramsStr, remainingParams);
        paramsStr = remainingParams[0];

        // Extract optional "at x,y,z" placement from the params string
        float[] placement = {0, 0, 0};
        paramsStr = extractPlacement(paramsStr, placement);

        // Parse parameter values
        Map<String, Double> vars = parseParamValues(template, paramsStr);
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
        try {
            for (String bodyLine : template.getBodyLines()) {
                // Skip comments and blank lines
                String trimmedLine = bodyLine.trim();
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) continue;

                String resolved = resolveTemplateLine(bodyLine, instanceName, vars);
                String result = execute(resolved);
                output.append("  ").append(result).append("\n");

                // Track created parts for undo
                // Find the part that was just created by checking the last part name
                String partName = extractCreatedPartName(resolved, instanceName);
                if (partName != null) {
                    Part part = scene.getPart(partName);
                    if (part != null) {
                        assembly.addPart(part);
                        createdParts.add(part);
                    }
                }
            }
        } finally {
            suppressUndo = false;
        }

        // Normalize assembly position: shift all parts so the bounding box min
        // is at the target placement (default 0,0,0). This ensures the assembly's
        // reference point is consistent with the "move" command.
        {
            com.jme3.math.Vector3f target = new com.jme3.math.Vector3f(
                    units.toMm(placement[0]),
                    units.toMm(placement[1]),
                    units.toMm(placement[2]));
            com.jme3.math.Vector3f currentOrigin = assembly.getBoundingBoxMin(
                    pn -> { var r = scene.getObjectRecord(pn); return r != null ? r.position() : null; },
                    pn -> { var r = scene.getObjectRecord(pn); return r != null ? r.size() : null; });
            com.jme3.math.Vector3f delta = target.subtract(currentOrigin);
            if (delta.lengthSquared() > 0.001f) {
                for (Part part : createdParts) {
                    SceneManager.ObjectRecord rec = scene.getObjectRecord(part.getName());
                    if (rec != null) {
                        scene.moveObject(part.getName(), rec.position().add(delta));
                    }
                }
            }
        }
        boolean hasPlacement = placement[0] != 0 || placement[1] != 0 || placement[2] != 0;

        scene.registerAssembly(assembly);

        // Apply relative placement if specified (after registration so we can use assembly bbox)
        String posStr = "";
        if (relativePlacement != null) {
            String refName = relativePlacement.referenceName();
            Assembly refAssembly = scene.getAssembly(refName);
            SceneManager.ObjectRecord refRec = scene.getObjectRecord(refName);
            if (refAssembly == null && refRec == null) {
                // Still return success but warn
                posStr = " (warning: reference '" + refName + "' not found, placed at origin)";
            } else {
                com.jme3.math.Vector3f[] refBBox;
                if (refAssembly != null) {
                    var pl = (java.util.function.Function<String, com.jme3.math.Vector3f>)
                            (String pn) -> { var r = scene.getObjectRecord(pn); return r != null ? r.position() : null; };
                    var sl = (java.util.function.Function<String, com.jme3.math.Vector3f>)
                            (String pn) -> { var r = scene.getObjectRecord(pn); return r != null ? r.size() : null; };
                    refBBox = new com.jme3.math.Vector3f[]{
                            refAssembly.getBoundingBoxMin(pl, sl),
                            refAssembly.getBoundingBoxMax(pl, sl)};
                } else {
                    refBBox = new com.jme3.math.Vector3f[]{refRec.position(), refRec.position().add(refRec.size())};
                }

                var pl2 = (java.util.function.Function<String, com.jme3.math.Vector3f>)
                        (String pn) -> { var r = scene.getObjectRecord(pn); return r != null ? r.position() : null; };
                var sl2 = (java.util.function.Function<String, com.jme3.math.Vector3f>)
                        (String pn) -> { var r = scene.getObjectRecord(pn); return r != null ? r.size() : null; };
                com.jme3.math.Vector3f[] srcBBox = new com.jme3.math.Vector3f[]{
                        assembly.getBoundingBoxMin(pl2, sl2),
                        assembly.getBoundingBoxMax(pl2, sl2)};

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
        } else if (hasPlacement) {
            posStr = String.format(" at (%.2f, %.2f, %.2f)", placement[0], placement[1], placement[2]);
        }

        pushAction(new CreateTemplateAction(scene, instanceName, templateName, createdParts));

        return "Created " + templateName + " '" + instanceName + "' (" + createdParts.size() + " parts)" + posStr + ":\n"
                + output.toString().stripTrailing();
    }

    /**
     * Resolve a template body line: substitute $variables, evaluate arithmetic,
     * and prefix part names with the instance name.
     */
    private String resolveTemplateLine(String line, String instanceName, Map<String, Double> vars) {
        // Prefix part names: "left-side" → "instanceName/left-side"
        String resolved = prefixPartNames(line, instanceName);

        // Substitute variables and evaluate arithmetic
        resolved = ExpressionEvaluator.substituteInLine(resolved, vars);

        return resolved;
    }

    /**
     * Prefix quoted part names with the instance name, but NOT material names.
     * A quoted string is a part name unless it follows "material".
     */
    private String prefixPartNames(String line, String instanceName) {
        // Find all quoted strings and prefix them unless preceded by "material"
        Pattern quoted = Pattern.compile("(\\bmaterial\\s+)?\"([^\"]+)\"");
        Matcher m = quoted.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (m.group(1) != null) {
                // Preceded by "material" — leave the quoted string alone
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                // Part name — prefix with instance name
                m.appendReplacement(sb, Matcher.quoteReplacement("\"" + instanceName + "/" + m.group(2) + "\""));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Extract the part name from a resolved create/rotate command. */
    private String extractCreatedPartName(String line, String instanceName) {
        // Look for create part "name" pattern
        Matcher m = Pattern.compile("(?i)create\\s+part\\s+\"([^\"]+)\"").matcher(line);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Extract "at x,y,z" or "@ x,y,z" from a params string.
     * Populates the placement array and returns the params string with the at clause removed.
     */
    private String extractPlacement(String paramsStr, float[] placement) {
        // Match: at/@ followed by three comma-separated numbers
        Pattern atPattern = Pattern.compile(
                "(?i)(?:at|@)\\s+(-?[0-9]*\\.?[0-9]+)\\s*,\\s*(-?[0-9]*\\.?[0-9]+)\\s*,\\s*(-?[0-9]*\\.?[0-9]+)");
        Matcher m = atPattern.matcher(paramsStr);
        if (m.find()) {
            placement[0] = Float.parseFloat(m.group(1));
            placement[1] = Float.parseFloat(m.group(2));
            placement[2] = Float.parseFloat(m.group(3));
            // Remove the at clause from the params string
            return (paramsStr.substring(0, m.start()) + paramsStr.substring(m.end())).trim();
        }
        return paramsStr;
    }

    /**
     * Extract "to left/right/behind/in front/above/below of <name> [gap N]" from params.
     * Returns the relative placement info or null if not found.
     */
    private record RelativePlacement(String direction, String referenceName, float gapUnits) {}

    private RelativePlacement extractRelativePlacement(String paramsStr, String[] remaining) {
        Pattern relPattern = Pattern.compile(
                "(?i)to\\s+(left|right|behind|in[- ]front|above|below)\\s+of\\s+" +
                "(?:\"([^\"]+)\"|([a-zA-Z_][a-zA-Z0-9_-]*))" +
                "(?:\\s+gap\\s+(-?[0-9]*\\.?[0-9]+))?");
        Matcher m = relPattern.matcher(paramsStr);
        if (m.find()) {
            String dir = m.group(1).toLowerCase();
            String ref = m.group(2) != null ? m.group(2) : m.group(3);
            float gap = m.group(4) != null ? Float.parseFloat(m.group(4)) : 0;
            remaining[0] = (paramsStr.substring(0, m.start()) + paramsStr.substring(m.end())).trim();
            return new RelativePlacement(dir, ref, gap);
        }
        remaining[0] = paramsStr;
        return null;
    }

    /** Common parameter name aliases. */
    private static final Map<String, String> PARAM_ALIASES = Map.of(
            "w", "width",
            "h", "height",
            "d", "depth",
            "l", "length",
            "mat", "material",
            "gr", "grain",
            "sz", "size"
    );

    /** Resolve a parameter name alias to its canonical form. */
    private static String resolveParamAlias(String name) {
        return PARAM_ALIASES.getOrDefault(name, name);
    }

    /**
     * Parse "param1 val1 param2 val2" into a variable map.
     * Resolves aliases using: (1) the template's own declared aliases, then
     * (2) the global alias table as fallback.
     */
    private Map<String, Double> parseParamValues(Template template, String paramsStr) {
        List<String> paramNames = template.getParamNames();
        Map<String, Double> vars = new LinkedHashMap<>();

        if (paramsStr.isEmpty() && !paramNames.isEmpty()) {
            return null; // missing params
        }

        // Split into tokens and resolve aliases
        String[] tokens = paramsStr.trim().split("\\s+");
        Map<String, String> rawValues = new LinkedHashMap<>();
        for (int i = 0; i < tokens.length - 1; i += 2) {
            String key = tokens[i].toLowerCase();
            // Try template-specific alias first, then global alias, then use as-is
            String resolved = template.resolveParam(key);
            if (resolved == null) {
                resolved = resolveParamAlias(key);
            }
            rawValues.put(resolved, tokens[i + 1]);
        }

        // Validate all declared params are provided
        for (String param : paramNames) {
            String val = rawValues.get(param);
            if (val == null) {
                return null; // missing param
            }
            try {
                vars.put(param, Double.parseDouble(val));
            } catch (NumberFormatException e) {
                return null; // non-numeric value
            }
        }

        return vars;
    }

    // ======================== Run / Script ========================

    private String handleRun(String argument) {
        Path file;
        if (argument.isEmpty()) {
            if (fileChooser == null) {
                return "No file chooser available.";
            }
            file = fileChooser.get();
            if (file == null) {
                return "Cancelled.";
            }
        } else {
            String path = argument;
            if (path.startsWith("\"") && path.endsWith("\"") && path.length() >= 2) {
                path = path.substring(1, path.length() - 1);
            }
            file = Path.of(path);
        }

        if (!Files.exists(file)) {
            return "File not found: " + file;
        }

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
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String result = execute(trimmed);
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
        Path startupDir = Path.of(System.getProperty("user.home"), ".jigger");
        Path startupFile = startupDir.resolve("startup.jigs");
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

    private static String lowercaseOutsideQuotes(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        boolean inQuote = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                sb.append(c);
            } else if (inQuote) {
                sb.append(c);
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
