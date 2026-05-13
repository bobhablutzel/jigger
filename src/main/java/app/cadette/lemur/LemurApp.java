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
import app.cadette.SelectionManager;
import app.cadette.command.CommandExecutor;
import com.jme3.system.AppSettings;
import com.jme3.math.ColorRGBA;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.style.BaseStyles;
import com.simsilica.lemur.style.Styles;

import java.util.ArrayList;
import java.util.List;

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
        // Allow the user to corner-drag / maximize the window. LemurAppState
        // detects the new dimensions in update() and reflows panel positions
        // and inner-widget preferred sizes.
        settings.setResizable(true);
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

        // SelectionManager is the single source of truth for what's
        // selected. The 3D viewport's CameraController and the Lemur
        // parts panel both drive it; a listener mirrors selection state
        // into the 3D highlight (same pattern as CadetteApp).
        SelectionManager selectionManager = new SelectionManager(this);
        setSelectionManager(selectionManager);
        wireSelectionHighlights(selectionManager);

        // Lemur init — must happen after the GL context is up (i.e. inside
        // simpleInitApp, not before start()).
        GuiGlobals.initialize(this);
        BaseStyles.loadGlassStyle();
        GuiGlobals.getInstance().getStyles().setDefaultStyle("glass");

        // Tighten the default glass style: 12pt font reduces the dead
        // leading between rows. Themeable backlog covers a proper
        // light/dark + font-family/size selector; this is the placeholder
        // that makes the current build readable. Apply to every selector
        // that renders text — labels, the command textfield, list items
        // (ListBox cells), and buttons.
        //
        // Also flatten the TextField background — the glass style ships
        // a tinted/gradient background that reads as distracting noise
        // for a CAD command shell. A flat dark grey is cleaner.
        Styles styles = GuiGlobals.getInstance().getStyles();
        float font = 12f;
        styles.getSelector("label",     "glass").set("fontSize", font);
        styles.getSelector("textField", "glass").set("fontSize", font);
        styles.getSelector("items",     "glass").set("fontSize", font);
        styles.getSelector("button",    "glass").set("fontSize", font);
        styles.getSelector("textField", "glass").set("background",
                new QuadBackgroundComponent(new ColorRGBA(0.18f, 0.18f, 0.20f, 0.92f)));

        LemurAppState uiState = new LemurAppState(executor, selectionManager);
        getStateManager().attach(uiState);

        // Gate the camera controller so scroll-zoom and click-orbit don't
        // fire when the cursor is over a Lemur panel. Without this, wheel
        // events both scroll the ListBox and zoom the 3D viewport.
        if (getCameraController() != null) {
            getCameraController().setInputBlocker(uiState::isMouseOverUi);
        }

        System.err.println("[lemur-app] init complete");
    }

    /** Mirror SelectionManager state into 3D viewport highlights — same wiring
     *  pattern Swing's CadetteApp uses, so Lemur and Swing selection look
     *  identical from the 3D side. */
    private void wireSelectionHighlights(SelectionManager selectionManager) {
        List<String> previousHighlights = new ArrayList<>();
        selectionManager.addSelectionListener(event -> {
            for (String name : previousHighlights) {
                setHighlight(name, false);
            }
            previousHighlights.clear();
            for (String name : selectionManager.getSelectedPartNames()) {
                setHighlight(name, true);
                previousHighlights.add(name);
            }
        });
    }
}
