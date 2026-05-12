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

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

/**
 * CAD-style orbit camera. Parameterised by a focal point + spherical coords
 * (azimuth, elevation, distance) rather than position + lookat angles, so
 * orbit/pan/zoom operations are straightforward and the camera always
 * frames the focal point.
 *
 * <p>World up is +Y (jME3 default). Azimuth rotates around Y; elevation is
 * the angle above the X-Z plane.
 *
 * <p>Distance is in scene units (mm in Cadette's case). Defaults frame the
 * origin from roughly a metre away — most parts and small assemblies fit
 * comfortably without explicit setup.
 */
public class OrbitCamera {

    private final Camera jmeCamera;
    private final Vector3f focalPoint = new Vector3f(0, 0, 0);
    private float azimuthRad;
    private float elevationRad;
    private float distance;

    // Defaults — saved so the "reset" view can restore them.
    private static final float DEFAULT_AZIMUTH = FastMath.PI / 4f;          // 45°
    private static final float DEFAULT_ELEVATION = FastMath.PI / 6f;        // 30°
    private static final float DEFAULT_DISTANCE = 1500f;                    // 1.5 m
    private static final Vector3f DEFAULT_FOCAL = new Vector3f(0, 0, 0);

    // Elevation must stay clear of the poles or look-at goes singular.
    private static final float ELEVATION_LIMIT = FastMath.PI / 2f - 0.01f;
    private static final float MIN_DISTANCE = 10f;
    private static final float MAX_DISTANCE = 100_000f;

    public OrbitCamera(Camera jmeCamera) {
        this.jmeCamera = jmeCamera;
        reset();
    }

    // ---- Operations -------------------------------------------------------

    /** Drag-deltas in pixels. Positive dx = mouse right, positive dy = down. */
    public void orbit(float dxPixels, float dyPixels) {
        float sensitivity = 0.006f;
        azimuthRad -= dxPixels * sensitivity;
        elevationRad += dyPixels * sensitivity;
        elevationRad = clamp(elevationRad, -ELEVATION_LIMIT, ELEVATION_LIMIT);
        applyToCamera();
    }

    /** Drag-deltas in pixels; pan speed scales with distance so the world
     *  travels under the cursor at roughly constant pixel-rate. */
    public void pan(float dxPixels, float dyPixels) {
        float scale = distance * 0.0012f;
        Vector3f right = jmeCamera.getLeft().negate();   // jME's "Left" is camera-leftward
        Vector3f up = jmeCamera.getUp();
        focalPoint.subtractLocal(right.mult(dxPixels * scale));
        focalPoint.addLocal(up.mult(dyPixels * scale));
        applyToCamera();
    }

    /** Positive delta zooms in (closer to focal point). One scroll-wheel
     *  detent is one unit of delta in GLFW; we scale to a comfortable step. */
    public void zoom(float delta) {
        float factor = (float) Math.pow(1.15f, -delta);
        distance = clamp(distance * factor, MIN_DISTANCE, MAX_DISTANCE);
        applyToCamera();
    }

    // ---- Preset views -----------------------------------------------------

    public void viewFront() { azimuthRad = 0;                elevationRad = 0;                 applyToCamera(); }
    public void viewSide()  { azimuthRad = FastMath.HALF_PI; elevationRad = 0;                 applyToCamera(); }
    public void viewTop()   { azimuthRad = 0;                elevationRad = ELEVATION_LIMIT;   applyToCamera(); }
    public void viewIso()   { azimuthRad = DEFAULT_AZIMUTH;  elevationRad = DEFAULT_ELEVATION; applyToCamera(); }

    public void reset() {
        focalPoint.set(DEFAULT_FOCAL);
        azimuthRad = DEFAULT_AZIMUTH;
        elevationRad = DEFAULT_ELEVATION;
        distance = DEFAULT_DISTANCE;
        applyToCamera();
    }

    // ---- Apply state to jME3 camera ---------------------------------------

    private void applyToCamera() {
        float cosE = FastMath.cos(elevationRad);
        float sinE = FastMath.sin(elevationRad);
        float cosA = FastMath.cos(azimuthRad);
        float sinA = FastMath.sin(azimuthRad);
        Vector3f offset = new Vector3f(
                distance * cosE * sinA,
                distance * sinE,
                distance * cosE * cosA);
        jmeCamera.setLocation(focalPoint.add(offset));
        jmeCamera.lookAt(focalPoint, Vector3f.UNIT_Y);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
