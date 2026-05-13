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

package app.cadette.imgui;

import app.cadette.SceneManager;
import app.cadette.command.CommandExecutor;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.system.lwjgl.LwjglWindow;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorEnterCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallback;

import java.nio.DoubleBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws the ImGui overlay each frame and owns input dispatch for the
 * single-window CADette spike. Three dockable panels (Command, Log, Scene)
 * live inside a host DockSpace; the central area is a passthru node where
 * the 3D viewport shows through.
 *
 * <p>Input model: all GLFW mouse/keyboard callbacks are installed by this
 * state, replacing whatever jME3's default flyCam would have set up. Each
 * callback forwards to ImGui first; if ImGui doesn't want the event
 * (cursor outside any panel, no panel drag in progress), the event flows
 * to {@link ViewportInputHandler} which drives the {@link OrbitCamera}.
 * Gesture ownership is locked at button-down so drags that cross a panel
 * boundary don't get hijacked.
 */
public class ImGuiAppState extends BaseAppState {

    private final CommandExecutor executor;
    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();

    // Camera + input. Created in initialize once we have the jME3 camera.
    private OrbitCamera orbitCamera;
    private ViewportInputHandler viewportInput;
    private ImGuiCutSheetPanel cutSheetPanel;

    // GLFW callback references — held to prevent GC.
    private GLFWMouseButtonCallback mouseButtonCb;
    private GLFWCursorPosCallback   cursorPosCb;
    private GLFWScrollCallback      scrollCb;
    private GLFWKeyCallback         keyCb;
    private GLFWCharCallback        charCb;
    private GLFWWindowFocusCallback windowFocusCb;
    private GLFWCursorEnterCallback cursorEnterCb;

    // Command panel state.
    private final ImString commandInput = new ImString(512);
    private final List<String> commandLines = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();
    private boolean commandScrollPending = false;
    private boolean logScrollPending = false;
    private boolean focusCommandInput = true;

    // Command history (up/down arrow recall). Position points into the
    // history list; -1 means "currently editing fresh input." A saved
    // pre-recall snapshot lets down-arrow restore work-in-progress.
    private final List<String> commandHistory = new ArrayList<>();
    private int historyPos = -1;
    private String preRecallBuffer = "";

    // Default-layout flag — true on first run (no imgui.ini exists yet).
    private boolean buildDefaultLayout;

    // Selection state. Insertion-ordered so the UI can display in click order.
    private final Set<String> selected = new LinkedHashSet<>();
    /** When non-null, clicks select parts within this assembly (drilled in).
     *  When null, clicks on a part-in-an-assembly select the whole assembly. */
    private String openAssembly = null;
    /** Per-selection 3D wireframe highlights. Keyed by the same names that
     *  appear in {@link #selected}; rebuilt each frame so moves/resizes
     *  keep the highlight in place. */
    private final Map<String, com.jme3.scene.Geometry> highlightGeoms = new HashMap<>();
    /** Last part-Mesh reference seen for each highlighted part. When the
     *  part's mesh is rebuilt (after `cut`/`resize`/etc.), this ref no
     *  longer matches the current one and we know to rebuild the highlight. */
    private final Map<String, com.jme3.scene.Mesh> lastSeenPartMesh = new HashMap<>();

    /** History file (~/.cadette/cmd_history). Loaded once on startup,
     *  appended-and-trimmed on each command. Caps at HISTORY_MAX entries. */
    private static final Path HISTORY_FILE =
            Path.of(System.getProperty("user.home"), ".cadette", "cmd_history");
    private static final int HISTORY_MAX = 200;

    public ImGuiAppState(CommandExecutor executor) {
        this.executor = executor;
        loadHistory();
        appendCommand("*** CADette ImGui SPIKE — engine-UI + docking ***");
        appendCommand("");
        appendCommand("Viewport bindings:");
        appendCommand("  LMB click ......... select assembly (or part if drilled in)");
        appendCommand("  LMB double-click .. drill into assembly to pick parts");
        appendCommand("    shift = add, Cmd/Ctrl = toggle");
        appendCommand("  Esc ............... exit assembly / deselect");
        appendCommand("  click empty ....... deselect all (and exit assembly)");
        appendCommand("  RMB drag .......... orbit");
        appendCommand("  Shift + RMB drag .. pan");
        appendCommand("  Scroll ............ zoom (mouse wheel / trackpad two-finger)");
        appendCommand("  F / T / S / I ..... front / top / side / iso view");
        appendCommand("  R ................. frame all parts (reset if empty)");
        appendCommand("");
        appendCommand("Try: create part \"p\" length 600 material \"lumber-2x4-spf\"");
        appendCommand("");
        appendLog("Log panel — warnings and diagnostic info land here, not in Command.");
    }

    @Override
    public void stateAttached(com.jme3.app.state.AppStateManager mgr) {
        super.stateAttached(mgr);
        Application app = mgr.getApplication();
        if (app instanceof SceneManager scene) {
            scene.setWarningSink(this::appendLog);
        }
    }

    @Override
    protected void initialize(Application app) {
        System.err.println("[spike] AppState.initialize() entered on thread="
                + Thread.currentThread().getName());
        if (!(app.getContext() instanceof LwjglWindow window)) {
            throw new IllegalStateException(
                    "ImGui spike requires LWJGL3 Display mode (got "
                            + app.getContext().getClass().getName() + ")");
        }
        long handle = window.getWindowHandle();
        System.err.println("[spike] GLFW window handle = " + Long.toHexString(handle));

        // Disable jME3's default flyCam — we're owning input from scratch.
        // The flyCam is still attached after simpleInitApp ran; pull its
        // registration so it can't fight our GLFW callbacks.
        if (app instanceof SimpleApplication simple) {
            var flyCam = simple.getFlyByCamera();
            if (flyCam != null) {
                flyCam.setEnabled(false);
                flyCam.unregisterInput();
            }
            simple.getViewPort().setBackgroundColor(
                    new com.jme3.math.ColorRGBA(0.10f, 0.18f, 0.22f, 1f));  // dark teal
        }

        // Camera and viewport input. R reframes the whole scene if parts
        // exist; LMB-click raycasts against the scene to update selection;
        // Esc exits the currently-open assembly (or clears selection).
        orbitCamera = new OrbitCamera(app.getCamera());
        viewportInput = new ViewportInputHandler(
                orbitCamera, this::resetView, this::handleViewportClick, this::handleEscape);
        cutSheetPanel = new ImGuiCutSheetPanel((SceneManager) app, executor::getUnits);

        // ---- ImGui setup ------------------------------------------------
        ImGui.createContext();
        ImGui.getIO().setConfigFlags(
                ImGuiConfigFlags.NavEnableKeyboard | ImGuiConfigFlags.DockingEnable);

        // Persist layout under ~/.cadette/imgui.ini (set before any newFrame).
        Path iniPath = Path.of(System.getProperty("user.home"), ".cadette", "imgui.ini");
        try {
            Files.createDirectories(iniPath.getParent());
        } catch (Exception ignored) { /* fall back to in-project imgui.ini */ }
        buildDefaultLayout = !Files.exists(iniPath);
        ImGui.getIO().setIniFilename(iniPath.toAbsolutePath().toString());

        ImGui.styleColorsDark();
        // Pass false: we install GLFW callbacks ourselves below so we can
        // route to the viewport input handler in the same callback.
        imGuiGlfw.init(handle, false);
        // GLSL 330 instead of 150: Apple's GL 4.1 Core profile on Metal
        // exposes GLSL 4.10 and seems to silently fail to compile the
        // 1.50-versioned shaders ImGui ships. 330 is the most common
        // compat value used by imgui-java examples and matches Core profile.
        imGuiGl3.init("#version 330");

        // Bootstrap ImGui's focus state. imgui-java's installCallbacks
        // normally does this; we skip that path (init(handle, false)) so
        // we can route through our own callbacks. Without an explicit
        // focus event, ImGui treats the window as unfocused on macOS
        // (where no follow-up GLFWfocus event fires for an already-
        // focused window) and silently ignores all input.
        ImGui.getIO().addFocusEvent(true);

        // Also seed the current cursor position so ImGui's hover state
        // starts in a meaningful place (otherwise it's 0,0 until the
        // first cursor-move event).
        double[] cxArr = new double[1], cyArr = new double[1];
        GLFW.glfwGetCursorPos(handle, cxArr, cyArr);
        ImGui.getIO().addMousePosEvent((float) cxArr[0], (float) cyArr[0]);

        installCallbacks(handle);
        System.err.println("[spike] GLFW callbacks installed; initialize() complete");
    }

    /**
     * Single set of master GLFW callbacks. Each one calls ImGui's handler
     * first (so ImGui's hover state stays current and panels respond), then
     * dispatches to the viewport handler if ImGui doesn't want the event.
     *
     * <p>Note we don't chain to jME3's previous callbacks — replacing them
     * is intentional; the whole point is to take input ownership.
     */
    private void installCallbacks(long handle) {
        DoubleBuffer cx = org.lwjgl.BufferUtils.createDoubleBuffer(1);
        DoubleBuffer cy = org.lwjgl.BufferUtils.createDoubleBuffer(1);

        mouseButtonCb = new GLFWMouseButtonCallback() {
            @Override public void invoke(long window, int button, int action, int mods) {
                System.err.println("[spike] mouseButton callback: btn=" + button
                        + " action=" + action + " thread=" + Thread.currentThread().getName());
                imGuiGlfw.mouseButtonCallback(window, button, action, mods);
                if (action == GLFW.GLFW_PRESS && ImGui.getIO().getWantCaptureMouse()) {
                    return;  // panel owns this gesture
                }
                GLFW.glfwGetCursorPos(window, cx, cy);
                viewportInput.onMouseButton(button, action, mods, cx.get(0), cy.get(0));
            }
        };

        cursorPosCb = new GLFWCursorPosCallback() {
            int n = 0;
            @Override public void invoke(long window, double x, double y) {
                // Throttled: log first 5 cursor moves so we can confirm
                // they're firing without spamming.
                if (n < 5) {
                    System.err.println("[spike] cursorPos #" + n
                            + " (" + x + ", " + y + ") thread=" + Thread.currentThread().getName());
                    n++;
                }
                imGuiGlfw.cursorPosCallback(window, x, y);
                // Always forward — viewport handler ignores when no drag
                // is active. This keeps drags that wander over panels alive.
                viewportInput.onCursorPos(x, y);
            }
        };

        scrollCb = new GLFWScrollCallback() {
            @Override public void invoke(long window, double dx, double dy) {
                imGuiGlfw.scrollCallback(window, dx, dy);
                if (ImGui.getIO().getWantCaptureMouse()) return;
                viewportInput.onScroll(dx, dy);
            }
        };

        keyCb = new GLFWKeyCallback() {
            @Override public void invoke(long window, int key, int scancode, int action, int mods) {
                imGuiGlfw.keyCallback(window, key, scancode, action, mods);
                if (ImGui.getIO().getWantCaptureKeyboard()) return;
                viewportInput.onKey(key, action, mods);
            }
        };

        charCb = new GLFWCharCallback() {
            @Override public void invoke(long window, int codepoint) {
                imGuiGlfw.charCallback(window, codepoint);
            }
        };

        // Window focus and cursor enter are critical on macOS — without
        // these forwarded, ImGui treats the window as unfocused and
        // refuses to dispatch mouse/keyboard input.
        windowFocusCb = new GLFWWindowFocusCallback() {
            @Override public void invoke(long window, boolean focused) {
                imGuiGlfw.windowFocusCallback(window, focused);
            }
        };

        cursorEnterCb = new GLFWCursorEnterCallback() {
            @Override public void invoke(long window, boolean entered) {
                imGuiGlfw.cursorEnterCallback(window, entered);
            }
        };

        GLFW.glfwSetMouseButtonCallback(handle, mouseButtonCb);
        GLFW.glfwSetCursorPosCallback(handle, cursorPosCb);
        GLFW.glfwSetScrollCallback(handle, scrollCb);
        GLFW.glfwSetKeyCallback(handle, keyCb);
        GLFW.glfwSetCharCallback(handle, charCb);
        GLFW.glfwSetWindowFocusCallback(handle, windowFocusCb);
        GLFW.glfwSetCursorEnterCallback(handle, cursorEnterCb);
    }

    @Override
    protected void cleanup(Application app) {
        if (cutSheetPanel != null) cutSheetPanel.dispose();
        imGuiGl3.shutdown();
        imGuiGlfw.shutdown();
        ImGui.destroyContext();
        // GLFW callbacks free themselves on JVM exit; in long-running tests
        // we'd want to set them back to null and release(), but Cadette's
        // process is single-shot.
    }

    @Override
    protected void onEnable() { }

    @Override
    protected void onDisable() { }

    private int frameCount = 0;

    @Override
    public void postRender() {
        if (frameCount < 3) {
            System.err.println("[spike] postRender #" + frameCount
                    + " thread=" + Thread.currentThread().getName());
        }
        // Refresh selection highlights each frame so they follow parts that
        // get moved/resized via commands. Cheap when selection is empty.
        syncSelectionHighlights();

        imGuiGl3.newFrame();
        imGuiGlfw.newFrame();
        ImGui.newFrame();

        // Diagnostic: dump ImGui IO state. First 3 frames: every frame.
        // After that: every 10 frames. Also dumps GLFW window/framebuffer
        // size so we can compare to ImGui's DisplaySize for the Retina/
        // multi-monitor coordinate-system bug hypothesis.
        if (frameCount < 3 || (frameCount < 200 && frameCount % 10 == 0)) {
            var io = ImGui.getIO();
            if (getApplication().getContext() instanceof LwjglWindow w) {
                long h = w.getWindowHandle();
                int[] wW = new int[1], wH = new int[1];
                int[] fbW = new int[1], fbH = new int[1];
                int[] xpos = new int[1], ypos = new int[1];
                GLFW.glfwGetWindowSize(h, wW, wH);
                GLFW.glfwGetFramebufferSize(h, fbW, fbH);
                GLFW.glfwGetWindowPos(h, xpos, ypos);
                System.err.println(String.format(
                    "[spike] frame %d: cursor=(%.0f,%.0f) lmb=%b rmb=%b "
                    + "wantMouse=%b anyHovered=%b "
                    + "winPos=(%d,%d) winSize=%dx%d fb=%dx%d disp=%.0fx%.0f scale=(%.1f,%.1f)",
                    frameCount,
                    io.getMousePosX(), io.getMousePosY(),
                    io.getMouseDown(0), io.getMouseDown(1),
                    io.getWantCaptureMouse(),
                    ImGui.isAnyItemHovered(),
                    xpos[0], ypos[0], wW[0], wH[0], fbW[0], fbH[0],
                    io.getDisplaySizeX(), io.getDisplaySizeY(),
                    io.getDisplayFramebufferScaleX(), io.getDisplayFramebufferScaleY()));
            }
        }
        frameCount++;

        int dockId = ImGui.dockSpaceOverViewport(ImGui.getMainViewport(),
                ImGuiDockNodeFlags.PassthruCentralNode);

        // DockBuilder programmatic layout is silently failing on macOS
        // (panels end up in unrenderable dock nodes). Until that's
        // understood, fall back to setNextWindowPos on each panel below
        // with FirstUseEver — windows appear floating at sensible
        // positions on first launch, and imgui.ini takes over on
        // subsequent launches if the user docks them by hand.
        // if (buildDefaultLayout) {
        //     buildDefaultLayout(dockId);
        //     buildDefaultLayout = false;
        // }

        // DIAGNOSTIC: ImGui's built-in demo window. If this renders on Mac,
        // ImGui works and our panel-layout code is the bug. If it doesn't,
        // ImGui's GL3 backend is genuinely broken on Apple's Metal GL.
        ImGui.showDemoWindow();

        drawCommandPanel();
        drawLogPanel();
        drawPartsPanel();
        cutSheetPanel.draw();

        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    /**
     * First-launch dock layout: Command + Log tabbed along the bottom 30%,
     * Scene strip on the right 25%, central area reserved as the passthru
     * node where the 3D viewport shows through.
     */
    private static void buildDefaultLayout(int dockId) {
        imgui.internal.ImGui.dockBuilderRemoveNode(dockId);
        imgui.internal.ImGui.dockBuilderAddNode(dockId,
                imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
        imgui.internal.ImGui.dockBuilderSetNodeSize(dockId,
                ImGui.getMainViewport().getSizeX(), ImGui.getMainViewport().getSizeY());

        ImInt bottomId = new ImInt();
        ImInt topId = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(dockId, ImGuiDir.Down, 0.30f, bottomId, topId);

        ImInt rightId = new ImInt();
        ImInt centerId = new ImInt();
        imgui.internal.ImGui.dockBuilderSplitNode(topId.get(), ImGuiDir.Right, 0.25f, rightId, centerId);

        imgui.internal.ImGui.dockBuilderDockWindow("Command",   bottomId.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Log",       bottomId.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Parts",     rightId.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Cut Sheet", rightId.get());  // tabbed with Parts

        imgui.internal.ImGui.dockBuilderFinish(dockId);
    }

    // ---- Command panel ---------------------------------------------------

    private void drawCommandPanel() {
        // FirstUseEver: only applied on the very first run (when imgui.ini
        // doesn't yet have a saved position). On subsequent runs the user's
        // saved layout wins. Window is 1600×1000 by default; lay panels out
        // so they're all visible and non-overlapping.
        ImGui.setNextWindowPos(20, 700, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(1000, 280, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.begin("Command", ImGuiWindowFlags.NoCollapse);

        ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.85f, 0.2f, 1f);
        ImGui.text("[SPIKE — ImGui engine-UI + docking]");
        ImGui.popStyleColor();
        ImGui.separator();

        float footerHeight = ImGui.getFrameHeightWithSpacing();
        if (ImGui.beginChild("##cmd-scrollback", 0, -footerHeight, true)) {
            for (String line : commandLines) {
                ImGui.textWrapped(line);
            }
            if (commandScrollPending) {
                ImGui.setScrollHereY(1.0f);
                commandScrollPending = false;
            }
        }
        ImGui.endChild();

        if (focusCommandInput) {
            ImGui.setKeyboardFocusHere();
            focusCommandInput = false;
        }
        ImGui.pushItemWidth(-1);
        int flags = ImGuiInputTextFlags.EnterReturnsTrue
                  | ImGuiInputTextFlags.CallbackResize
                  | ImGuiInputTextFlags.CallbackHistory;
        if (ImGui.inputText("##command", commandInput, flags, historyCallback)) {
            String typed = commandInput.get().trim();
            if (!typed.isEmpty()) {
                pushHistory(typed);
                runCommand(typed);
            }
            commandInput.set("");
            historyPos = -1;
            preRecallBuffer = "";
            focusCommandInput = true;
        }
        ImGui.popItemWidth();

        ImGui.end();
    }

    private void runCommand(String command) {
        appendCommand("> " + command);
        try {
            String result = executor.execute(command);
            if (result != null && !result.isEmpty()) {
                for (String line : result.split("\n", -1)) {
                    appendCommand(line);
                }
            }
        } catch (Throwable t) {
            appendCommand("Error: " + t.getMessage());
        }
    }

    /** Append a freshly-submitted command to history, dropping a duplicate
     *  consecutive entry so press-up after Enter doesn't recall the same
     *  thing you just ran. Also persists to disk so history spans sessions. */
    private void pushHistory(String command) {
        if (!commandHistory.isEmpty()
                && commandHistory.get(commandHistory.size() - 1).equals(command)) {
            return;
        }
        commandHistory.add(command);
        if (commandHistory.size() > HISTORY_MAX) {
            commandHistory.subList(0, commandHistory.size() - HISTORY_MAX).clear();
        }
        saveHistory();
    }

    /** Load command history from ~/.cadette/cmd_history (if it exists).
     *  Silent on errors — history is a convenience, not load-bearing. */
    private void loadHistory() {
        try {
            if (Files.exists(HISTORY_FILE)) {
                List<String> lines = Files.readAllLines(HISTORY_FILE);
                for (String line : lines) {
                    if (!line.isEmpty()) commandHistory.add(line);
                }
                // Trim if the file grew beyond the cap (older format etc.).
                if (commandHistory.size() > HISTORY_MAX) {
                    commandHistory.subList(0, commandHistory.size() - HISTORY_MAX).clear();
                }
            }
        } catch (Exception ignored) { /* fresh start */ }
    }

    /** Persist command history to disk. Called after each push; the file
     *  is small enough (≤ 200 lines) that rewriting on every command is
     *  cheap and avoids partial-write/locking complexity. */
    private void saveHistory() {
        try {
            Files.createDirectories(HISTORY_FILE.getParent());
            Files.write(HISTORY_FILE, commandHistory);
        } catch (Exception ignored) { /* non-fatal */ }
    }

    /**
     * History-recall callback. ImGui fires this on up/down arrow when the
     * input has focus and the flag is enabled. We swap the buffer text to
     * the recalled history entry; down-arrow past the newest entry restores
     * the user's in-progress input (saved on first up-press).
     */
    private final imgui.callback.ImGuiInputTextCallback historyCallback =
            new imgui.callback.ImGuiInputTextCallback() {
        @Override
        public void accept(imgui.ImGuiInputTextCallbackData data) {
            if (!data.hasEventFlag(ImGuiInputTextFlags.CallbackHistory)) return;
            int prevPos = historyPos;
            if (data.getEventKey() == imgui.flag.ImGuiKey.UpArrow) {
                if (historyPos == -1) {
                    preRecallBuffer = data.getBuf();  // save fresh input
                    historyPos = commandHistory.size() - 1;
                } else if (historyPos > 0) {
                    historyPos--;
                }
            } else if (data.getEventKey() == imgui.flag.ImGuiKey.DownArrow) {
                if (historyPos != -1) {
                    historyPos++;
                    if (historyPos >= commandHistory.size()) historyPos = -1;
                }
            }
            if (prevPos != historyPos) {
                String text = (historyPos >= 0)
                        ? commandHistory.get(historyPos)
                        : preRecallBuffer;
                data.deleteChars(0, data.getBufTextLen());
                data.insertChars(0, text);
            }
        }
    };

    // ---- Log panel -------------------------------------------------------

    private void drawLogPanel() {
        // Just right of Command at the bottom.
        ImGui.setNextWindowPos(1030, 700, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(250, 280, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.begin("Log", ImGuiWindowFlags.NoCollapse);
        if (ImGui.beginChild("##log-scrollback", 0, 0, true)) {
            for (String line : logLines) {
                ImGui.textWrapped(line);
            }
            if (logScrollPending) {
                ImGui.setScrollHereY(1.0f);
                logScrollPending = false;
            }
        }
        ImGui.endChild();
        ImGui.end();
    }

    // ---- Parts panel -----------------------------------------------------

    private void drawPartsPanel() {
        // Right side, top half.
        ImGui.setNextWindowPos(1290, 20, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(290, 340, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.begin("Parts", ImGuiWindowFlags.NoCollapse);
        SceneManager scene = (SceneManager) getApplication();
        var parts = scene.getAllParts();
        ImGui.text(parts.size() + " part" + (parts.size() == 1 ? "" : "s")
                + (selected.isEmpty() ? "" : ", " + selected.size() + " selected"));
        if (openAssembly != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0.6f, 0.85f, 1f, 1f);  // light blue
            ImGui.text("inside assembly: " + openAssembly + "  (Esc to exit)");
            ImGui.popStyleColor();
        }
        ImGui.separator();
        for (var entry : parts.entrySet()) {
            var part = entry.getValue();
            boolean isSelected = selected.contains(part.getName());
            if (isSelected) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.85f, 0.2f, 1f);  // yellow
            }
            ImGui.text(String.format("%s%s — %.1f × %.1f mm",
                    isSelected ? "● " : "  ",
                    part.getName(), part.getCutWidthMm(), part.getCutHeightMm()));
            if (isSelected) {
                ImGui.popStyleColor();
            }
        }
        ImGui.end();
    }

    /**
     * Raycast from the camera through the cursor position, find the
     * closest part the ray hits, and update {@link #selected} according to
     * the modifier keys: shift = add, Cmd/Ctrl = toggle, plain = replace.
     * Click on empty space with no modifier = deselect all.
     */
    private void handleViewportClick(ViewportInputHandler.ClickEvent click) {
        SceneManager scene = (SceneManager) getApplication();
        com.jme3.renderer.Camera cam = scene.getCamera();

        // GLFW cursor coords have origin top-left; jME3 screen coords have
        // origin bottom-left. Flip Y when handing to getWorldCoordinates.
        float sx = (float) click.x();
        float sy = cam.getHeight() - (float) click.y();
        com.jme3.math.Vector2f screen = new com.jme3.math.Vector2f(sx, sy);
        com.jme3.math.Vector3f near = cam.getWorldCoordinates(screen, 0f);
        com.jme3.math.Vector3f far  = cam.getWorldCoordinates(screen, 1f);
        com.jme3.math.Ray ray = new com.jme3.math.Ray(near, far.subtractLocal(near).normalizeLocal());

        com.jme3.collision.CollisionResults results = new com.jme3.collision.CollisionResults();
        scene.getObjectsNode().collideWith(ray, results);

        String hit = null;
        if (results.size() > 0) {
            com.jme3.scene.Spatial s = results.getClosestCollision().getGeometry();
            while (s != null) {
                String name = s.getName();
                if (name != null && name.startsWith("node_")) {
                    hit = name.substring("node_".length());
                    break;
                }
                s = s.getParent();
            }
        }

        // Resolve the hit to either an assembly or a part, depending on
        // hierarchical state. Convention: parts created inside a template
        // are named "<assembly>/<part>" — the prefix is the assembly.
        String resolved = null;
        if (hit != null) {
            int slash = hit.indexOf('/');
            String hitAssembly = (slash >= 0) ? hit.substring(0, slash) : null;
            if (click.isDouble() && hitAssembly != null) {
                // Double-click: drill into the assembly and select the part.
                openAssembly = hitAssembly;
                resolved = hit;
            } else if (hitAssembly != null && hitAssembly.equals(openAssembly)) {
                // Click inside the currently-open assembly → select part.
                resolved = hit;
            } else if (hitAssembly != null) {
                // Click on a part in an assembly we haven't entered → select
                // the assembly as a whole.
                resolved = hitAssembly;
            } else {
                // Standalone part (no assembly prefix). Always selects itself.
                resolved = hit;
            }
        }

        boolean shift  = (click.mods() & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean toggle = (click.mods() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (resolved == null) {
            // Click on empty space.
            if (!shift && !toggle) {
                selected.clear();
                openAssembly = null;  // also exits any open assembly
            }
        } else if (toggle) {
            if (!selected.add(resolved)) selected.remove(resolved);
        } else if (shift) {
            selected.add(resolved);
        } else {
            selected.clear();
            selected.add(resolved);
        }
    }

    /** Esc: if an assembly is open, close it (and clear selection); else
     *  just clear selection. Standard hierarchical-pop pattern. */
    private void handleEscape() {
        if (openAssembly != null) {
            openAssembly = null;
        }
        selected.clear();
    }

    /**
     * Diff the current {@link #selected} set against {@link #highlightGeoms}
     * and attach/detach yellow profile-outline overlays accordingly.
     *
     * <p>The overlay is a custom Lines mesh built from the part's final 2D
     * profile (after cuts/keeps), extruded to the part's thickness — top
     * ring + bottom ring + vertical connectors. Result: a clean silhouette
     * that follows miters and through-cuts without the internal
     * triangulation visual noise of the full-mesh wireframe.
     *
     * <p>Partial-depth pockets aren't in the silhouette (by definition —
     * the outer profile is unchanged), so the wireframe outlines just the
     * external shape and any through-holes.
     */
    private void syncSelectionHighlights() {
        SceneManager scene = (SceneManager) getApplication();

        // Selected assemblies expand to all their member parts so each
        // part's mesh gets its own highlight.
        java.util.Set<String> targetParts = new LinkedHashSet<>();
        for (String name : selected) {
            app.cadette.model.Assembly asm = scene.getAssembly(name);
            if (asm != null) {
                for (app.cadette.model.Part p : asm.getParts()) {
                    targetParts.add(p.getName());
                }
            } else {
                targetParts.add(name);
            }
        }

        // Drop highlights for parts no longer in the target set.
        var iter = highlightGeoms.entrySet().iterator();
        while (iter.hasNext()) {
            var e = iter.next();
            if (!targetParts.contains(e.getKey())) {
                e.getValue().removeFromParent();
                lastSeenPartMesh.remove(e.getKey());
                iter.remove();
            }
        }

        // Build / refresh highlights for everything that should be highlighted.
        for (String partName : targetParts) {
            app.cadette.model.Part part = scene.getPart(partName);
            if (part == null) continue;
            com.jme3.scene.Spatial wrapperSpatial =
                    scene.getObjectsNode().getChild("node_" + partName);
            if (!(wrapperSpatial instanceof com.jme3.scene.Node wrapper)) continue;

            // Find the part's actual Geometry inside the wrapper so we can
            // mirror its local translation and detect mesh rebuilds.
            com.jme3.scene.Geometry partGeom = null;
            for (com.jme3.scene.Spatial s : wrapper.getChildren()) {
                if (s instanceof com.jme3.scene.Geometry g
                        && partName.equals(g.getName())) {
                    partGeom = g;
                    break;
                }
            }
            if (partGeom == null) continue;

            com.jme3.scene.Geometry existing = highlightGeoms.get(partName);
            boolean partMeshRebuilt = lastSeenPartMesh.get(partName) != partGeom.getMesh();
            boolean wrapperChanged = existing != null && existing.getParent() != wrapper;
            if (existing == null || partMeshRebuilt || wrapperChanged) {
                if (existing != null) existing.removeFromParent();
                com.jme3.scene.Mesh outlineMesh = buildProfileOutlineMesh(part);
                if (outlineMesh == null) continue;  // degenerate part
                com.jme3.material.Material mat = new com.jme3.material.Material(
                        scene.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
                mat.setColor("Color", com.jme3.math.ColorRGBA.Yellow);
                mat.getAdditionalRenderState().setDepthTest(false);  // draw on top
                com.jme3.scene.Geometry hl = new com.jme3.scene.Geometry(
                        "highlight_" + partName, outlineMesh);
                hl.setMaterial(mat);
                hl.setLocalTranslation(partGeom.getLocalTranslation());
                wrapper.attachChild(hl);
                highlightGeoms.put(partName, hl);
                lastSeenPartMesh.put(partName, partGeom.getMesh());
            } else {
                existing.setLocalTranslation(partGeom.getLocalTranslation());
            }
        }
    }

    /**
     * Build a Lines mesh tracing the part's silhouette outline at both
     * faces plus vertical connectors. Coordinates are centred (matching
     * PartMeshBuilder's convention) so the geometry can share the part's
     * geomOffset translation. Returns null if the profile is empty.
     */
    private static com.jme3.scene.Mesh buildProfileOutlineMesh(app.cadette.model.Part part) {
        org.locationtech.jts.geom.Geometry profile =
                app.cadette.model.PartMeshBuilder.computeFinalProfile(
                        part.getCutWidthMm(), part.getCutHeightMm(),
                        part.getCutouts(), part.getKeeps());
        if (profile.isEmpty()) return null;

        float halfW = part.getCutWidthMm()  * 0.5f;
        float halfH = part.getCutHeightMm() * 0.5f;
        float halfT = part.getThicknessMm() * 0.5f;

        java.util.List<double[][]> rings = new java.util.ArrayList<>();
        collectRings(profile, rings);
        if (rings.isEmpty()) return null;

        int totalVerts = 0;
        int totalLineIdx = 0;
        for (double[][] ring : rings) {
            totalVerts += ring.length * 2;     // bottom + top
            totalLineIdx += ring.length * 6;   // (n top + n bottom + n verticals) × 2 indices
        }

        java.nio.FloatBuffer posBuf =
                org.lwjgl.BufferUtils.createFloatBuffer(totalVerts * 3);
        java.nio.IntBuffer idxBuf =
                org.lwjgl.BufferUtils.createIntBuffer(totalLineIdx);

        int vBase = 0;
        for (double[][] ring : rings) {
            int n = ring.length;
            // Bottom ring at z = -halfT (part's back face).
            for (int i = 0; i < n; i++) {
                posBuf.put((float) ring[i][0] - halfW);
                posBuf.put((float) ring[i][1] - halfH);
                posBuf.put(-halfT);
            }
            // Top ring at z = +halfT (part's front face).
            for (int i = 0; i < n; i++) {
                posBuf.put((float) ring[i][0] - halfW);
                posBuf.put((float) ring[i][1] - halfH);
                posBuf.put(halfT);
            }
            // Bottom edges.
            for (int i = 0; i < n; i++) {
                idxBuf.put(vBase + i);
                idxBuf.put(vBase + ((i + 1) % n));
            }
            // Top edges.
            for (int i = 0; i < n; i++) {
                idxBuf.put(vBase + n + i);
                idxBuf.put(vBase + n + ((i + 1) % n));
            }
            // Vertical connectors.
            for (int i = 0; i < n; i++) {
                idxBuf.put(vBase + i);
                idxBuf.put(vBase + n + i);
            }
            vBase += n * 2;
        }
        posBuf.flip();
        idxBuf.flip();

        com.jme3.scene.Mesh mesh = new com.jme3.scene.Mesh();
        mesh.setMode(com.jme3.scene.Mesh.Mode.Lines);
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Position, 3, posBuf);
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Index, 2, idxBuf);
        mesh.updateBound();
        return mesh;
    }

    /** Flatten a JTS profile Geometry into a list of closed rings (exterior +
     *  holes, across all polygons). JTS closes rings by repeating the first
     *  coordinate; we drop the duplicate so the index math wraps cleanly. */
    private static void collectRings(org.locationtech.jts.geom.Geometry g,
                                      java.util.List<double[][]> out) {
        if (g instanceof org.locationtech.jts.geom.Polygon p) {
            out.add(ringCoords(p.getExteriorRing()));
            for (int i = 0; i < p.getNumInteriorRing(); i++) {
                out.add(ringCoords(p.getInteriorRingN(i)));
            }
        } else {
            for (int i = 0; i < g.getNumGeometries(); i++) {
                collectRings(g.getGeometryN(i), out);
            }
        }
    }

    private static double[][] ringCoords(org.locationtech.jts.geom.LineString ring) {
        org.locationtech.jts.geom.Coordinate[] cs = ring.getCoordinates();
        int n = cs.length - 1;  // JTS closes rings; drop the duplicate
        double[][] r = new double[n][2];
        for (int i = 0; i < n; i++) {
            r[i][0] = cs[i].x;
            r[i][1] = cs[i].y;
        }
        return r;
    }

    /**
     * "R" handler: frame all parts in the scene if any exist; otherwise
     * fall back to the camera's default reset.
     */
    private void resetView() {
        SceneManager scene = (SceneManager) getApplication();
        com.jme3.math.Vector3f min = null;
        com.jme3.math.Vector3f max = null;
        for (String name : scene.getAllParts().keySet()) {
            com.jme3.math.Vector3f[] bb = scene.computeObjectAABB(name);
            if (bb == null) continue;
            if (min == null) {
                min = bb[0].clone();
                max = bb[1].clone();
            } else {
                min.x = Math.min(min.x, bb[0].x);
                min.y = Math.min(min.y, bb[0].y);
                min.z = Math.min(min.z, bb[0].z);
                max.x = Math.max(max.x, bb[1].x);
                max.y = Math.max(max.y, bb[1].y);
                max.z = Math.max(max.z, bb[1].z);
            }
        }
        if (min != null) {
            orbitCamera.frameBox(min, max);
        } else {
            orbitCamera.reset();
        }
    }

    // ---- Output buffers --------------------------------------------------

    private void appendCommand(String line) {
        commandLines.add(line);
        if (commandLines.size() > 1000) {
            commandLines.subList(0, commandLines.size() - 1000).clear();
        }
        commandScrollPending = true;
    }

    private void appendLog(String line) {
        logLines.add(line);
        if (logLines.size() > 1000) {
            logLines.subList(0, logLines.size() - 1000).clear();
        }
        logScrollPending = true;
    }
}
