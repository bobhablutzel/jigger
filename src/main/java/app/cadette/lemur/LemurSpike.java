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
import com.jme3.input.KeyInput;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.ListBox;
import com.simsilica.lemur.TextField;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.core.VersionedList;
import com.simsilica.lemur.core.VersionedReference;
import com.simsilica.lemur.event.KeyAction;
import com.simsilica.lemur.event.KeyActionListener;
import com.simsilica.lemur.style.BaseStyles;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Lemur viability spike — four-panel mock that exercises the widgets a real
 * CADette port will need. Goal: prove or disprove that Lemur covers our
 * day-to-day surface before committing to a multi-week rewrite.
 *
 * <p>Layout (window is split into quarters; top-left is the 3D viewport,
 * the other three quarters host Lemur panels):
 * <pre>
 *   ┌──────────────┬──────────────┐
 *   │  3D viewport │  Parts list  │   (top-left = scene; top-right = ListBox)
 *   ├──────────────┼──────────────┤
 *   │ Command panel│ Cut sheet    │   (text input + scrolling output / textured quad)
 *   └──────────────┴──────────────┘
 * </pre>
 *
 * <p>Each panel answers one specific unknown about Lemur:
 * <ul>
 *   <li><b>Parts list</b> — does {@code ListBox<String>} bind cleanly to a
 *       {@code VersionedList}, scroll when items overflow, and report
 *       selection?</li>
 *   <li><b>Command panel</b> — does {@code TextField} take input, fire on
 *       Enter (via {@code getActionMap()}), and can we pair it with a
 *       scrolling ListBox for output?</li>
 *   <li><b>Cut sheet</b> — does the Java2D → BufferedImage → Texture2D →
 *       {@code QuadBackgroundComponent(Texture)} path render a panel
 *       background correctly? This is the pattern we used in
 *       {@code ImGuiCutSheetPanel}; if Lemur takes the texture directly,
 *       the port is trivial.</li>
 *   <li><b>Layout</b> — do three independently-positioned containers
 *       coexist with the 3D scene, and does input route correctly (UI
 *       absorbs clicks that hit it; clicks on the viewport pass through)?</li>
 * </ul>
 */
public class LemurSpike extends SimpleApplication {

    private static final float PANEL_PAD = 10;

    private VersionedList<String> outputLines;
    private TextField commandInput;
    private Container partsPanel;
    private Container commandPanel;
    private Container cutSheetPanel;
    private VersionedList<String> partsModel;
    private VersionedReference<Integer> partsSelectionRef;

    public static void main(String[] args) {
        System.out.println();
        System.out.println("============================================");
        System.out.println("  CADette LEMUR SPIKE — four-panel viability");
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
        viewPort.setBackgroundColor(new ColorRGBA(0.10f, 0.18f, 0.22f, 1f));

        // 3D viewport content — an orange cube so the top-left quarter
        // has something to render.
        Box box = new Box(1, 1, 1);
        Geometry geom = new Geometry("Box", box);
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", ColorRGBA.Orange);
        geom.setMaterial(mat);
        rootNode.attachChild(geom);

        // Lemur init + glass style.
        GuiGlobals.initialize(this);
        BaseStyles.loadGlassStyle();
        GuiGlobals.getInstance().getStyles().setDefaultStyle("glass");

        buildPartsPanel();
        buildCommandPanel();
        buildCutSheetPanel();
        layoutPanels();

        System.err.println("[lemur] four-panel spike ready. Try:");
        System.err.println("  - Click a part in the top-right list (should highlight).");
        System.err.println("  - Type in the Command field, press Enter.");
        System.err.println("  - Resize the window — panels stay anchored to their corners.");
    }

    /** Top-right: a ListBox of fake parts to test selection + scroll. */
    private void buildPartsPanel() {
        partsPanel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        partsPanel.addChild(new Label("Parts"));

        partsModel = new VersionedList<>();
        partsModel.add("base");
        partsModel.add("left side");
        partsModel.add("right side");
        partsModel.add("top");
        partsModel.add("back");
        partsModel.add("shelf 1");
        partsModel.add("shelf 2");
        partsModel.add("shelf 3");
        partsModel.add("door L");
        partsModel.add("door R");
        partsModel.add("face frame top rail");
        partsModel.add("face frame bottom rail");
        partsModel.add("face frame left stile");
        partsModel.add("face frame right stile");

        ListBox<String> listBox = partsPanel.addChild(new ListBox<>(partsModel));
        listBox.setPreferredSize(new Vector3f(280, 320, 0));

        // Lemur uses versioned-reference polling for change detection rather
        // than callbacks. Capture the reference here; simpleUpdate() reads it.
        partsSelectionRef = listBox.getSelectionModel().createSelectionReference();
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (partsSelectionRef != null && partsSelectionRef.update()) {
            Integer idx = partsSelectionRef.get();
            if (idx != null && idx >= 0 && idx < partsModel.size()) {
                System.err.println("[lemur] selected: " + partsModel.get(idx));
            }
        }
    }

    /** Bottom-left: TextField + scrolling output ListBox to test command flow. */
    private void buildCommandPanel() {
        commandPanel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        commandPanel.addChild(new Label("Command"));

        outputLines = new VersionedList<>();
        outputLines.add("> set units cm");
        outputLines.add("> add 30 60 base color brown");
        outputLines.add("added base at (0, 0, 0)");
        outputLines.add("> add 30 60 left rotate 90");
        outputLines.add("added left at (0, 0, 0) rot 90");
        outputLines.add("(this is a mock — Enter appends two lines)");

        ListBox<String> output = commandPanel.addChild(new ListBox<>(outputLines));
        output.setPreferredSize(new Vector3f(560, 260, 0));

        commandInput = commandPanel.addChild(new TextField(""));
        commandInput.setPreferredWidth(560);
        commandInput.setSingleLine(true);

        KeyActionListener submit = (source, key) -> submitCommand(output);
        commandInput.getActionMap().put(new KeyAction(KeyInput.KEY_RETURN), submit);
        commandInput.getActionMap().put(new KeyAction(KeyInput.KEY_NUMPADENTER), submit);

        // Auto-focus so user can type immediately.
        GuiGlobals.getInstance().requestFocus(commandInput);
    }

    private void submitCommand(ListBox<String> output) {
        String cmd = commandInput.getText();
        if (cmd == null || cmd.isBlank()) {
            return;
        }
        outputLines.add("> " + cmd);
        outputLines.add("  (mock parsed: '" + cmd + "')");
        commandInput.setText("");
        // Scroll to bottom by selecting the last row.
        output.getSelectionModel().setSelection(outputLines.size() - 1);
        System.err.println("[lemur] command submitted: " + cmd);
    }

    /** Bottom-right: panel whose background is a Java2D-rendered texture. */
    private void buildCutSheetPanel() {
        cutSheetPanel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        cutSheetPanel.addChild(new Label("Cut Sheet"));

        int w = 560;
        int h = 280;
        BufferedImage img = renderFakeCutSheet(w, h);

        AWTLoader loader = new AWTLoader();
        Image jmeImage = loader.load(img, true);
        Texture2D texture = new Texture2D(jmeImage);

        // Inner panel whose background quad displays the texture. The
        // QuadBackgroundComponent(Texture) constructor is the win here —
        // no manual Material / Geometry plumbing, Lemur handles sizing
        // and z-ordering inside its container hierarchy.
        Container imageHolder = cutSheetPanel.addChild(new Container());
        imageHolder.setBackground(new QuadBackgroundComponent(texture));
        imageHolder.setPreferredSize(new Vector3f(w, h, 0));
    }

    /** Mock cut-sheet image — three rectangles + labels, roughly what
     *  {@code CutSheetRenderer.render} would produce, minus the real layout. */
    private BufferedImage renderFakeCutSheet(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(245, 245, 235));
            g2.fillRect(0, 0, w, h);

            g2.setColor(new Color(180, 140, 90));
            g2.fillRect(20, 20, 200, 100);
            g2.fillRect(20, 140, 200, 100);
            g2.fillRect(240, 20, 300, 220);

            g2.setColor(new Color(80, 60, 40));
            g2.setStroke(new java.awt.BasicStroke(1.5f));
            g2.drawRect(20, 20, 200, 100);
            g2.drawRect(20, 140, 200, 100);
            g2.drawRect(240, 20, 300, 220);

            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Dialog", Font.PLAIN, 11));
            g2.drawString("base  60 × 30", 30, 38);
            g2.drawString("left side  60 × 30", 30, 158);
            g2.drawString("top  90 × 60", 250, 38);

            g2.setColor(new Color(120, 120, 120));
            g2.setFont(new Font("Dialog", Font.ITALIC, 9));
            g2.drawString("(mock — proves Java2D → Lemur texture path)", 20, h - 8);
        } finally {
            g2.dispose();
        }
        return img;
    }

    /** Position the three Lemur panels in the three quarters that aren't
     *  the 3D viewport. Top-left is left empty so the cube is visible. */
    private void layoutPanels() {
        float W = settings.getWidth();
        float H = settings.getHeight();
        float halfW = W / 2f;
        float halfH = H / 2f;

        // Lemur containers anchor at top-left of their bounding box;
        // jME3 GUI Y axis is bottom-up.
        partsPanel.setLocalTranslation(halfW + PANEL_PAD, H - PANEL_PAD, 0);
        commandPanel.setLocalTranslation(PANEL_PAD, halfH - PANEL_PAD, 0);
        cutSheetPanel.setLocalTranslation(halfW + PANEL_PAD, halfH - PANEL_PAD, 0);

        guiNode.attachChild(partsPanel);
        guiNode.attachChild(commandPanel);
        guiNode.attachChild(cutSheetPanel);
    }
}
