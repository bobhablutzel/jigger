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
21 */

package app.cadette.imgui;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;

/**
 * Drives the orbit camera + click selection by polling ImGui's IO each
 * frame. Compared to the earlier callback-based version, this lets
 * imgui-java install its own GLFW callbacks (the standard,
 * macOS-friendly path) and we just read ImGui's interpreted state.
 *
 * <p>Gesture ownership is locked at button-down: a viewport drag that
 * starts in the 3D area keeps feeding the camera even if the cursor
 * wanders through a panel.
 *
 * <p>Bindings:
 * <ul>
 *   <li>LMB click → onClick callback (caller raycasts)</li>
 *   <li>RMB drag → orbit</li>
 *   <li>Shift + RMB drag → pan</li>
 *   <li>Scroll → zoom</li>
 *   <li>F / T / S / I / R → preset views + reset</li>
 *   <li>Esc → onEscape callback</li>
 * </ul>
 */
public class ViewportInputHandler {

    /** Click event: screen coords + the GLFW mods active at click time. */
    public record ClickEvent(double x, double y, int mods, boolean isDouble) {}

    private final OrbitCamera camera;
    private final Runnable onReset;
    private final java.util.function.Consumer<ClickEvent> onClick;
    private final Runnable onEscape;

    // Per-gesture state. Tracked across frames since we poll.
    private boolean rmbActive = false;
    private int rmbMods = 0;

    // LMB click detection: snapshot press position; accumulate motion; if
    // small at release time, fire a click.
    private boolean lmbActive = false;
    private float lmbPressX, lmbPressY;
    private float lmbMotionAccum;
    private static final float CLICK_MOTION_TOLERANCE_PX = 5f;

    // Double-click detection.
    private double lastClickTime = -1;
    private float lastClickX, lastClickY;
    private static final double DOUBLE_CLICK_WINDOW_S = 0.40;
    private static final float DOUBLE_CLICK_DIST_PX = 5f;

    public ViewportInputHandler(OrbitCamera camera,
                                 Runnable onReset,
                                 java.util.function.Consumer<ClickEvent> onClick,
                                 Runnable onEscape) {
        this.camera = camera;
        this.onReset = onReset;
        this.onClick = onClick;
        this.onEscape = onEscape;
    }

    /**
     * Poll ImGui's IO and drive camera + selection. Called from the
     * AppState's update each frame; ImGui's WantCapture flags gate
     * everything so panel interactions don't bleed into the viewport.
     */
    public void poll() {
        var io = ImGui.getIO();
        boolean mouseCaptured = io.getWantCaptureMouse();
        boolean keyCaptured = io.getWantCaptureKeyboard();

        boolean rmbDown = ImGui.isMouseDown(ImGuiMouseButton.Right);
        boolean lmbDown = ImGui.isMouseDown(ImGuiMouseButton.Left);
        float mx = io.getMousePosX();
        float my = io.getMousePosY();
        float dx = io.getMouseDeltaX();
        float dy = io.getMouseDeltaY();

        // ---- RMB drag → orbit / pan -----------------------------------
        if (rmbDown && !rmbActive && !mouseCaptured) {
            rmbActive = true;
            rmbMods = computeModMask(io);
        } else if (!rmbDown && rmbActive) {
            rmbActive = false;
        }
        if (rmbActive && (dx != 0 || dy != 0)) {
            boolean shift = (rmbMods & MOD_SHIFT) != 0;
            if (shift) camera.pan(dx, dy);
            else       camera.orbit(dx, dy);
        }

        // ---- LMB click (no-drag release) → select ---------------------
        if (lmbDown && !lmbActive && !mouseCaptured) {
            lmbActive = true;
            lmbPressX = mx;
            lmbPressY = my;
            lmbMotionAccum = 0;
        } else if (lmbDown && lmbActive) {
            lmbMotionAccum += Math.abs(dx) + Math.abs(dy);
        } else if (!lmbDown && lmbActive) {
            // Release. Fire click if motion was small.
            if (lmbMotionAccum < CLICK_MOTION_TOLERANCE_PX) {
                double now = org.lwjgl.glfw.GLFW.glfwGetTime();
                boolean isDouble = lastClickTime > 0
                        && (now - lastClickTime) < DOUBLE_CLICK_WINDOW_S
                        && Math.abs(mx - lastClickX) < DOUBLE_CLICK_DIST_PX
                        && Math.abs(my - lastClickY) < DOUBLE_CLICK_DIST_PX;
                lastClickTime = isDouble ? -1 : now;
                lastClickX = mx;
                lastClickY = my;
                onClick.accept(new ClickEvent(mx, my, computeModMask(io), isDouble));
            }
            lmbActive = false;
        }

        // ---- Scroll → zoom --------------------------------------------
        float wheel = io.getMouseWheel();
        if (wheel != 0 && !mouseCaptured) {
            camera.zoom(wheel);
        }

        // ---- Keyboard ------------------------------------------------
        if (!keyCaptured) {
            if (ImGui.isKeyPressed(ImGuiKey.F)) camera.viewFront();
            if (ImGui.isKeyPressed(ImGuiKey.T)) camera.viewTop();
            if (ImGui.isKeyPressed(ImGuiKey.S)) camera.viewSide();
            if (ImGui.isKeyPressed(ImGuiKey.I)) camera.viewIso();
            if (ImGui.isKeyPressed(ImGuiKey.R)) onReset.run();
            if (ImGui.isKeyPressed(ImGuiKey.Escape)) onEscape.run();
            if (ImGui.isKeyPressed(ImGuiKey.Equal) || ImGui.isKeyPressed(ImGuiKey.KeypadAdd))
                camera.zoom(+1f);
            if (ImGui.isKeyPressed(ImGuiKey.Minus) || ImGui.isKeyPressed(ImGuiKey.KeypadSubtract))
                camera.zoom(-1f);
        }
    }

    // GLFW modifier bits — mirror org.lwjgl.glfw.GLFW.GLFW_MOD_* so the
    // caller (which interprets ClickEvent.mods) keeps working unchanged.
    private static final int MOD_SHIFT = 0x0001;
    private static final int MOD_CONTROL = 0x0002;
    private static final int MOD_ALT = 0x0004;
    private static final int MOD_SUPER = 0x0008;

    private static int computeModMask(imgui.ImGuiIO io) {
        int mask = 0;
        if (io.getKeyShift()) mask |= MOD_SHIFT;
        if (io.getKeyCtrl())  mask |= MOD_CONTROL;
        if (io.getKeyAlt())   mask |= MOD_ALT;
        if (io.getKeySuper()) mask |= MOD_SUPER;
        return mask;
    }
}
