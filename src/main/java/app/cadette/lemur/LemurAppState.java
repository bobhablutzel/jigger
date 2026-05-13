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
17 */

package app.cadette.lemur;

import app.cadette.CutSheetRenderer;
import app.cadette.SceneManager;
import app.cadette.SelectionManager;
import app.cadette.command.CommandExecutor;
import app.cadette.model.SheetLayout;
import app.cadette.model.SheetLayoutGenerator;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
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
import com.simsilica.lemur.core.GuiControl;
import com.simsilica.lemur.core.VersionedList;
import com.simsilica.lemur.core.VersionedReference;
import com.simsilica.lemur.event.KeyAction;
import com.simsilica.lemur.event.KeyActionListener;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lemur UI shell. Hosts:
 * <ul>
 *   <li>Command panel (right) — TextField + scrolling output, hooked to the
 *       supplied {@link CommandExecutor}. History recall with cross-session
 *       persistence at {@code ~/.cadette/cmd_history}. Up/down arrows
 *       walk history; first up-press saves the in-progress buffer so
 *       down-past-newest can restore it.</li>
 *   <li>Parts panel (left) — ListBox of parts, alphabetically sorted, kept
 *       in sync with the SceneManager via {@code addSceneChangeListener}.
 *       Click a name to select it; selection round-trips through
 *       {@link SelectionManager}, so 3D-viewport clicks also reflect in the
 *       list and vice versa.</li>
 * </ul>
 *
 * <p>The mouseOverUi flag (polled each frame against actual panel bounds)
 * gates the camera controller so scroll-zoom and click-orbit don't fire
 * when the cursor is on a Lemur panel.
 */
@RequiredArgsConstructor
public class LemurAppState extends BaseAppState {

    private static final Path HISTORY_FILE =
            Path.of(System.getProperty("user.home"), ".cadette", "cmd_history");
    private static final int HISTORY_MAX = 200;

    /** Initial split ratios for the hardcoded layout tree. Persistence and
     *  user-saved layouts come in a later session — for now these are
     *  what every launch starts with (and what the user adjusts at runtime
     *  by dragging the dividers). */
    private static final float ROOT_RATIO            = 0.72f; // upper : command
    private static final float UPPER_PARTS_RATIO     = 0.20f; // parts : center
    private static final float CENTER_VIEWPORT_RATIO = 0.62f; // 3D viewport : cut sheet

    private final CommandExecutor executor;
    private final SelectionManager selectionManager;

    private final List<String> history = new ArrayList<>();
    /** -1 means "not recalling — typing fresh input"; ≥0 indexes into history. */
    private int historyPos = -1;
    /** What the user had typed when up-arrow first fired, restored on
     *  down-past-newest so partial input survives a recall round-trip. */
    private String preRecallBuffer = "";

    private Container commandPanel;
    private TextField commandInput;
    private VersionedList<String> outputModel;
    private ListBox<String> outputList;

    private Container partsPanel;
    private VersionedList<String> partsModel;
    private ListBox<String> partsList;
    private VersionedReference<Integer> partsSelectionRef;
    /** Suppresses our own selection-listener echo when we update the ListBox
     *  in response to a SelectionManager change. Without this, list-click →
     *  model update → listener → list-click loops via the polling code. */
    private boolean syncingSelectionToList = false;

    private Container cutSheetPanel;
    /** Container whose background quad displays the rendered cut-sheet
     *  texture. We hold the inner holder rather than the outer panel so
     *  the label header isn't included in the texture-bounded area. */
    private Container cutSheetImageHolder;
    /** Single Texture2D reused across renders — we call setImage() on it
     *  rather than allocating a new GL texture each refresh. */
    private Texture2D cutSheetTexture;
    /** Last rendered pixel size; used to detect panel-size changes that
     *  warrant a re-render even when the scene isn't dirty. */
    private int lastCutSheetW = -1;
    private int lastCutSheetH = -1;

    /** Panels enrolled in the mouseOverUi gate. Their world bounds are
     *  OR'd each frame; the splitter tree decides where each ends up. */
    private final List<Container> gatedPanels = new ArrayList<>();

    /** Root of the layout tree; covers the full window minus jME3 chrome.
     *  Resized in update() when the camera dimensions change. */
    private LemurSplitter rootSplitter;

    /** Every splitter in the tree — needed so we can call updateDrag(...)
     *  on each per frame to advance any active drag. */
    private final List<LemurSplitter> allSplitters = new ArrayList<>();

    /** Last window dimensions we reflowed against. -1 means no reflow yet. */
    private int lastWinW = -1;
    private int lastWinH = -1;

    /**
     * True while the cursor is over any Lemur panel managed by this state.
     * Used by the camera controller as an input gate so scroll-zoom and
     * click-orbit don't fire when the user is interacting with the UI.
     */
    @Getter
    private boolean mouseOverUi = false;

    @Override
    protected void initialize(Application app) {
        loadHistory();

        SimpleApplication simpleApp = (SimpleApplication) app;

        commandPanel = buildCommandPanel();
        partsPanel = buildPartsPanel();
        cutSheetPanel = buildCutSheetPanel();

        // Track which Lemur panels participate in the mouseOverUi gate.
        // Their world bounds shift as the user drags splitter dividers,
        // so the check uses getWorldTranslation() each frame.
        gatedPanels.add(commandPanel);
        gatedPanels.add(partsPanel);
        gatedPanels.add(cutSheetPanel);

        // Build the layout tree. Hardcoded for now; user-saved layouts and
        // drag-to-rearrange are explicit follow-up sessions.
        //
        //   VSplit (ROOT_RATIO):
        //     ├── HSplit (UPPER_PARTS_RATIO):
        //     │     ├── partsPanel
        //     │     └── HSplit (CENTER_VIEWPORT_RATIO):
        //     │           ├── viewportSpacer  (empty Node — 3D shows through)
        //     │           └── cutSheetPanel
        //     └── commandPanel
        Node viewportSpacer = new Node("viewportSpacer");

        LemurSplitter centerSplit = makeSplit(LemurSplitter.Orient.HORIZONTAL,
                viewportSpacer, cutSheetPanel, CENTER_VIEWPORT_RATIO);
        LemurSplitter upperSplit = makeSplit(LemurSplitter.Orient.HORIZONTAL,
                partsPanel, centerSplit, UPPER_PARTS_RATIO);
        rootSplitter = makeSplit(LemurSplitter.Orient.VERTICAL,
                upperSplit, commandPanel, ROOT_RATIO);

        simpleApp.getGuiNode().attachChild(rootSplitter);

        // Position + size against the current camera dimensions. The camera
        // is the source of truth — it tracks the actual GL viewport and
        // updates on window resize, whereas AppSettings holds the initial
        // request that may differ after corner-drag / maximize.
        com.jme3.renderer.Camera cam = simpleApp.getCamera();
        applyRootSize(cam.getWidth(), cam.getHeight());

        // Wire scene → parts list and selection → parts list.
        SceneManager sceneManager = (SceneManager) app;
        sceneManager.addSceneChangeListener(this::refreshPartsList);
        selectionManager.addSelectionListener(this::onSelectionChanged);

        // Populate from whatever's already in the scene (zero on a fresh launch).
        refreshPartsList();

        appendOutput("CADette (Lemur UI). Type 'help' or start adding parts.");
    }

    /** Construct a Splitter with the standard reflow callback and register
     *  it in {@link #allSplitters} so {@link #update} can poll its drag. */
    private LemurSplitter makeSplit(LemurSplitter.Orient orient,
                                    Spatial first, Spatial second, float ratio) {
        LemurSplitter s = new LemurSplitter(orient, first, second, this::onChildReflow);
        s.setRatio(ratio);
        allSplitters.add(s);
        return s;
    }

    /** Reflow callback invoked by Splitter (and later TabHost) for each of
     *  its children when their allotted size changes. Dispatches by spatial
     *  identity to the appropriate inner-widget sizing logic.
     *
     *  <p>For each Lemur Container leaf we set BOTH the container's own
     *  preferred size (so the panel's background fills the splitter cell)
     *  AND the primary inner widget's preferred size (so the content
     *  stretches to fit rather than sitting at its natural minimum).
     *  Without the outer setPreferredSize, the container shrinks to wrap
     *  its children and leaves the rest of the splitter cell empty. */
    private void onChildReflow(Spatial child, float[] size) {
        float w = size[0];
        float h = size[1];
        if (child == partsPanel) {
            partsPanel.setPreferredSize(new Vector3f(w, h, 0));
            partsList.setPreferredSize(new Vector3f(Math.max(40, w - 20),
                                                    Math.max(40, h - 50), 0));
        } else if (child == commandPanel) {
            commandPanel.setPreferredSize(new Vector3f(w, h, 0));
            outputList.setPreferredSize(new Vector3f(Math.max(40, w - 20),
                                                     Math.max(40, h - 80), 0));
            commandInput.setPreferredWidth(Math.max(40, w - 20));
        } else if (child == cutSheetPanel) {
            cutSheetPanel.setPreferredSize(new Vector3f(w, h, 0));
            cutSheetImageHolder.setPreferredSize(new Vector3f(
                    Math.max(40, w - 20), Math.max(40, h - 50), 0));
        } else if (child instanceof LemurSplitter inner) {
            inner.setSize(w, h);
        } else if (child instanceof LemurTabHost tab) {
            tab.setSize(w, h);
        }
        // viewportSpacer and other no-content Spatials: ignore.
    }

    /** Position the root splitter at the top-left of the GUI and size it
     *  to cover the full window. Cascades through the tree via the
     *  reflow callbacks. */
    private void applyRootSize(int winW, int winH) {
        rootSplitter.setLocalTranslation(0, winH, 0);
        rootSplitter.setSize(winW, winH);
        lastWinW = winW;
        lastWinH = winH;
    }

    @Override
    protected void cleanup(Application app) {
        if (rootSplitter != null) rootSplitter.removeFromParent();
    }

    @Override
    protected void onEnable() { /* no-op */ }

    @Override
    protected void onDisable() { /* no-op */ }

    private Container buildCommandPanel() {
        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.addChild(new Label("Command"));

        outputModel = new VersionedList<>();
        outputList = panel.addChild(new ListBox<>(outputModel));

        commandInput = panel.addChild(new TextField(""));
        commandInput.setSingleLine(true);

        KeyActionListener submit = (src, key) -> runCurrentInput();
        commandInput.getActionMap().put(new KeyAction(KeyInput.KEY_RETURN), submit);
        commandInput.getActionMap().put(new KeyAction(KeyInput.KEY_NUMPADENTER), submit);

        commandInput.getActionMap().put(new KeyAction(KeyInput.KEY_UP),
                (src, key) -> recallHistory(-1));
        commandInput.getActionMap().put(new KeyAction(KeyInput.KEY_DOWN),
                (src, key) -> recallHistory(+1));

        GuiGlobals.getInstance().requestFocus(commandInput);
        return panel;
    }

    private Container buildCutSheetPanel() {
        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.addChild(new Label("Cut Sheet"));

        cutSheetImageHolder = panel.addChild(new Container());
        // Dark fill so an unpopulated/cleared cut sheet doesn't show the
        // panel's underlying glass background as a transparent void.
        cutSheetImageHolder.setBackground(new QuadBackgroundComponent(
                new com.jme3.math.ColorRGBA(0.13f, 0.13f, 0.13f, 1f)));
        return panel;
    }

    /** Re-render the cut sheet into the texture backing the bottom-right
     *  panel. Called whenever {@code sceneManager.isCutSheetDirty()} flips
     *  true (parts added, joints changed, kerf changed, etc.) or the
     *  panel's pixel size changes. The pixel size only changes on first
     *  layout or window resize, so this is cheap to keep checking.
     *
     *  <p>One Texture2D is held across renders; we replace its image so
     *  the GL texture is reused rather than leaking handles each refresh. */
    private void refreshCutSheet() {
        if (cutSheetImageHolder == null) return;
        GuiControl gc = cutSheetImageHolder.getControl(GuiControl.class);
        if (gc == null) return;

        Vector3f size = gc.getSize();
        int w = (int) size.x;
        int h = (int) size.y;
        if (w < 50 || h < 50) return; // not yet laid out

        SceneManager sceneManager = (SceneManager) getApplication();
        List<SheetLayout> layouts = SheetLayoutGenerator.generateLayouts(
                sceneManager.getAllParts(), sceneManager.getKerfMm());

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(245, 245, 235));
            g2.fillRect(0, 0, w, h);
            CutSheetRenderer.render(g2, w, h, layouts,
                    executor.getUnits(), false, Set.of(), null,
                    sceneManager.getEffectiveCutouts(),
                    sceneManager.getEffectiveKeeps());
        } finally {
            g2.dispose();
        }

        com.jme3.texture.Image jmeImage = new AWTLoader().load(img, true);
        if (cutSheetTexture == null) {
            cutSheetTexture = new Texture2D(jmeImage);
            cutSheetImageHolder.setBackground(new QuadBackgroundComponent(cutSheetTexture));
        } else {
            cutSheetTexture.setImage(jmeImage);
        }
        lastCutSheetW = w;
        lastCutSheetH = h;
        sceneManager.clearCutSheetDirty();
    }

    private Container buildPartsPanel() {
        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.addChild(new Label("Parts"));

        partsModel = new VersionedList<>();
        partsList = panel.addChild(new ListBox<>(partsModel));
        partsSelectionRef = partsList.getSelectionModel().createSelectionReference();

        return panel;
    }

    /** Repopulate the parts list from the scene. Sorted alphabetically since
     *  the SceneManager's ConcurrentHashMap has no defined order. */
    private void refreshPartsList() {
        if (partsModel == null) return;
        SceneManager sceneManager = (SceneManager) getApplication();
        // TreeSet for stable ordering; SceneManager.getAllParts returns
        // a Map<String,Part> with unspecified iteration order.
        List<String> sorted = new ArrayList<>(new TreeSet<>(sceneManager.getAllParts().keySet()));
        partsModel.clear();
        partsModel.addAll(sorted);
    }

    /** Fired when SelectionManager's state changes (from any source — list
     *  click, 3D click, command). Mirror the new selection into the parts
     *  list so the visible highlight matches. */
    private void onSelectionChanged(SelectionManager.SelectionChange change) {
        if (partsList == null) return;
        List<String> names = change.selectedNames();
        Integer target = null;
        if (!names.isEmpty()) {
            int idx = partsModel.indexOf(names.get(0));
            if (idx >= 0) target = idx;
        }
        Integer current = partsList.getSelectionModel().getSelection();
        if (Objects.equals(current, target)) {
            return; // already in the right state — avoid bumping the version
        }
        syncingSelectionToList = true;
        if (target == null) {
            // Lemur's SelectionModel doesn't tolerate setSelection(null) —
            // its renderer later does selection.intValue() unconditionally
            // and NPEs. Clear the underlying Set instead; that bumps the
            // version cleanly and getSelection() returns null afterward
            // without the renderer touching it.
            partsList.getSelectionModel().clear();
        } else {
            partsList.getSelectionModel().setSelection(target);
        }
        syncingSelectionToList = false;
    }

    @Override
    public void update(float tpf) {
        super.update(tpf);

        // 0. Detect window resize via the camera (source of truth — tracks
        //    the actual GL viewport size, unlike AppSettings which only
        //    holds the initial request). Reflow before everything else so
        //    bounds checks below see the new panel positions.
        com.jme3.renderer.Camera cam = getApplication().getCamera();
        if (cam.getWidth() != lastWinW || cam.getHeight() != lastWinH) {
            applyRootSize(cam.getWidth(), cam.getHeight());
        }

        // 0a. Advance any active splitter drags. The Splitter's button
        //     listener flips its dragging flag; we poll cursor + update
        //     the ratio here so a drag that strays off the divider still
        //     tracks.
        InputManager input = getApplication().getInputManager();
        for (LemurSplitter s : allSplitters) {
            s.updateDrag(input);
        }

        // 1. Refresh mouseOverUi from actual panel bounds. Panels live
        //    inside the splitter tree now so we use getWorldTranslation()
        //    rather than getLocalTranslation() — local would be relative
        //    to the splitter's coords.
        Vector2f cursor = input.getCursorPosition();
        boolean over = false;
        if (cursor != null) {
            for (Container panel : gatedPanels) {
                if (cursorOverPanel(cursor, panel)) {
                    over = true;
                    break;
                }
            }
        }
        mouseOverUi = over;

        // 2. Poll list-selection reference; on user-driven change, propagate
        //    to SelectionManager. The syncingSelectionToList guard blocks
        //    the SelectionManager → list → here echo path.
        if (partsSelectionRef != null && partsSelectionRef.update()
                && !syncingSelectionToList) {
            Integer idx = partsSelectionRef.get();
            if (idx != null && idx >= 0 && idx < partsModel.size()) {
                String name = partsModel.get(idx);
                if (!List.of(name).equals(selectionManager.getSelectedNames())) {
                    selectionManager.selectByPartName(name, false);
                }
            }
        }

        // 3. Cut-sheet refresh on scene-dirty or panel-size change.
        //    isCutSheetDirty is set by SceneManager on every mutation that
        //    affects the layout (parts added, joints changed, kerf changed).
        //    First-frame check fires once when the panel's GuiControl has
        //    finally laid out its size.
        SceneManager sceneManager = (SceneManager) getApplication();
        if (cutSheetImageHolder != null) {
            GuiControl gc = cutSheetImageHolder.getControl(GuiControl.class);
            int curW = gc == null ? -1 : (int) gc.getSize().x;
            int curH = gc == null ? -1 : (int) gc.getSize().y;
            boolean sizeChanged = (curW != lastCutSheetW || curH != lastCutSheetH);
            if (sceneManager.isCutSheetDirty() || sizeChanged || cutSheetTexture == null) {
                refreshCutSheet();
            }
        }
    }

    private boolean cursorOverPanel(Vector2f cursor, Container panel) {
        Vector3f loc = panel.getWorldTranslation();
        GuiControl gc = panel.getControl(GuiControl.class);
        if (gc == null) return false;
        Vector3f size = gc.getSize();
        return cursor.x >= loc.x
            && cursor.x <= loc.x + size.x
            && cursor.y >= loc.y - size.y
            && cursor.y <= loc.y;
    }

    private void runCurrentInput() {
        String cmd = commandInput.getText();
        if (cmd == null || cmd.isBlank()) {
            return;
        }
        appendOutput("> " + cmd);
        pushHistory(cmd);
        historyPos = -1;
        try {
            String result = executor.execute(cmd);
            if (result != null && !result.isEmpty()) {
                for (String line : result.split("\n", -1)) {
                    appendOutput(line);
                }
            }
        } catch (Throwable t) {
            appendOutput("Error: " + t.getMessage());
        }
        commandInput.setText("");
    }

    private void appendOutput(String line) {
        outputModel.add(line);
        if (outputList != null) {
            outputList.getSelectionModel().setSelection(outputModel.size() - 1);
        }
    }

    private void recallHistory(int direction) {
        if (history.isEmpty()) {
            return;
        }
        if (historyPos == -1) {
            preRecallBuffer = commandInput.getText() == null ? "" : commandInput.getText();
            if (direction < 0) {
                historyPos = history.size() - 1;
            } else {
                return;
            }
        } else {
            historyPos += direction;
            if (historyPos < 0) {
                historyPos = 0;
            }
            if (historyPos >= history.size()) {
                historyPos = -1;
                commandInput.setText(preRecallBuffer);
                return;
            }
        }
        commandInput.setText(history.get(historyPos));
    }

    private void pushHistory(String cmd) {
        if (!history.isEmpty() && history.get(history.size() - 1).equals(cmd)) {
            return;
        }
        history.add(cmd);
        if (history.size() > HISTORY_MAX) {
            history.subList(0, history.size() - HISTORY_MAX).clear();
        }
        saveHistory();
    }

    private void loadHistory() {
        try {
            if (Files.exists(HISTORY_FILE)) {
                List<String> lines = Files.readAllLines(HISTORY_FILE);
                for (String line : lines) {
                    if (!line.isEmpty()) history.add(line);
                }
                if (history.size() > HISTORY_MAX) {
                    history.subList(0, history.size() - HISTORY_MAX).clear();
                }
            }
        } catch (Exception ignored) { /* fresh start */ }
    }

    private void saveHistory() {
        try {
            Files.createDirectories(HISTORY_FILE.getParent());
            Files.write(HISTORY_FILE, history);
        } catch (Exception ignored) { /* non-fatal */ }
    }
}
