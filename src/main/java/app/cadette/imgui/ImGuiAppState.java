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
import com.jme3.app.state.BaseAppState;
import com.jme3.system.lwjgl.LwjglWindow;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the ImGui overlay each frame. Three dockable panels: Command (text
 * input + scrollback for user-driven commands), Log (warnings/info that
 * don't deserve to clutter Command), and Scene (parts list). All three live
 * inside a host DockSpace that covers the viewport, so the user can drag
 * them by their title bars into whatever arrangement they prefer.
 *
 * <p>Hooked into the jME3 lifecycle via {@link BaseAppState#initialize}/
 * {@code postRender}; ImGui rendering runs after the 3D pass on the GL
 * thread. Layout is persisted to {@code imgui.ini} automatically.
 *
 * <p>Spike-quality: no syntax color, no history-recall, no programmatic
 * default layout (panels start floating; user docks them).
 */
public class ImGuiAppState extends BaseAppState {

    private final CommandExecutor executor;
    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();

    // Command panel state.
    private final ImString commandInput = new ImString(512);
    private final List<String> commandLines = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();
    private boolean commandScrollPending = false;
    private boolean logScrollPending = false;
    private boolean focusCommandInput = true;  // grab focus on first frame

    public ImGuiAppState(CommandExecutor executor) {
        this.executor = executor;
        appendCommand("*** CADette ImGui SPIKE — engine-UI + docking ***");
        appendCommand("Drag any panel's title bar to dock it (left/right/top/");
        appendCommand("bottom edges, or onto another panel as a tab).");
        appendCommand("Layout is saved to imgui.ini between runs.");
        appendCommand("");
        appendCommand("Try: create part \"p\" length 600 material \"lumber-2x4-spf\"");
        appendCommand("");
        appendLog("Log panel — warnings and diagnostic info land here, not in Command.");
    }

    @Override
    public void stateAttached(com.jme3.app.state.AppStateManager mgr) {
        super.stateAttached(mgr);
        // Route mesh-build warnings (joint cutouts that fall outside their
        // receiver, etc.) to the Log panel — keeps the Command panel quiet.
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

        // Tint the 3D viewport so the spike is visually unmistakable from
        // the Swing CadetteApp's default-grey background.
        if (app instanceof com.jme3.app.SimpleApplication simple) {
            simple.getViewPort().setBackgroundColor(
                    new com.jme3.math.ColorRGBA(0.10f, 0.18f, 0.22f, 1f));  // dark teal
        }

        ImGui.createContext();
        // DockingEnable unlocks dock-into-panel behavior on title-bar drag
        // and saves layout to imgui.ini.
        ImGui.getIO().setConfigFlags(
                ImGuiConfigFlags.NavEnableKeyboard | ImGuiConfigFlags.DockingEnable);
        ImGui.styleColorsDark();
        imGuiGlfw.init(handle, true);
        imGuiGl3.init("#version 150");
    }

    @Override
    protected void cleanup(Application app) {
        imGuiGl3.shutdown();
        imGuiGlfw.shutdown();
        ImGui.destroyContext();
    }

    @Override
    protected void onEnable() { }

    @Override
    protected void onDisable() { }

    @Override
    public void postRender() {
        // Order matters: GL3 newFrame first (builds the font atlas on first
        // call); then GLFW newFrame (mouse/keyboard); then ImGui newFrame.
        imGuiGl3.newFrame();
        imGuiGlfw.newFrame();
        ImGui.newFrame();

        // Host DockSpace covering the entire main viewport. Panels that
        // start floating can be docked onto this; "PassthruCentralNode"
        // makes the empty centre of the dock space transparent so the 3D
        // viewport remains visible behind it.
        ImGui.dockSpaceOverViewport(ImGui.getMainViewport(),
                ImGuiDockNodeFlags.PassthruCentralNode);

        drawCommandPanel();
        drawLogPanel();
        drawScenePanel();

        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    // ---- Command panel ---------------------------------------------------

    private void drawCommandPanel() {
        ImGui.begin("Command", ImGuiWindowFlags.NoCollapse);

        // Bright "SPIKE" banner — visual proof you're in the ImGui build.
        ImGui.pushStyleColor(ImGuiCol.Text, 1f, 0.85f, 0.2f, 1f);
        ImGui.text("[SPIKE — ImGui engine-UI + docking]");
        ImGui.popStyleColor();
        ImGui.separator();

        // Scrollback. Reserve the bottom row for the input field.
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

        // Input line.
        if (focusCommandInput) {
            ImGui.setKeyboardFocusHere();
            focusCommandInput = false;
        }
        ImGui.pushItemWidth(-1);  // full width
        int flags = ImGuiInputTextFlags.EnterReturnsTrue
                  | ImGuiInputTextFlags.CallbackResize;
        if (ImGui.inputText("##command", commandInput, flags)) {
            String typed = commandInput.get().trim();
            if (!typed.isEmpty()) {
                runCommand(typed);
            }
            commandInput.set("");
            focusCommandInput = true;  // re-grab focus for the next command
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
            // Errors stay in Command (the user typed something; they should
            // see the failure inline). Diagnostic chatter goes to Log.
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

    // ---- Scene panel -----------------------------------------------------

    private void drawScenePanel() {
        ImGui.begin("Scene", ImGuiWindowFlags.NoCollapse);

        SceneManager scene = (SceneManager) getApplication();
        var parts = scene.getAllParts();
        ImGui.text(parts.size() + " part" + (parts.size() == 1 ? "" : "s"));
        ImGui.separator();
        for (var entry : parts.entrySet()) {
            var part = entry.getValue();
            ImGui.text(String.format("%s — %.1f × %.1f mm",
                    part.getName(), part.getCutWidthMm(), part.getCutHeightMm()));
        }

        ImGui.end();
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
