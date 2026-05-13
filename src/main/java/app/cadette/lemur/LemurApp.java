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

package app.cadette.lemur;

import app.cadette.SceneManager;
import app.cadette.command.CommandExecutor;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.style.BaseStyles;

/**
 * Entry point for the Lemur-based CADette UI. Subclasses {@link SceneManager}
 * so the existing 3D viewport, orbital camera, lighting, and part-management
 * infrastructure are reused as-is. On top of that we initialise Lemur and
 * attach {@link LemurAppState} for the UI panels.
 *
 * <p>Parallel to the Swing {@code CadetteApp} and the ImGui spike
 * {@code CadetteImGuiSpike}. All three drive the same {@link SceneManager}
 * model layer; only the UI shell differs.
 *
 * <p>Launch: {@code mvn -Plemur exec:exec}. The {@code lemur} Maven profile
 * passes {@code -XstartOnFirstThread} on macOS (via the auto-activated
 * {@code mac-thread} profile) and {@code -Djava.awt.headless=true} on every
 * platform — the latter dodges the AWT/Toolkit ↔ GLFW thread-0 deadlock
 * that wedged the four-panel spike on macOS prior to commit 438e6d5.
 */
public class LemurApp extends SceneManager {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("============================================");
        System.out.println("  CADette — Lemur UI");
        System.out.println("============================================");
        System.out.println();

        LemurApp app = new LemurApp();
        AppSettings settings = new AppSettings(true);
        settings.setTitle("CADette");
        settings.setResolution(1280, 800);
        settings.setFrameRate(60);
        settings.setSamples(Integer.getInteger("cadette.msaa", 4));
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        // SceneManager.simpleInitApp() disables flyCam, attaches the orbital
        // CameraController, sets up the axis display, lights, and the scene
        // background colour. Whatever else we add here is layered on top.
        super.simpleInitApp();

        CommandExecutor executor = new CommandExecutor(this);
        executor.loadTemplates();

        // Lemur init — must happen after the GL context is up (i.e. inside
        // simpleInitApp, not before start()).
        GuiGlobals.initialize(this);
        BaseStyles.loadGlassStyle();
        GuiGlobals.getInstance().getStyles().setDefaultStyle("glass");

        getStateManager().attach(new LemurAppState(executor));

        System.err.println("[lemur-app] init complete");
    }
}
