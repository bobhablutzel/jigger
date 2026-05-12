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
import com.jme3.system.AppSettings;

/**
 * Spike entry point for the ImGui-based UI. Runs in jME3 Display mode (no
 * Swing, no AWT canvas embedding), so it bypasses the macOS first-thread
 * problem from the start. UI panels are drawn by Dear ImGui (via imgui-java)
 * over the 3D viewport, all inside a single GLFW window.
 *
 * <p>Goal of the spike: feel out whether the engine-UI workflow holds up
 * end-to-end before committing to the full rewrite. The Swing-based
 * {@code CadetteApp} is unchanged and still the production entry point.
 *
 * <p>Launch with:
 * <pre>
 *   mvn -q exec:java -Dexec.mainClass=app.cadette.imgui.CadetteImGuiSpike
 * </pre>
 *
 * <p>On macOS add {@code -Dexec.args="-XstartOnFirstThread"} or pass
 * {@code -XstartOnFirstThread} via {@code MAVEN_OPTS} — GLFW requires it.
 */
public class CadetteImGuiSpike {

    public static void main(String[] args) {
        // Unmissable startup marker — proves the spike is what's running,
        // not the Swing CadetteApp. Visible in stdout and once more in the
        // command panel via the AppState constructor.
        System.out.println();
        System.out.println("============================================");
        System.out.println("  CADette IMGUI SPIKE — engine-UI mode");
        System.out.println("  (single GLFW window, no Swing, no AWT)");
        System.out.println("============================================");
        System.out.println();

        SceneManager sceneManager = new SceneManager();

        System.out.println( "Created scene manager");

        AppSettings settings = new AppSettings(true);
        System.out.println( "Created settings");
        // Visually distinct title so it can't be confused with CadetteApp's
        // "CADette - 3D Command Shell" window.
        settings.setTitle("[ImGui spike] CADette");
        System.out.println( "Set title");

        settings.setResolution(1600, 1000);
        System.out.println( "Set resolution");
        settings.setFrameRate(60);
        System.out.println( "set frame rate");
        settings.setSamples(Integer.getInteger("cadette.msaa", 4));
        System.out.println( "Set samples" );
        settings.setAudioRenderer(null);
        System.out.println( "Set audio renderer" );
        sceneManager.setSettings(settings);
        System.out.println( "Set scene manager settings" );
        sceneManager.setShowSettings(false);
        System.out.println( "set show settings" );
        sceneManager.setPauseOnLostFocus(false);
        System.out.println( "Set pause on lost focus" );

        CommandExecutor executor = new CommandExecutor(sceneManager);
        System.out.println( "Executor" );
        executor.loadTemplates();
        System.out.println( "Load templates" );

        // Attach the ImGui overlay state before start() so it initialises
        // when the GL context comes up.
        sceneManager.getStateManager().attach(new ImGuiAppState(executor));
        System.out.println( "Attached" );

        // start(Display, true) runs jME3's render+poll loop on the calling
        // thread. With -XstartOnFirstThread, the caller is the JVM main
        // thread = OS thread 0. macOS GLFW polling and Cocoa run-loop
        // dispatch only work on thread 0; default start() spawns a worker
        // and gets stuck.
        System.err.println("[spike] main() calling start(Display, true) on thread="
                + Thread.currentThread().getName());
        sceneManager.start(com.jme3.system.JmeContext.Type.Display, true);
        System.out.println( "Started" );
    }
}
