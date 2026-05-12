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

import static org.lwjgl.glfw.GLFW.*;

/**
 * Single-owner-per-gesture input handler for the 3D viewport. Receives raw
 * GLFW events that have been filtered against ImGui's WantCapture state by
 * the caller, so by the time onMouseButton/onScroll fire here the mouse is
 * known to belong to the viewport (not a panel).
 *
 * <p>Gesture ownership is locked at button-down: once a viewport drag
 * begins, subsequent move events feed the gesture regardless of where the
 * cursor wanders. That's what stops the camera from skipping when the
 * cursor briefly crosses a panel mid-drag.
 *
 * <p>Bindings (no MMB, trackpad-friendly):
 * <ul>
 *   <li>RMB drag → orbit</li>
 *   <li>Shift + RMB drag → pan</li>
 *   <li>Scroll → zoom</li>
 *   <li>F / T / S / I / R → front / top / side / iso / reset views</li>
 * </ul>
 * LMB-click selection comes later; for now LMB is a no-op in the viewport.
 */
public class ViewportInputHandler {

    /** Click event: screen coords + GLFW mods + whether this is a
     *  double-click (within ~400ms and ~5px of the previous click). */
    public record ClickEvent(double x, double y, int mods, boolean isDouble) {}

    private final OrbitCamera camera;
    /** Invoked when the user presses "R". Caller decides whether that means
     *  "frame all parts" or "reset to defaults" — typically frame-all when
     *  the scene has parts, defaults when it's empty. */
    private final Runnable onReset;
    /** Invoked when LMB is released without a meaningful drag (a click).
     *  Screen coords are in window pixels; caller does the raycast. */
    private final java.util.function.Consumer<ClickEvent> onClick;
    /** Invoked when Esc is pressed. Used for "close open assembly" etc. */
    private final Runnable onEscape;

    /** -1 when no gesture is active; otherwise the GLFW button that started it. */
    private int activeButton = -1;
    /** Mods snapshot from button-down — frozen for the gesture's duration so
     *  the user can release Shift mid-drag without flipping orbit↔pan. */
    private int activeMods = 0;
    private double lastX, lastY;

    // LMB click detection: where + when the press happened, plus how far
    // the cursor has wandered since. A release with small motion → click.
    private double pressX, pressY;
    private double pressMotionAccum;
    private static final double CLICK_MOTION_TOLERANCE_PX = 5.0;

    // Double-click detection: timestamp + position of the most recent
    // single click. A click within these thresholds counts as a double.
    private double lastClickTime = -1;
    private double lastClickX, lastClickY;
    private static final double DOUBLE_CLICK_WINDOW_S = 0.40;
    private static final double DOUBLE_CLICK_DIST_PX = 5.0;

    public ViewportInputHandler(OrbitCamera camera,
                                 Runnable onReset,
                                 java.util.function.Consumer<ClickEvent> onClick,
                                 Runnable onEscape) {
        this.camera = camera;
        this.onReset = onReset;
        this.onClick = onClick;
        this.onEscape = onEscape;
    }

    // Called only for events the viewport owns (ImGui didn't want them).
    public void onMouseButton(int button, int action, int mods, double cursorX, double cursorY) {
        if (action == GLFW_PRESS && activeButton == -1) {
            activeButton = button;
            activeMods = mods;
            lastX = cursorX;
            lastY = cursorY;
            // LMB-down: snapshot for click detection.
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                pressX = cursorX;
                pressY = cursorY;
                pressMotionAccum = 0;
            }
        } else if (action == GLFW_RELEASE && button == activeButton) {
            // LMB-up with negligible motion → click. Larger motion → drag
            // (currently unbound; reserved for box-select).
            if (button == GLFW_MOUSE_BUTTON_LEFT
                    && pressMotionAccum < CLICK_MOTION_TOLERANCE_PX) {
                double now = org.lwjgl.glfw.GLFW.glfwGetTime();
                boolean isDouble = lastClickTime > 0
                        && (now - lastClickTime) < DOUBLE_CLICK_WINDOW_S
                        && Math.abs(cursorX - lastClickX) < DOUBLE_CLICK_DIST_PX
                        && Math.abs(cursorY - lastClickY) < DOUBLE_CLICK_DIST_PX;
                // Reset on double so a third click doesn't fire as double again.
                lastClickTime = isDouble ? -1 : now;
                lastClickX = cursorX;
                lastClickY = cursorY;
                onClick.accept(new ClickEvent(cursorX, cursorY, activeMods, isDouble));
            }
            activeButton = -1;
            activeMods = 0;
        }
    }

    // Called for every cursor move (gesture tracking needs to see them all).
    public void onCursorPos(double x, double y) {
        if (activeButton == -1) return;
        double dx = x - lastX;
        double dy = y - lastY;
        lastX = x;
        lastY = y;
        if (activeButton == GLFW_MOUSE_BUTTON_LEFT) {
            // Accumulate motion to distinguish click from drag.
            pressMotionAccum += Math.abs(dx) + Math.abs(dy);
        } else if (activeButton == GLFW_MOUSE_BUTTON_RIGHT) {
            boolean shift = (activeMods & GLFW_MOD_SHIFT) != 0;
            if (shift) {
                camera.pan((float) dx, (float) dy);
            } else {
                camera.orbit((float) dx, (float) dy);
            }
        }
    }

    public void onScroll(double dxOffset, double dyOffset) {
        // GLFW dy: positive = scroll up = zoom in. Trackpad two-finger
        // scroll also flows through this callback with smaller magnitudes.
        camera.zoom((float) dyOffset);
    }

    public void onKey(int key, int action, int mods) {
        if (action != GLFW_PRESS) return;
        switch (key) {
            case GLFW_KEY_F -> camera.viewFront();
            case GLFW_KEY_T -> camera.viewTop();
            case GLFW_KEY_S -> camera.viewSide();
            case GLFW_KEY_I -> camera.viewIso();
            case GLFW_KEY_R -> onReset.run();
            case GLFW_KEY_ESCAPE -> onEscape.run();
            case GLFW_KEY_EQUAL, GLFW_KEY_KP_ADD       -> camera.zoom(+1f);
            case GLFW_KEY_MINUS, GLFW_KEY_KP_SUBTRACT  -> camera.zoom(-1f);
            default -> { /* not bound */ }
        }
    }

    /** True when a viewport drag is in progress. UI can read this to suppress
     *  hover effects on parts, show a status indicator, etc. */
    public boolean isDragging() {
        return activeButton != -1;
    }
}
