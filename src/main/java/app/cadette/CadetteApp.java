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

import app.cadette.command.CommandExecutor;
import app.cadette.lemur.LemurAppState;
import app.cadette.theme.ThemeRegistry;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.style.BaseStyles;
import com.simsilica.lemur.style.Styles;

import java.util.ArrayList;
import java.util.List;

/**
 * CADette entry point. Subclasses {@link SceneManager} (which is a jME3
 * SimpleApplication) so the 3D viewport, orbital camera, lighting, and
 * part-management infrastructure are reused as-is, then layers a Lemur-based
 * UI shell on top via {@link LemurAppState}.
 *
 * <p>Launch: {@code mvn exec:exec}. The build passes
 * {@code -XstartOnFirstThread} on macOS (via the auto-activated
 * {@code mac-thread} profile) and {@code -Djava.awt.headless=true} on every
 * platform — the latter dodges the AWT/Toolkit ↔ GLFW thread-0 deadlock
 * that wedged earlier UI prototypes on macOS.
 *
 * <p>The UI implementation lives in {@code app.cadette.lemur} (LemurAppState
 * + LemurSplitter + LemurTabHost); this class wires it to the scene.
 */
public class CadetteApp extends SceneManager {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("============================================");
        System.out.println("  CADette");
        System.out.println("============================================");
        System.out.println();

        CadetteApp app = new CadetteApp();
        AppSettings settings = new AppSettings(true);
        settings.setTitle("CADette");
        settings.setResolution(1280, 800);
        settings.setFrameRate(60);
        settings.setSamples(Integer.getInteger("cadette.msaa", 4));
        settings.setAudioRenderer(null);
        // Corner-drag / maximize support. LemurAppState detects the new
        // dimensions in update() and reflows panel positions accordingly.
        settings.setResizable(true);
        // Gamma correction OFF: jME3 3.9 defaults this on, which makes the
        // framebuffer sRGB-encode at output. For our 2D-heavy UI we want
        // theme hex values to map 1:1 to displayed pixels — otherwise
        // sRGB encoding makes hex pick → render ≠ what the user picked.
        // Trade-off: 3D shading isn't sRGB-correct, but the cuboid
        // woodworking previews use Unshaded materials so it doesn't show.
        settings.setGammaCorrection(false);
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

        // SimpleApplication binds Esc to "SIMPLEAPP_Exit" by default —
        // surprising for a CAD shell where Esc is more naturally a
        // "cancel current op" or "focus viewport" key. Drop the default
        // here; broader keybinding rethink is in
        // project_keybindings_backlog.md.
        if (getInputManager().hasMapping(INPUT_MAPPING_EXIT)) {
            getInputManager().deleteMapping(INPUT_MAPPING_EXIT);
        }

        CommandExecutor executor = new CommandExecutor(this);
        executor.loadTemplates();
        executor.setOnExit(this::stop);

        // SelectionManager is the single source of truth for what's
        // selected. The 3D viewport's CameraController and the Lemur
        // parts panel both drive it; a listener mirrors selection state
        // into the 3D highlight.
        SelectionManager selectionManager = new SelectionManager(this);
        setSelectionManager(selectionManager);
        wireSelectionHighlights(selectionManager);

        // Lemur init — must happen after the GL context is up (i.e. inside
        // simpleInitApp, not before start()).
        GuiGlobals.initialize(this);
        BaseStyles.loadGlassStyle();
        GuiGlobals.getInstance().getStyles().setDefaultStyle("glass");

        // Load + apply the active theme. ThemeRegistry reads .cdt YAML
        // files from resources/themes/ (bundled) and ~/.cadette/themes/
        // (user overlays), and writes their per-element attributes into
        // Lemur's Styles object. Default theme is "dark"; the active
        // theme persists to disk via CommandExecutor and is restored on
        // launch.
        //
        // `set theme <name>` updates the Styles for NEW widgets but does
        // NOT live-restyle existing widgets — that path turned out to be
        // a Lemur layout-cascade hornet's nest (see
        // project_theme_listbox_collapse_backlog.md). Theme changes take
        // effect on the next launch; the visitor's response prompts the
        // user accordingly.
        Styles styles = GuiGlobals.getInstance().getStyles();
        ThemeRegistry themeRegistry = new ThemeRegistry(getAssetManager());
        executor.setThemeRegistry(themeRegistry);
        themeRegistry.applyTheme(executor.getThemeName(), styles);

        // 3D viewport background driven by the active theme. We read the
        // styled "viewport" selector's background after applyTheme has
        // populated it, then push the color onto the actual jME3 viewPort.
        // (SceneManager already set a placeholder in simpleInitApp; this
        // overrides it once theme load is done.)
        Object viewportBg = styles.getSelector("viewport", "glass").get("background");
        if (viewportBg instanceof com.simsilica.lemur.component.QuadBackgroundComponent qbc) {
            getViewPort().setBackgroundColor(qbc.getColor().clone());
        }

        LemurAppState uiState = new LemurAppState(executor, selectionManager);
        getStateManager().attach(uiState);

        // Gate the camera controller so scroll-zoom and click-orbit don't
        // fire when the cursor is over a Lemur panel. Without this, wheel
        // events both scroll the ListBox and zoom the 3D viewport.
        if (getCameraController() != null) {
            getCameraController().setInputBlocker(uiState::isMouseOverUi);
        }

        System.err.println("[cadette] init complete");
    }

    /** Mirror SelectionManager state into 3D viewport highlights. */
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
