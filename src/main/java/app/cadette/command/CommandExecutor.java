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
import app.cadette.model.*;
import com.jme3.math.Vector3f;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final List<Consumer<String>> themeChangeListeners = new ArrayList<>();
    @Getter private String themeName = "dark";
    /** Installed by CadetteApp once Lemur + the registry are up. Used
     *  by the {@code set theme}/{@code show themes} visitor paths to
     *  validate names against the actually-loaded set. May be null
     *  during early init or in test contexts that don't construct a UI. */
    @Getter @Setter private app.cadette.theme.ThemeRegistry themeRegistry;

    private final Deque<UndoableAction> undoStack = new ArrayDeque<>();
    private final Deque<UndoableAction> redoStack = new ArrayDeque<>();

    /**
     * Session-scoped registry of named shapes declared via the
     * {@code shape <name> polygon|spline ...} command. Reused at
     * {@code cut <part> shape <name> at x, y} call sites.
     *
     * <p>Strict script-locality (separate registry per script) is a
     * follow-up; today's behaviour is session-wide. See
     * {@code project_shape_library_backlog.md}.
     */
    private final Map<String, Shape> shapeRegistry = new LinkedHashMap<>();

    public void registerShape(String name, Shape shape) {
        shapeRegistry.put(name, shape);
    }

    public Shape lookupShape(String name) {
        return shapeRegistry.get(name);
    }

    /**
     * Stack of variable scopes. Innermost scope is at the top; VAR_REF
     * lookups walk the stack inner-to-outer. The bottom-most scope is the
     * always-on global scope (pushed in the constructor) that holds
     * top-level `let` bindings; templates and for-loops push their own
     * scopes on top, which auto-pop when the block ends.
     */
    private final Deque<Map<String, Double>> varScopes = new ArrayDeque<>();

    /** Push a new variable scope. Caller must pop exactly once. */
    void pushVarScope(Map<String, Double> scope) {
        varScopes.push(new LinkedHashMap<>(scope));
    }

    void popVarScope() {
        varScopes.pop();
    }

    /** Mutate the innermost scope — used by for-loop to update loop-var value each iteration. */
    void setInCurrentScope(String name, double value) {
        if (varScopes.isEmpty()) throw new IllegalStateException("No active variable scope");
        varScopes.peek().put(name, value);
    }

    /** Resolve a variable name, searching innermost scope outward. Null if unknown. */
    Double resolveVar(String name) {
        for (Map<String, Double> scope : varScopes) {
            Double v = scope.get(name);
            if (v != null) return v;
        }
        return null;
    }

    /** Callback to open a file chooser dialog (set by CadetteApp). Returns null if cancelled. */
    @Setter private Supplier<Path> fileChooser;

    /** Callback to open a save-file dialog. Accepts a description and extensions. Returns null if cancelled. */
    @Setter private BiFunction<String, String[], Path> saveFileChooser;

    /** Supplies a snapshot of the command output log (set by the UI). Used
     *  by the `save output as` command; null when running headless. */
    @Setter private Supplier<List<String>> outputLogSupplier;

    // -- REPL line accumulation --
    // The REPL feeds input one line at a time. A multi-line construct
    // (`for`/`if`/`define`) isn't complete until its `end` arrives, so lines
    // accumulate here until they parse as a complete `program`. ANTLR — not
    // string matching — decides when the buffer is complete: see parseProgram.
    private final List<String> pendingLines = new ArrayList<>();

    // Suppress individual undo pushes during template instantiation or script runs
    private boolean suppressUndo = false;
    // Collect actions during script run (null when not collecting)
    private List<UndoableAction> collectingActions = null;

    // When non-null, the loader is reading a .cds file and any template
    // registered via `registerDefine` gets this string tagged as its source.
    private String currentLoadingSource = null;

    // Template-expansion context: when non-null, the visitor prefixes object-name
    // references with "<prefix>/" so a body-line "left-side" becomes "K/left-side".
    @Getter(AccessLevel.PACKAGE) private String currentInstancePrefix = null;

    // Source string of the template currently being instantiated (mirrors
    // currentInstancePrefix). When non-null, visitors that record source
    // attribution (e.g. visitJoinCommand) prefer this over currentLoadingSource
    // so source lines stay stable across multiple instantiations of the same
    // template — important for source-location-keyed dedup of validation
    // messages. See project_joint_validation_dedup_backlog.md.
    private String currentInstanceTemplateSource = null;

    // Ordered list of namespace prefixes searched when a bare template name
    // (no slashes) is referenced. Mutated by the `using` command. Script-scoped:
    // see runScriptIsolated.
    private List<String> usingNamespaces = new ArrayList<>();

    // User-configured prefix to the script search path, mutated by
    // `set script_path`. The defaults (~/.cadette/scripts/, ./scripts/) are
    // always appended after the user entries — see resolveScriptPath. Held
    // as an immutable list so getters can hand it back without copying.
    private List<Path> userScriptPath = List.of();
    // Set by visitor's visitCreatePartCommand after each part create, consumed by
    // instantiateTemplate to collect the parts belonging to the new assembly.
    @Getter(AccessLevel.PACKAGE) @Setter(AccessLevel.PACKAGE) private String lastCreatedPartName = null;

    public CommandExecutor(SceneManager scene) {
        this.scene = scene;
        // Always-on global scope so top-level `let $x = ...` has somewhere to
        // bind. Template/for-loop scopes push on top of this; lookups walk
        // outward to the global as a fallback (matches how shells handle
        // env vars vs locals).
        this.varScopes.push(new LinkedHashMap<>());

        // Restore the active theme from the previous session. setThemeName
        // would fire listeners (which aren't attached yet here) — assign the
        // field directly so init order stays clean. CadetteApp picks this up
        // via getThemeName() at first applyTheme.
        String saved = loadThemePreference();
        if (saved != null && !saved.isBlank()) {
            this.themeName = saved;
        }
    }

    private static String loadThemePreference() {
        return app.cadette.prefs.Preferences.instance()
                .getString(null, "ui", "theme");
    }

    private static void saveThemePreference(String name) {
        app.cadette.prefs.Preferences.instance()
                .set(name == null ? "" : name, "ui", "theme");
    }

    /**
     * Effective source attribution for an element being created right now.
     * Prefers the active template's source (so joints inside a template
     * body keep a stable source across instantiations), falling back to the
     * loading-script source, then to "interactive".
     */
    public String effectiveSource() {
        if (currentInstanceTemplateSource != null) return currentInstanceTemplateSource;
        if (currentLoadingSource != null) return currentLoadingSource;
        return "interactive";
    }

    // Hand-coded: fires change listeners on write. @Setter can't express dispatch.
    public void setUnits(UnitSystem units) {
        this.units = units;
        unitChangeListeners.forEach(l -> l.accept(units));
    }

    public void addUnitChangeListener(Consumer<UnitSystem> listener) {
        unitChangeListeners.add(listener);
    }

    // Hand-coded: fires change listeners on write. @Setter can't express dispatch.
    public void setDefaultMaterial(Material material) {
        this.defaultMaterial = material;
        materialChangeListeners.forEach(l -> l.accept(material));
    }

    public void addMaterialChangeListener(Consumer<Material> listener) {
        materialChangeListeners.add(listener);
    }

    /** Switch the active theme. Fires {@link #themeChangeListeners} so the
     *  UI shell can apply the new theme to Lemur's Styles. Persists the
     *  choice to {@code ~/.cadette/active_theme} so subsequent launches
     *  restore it. Doesn't validate the name here — callers
     *  (CommandVisitor) check against the loaded registry first. */
    public void setThemeName(String themeName) {
        this.themeName = themeName;
        saveThemePreference(themeName);
        themeChangeListeners.forEach(l -> l.accept(themeName));
    }

    public void addThemeChangeListener(Consumer<String> listener) {
        themeChangeListeners.add(listener);
    }

    /**
     * Open a save-file dialog. Returns null if cancelled or no chooser is available.
     */
    public Path chooseSaveFile(String description, String... extensions) {
        if (saveFileChooser == null) return null;
        return saveFileChooser.apply(description, extensions);
    }

    /** Snapshot of the command output log, or empty when no UI is attached
     *  (e.g. headless script runs). */
    public List<String> getOutputLog() {
        return outputLogSupplier == null ? List.of() : outputLogSupplier.get();
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

    /**
     * Execute one REPL submission. The REPL feeds input a line at a time, so
     * a `for`/`if`/`define` block arrives across several calls — lines
     * accumulate in {@code pendingLines} until they parse as a complete
     * {@code program}. ANTLR decides completeness (see {@link #parseProgram});
     * there is no keyword string-matching here.
     */
    public String execute(String input) {
        pendingLines.add(input);
        ParseOutcome outcome = parseProgram(String.join("\n", pendingLines));
        switch (outcome.status()) {
            case INCOMPLETE -> {
                // An open for/if/define block — keep buffering until its `end`.
                return "";
            }
            case ERROR -> {
                pendingLines.clear();
                return outcome.errors() + "\nType 'help' for usage.";
            }
            default -> {
                pendingLines.clear();
                try {
                    return executeProgram(outcome.program());
                } catch (Exception e) {
                    return "Error: " + e.getMessage() + "\nType 'help' for usage.";
                }
            }
        }
    }

    // ======================== Program parsing ========================

    private enum ParseStatus { OK, INCOMPLETE, ERROR }

    /**
     * Result of parsing accumulated input as a {@code program}. INCOMPLETE
     * means the input ended inside an unclosed block and more lines are
     * expected; ERROR carries a formatted message; OK carries the tree.
     */
    private record ParseOutcome(ParseStatus status,
                                CadetteCommandParser.ProgramContext program,
                                String errors) {
        static ParseOutcome ok(CadetteCommandParser.ProgramContext p) {
            return new ParseOutcome(ParseStatus.OK, p, null);
        }
        static ParseOutcome incomplete() {
            return new ParseOutcome(ParseStatus.INCOMPLETE, null, null);
        }
        static ParseOutcome error(String e) {
            return new ParseOutcome(ParseStatus.ERROR, null, e);
        }
    }

    /**
     * Parse {@code source} as a {@code program}. The grammar is the sole
     * authority on block structure: if the first syntax error is at EOF while
     * the parser is still inside a {@code forBlock}/{@code ifBlock}/{@code
     * defineBlock}, the input is INCOMPLETE (needs more lines); any other
     * error is a genuine syntax error, reported fail-fast at its position.
     */
    private ParseOutcome parseProgram(String source) {
        CadetteCommandLexer lexer = new CadetteCommandLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        CadetteCommandParser parser = new CadetteCommandParser(tokens);
        parser.removeErrorListeners();

        String[] sourceLines = source.split("\n", -1);
        StringBuilder errors = new StringBuilder();
        boolean[] incomplete = {false};
        boolean[] sawError = {false};
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPos, String msg, RecognitionException e) {
                if (sawError[0]) return;   // only the first error is meaningful
                sawError[0] = true;
                Token tok = offendingSymbol instanceof Token t ? t : null;
                boolean atEof = tok != null && tok.getType() == Token.EOF;
                List<String> ruleStack = ((Parser) recognizer).getRuleInvocationStack();
                boolean insideBlock = ruleStack.contains("forBlock")
                        || ruleStack.contains("ifBlock")
                        || ruleStack.contains("defineBlock");
                if (atEof && insideBlock) {
                    incomplete[0] = true;
                    return;
                }
                String src = (line >= 1 && line <= sourceLines.length)
                        ? sourceLines[line - 1] : "";
                // A single-line submission (the common REPL case) needs no
                // line number — column alone is unambiguous.
                String where = sourceLines.length > 1
                        ? "line " + line + ", column " + (charPos + 1)
                        : "column " + (charPos + 1);
                errors.append("Parse error at ").append(where)
                        .append(": ").append(msg)
                        .append(pointerLine(src, charPos));
            }
        });

        CadetteCommandParser.ProgramContext program = parser.program();
        if (incomplete[0]) return ParseOutcome.incomplete();
        if (!errors.isEmpty()) return ParseOutcome.error(errors.toString());
        return ParseOutcome.ok(program);
    }

    /**
     * Walk a parsed program, executing each top-level statement in order.
     * A {@code defineBlock} registers a template; any other statement (a
     * command, or an {@code if}/{@code for} block) runs via the visitor.
     */
    String executeProgram(CadetteCommandParser.ProgramContext program) {
        CommandVisitor visitor = new CommandVisitor(this, scene);
        StringBuilder output = new StringBuilder();
        for (var tls : program.topLevelStatement()) {
            String result = tls.defineBlock() != null
                    ? registerDefine(tls.defineBlock())
                    : visitor.executeStatement(tls.statement());
            if (result != null && !result.isEmpty()) {
                if (!output.isEmpty()) output.append("\n");
                output.append(result);
            }
        }
        return output.toString();
    }

    // ======================== Template Define ========================

    /**
     * Build and register a Template from a parsed {@code defineBlock}. The
     * whole block is parsed in one pass as part of the enclosing program, so
     * there is no recording mode — the parse tree is the template body.
     */
    String registerDefine(CadetteCommandParser.DefineBlockContext ctx) {
        String name = CommandVisitor.templateRefText(ctx.templateRef());
        List<String> paramNames = ctx.paramDecl().stream()
                .map(d -> d.paramName().get(0).getText().toLowerCase())
                .toList();
        Map<String, String> paramAliases = ctx.paramDecl().stream()
                .filter(d -> d.paramName().size() > 1)
                .collect(Collectors.toMap(
                        d -> d.paramName().get(1).getText().toLowerCase(),
                        d -> d.paramName().get(0).getText().toLowerCase(),
                        (a, b) -> b,
                        LinkedHashMap::new));
        // Defaults are stored as unevaluated ExpressionContext so they can
        // reference prior params at instantiation time (e.g. `height = $width * 2`).
        Map<String, CadetteCommandParser.ExpressionContext> paramDefaults =
                ctx.paramDecl().stream()
                        .filter(d -> d.expression() != null)
                        .collect(Collectors.toMap(
                                d -> d.paramName().get(0).getText().toLowerCase(),
                                CadetteCommandParser.ParamDeclContext::expression,
                                (a, b) -> b,
                                LinkedHashMap::new));
        List<String> bodyLines = extractDefineBodyLines(ctx);
        String src = currentLoadingSource != null ? currentLoadingSource : "interactive";

        Template template = new Template(name, paramNames, paramAliases,
                paramDefaults, bodyLines, ctx, src);
        TemplateRegistry.instance().register(template);

        return "Template '" + name + "' defined ("
                + bodyLines.size() + " lines, "
                + paramNames.size() + " params).";
    }

    /**
     * Recover the raw body lines of a define block — everything the user typed
     * between the header and {@code end define}, comments included — from the
     * original source, so {@code show template} can round-trip the definition.
     */
    private static List<String> extractDefineBodyLines(CadetteCommandParser.DefineBlockContext ctx) {
        int defineLine = ctx.getStart().getLine();
        int endLine = ctx.END().getSymbol().getLine();
        Token lastHeaderTok = ctx.paramDecl().isEmpty()
                ? ctx.templateRef().getStop()
                : ctx.paramDecl(ctx.paramDecl().size() - 1).getStop();
        int headerLine = lastHeaderTok.getLine();

        String blockText = ctx.getStart().getInputStream().getText(
                Interval.of(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex()));
        String[] blockLines = blockText.split("\n", -1);

        List<String> body = new ArrayList<>();
        for (int ln = headerLine + 1; ln < endLine; ln++) {
            int idx = ln - defineLine;
            if (idx >= 0 && idx < blockLines.length) {
                String trimmed = blockLines[idx].trim();
                if (!trimmed.isEmpty()) body.add(trimmed);
            }
        }
        return body;
    }

    /**
     * Build a clang/rust-style "  <source-line>\n  <spaces>^" pointer that
     * appended to a parse-error message visually marks the offending column.
     * Package-private so CommandVisitor's interpolation listener can share it.
     */
    static String pointerLine(String sourceLine, int charPos) {
        int safe = Math.max(0, Math.min(charPos, sourceLine.length()));
        return "\n  " + sourceLine + "\n  " + " ".repeat(safe) + "^";
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
                                Map<String, Double> rawParamValues) {
        TemplateResolution resolution = resolveTemplate(templateName);
        if (resolution.template == null) {
            return resolution.errorMessage;
        }
        Template template = resolution.template;
        // Use the canonical (resolved) name for downstream metadata so assemblies
        // record the fully-qualified template name even when invoked with a bare alias.
        templateName = template.getName();

        if (scene.getAssembly(instanceName) != null) {
            return "Assembly '" + instanceName + "' already exists.";
        }

        Map<String, Double> vars = resolveParamValues(template, rawParamValues);
        if (vars == null) {
            return "Usage: create " + templateName + " \"name\" "
                    + String.join(" ", template.getParamNames().stream()
                        .map(p -> p + " <value>").toList());
        }

        // Implicit variables (all in current display units).
        // Note: $mm was retired when unit-suffix literals (100mm / 5cm / …)
        // landed — they replace the old `$mm * N` idiom with a cleaner
        // portable literal. $thickness / $back_thickness still make sense
        // as domain-aware magic values rather than conversion factors.

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
        String previousTemplateSource = currentInstanceTemplateSource;
        currentInstancePrefix = instanceName;
        currentInstanceTemplateSource = template.getSource();
        pushVarScope(vars);
        try {
            // Tree-walking instantiation: the body parsed once at define time;
            // each instantiation walks the tree with the active scope stack.
            // Loop iterations and if-branches push/pop their own scopes on top.
            CadetteCommandParser.DefineBlockContext parsed = template.getParsedBody();
            if (parsed != null) {
                CommandVisitor visitor = new CommandVisitor(this, scene);
                visitor.instantiateTemplateBody(parsed, assembly, createdParts, output);
            }
        } finally {
            popVarScope();
            suppressUndo = false;
            currentInstancePrefix = previousPrefix;
            currentInstanceTemplateSource = previousTemplateSource;
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
                shiftParts(createdParts, delta);
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
                createdParts.reversed().forEach(part -> scene.deleteObject(part.getName()));
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
                    shiftParts(createdParts, moveDelta);
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
     * Missing params fall back to their declared default expression (if any) —
     * defaults are evaluated left-to-right with earlier params already in scope,
     * so {@code params width, height=$width*2} works the way it reads.
     * Returns null if any declared param is missing and has no default.
     */
    private Map<String, Double> resolveParamValues(Template template, Map<String, Double> rawValues) {
        List<String> paramNames = template.getParamNames();
        // All params required and no defaults? Early exit.
        if (rawValues.isEmpty() && !paramNames.isEmpty()
                && paramNames.stream().noneMatch(template::hasDefault)) {
            return null;
        }

        Map<String, Double> canonical = rawValues.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> canonicalParamKey(template, e.getKey()),
                        Map.Entry::getValue,
                        (a, b) -> b,   // last-wins on duplicate resolved keys
                        LinkedHashMap::new));

        // Evaluate defaults with the partially-bound scope visible — push a
        // fresh scope, bind each param in order, evaluate missing ones via
        // their declared default expression. Drain into `vars` at the end.
        CommandVisitor visitor = new CommandVisitor(this, scene);
        pushVarScope(new LinkedHashMap<>());
        try {
            for (String param : paramNames) {
                Double val = canonical.get(param);
                if (val == null) {
                    CadetteCommandParser.ExpressionContext defaultExpr =
                            template.getParamDefaults().get(param);
                    if (defaultExpr == null) return null;
                    val = visitor.evaluateExpression(defaultExpr);
                }
                setInCurrentScope(param, val);
            }
            Map<String, Double> vars = new LinkedHashMap<>();
            for (String p : paramNames) vars.put(p, resolveVar(p));
            return vars;
        } finally {
            popVarScope();
        }
    }

    /** Canonicalize a user-supplied param key via the template's own aliases, then global aliases. */
    private static String canonicalParamKey(Template template, String rawKey) {
        String lower = rawKey.toLowerCase();
        String resolved = template.resolveParam(lower);
        return resolved != null ? resolved : PARAM_ALIASES.getOrDefault(lower, lower);
    }

    // ======================== Template Resolution / Using ========================

    /** Result of resolving a possibly-bare template name. Exactly one field is non-null. */
    record TemplateResolution(Template template, String errorMessage) {
        static TemplateResolution found(Template t) { return new TemplateResolution(t, null); }
        static TemplateResolution error(String msg) { return new TemplateResolution(null, msg); }
    }

    /**
     * Resolve a template reference to a registry entry.
     * A qualified name (contains '/') is looked up exactly.
     * A bare name is tried against each using-namespace in order, then
     * against the registry for a unique last-segment match.
     */
    TemplateResolution resolveTemplate(String name) {
        TemplateRegistry registry = TemplateRegistry.instance();

        // Qualified names (containing '/') are always exact-match — no fallbacks.
        if (name.contains("/")) {
            Template t = registry.get(name);
            return t != null ? TemplateResolution.found(t)
                    : TemplateResolution.error("Template '" + name + "' not found.");
        }

        // Bare name: `using` namespaces take priority — the whole point of
        // `using foo` is that `create bar` should prefer `foo/bar` even if a
        // template literally named `bar` also happens to exist.
        Optional<Template> usingHit = usingNamespaces.stream()
                .map(ns -> registry.get(ns + "/" + name))
                .filter(Objects::nonNull)
                .findFirst();
        if (usingHit.isPresent()) return TemplateResolution.found(usingHit.get());

        // No using namespace matched — exact bare match is next.
        Template exact = registry.get(name);
        if (exact != null) return TemplateResolution.found(exact);

        // Fall back to registry-wide uniqueness of the last path segment.
        String lowered = name.toLowerCase();
        List<Template> matches = registry.getAll().stream()
                .filter(reg -> lastSegment(reg.getName().toLowerCase()).equals(lowered))
                .toList();
        if (matches.size() == 1) return TemplateResolution.found(matches.get(0));
        if (matches.isEmpty()) {
            return TemplateResolution.error("Template '" + name + "' not found.");
        }
        String ambiguous = "Template '" + name + "' is ambiguous:\n"
                + matches.stream()
                        .map(m -> "  " + m.getName())
                        .collect(Collectors.joining("\n"))
                + "\nUse the fully-qualified name or add a `using` statement.";
        return TemplateResolution.error(ambiguous);
    }

    /** Last slash-separated segment of a path-like template name, or the whole string. */
    private static String lastSegment(String pathLike) {
        int slash = pathLike.lastIndexOf('/');
        return slash >= 0 ? pathLike.substring(slash + 1) : pathLike;
    }

    /** Called by the visitor to append to the using-namespace list. */
    String addUsingNamespace(String namespace) {
        String trimmed = namespace.trim();
        // Strip a trailing slash so `using standard/` behaves like `using standard`.
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isEmpty()) return "Empty namespace.";
        if (!usingNamespaces.contains(trimmed)) {
            usingNamespaces.add(trimmed);
        }
        return "Using '" + trimmed + "' for bare template names.";
    }

    /** Snapshot the using list — used to isolate user-invoked script scope. */
    List<String> snapshotUsingNamespaces() {
        return new ArrayList<>(usingNamespaces);
    }

    /** Restore a previously snapshotted using list. */
    void restoreUsingNamespaces(List<String> snapshot) {
        usingNamespaces = new ArrayList<>(snapshot);
    }

    /** Clear all using-namespaces. Exposed for tests to isolate REPL state. */
    public void clearUsingNamespaces() {
        usingNamespaces.clear();
    }

    // ======================== Template Loading ========================

    /**
     * Collected loader diagnostics (override warnings, parse errors, rejected
     * files, etc.). Populated as side-effects of template loading so the UI
     * can surface them after startup. Also echoed to stderr for CLI / test use.
     */
    private final List<String> loaderMessages = new ArrayList<>();

    /** Append a loader diagnostic: collected for the UI, echoed to stderr. */
    private void recordLoaderMessage(String msg) {
        loaderMessages.add(msg);
        System.err.println(msg);
    }

    /** Return and clear accumulated loader diagnostics. The UI drains these at startup. */
    public List<String> drainLoaderMessages() {
        List<String> out = List.copyOf(loaderMessages);
        loaderMessages.clear();
        return out;
    }

    /**
     * Load all templates from classpath ({@code resources/templates/}) and from
     * {@code ~/.cadette/templates/}. Filesystem templates override classpath
     * entries of the same name, with a warning. Called once at application startup.
     */
    public void loadTemplates() {
        loadBundledTemplates();
        Path fsRoot = Path.of(System.getProperty("user.home"), ".cadette", "templates");
        if (Files.isDirectory(fsRoot)) {
            loadTemplatesFromFilesystem(fsRoot);
        }
        validateTemplateReferences();
    }

    /**
     * After loading, walk every template's stored parse tree and flag
     * {@code create <template>} references that resolve nowhere in the
     * registry. Syntax errors are caught earlier, when the define block is
     * parsed — broken templates never register, so everything we see here is
     * a well-formed parse tree.
     *
     * ParseTreeWalker recurses into nested ifBlock / forBlock bodies for
     * free, so refs inside control flow are validated alongside top-level
     * ones. {@code create part "..."} parses as a different production
     * (createPartCommand) and doesn't trigger this listener.
     *
     * Reference resolution checks the whole registry (no {@code using}
     * context); we only flag references that have zero possible matches —
     * ambiguity is a runtime concern the user disambiguates with {@code using}.
     */
    public void validateTemplateReferences() {
        TemplateRegistry registry = TemplateRegistry.instance();
        for (Template t : registry.getAll()) {
            if (t.getParsedBody() == null) continue;  // legacy test fixtures
            ParseTreeWalker.DEFAULT.walk(new CadetteCommandParserBaseListener() {
                @Override
                public void enterCreateTemplateCommand(
                        CadetteCommandParser.CreateTemplateCommandContext ctx) {
                    String ref = CommandVisitor.templateRefText(ctx.templateRef());
                    if (!referenceResolvesAnywhere(ref, registry)) {
                        int line = ctx.getStart().getLine();
                        recordLoaderMessage(locate(t, line)
                                + ": references unknown template '" + ref + "'.");
                    }
                }
            }, t.getParsedBody());
        }
    }

    private static String locate(Template owner, int lineNum) {
        String src = owner.getSource() != null ? " (from " + owner.getSource() + ")" : "";
        return "Template '" + owner.getName() + "' body line " + lineNum + src;
    }

    private static boolean referenceResolvesAnywhere(String ref, TemplateRegistry registry) {
        if (ref.contains("/")) return registry.get(ref) != null;
        String lowered = ref.toLowerCase();
        return registry.getAll().stream()
                .anyMatch(t -> lastSegment(t.getName().toLowerCase()).equals(lowered));
    }

    /**
     * Load only the bundled (classpath) templates, skipping user-local ones.
     * Tests use this so results don't depend on whatever the developer has
     * in {@code ~/.cadette/templates/}.
     */
    public void loadBundledTemplates() {
        loadTemplatesFromClasspath();
    }

    private void loadTemplatesFromClasspath() {
        URL rootUrl = CommandExecutor.class.getResource("/templates");
        if (rootUrl == null) return;
        URI uri;
        try {
            uri = rootUrl.toURI();
        } catch (Exception e) {
            recordLoaderMessage("Could not locate bundled templates: " + e.getMessage());
            return;
        }
        if ("jar".equals(uri.getScheme())) {
            try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
                loadTemplatesFromTree(fs.getPath("/templates"), /*isClasspath=*/true);
            } catch (IOException e) {
                recordLoaderMessage("Could not open bundled templates jar: " + e.getMessage());
            }
        } else {
            loadTemplatesFromTree(Path.of(uri), /*isClasspath=*/true);
        }
    }

    private void loadTemplatesFromFilesystem(Path root) {
        loadTemplatesFromTree(root, /*isClasspath=*/false);
    }

    /**
     * Load template files from an arbitrary filesystem directory. Files are
     * treated as user-provided (i.e. they override classpath entries with a
     * warning on stderr). Used by tests and reserved for a future `set path`
     * command. The directory must exist; this method does not create it.
     */
    public void loadTemplatesFromDirectory(Path root) {
        if (!Files.isDirectory(root)) return;
        loadTemplatesFromFilesystem(root);
    }

    private void loadTemplatesFromTree(Path root, boolean isClasspath) {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            // Sort inside the try-with-resources so the stream is still open.
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".cds"))
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                    .toList();
        } catch (IOException e) {
            recordLoaderMessage("Could not walk template root '" + root + "': " + e.getMessage());
            return;
        }
        files.forEach(file -> loadOneTemplateFile(root, file, isClasspath));
    }

    private void loadOneTemplateFile(Path root, Path file, boolean isClasspath) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        String expectedName = rel.substring(0, rel.length() - ".cds".length());
        String expectedKey = expectedName.toLowerCase();

        TemplateRegistry registry = TemplateRegistry.instance();
        Template existing = registry.get(expectedName);
        if (existing != null && !isClasspath) {
            recordLoaderMessage("Filesystem template '" + file + "' overrides classpath template '"
                    + expectedName + "'.");
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            recordLoaderMessage("Could not read template '" + file + "': " + e.getMessage());
            return;
        }

        // Snapshot the registry before the file runs so we can detect any
        // templates it registers and reject ones that don't match the filename.
        Set<String> keysBefore = registry.getAll().stream()
                .map(t -> t.getName().toLowerCase())
                .collect(Collectors.toSet());

        boolean prevSuppress = suppressUndo;
        String prevSource = currentLoadingSource;
        suppressUndo = true;
        currentLoadingSource = (isClasspath ? "classpath:" : "") + file.toString();
        try {
            // Parse the whole file as one program. A template file is normally
            // a single `define` block; whatever it contains, ANTLR validates it
            // in one pass and the loader surfaces any failure.
            ParseOutcome outcome = parseProgram(String.join("\n", lines));
            if (outcome.status() == ParseStatus.INCOMPLETE) {
                recordLoaderMessage("Template '" + file + "' did not end its define block.");
            } else if (outcome.status() == ParseStatus.ERROR) {
                recordLoaderMessage("Template '" + file + "': "
                        + outcome.errors().split("\n", 2)[0]);
            } else {
                try {
                    executeProgram(outcome.program());
                } catch (Exception e) {
                    recordLoaderMessage("Template '" + file + "': Error: " + e.getMessage());
                }
            }
        } finally {
            suppressUndo = prevSuppress;
            currentLoadingSource = prevSource;
        }

        // Reconcile what the file registered against its filename-derived name.
        // Rule: exactly one template may be registered per file, and its name
        // must match the filename. Anything else is a contract violation — roll
        // it back so the misnamed template doesn't silently shadow real ones.
        List<String> newlyRegistered = registry.getAll().stream()
                .map(Template::getName)
                .filter(n -> !keysBefore.contains(n.toLowerCase()))
                .toList();

        if (newlyRegistered.isEmpty()) {
            recordLoaderMessage("Template file '" + file
                    + "' did not define any template.");
            return;
        }

        boolean expectedPresent = newlyRegistered.stream()
                .anyMatch(n -> n.toLowerCase().equals(expectedKey));

        if (!expectedPresent) {
            recordLoaderMessage("Template file '" + file + "' defines "
                    + quoteList(newlyRegistered) + " but the filename implies '"
                    + expectedName + "'. Rejecting misnamed template(s).");
            newlyRegistered.forEach(registry::unregister);
            return;
        }

        if (newlyRegistered.size() > 1) {
            // One matched; others are extras. Keep the match, reject the rest.
            List<String> extras = newlyRegistered.stream()
                    .filter(n -> !n.toLowerCase().equals(expectedKey))
                    .toList();
            extras.forEach(registry::unregister);
            recordLoaderMessage("Template file '" + file + "' defined extra template(s) "
                    + quoteList(extras) + " beyond the expected '" + expectedName
                    + "'. One template per file — extras rejected.");
        }
    }

    private static String quoteList(List<String> names) {
        return names.stream()
                .map(n -> "'" + n + "'")
                .collect(Collectors.joining(", "));
    }

    // ======================== Run / Script ========================

    /** Called by the visitor when `run` has no path — prompt the user via the file chooser. */
    String runWithFileChooser() {
        if (fileChooser == null) return "No file chooser available.";
        Path file = fileChooser.get();
        if (file == null) return "Cancelled.";
        if (!Files.exists(file)) return "File not found: " + file;
        return runScriptIsolated(file);
    }

    /** Called by the visitor with an explicit path (already variable-expanded). */
    String runScriptPath(String path) {
        if (path.isEmpty()) return "Empty path.";
        Path file = resolveScriptPath(path);
        if (file == null) return "File not found: " + path;
        return runScriptIsolated(file);
    }

    /**
     * Resolve a `run`-command path against the script search path. For each
     * suffix variant (the input as-given, then with ".cds" appended if the
     * input doesn't already end in .cds), try each entry in order:
     *   1. Literal path (absolute, or relative to cwd).
     *   2. Each user-configured entry (set via {@code set script_path}).
     *   3. {@code ~/.cadette/scripts/} — user/installed scripts.
     *   4. {@code ./scripts/} — dev-tree project-root fallback.
     * Returns null if no combination matches.
     */
    private Path resolveScriptPath(String path) {
        boolean hasExt = path.toLowerCase().endsWith(".cds");
        List<String> candidates = hasExt ? List.of(path) : List.of(path, path + ".cds");
        for (String candidate : candidates) {
            Path literal = Path.of(candidate);
            if (Files.exists(literal)) return literal;
            for (Path dir : effectiveScriptSearchPath()) {
                Path resolved = dir.resolve(candidate);
                if (Files.exists(resolved)) return resolved;
            }
        }
        return null;
    }

    /**
     * Full ordered search path (excluding the literal-path attempt): user
     * entries first, then defaults. Used by {@link #resolveScriptPath} and
     * by status echoes that need to show what `run` would search.
     */
    public List<Path> effectiveScriptSearchPath() {
        List<Path> path = new ArrayList<>(userScriptPath);
        path.add(Path.of(System.getProperty("user.home"), ".cadette", "scripts"));
        path.add(Path.of("scripts"));
        return path;
    }

    /** User-configured prefix entries only (excludes defaults). */
    public List<Path> getUserScriptPath() {
        return userScriptPath;
    }

    /** Replace the user-configured prefix entries. Pass an empty list to clear. */
    public void setUserScriptPath(List<Path> entries) {
        this.userScriptPath = List.copyOf(entries);
    }

    /**
     * Run a script with script-scoped `using` isolation: any `using` statements
     * inside the script are undone when the script finishes. Used for the `run`
     * command. Startup scripts intentionally do NOT use this variant so that
     * startup-configured `using` namespaces persist for the session.
     */
    public String runScriptIsolated(Path file) {
        List<String> snapshot = snapshotUsingNamespaces();
        try {
            return runScript(file);
        } finally {
            restoreUsingNamespaces(snapshot);
        }
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

        // Shebang identifier check: warn if the first non-empty line is a
        // shebang that doesn't mention "cadette". The line itself is still
        // processed normally — it's a comment to the lexer and gets skipped.
        lines.stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .findFirst()
                .filter(first -> first.startsWith("#!") && !first.toLowerCase().contains("cadette"))
                .ifPresent(first -> output.append("  Warning: first-line shebang does not identify this as a ")
                        .append("CADette script — expected '#! cadette'. Proceeding anyway.\n"));

        // Parse the whole file as one program — ANTLR catches every syntax
        // error up front, so a malformed script runs nothing rather than
        // half-applying. (Runtime errors still surface per-statement below.)
        ParseOutcome outcome = parseProgram(String.join("\n", lines));
        if (outcome.status() == ParseStatus.INCOMPLETE) {
            output.append("  Error: unterminated block — missing `end for`, ")
                    .append("`end if`, or `end define`.\n")
                    .append("Done. (").append(file.getFileName()).append(")");
            return output.toString();
        }
        if (outcome.status() == ParseStatus.ERROR) {
            output.append("  ").append(outcome.errors().replace("\n", "\n  ")).append("\n")
                    .append("Done. (").append(file.getFileName()).append(")");
            return output.toString();
        }

        // Collect all individual actions into a single composite undo action
        List<UndoableAction> previousCollecting = collectingActions;
        collectingActions = new ArrayList<>();

        try {
            CommandVisitor visitor = new CommandVisitor(this, scene);
            for (var tls : outcome.program().topLevelStatement()) {
                int lineNum = tls.getStart().getLine();
                String echo = (lineNum >= 1 && lineNum <= lines.size())
                        ? lines.get(lineNum - 1).trim() : "";
                String result;
                try {
                    result = tls.defineBlock() != null
                            ? registerDefine(tls.defineBlock())
                            : visitor.executeStatement(tls.statement());
                } catch (Exception e) {
                    result = "Error: " + e.getMessage();
                }
                if (result != null && !result.isEmpty()) {
                    output.append(String.format("  [%d] %s → %s%n", lineNum, echo, result));
                }
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

    /** Translate each part by {@code delta}; parts without a scene record are skipped. */
    private void shiftParts(List<Part> parts, com.jme3.math.Vector3f delta) {
        parts.forEach(part -> {
            SceneManager.ObjectRecord rec = scene.getObjectRecord(part.getName());
            if (rec != null) scene.moveObject(part.getName(), rec.position().add(delta));
        });
    }

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
