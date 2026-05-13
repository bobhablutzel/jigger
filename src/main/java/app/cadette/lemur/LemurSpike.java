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

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.style.BaseStyles;

/**
 * Smoke test for Lemur UI on jME3. Goal: prove that a button responds to
 * hover and click on the user's Mac, where ImGui's hit-testing silently
 * fails. Lemur renders entirely inside jME3's scene graph and routes
 * input through jME3's InputManager, so it doesn't cross any
 * GLFW/Cocoa thread boundary.
 *
 * <p>If hover changes the button's appearance and clicking prints
 * "[lemur] CLICKED ...", Lemur is a viable alternative to ImGui for
 * the CADette UI rewrite. If not, jME3 itself is the problem and we
 * need to look outside the JVM stack.
 */
public class LemurSpike extends SimpleApplication {

    public static void main(String[] args) {
        System.out.println();
        System.out.println("============================================");
        System.out.println("  CADette LEMUR SPIKE — jME3-native UI test");
        System.out.println("============================================");
        System.out.println();

        LemurSpike app = new LemurSpike();
        AppSettings settings = new AppSettings(true);
        settings.setTitle("[Lemur spike] CADette");
        settings.setResolution(1280, 800);
        settings.setFrameRate(60);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        // 1. Tint the viewport so the spike is visually distinct.
        viewPort.setBackgroundColor(new ColorRGBA(0.10f, 0.18f, 0.22f, 1f));

        // 2. Drop in a cube so the 3D viewport has something to render.
        Box box = new Box(1, 1, 1);
        Geometry geom = new Geometry("Box", box);
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", ColorRGBA.Orange);
        geom.setMaterial(mat);
        rootNode.attachChild(geom);

        // 3. Initialise Lemur and pick the bundled "glass" style.
        GuiGlobals.initialize(this);
        BaseStyles.loadGlassStyle();
        GuiGlobals.getInstance().getStyles().setDefaultStyle("glass");

        // 4. Build a panel with a label and a click-tracking button. Lemur
        //    uses jME3's GUI coordinate system (bottom-left origin), so
        //    Y is measured from the bottom of the window.
        Container panel = new Container();
        panel.addChild(new Label("Lemur smoke test"));
        panel.addChild(new Label("Hover the button. It should highlight."));
        panel.addChild(new Label("Click it. Look for [lemur] CLICKED in the terminal."));
        Button button = panel.addChild(new Button("Click me"));
        button.addClickCommands(src -> {
            System.err.println("[lemur] CLICKED at " + System.currentTimeMillis());
        });

        // Anchor the panel ~100 px in from the left, ~100 px below the top.
        float screenW = settings.getWidth();
        float screenH = settings.getHeight();
        panel.setLocalTranslation(100, screenH - 100, 0);
        guiNode.attachChild(panel);

        // 5. Diagnostic line so we know we got this far on macOS.
        System.err.println("[lemur] simpleInitApp complete, GUI attached at "
                + panel.getLocalTranslation());
    }
}
