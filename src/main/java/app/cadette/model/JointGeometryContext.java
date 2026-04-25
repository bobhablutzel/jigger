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

package app.cadette.model;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

/**
 * World-frame queries that joint cutout inference needs but that don't live
 * on {@link Part} itself — wrapper-origin position and rotation are scene
 * state. Implemented by SceneManager; injected into Joint subclasses so the
 * model layer stays independent of the scene.
 *
 * <p>The wrapper origin is the part's (0, 0, 0) cut-face corner; rotation is
 * applied around that point. Together they place the part in world space.
 */
public interface JointGeometryContext {

    /** World position of the part's wrapper origin (= cut-face corner at local (0,0,0)). */
    Vector3f cornerPosition(String partName);

    /** World rotation applied around the wrapper origin. */
    Quaternion rotation(String partName);
}
