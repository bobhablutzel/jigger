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
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the ImGui overlay each frame: a command panel (text input + output
 * scrollback) and a scene panel listing parts. Hooked into the jME3 lifecycle
 * via {@link BaseAppState#render}/{@code postRender}; ImGui rendering happens
 * after the 3D pass on the GL thread.
 *
 * <p>Spike-quality: no syntax color, no history-recall keybinds yet, no
 * docking (basic ImGui windows). Enough to feel whether the workflow is
 * productive.
 */
public class ImGuiAppState extends BaseAppState {

    private final CommandExecutor executor;
    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();

    // Command panel state.
    private final ImString commandInput = new ImString(512);
    private final List<String> outputLines = new ArrayList<>();
    private boolean scrollToBottom = false;
    private boolean focusInput = true;  // grab focus on first frame

    public ImGuiAppState(CommandExecutor executor) {
        this.executor = executor;
        appendOutput("*** CADette ImGui SPIKE (engine-UI mode) ***");
        appendOutput("If you see this banner, you're in the ImGui-overlay build,");
        appendOutput("not the Swing CadetteApp. Window title should read [ImGui spike].");
        appendOutput("");
        appendOutput("Try: create part \"p\" length 600 material \"lumber-2x4-spf\"");
        appendOutput("");
    }

    @Override
    public void stateAttached(com.jme3.app.state.AppStateManager mgr) {
        super.stateAttached(mgr);
        // Route mesh-build warnings (joint cutouts that fall outside their
        // receiver, etc.) into the output panel instead of stderr — same
        // role the CommandPanel plays in the Swing app.
        Application app = mgr.getApplication();
        if (app instanceof SceneManager scene) {
            scene.setWarningSink(msg -> appendOutput(msg));
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
        ImGui.getIO().setConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
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
        // call, uploads to GPU); then GLFW newFrame (mouse/keyboard input);
        // then ImGui newFrame (logical frame begin).
        imGuiGl3.newFrame();
        imGuiGlfw.newFrame();
        ImGui.newFrame();

        drawCommandPanel();
        drawScenePanel();

        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    // ---- Command panel ---------------------------------------------------

    private void drawCommandPanel() {
        // Anchor along the bottom-left of the window. Spike layout — proper
        // docking comes in a follow-up.
        float windowHeight = ImGui.getIO().getDisplaySizeY();
        float windowWidth  = ImGui.getIO().getDisplaySizeX();
        float panelHeight = 260f;
        ImGui.setNextWindowPos(0, windowHeight - panelHeight, ImGuiCond.Always);
        ImGui.setNextWindowSize(windowWidth - 320, panelHeight, ImGuiCond.Always);

        ImGui.begin("Command", ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoMove);

        // Bright "SPIKE" banner — visual proof you're in the ImGui build,
        // not the Swing app. Yellow on the dark theme is unmissable.
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 1f, 0.85f, 0.2f, 1f);
        ImGui.text("[SPIKE — ImGui engine-UI mode]");
        ImGui.popStyleColor();
        ImGui.separator();

        // Scrollback. Reserve the bottom row for the input field.
        float footerHeight = ImGui.getFrameHeightWithSpacing();
        if (ImGui.beginChild("##scrollback", 0, -footerHeight, true)) {
            for (String line : outputLines) {
                ImGui.textWrapped(line);
            }
            if (scrollToBottom) {
                ImGui.setScrollHereY(1.0f);
                scrollToBottom = false;
            }
        }
        ImGui.endChild();

        // Input line.
        if (focusInput) {
            ImGui.setKeyboardFocusHere();
            focusInput = false;
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
            focusInput = true;  // re-grab focus for the next command
        }
        ImGui.popItemWidth();

        ImGui.end();
    }

    private void runCommand(String command) {
        appendOutput("> " + command);
        try {
            String result = executor.execute(command);
            if (result != null && !result.isEmpty()) {
                for (String line : result.split("\n", -1)) {
                    appendOutput(line);
                }
            }
        } catch (Throwable t) {
            appendOutput("Error: " + t.getMessage());
        }
        scrollToBottom = true;
    }

    private void appendOutput(String line) {
        outputLines.add(line);
        if (outputLines.size() > 1000) {
            outputLines.subList(0, outputLines.size() - 1000).clear();
        }
    }

    // ---- Scene panel -----------------------------------------------------

    private void drawScenePanel() {
        float windowWidth = ImGui.getIO().getDisplaySizeX();
        float scenePanelWidth = 320;
        ImGui.setNextWindowPos(windowWidth - scenePanelWidth, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(scenePanelWidth, ImGui.getIO().getDisplaySizeY(), ImGuiCond.Always);

        ImGui.begin("Scene", ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoMove);

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
}
