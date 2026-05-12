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
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;

import java.nio.DoubleBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    // GLFW callback references — held to prevent GC.
    private GLFWMouseButtonCallback mouseButtonCb;
    private GLFWCursorPosCallback   cursorPosCb;
    private GLFWScrollCallback      scrollCb;
    private GLFWKeyCallback         keyCb;
    private GLFWCharCallback        charCb;

    // Command panel state.
    private final ImString commandInput = new ImString(512);
    private final List<String> commandLines = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();
    private boolean commandScrollPending = false;
    private boolean logScrollPending = false;
    private boolean focusCommandInput = true;

    // Default-layout flag — true on first run (no imgui.ini exists yet).
    private boolean buildDefaultLayout;

    // Selection state. Insertion-ordered so the UI can display in click order.
    private final Set<String> selected = new LinkedHashSet<>();

    public ImGuiAppState(CommandExecutor executor) {
        this.executor = executor;
        appendCommand("*** CADette ImGui SPIKE — engine-UI + docking ***");
        appendCommand("");
        appendCommand("Viewport bindings:");
        appendCommand("  LMB click ......... select part (shift = add, Cmd/Ctrl = toggle)");
        appendCommand("  click empty ....... deselect all");
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
        if (!(app.getContext() instanceof LwjglWindow window)) {
            throw new IllegalStateException(
                    "ImGui spike requires LWJGL3 Display mode (got "
                            + app.getContext().getClass().getName() + ")");
        }
        long handle = window.getWindowHandle();

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
        // exist; LMB-click raycasts against the scene to update selection.
        orbitCamera = new OrbitCamera(app.getCamera());
        viewportInput = new ViewportInputHandler(orbitCamera, this::resetView, this::handleViewportClick);

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
        imGuiGl3.init("#version 150");

        installCallbacks(handle);
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
                imGuiGlfw.mouseButtonCallback(window, button, action, mods);
                if (action == GLFW.GLFW_PRESS && ImGui.getIO().getWantCaptureMouse()) {
                    return;  // panel owns this gesture
                }
                GLFW.glfwGetCursorPos(window, cx, cy);
                viewportInput.onMouseButton(button, action, mods, cx.get(0), cy.get(0));
            }
        };

        cursorPosCb = new GLFWCursorPosCallback() {
            @Override public void invoke(long window, double x, double y) {
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

        GLFW.glfwSetMouseButtonCallback(handle, mouseButtonCb);
        GLFW.glfwSetCursorPosCallback(handle, cursorPosCb);
        GLFW.glfwSetScrollCallback(handle, scrollCb);
        GLFW.glfwSetKeyCallback(handle, keyCb);
        GLFW.glfwSetCharCallback(handle, charCb);
    }

    @Override
    protected void cleanup(Application app) {
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

    @Override
    public void postRender() {
        imGuiGl3.newFrame();
        imGuiGlfw.newFrame();
        ImGui.newFrame();

        int dockId = ImGui.dockSpaceOverViewport(ImGui.getMainViewport(),
                ImGuiDockNodeFlags.PassthruCentralNode);

        if (buildDefaultLayout) {
            buildDefaultLayout(dockId);
            buildDefaultLayout = false;
        }

        drawCommandPanel();
        drawLogPanel();
        drawPartsPanel();

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

        imgui.internal.ImGui.dockBuilderDockWindow("Command", bottomId.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Log",     bottomId.get());
        imgui.internal.ImGui.dockBuilderDockWindow("Parts",   rightId.get());

        imgui.internal.ImGui.dockBuilderFinish(dockId);
    }

    // ---- Command panel ---------------------------------------------------

    private void drawCommandPanel() {
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
                  | ImGuiInputTextFlags.CallbackResize;
        if (ImGui.inputText("##command", commandInput, flags)) {
            String typed = commandInput.get().trim();
            if (!typed.isEmpty()) {
                runCommand(typed);
            }
            commandInput.set("");
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

    // ---- Log panel -------------------------------------------------------

    private void drawLogPanel() {
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
        ImGui.begin("Parts", ImGuiWindowFlags.NoCollapse);
        SceneManager scene = (SceneManager) getApplication();
        var parts = scene.getAllParts();
        ImGui.text(parts.size() + " part" + (parts.size() == 1 ? "" : "s")
                + (selected.isEmpty() ? "" : ", " + selected.size() + " selected"));
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

        boolean shift  = (click.mods() & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean toggle = (click.mods() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (hit == null) {
            if (!shift && !toggle) selected.clear();
        } else if (toggle) {
            if (!selected.add(hit)) selected.remove(hit);
        } else if (shift) {
            selected.add(hit);
        } else {
            selected.clear();
            selected.add(hit);
        }
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
