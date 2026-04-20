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

package app.cadette;

import com.jme3.app.state.BaseAppState;
import com.jme3.app.Application;
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.*;
import com.jme3.math.FastMath;
import com.jme3.math.Ray;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import lombok.RequiredArgsConstructor;

/**
 * Orbital camera controller.
 * - Left-mouse-drag pans the view (shifts the look-at target).
 * - Right-mouse-drag or arrow keys rotate the view around the target.
 * - Mouse wheel zooms in/out.
 */
@RequiredArgsConstructor
public class CameraController extends BaseAppState implements AnalogListener, ActionListener {

    // Keyboard rotation
    private static final String KEY_LEFT     = "CAM_KEY_LEFT";
    private static final String KEY_RIGHT    = "CAM_KEY_RIGHT";
    private static final String KEY_UP       = "CAM_KEY_UP";
    private static final String KEY_DOWN     = "CAM_KEY_DOWN";

    // Mouse rotation (right-drag)
    private static final String MOUSE_LEFT   = "CAM_MOUSE_LEFT";
    private static final String MOUSE_RIGHT  = "CAM_MOUSE_RIGHT";
    private static final String MOUSE_UP     = "CAM_MOUSE_UP";
    private static final String MOUSE_DOWN   = "CAM_MOUSE_DOWN";

    // Mouse panning (left-drag)
    private static final String PAN_LEFT     = "CAM_PAN_LEFT";
    private static final String PAN_RIGHT    = "CAM_PAN_RIGHT";
    private static final String PAN_UP       = "CAM_PAN_UP";
    private static final String PAN_DOWN     = "CAM_PAN_DOWN";

    // Zoom + button triggers
    private static final String ZOOM_IN      = "CAM_ZOOM_IN";
    private static final String ZOOM_OUT     = "CAM_ZOOM_OUT";
    private static final String ROTATE_DRAG  = "CAM_ROTATE_DRAG";
    private static final String PAN_DRAG     = "CAM_PAN_DRAG";
    private static final String SHIFT_KEY    = "CAM_SHIFT";

    private static final float KEYBOARD_SPEED = 2.0f;   // radians/sec
    private static final float MOUSE_SPEED    = 4.0f;
    private static final float PAN_SPEED      = 15.0f;
    private static final float ZOOM_SPEED     = 800.0f;  // scaled for mm
    private static final float MIN_DISTANCE   = 10f;     // ~1cm
    private static final float MAX_DISTANCE   = 50000f;  // ~50 meters

    private static final float CLICK_THRESHOLD = 5f;  // pixels — less than this = click, not drag

    private final Camera cam;
    private final InputManager inputManager;

    private float azimuth   = FastMath.QUARTER_PI;   // horizontal angle
    private float elevation = FastMath.QUARTER_PI * 0.8f; // vertical angle
    private float distance  = 2500f;  // start ~2.5m back
    private final Vector3f target = new Vector3f(0, 0, 0); // look-at point (panning moves this)
    private boolean rotating = false;
    private boolean panning  = false;

    // Click detection: track mouse position on left-press
    private Vector2f leftPressPos = null;
    private boolean shiftHeld = false;
    private SelectionManager selectionManager;
    private Node pickableNode;  // the node to ray-cast against (objectsNode)

    /** Set the selection manager and the node to pick against. */
    public void setSelectionManager(SelectionManager selectionManager, Node pickableNode) {
        this.selectionManager = selectionManager;
        this.pickableNode = pickableNode;
    }

    @Override
    protected void initialize(Application app) {
        // Keyboard mappings — always active
        inputManager.addMapping(KEY_LEFT,  new KeyTrigger(KeyInput.KEY_LEFT));
        inputManager.addMapping(KEY_RIGHT, new KeyTrigger(KeyInput.KEY_RIGHT));
        inputManager.addMapping(KEY_UP,    new KeyTrigger(KeyInput.KEY_UP));
        inputManager.addMapping(KEY_DOWN,  new KeyTrigger(KeyInput.KEY_DOWN));

        // Mouse axis mappings — shared by rotate and pan (distinguished by which button is held)
        inputManager.addMapping(MOUSE_LEFT,  new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping(MOUSE_RIGHT, new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping(MOUSE_UP,    new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        inputManager.addMapping(MOUSE_DOWN,  new MouseAxisTrigger(MouseInput.AXIS_Y, true));

        // Zoom
        inputManager.addMapping(ZOOM_IN,  new MouseAxisTrigger(MouseInput.AXIS_WHEEL, false));
        inputManager.addMapping(ZOOM_OUT, new MouseAxisTrigger(MouseInput.AXIS_WHEEL, true));

        // Button triggers
        inputManager.addMapping(ROTATE_DRAG, new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        inputManager.addMapping(PAN_DRAG,    new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        inputManager.addMapping(SHIFT_KEY,   new KeyTrigger(KeyInput.KEY_LSHIFT),
                                             new KeyTrigger(KeyInput.KEY_RSHIFT));

        inputManager.addListener((ActionListener) this, ROTATE_DRAG, PAN_DRAG, SHIFT_KEY);
        inputManager.addListener((AnalogListener) this,
                KEY_LEFT, KEY_RIGHT, KEY_UP, KEY_DOWN,
                MOUSE_LEFT, MOUSE_RIGHT, MOUSE_UP, MOUSE_DOWN,
                ZOOM_IN, ZOOM_OUT);

        updateCamera();
    }

    @Override
    protected void cleanup(Application app) {
        inputManager.removeListener(this);
    }

    @Override protected void onEnable()  {}
    @Override protected void onDisable() {}

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        switch (name) {
            case ROTATE_DRAG -> rotating = isPressed;
            case SHIFT_KEY -> shiftHeld = isPressed;
            case PAN_DRAG -> {
                panning = isPressed;
                if (isPressed) {
                    leftPressPos = inputManager.getCursorPosition().clone();
                } else if (leftPressPos != null) {
                    Vector2f releasePos = inputManager.getCursorPosition();
                    float dist = leftPressPos.distance(releasePos);
                    if (dist < CLICK_THRESHOLD) {
                        handleClick(releasePos, shiftHeld);
                    }
                    leftPressPos = null;
                }
            }
        }
    }

    /** Ray cast from the camera through the click point and select the hit object. */
    private void handleClick(Vector2f screenPos, boolean shiftDown) {
        if (selectionManager == null || pickableNode == null) return;

        // Build a ray from the camera through the click point
        Vector3f worldPos = cam.getWorldCoordinates(screenPos, 0f);
        Vector3f direction = cam.getWorldCoordinates(screenPos, 1f)
                .subtractLocal(worldPos).normalizeLocal();
        Ray ray = new Ray(worldPos, direction);

        // Collision test against all pickable geometry
        CollisionResults results = new CollisionResults();
        pickableNode.collideWith(ray, results);

        if (results.size() > 0) {
            // Skip outline geometries (they're not real objects)
            for (int i = 0; i < results.size(); i++) {
                CollisionResult hit = results.getCollision(i);
                String geomName = hit.getGeometry().getName();
                if (geomName != null && geomName.startsWith("outline_")) continue;

                String partName = resolvePartName(hit.getGeometry());
                if (partName != null) {
                    selectionManager.selectByPartName(partName, shiftDown);
                    return;
                }
            }
        }

        // Clicked empty space — deselect all (unless shift is held)
        if (!shiftDown) {
            selectionManager.deselect();
        }
    }

    /**
     * Walk up the scene graph from a hit geometry to find the wrapper node
     * (named "node_<partName>") and extract the part name.
     */
    private String resolvePartName(Geometry geom) {
        Spatial current = geom;
        while (current != null) {
            String nodeName = current.getName();
            if (nodeName != null && nodeName.startsWith("node_")) {
                return nodeName.substring(5);  // strip "node_" prefix
            }
            current = current.getParent();
        }
        return null;
    }

    @Override
    public void onAnalog(String name, float value, float tpf) {
        switch (name) {
            // Keyboard rotation — always active
            case KEY_LEFT   -> azimuth -= KEYBOARD_SPEED * value;
            case KEY_RIGHT  -> azimuth += KEYBOARD_SPEED * value;
            case KEY_UP     -> elevation = Math.min(FastMath.HALF_PI - 0.01f, elevation + KEYBOARD_SPEED * value);
            case KEY_DOWN   -> elevation = Math.max(-FastMath.HALF_PI + 0.01f, elevation - KEYBOARD_SPEED * value);

            // Mouse axes — rotate (right-drag) or pan (left-drag)
            case MOUSE_LEFT -> {
                if (rotating)     azimuth -= MOUSE_SPEED * value;
                else if (panning) pan(value, 0);
            }
            case MOUSE_RIGHT -> {
                if (rotating)     azimuth += MOUSE_SPEED * value;
                else if (panning) pan(-value, 0);
            }
            case MOUSE_UP -> {
                if (rotating)     elevation = Math.min(FastMath.HALF_PI - 0.01f, elevation + MOUSE_SPEED * value);
                else if (panning) pan(0, value);
            }
            case MOUSE_DOWN -> {
                if (rotating)     elevation = Math.max(-FastMath.HALF_PI + 0.01f, elevation - MOUSE_SPEED * value);
                else if (panning) pan(0, -value);
            }

            // Zoom
            case ZOOM_IN  -> distance = Math.max(MIN_DISTANCE, distance - ZOOM_SPEED * value);
            case ZOOM_OUT -> distance = Math.min(MAX_DISTANCE, distance + ZOOM_SPEED * value);
        }
        updateCamera();
    }

    /** Pan the look-at target along the camera's local right and up vectors. */
    private void pan(float dx, float dy) {
        Vector3f right = cam.getLeft().negate();
        Vector3f up = cam.getUp();
        float scale = PAN_SPEED * distance / 12f; // pan faster when zoomed out
        target.addLocal(right.mult(dx * scale));
        target.addLocal(up.mult(dy * scale));
    }

    private void updateCamera() {
        float x = distance * FastMath.cos(elevation) * FastMath.sin(azimuth);
        float y = distance * FastMath.sin(elevation);
        float z = distance * FastMath.cos(elevation) * FastMath.cos(azimuth);
        cam.setLocation(target.add(x, y, z));
        cam.lookAt(target, Vector3f.UNIT_Y);
    }
}
