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
import app.cadette.UnitSystem;
import app.cadette.command.CommandExecutor;
import app.cadette.model.Assembly;
import app.cadette.model.Part;
import app.cadette.model.SheetLayout;
import app.cadette.model.SheetLayoutGenerator;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.ListBox;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.TextField;
import com.simsilica.lemur.ValueRenderer;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.core.GuiComponent;
import com.simsilica.lemur.core.GuiControl;
import com.simsilica.lemur.core.VersionedList;
import com.simsilica.lemur.event.CursorButtonEvent;
import com.simsilica.lemur.event.CursorEventControl;
import com.simsilica.lemur.event.DefaultCursorListener;
import com.simsilica.lemur.event.MouseEventControl;
import com.simsilica.lemur.event.MouseListener;
import com.simsilica.lemur.event.KeyAction;
import com.simsilica.lemur.event.KeyActionListener;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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
    private static final float ROOT_RATIO             = 0.72f; // upper : command
    private static final float UPPER_LEFT_RATIO       = 0.20f; // left column : center
    private static final float LEFT_PARTS_RATIO       = 0.55f; // parts tree : properties
    private static final float CENTER_VIEWPORT_RATIO  = 0.62f; // 3D viewport : cut sheet

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
    /** TextField background component we own (cloned from the styled one
     *  at construction) so we can mutate its color on focus change without
     *  bleeding to every other widget sharing the style instance. */
    private QuadBackgroundComponent commandInputBg;
    private ColorRGBA commandInputRestingBg;
    private ColorRGBA commandInputFocusedBg;
    /** Last-seen focus state for commandInput, to detect transitions and
     *  only update the background when it actually changes. */
    private boolean commandInputFocused = false;
    private VersionedList<String> outputModel;
    private ListBox<String> outputList;

    private Container partsPanel;
    /** Body container for the parts tree — rows are added as child
     *  Containers with their own click handlers. Matches the Properties
     *  pane structure (Container-of-rows, FillMode.None on major axis)
     *  to avoid the GridPanel cell-stretch behaviour ListBox has. */
    private Container partsBody;
    /** Currently selected row index in {@link #treeRows}; -1 = no selection.
     *  Drives the highlight tint on the row Container. */
    private int selectedRowIndex = -1;

    /** Hierarchical row descriptors parallel to the child Containers in
     *  {@link #partsBody}. Index i in the displayed tree corresponds to
     *  {@code treeRows.get(i)} and tells us whether row i is an assembly
     *  header (caret-prefixed) or a child/standalone part, plus the
     *  underlying name to act on. */
    private final List<TreeRow> treeRows = new ArrayList<>();

    /** Set of assembly names currently expanded. Tree click on an assembly
     *  header toggles membership here and triggers a rebuild. */
    private final Set<String> expandedAssemblies = new HashSet<>();

    private Container propertiesPanel;
    /** Body container inside the properties panel — gets cleared and
     *  rebuilt with a fresh set of Label children on each selection change.
     *  Held separately from the outer panel so the header Label survives. */
    private Container propertiesBody;

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

    /** Drop-target nodes (TabHosts holding panels, plus placeholders)
     *  enrolled in the mouseOverUi gate. Their world bounds are OR'd
     *  each frame; the splitter tree decides where each ends up. */
    private final List<Node> gatedPanels = new ArrayList<>();

    /** Root of the layout tree; covers the full window minus jME3 chrome.
     *  Resized in update() when the camera dimensions change. */
    private LemurSplitter rootSplitter;

    /** Every splitter in the tree — needed so we can call updateDrag(...)
     *  on each per frame to advance any active drag. */
    private final List<LemurSplitter> allSplitters = new ArrayList<>();

    /** Last window dimensions we reflowed against. -1 means no reflow yet. */
    private int lastWinW = -1;
    private int lastWinH = -1;

    /** Previous frame's drag state — used to detect drag-release and
     *  trigger a final reflow with up-to-date visibleItems. */
    private boolean wasDragging = false;

    // ---- Drag-to-rearrange state ----------------------------------------
    /** Set of panels (and TabHosts) eligible to be drag sources / drop
     *  targets. Viewport spacer + the splitters themselves stay out. */
    private final java.util.List<com.jme3.scene.Node> draggablePanels = new ArrayList<>();
    /** Stable display label for each draggable panel — used as the tab
     *  title when wrapping a panel into a TabHost on drop. */
    private final java.util.Map<com.jme3.scene.Node, String> panelLabels =
            new java.util.HashMap<>();
    /** Currently-dragged panel; null if no drag in progress. */
    private com.jme3.scene.Node draggingPanel = null;
    /** The TabHost the dragging panel is currently in. Captured at drag
     *  promotion. Used to exclude the source from drop-hover detection
     *  and from completeDrag's move dispatch — dropping on the panel's
     *  own host should be a no-op, not a detach-and-readd round-trip
     *  (which would leave the panel orphaned because the empty source
     *  TabHost gets collapsed to a placeholder before the readd). */
    private LemurTabHost dragSourceHost = null;
    /** Currently-hovered drop target during a drag; null if cursor is
     *  not over any other panel. */
    private com.jme3.scene.Node dragHoverTarget = null;
    /** Translucent overlay Container attached as a child of
     *  {@link #dragHoverTarget} during drag-hover. Reused across hovers;
     *  attached/detached as the target changes. */
    private Container dragHoverOverlay = null;
    /** Tint applied to a drop target to telegraph "release here to drop". */
    private static final com.jme3.math.ColorRGBA DROP_TARGET_BG =
            new com.jme3.math.ColorRGBA(0.30f, 0.50f, 0.75f, 0.40f);
    private static final float DRAG_THRESHOLD_PX = 6f;
    /** A press has happened on a draggable header but cursor hasn't moved
     *  far enough yet to commit to a drag. Promoted to {@link #draggingPanel}
     *  once the cursor crosses {@link #DRAG_THRESHOLD_PX}. */
    private com.jme3.scene.Node dragCandidate = null;
    private com.jme3.math.Vector2f dragCandidateStart = null;

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
        propertiesPanel = buildPropertiesPanel();
        cutSheetPanel = buildCutSheetPanel();

        // Wrap each panel in its own TabHost. Always-tabbed model: even
        // a single-panel region is a TabHost so the UX is consistent
        // (tab button = drag handle = title, regardless of how many
        // panels share the region).
        LemurTabHost partsHost      = wrapInTabHost(partsPanel,      "Parts");
        LemurTabHost propertiesHost = wrapInTabHost(propertiesPanel, "Properties");
        LemurTabHost cutSheetHost   = wrapInTabHost(cutSheetPanel,   "Cut Sheet");
        LemurTabHost commandHost    = wrapInTabHost(commandPanel,    "Command");

        // Each TabHost participates in the mouseOverUi gate — its bounds
        // cover both tab bar and content, so the gate kicks in correctly
        // for either area.
        gatedPanels.add(partsHost);
        gatedPanels.add(propertiesHost);
        gatedPanels.add(cutSheetHost);
        gatedPanels.add(commandHost);

        // Build the layout tree. Hardcoded for now; user-saved layouts
        // are an explicit follow-up session.
        //
        //   VSplit (ROOT_RATIO):
        //     ├── HSplit (UPPER_LEFT_RATIO):
        //     │     ├── VSplit (LEFT_PARTS_RATIO):
        //     │     │     ├── partsHost       (TabHost wrapping parts)
        //     │     │     └── propertiesHost  (TabHost wrapping properties)
        //     │     └── HSplit (CENTER_VIEWPORT_RATIO):
        //     │           ├── viewportSpacer  (empty Node — 3D shows through)
        //     │           └── cutSheetHost
        //     └── commandHost
        Node viewportSpacer = new Node("viewportSpacer");

        LemurSplitter leftSplit = makeSplit(LemurSplitter.Orient.VERTICAL,
                partsHost, propertiesHost, LEFT_PARTS_RATIO);
        leftSplit.setMinSizes(150, 180);

        LemurSplitter centerSplit = makeSplit(LemurSplitter.Orient.HORIZONTAL,
                viewportSpacer, cutSheetHost, CENTER_VIEWPORT_RATIO);
        centerSplit.setMinSizes(160, 240);

        LemurSplitter upperSplit = makeSplit(LemurSplitter.Orient.HORIZONTAL,
                leftSplit, centerSplit, UPPER_LEFT_RATIO);
        upperSplit.setMinSizes(200, 420);

        rootSplitter = makeSplit(LemurSplitter.Orient.VERTICAL,
                upperSplit, commandHost, ROOT_RATIO);
        rootSplitter.setMinSizes(200, 80);

        simpleApp.getGuiNode().attachChild(rootSplitter);

        // Restore persisted splitter ratios + active tabs over the
        // hardcoded defaults so the layout the user last left is what
        // they get back on next launch. Listeners attached AFTER this
        // so the restore's setActive() calls don't trigger a save with
        // the partially-populated state.
        restoreLayoutProperties();
        for (LemurTabHost host : allTabHosts) {
            host.setActiveChangeListener(this::saveLayoutProperties);
        }

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
        refreshProperties();

        appendOutput("CADette. Type 'help' or start adding parts.");
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

    /** True while at least one splitter divider is being dragged. Used to
     *  defer expensive / step-jittery layout updates (notably ListBox
     *  visibleItems) until the drag finishes. */
    private boolean isAnySplitterDragging() {
        for (LemurSplitter s : allSplitters) {
            if (s.isDragging()) return true;
        }
        return false;
    }

    /** Target pixel height per ListBox row. Drives the dynamic
     *  visibleItems calculation in {@link #onChildReflow} — Lemur's
     *  GridPanel stretches cells to fill the ListBox's allocated
     *  height, so the way to keep cells compact is to compute
     *  visibleItems = listHeight / TARGET_ROW_H. */
    private static final float TARGET_ROW_H = 18f;

    /** Approximate pixel width per glyph at the current 12pt font. Used
     *  for the output-truncation math; not load-bearing, just needs to
     *  be in the right ballpark so the ellipsis lands near the right
     *  edge rather than mid-cell. */
    private static final float OUTPUT_GLYPH_W = 7f;

    /** Width currently allotted to the output ListBox. Set in
     *  onChildReflow for commandPanel; consumed by
     *  {@link #truncateForOutput} to decide how aggressively to ellipsis
     *  each row's text. */
    private float outputCellWidth = 0f;

    /** Reflow callback invoked by Splitter (and later TabHost) for each of
     *  its children when their allotted size changes. Dispatches by spatial
     *  identity to the appropriate inner-widget sizing logic.
     *
     *  <p>For each Lemur Container leaf we set BOTH the container's own
     *  preferred size (so the panel's background fills the splitter cell)
     *  AND the primary inner widget's preferred size (so the content
     *  stretches to fit rather than sitting at its natural minimum).
     *  Without the outer setPreferredSize, the container shrinks to wrap
     *  its children and leaves the rest of the splitter cell empty.
     *
     *  <p>ListBoxes additionally get a dynamic {@code setVisibleItems}
     *  matched to their allocated height — that keeps row height roughly
     *  constant ({@link #TARGET_ROW_H}) regardless of how much vertical
     *  space the panel has, instead of stretching a handful of rows to
     *  fill the panel. */
    private void onChildReflow(Spatial child, float[] size) {
        float w = size[0];
        float h = size[1];
        if (child == partsPanel) {
            // Panel headers removed (tab button now provides the title);
            // body subtracts only padding, not header-height.
            partsPanel.setPreferredSize(new Vector3f(w, h, 0));
            partsBody.setPreferredSize(new Vector3f(
                    Math.max(40, w - 10), Math.max(20, h - 10), 0));
            if (!isAnySplitterDragging()) {
                refreshPartsList();
            }
        } else if (child == commandPanel) {
            commandPanel.setPreferredSize(new Vector3f(w, h, 0));
            // Reserve ~40px for the input TextField + padding.
            float listH = Math.max(40, h - 40);
            float listW = Math.max(40, w - 10);
            outputList.setPreferredSize(new Vector3f(listW, listH, 0));
            // Cell width = list width minus a slider/padding allowance.
            outputCellWidth = Math.max(20, listW - 24);
            if (!isAnySplitterDragging()) {
                outputList.setVisibleItems(Math.max(3, (int) (listH / TARGET_ROW_H)));
                // Force the ListBox to re-render visible cells through the
                // cell renderer with the new outputCellWidth (and thus a
                // new truncation budget). Bumping the model version via
                // clear+addAll is the cleanest trigger; cost is fine at
                // the output sizes we expect.
                rerenderOutputCells();
            }
            commandInput.setPreferredWidth(Math.max(40, w - 10));
        } else if (child == cutSheetPanel) {
            cutSheetPanel.setPreferredSize(new Vector3f(w, h, 0));
            cutSheetImageHolder.setPreferredSize(new Vector3f(
                    Math.max(40, w - 10), Math.max(40, h - 10), 0));
        } else if (child == propertiesPanel) {
            propertiesPanel.setPreferredSize(new Vector3f(w, h, 0));
            propertiesBody.setPreferredSize(new Vector3f(
                    Math.max(40, w - 10), Math.max(20, h - 10), 0));
        } else if (child instanceof LemurSplitter inner) {
            inner.setSize(w, h);
        } else if (child instanceof LemurTabHost tab) {
            tab.setSize(w, h);
        } else if (child instanceof Container c && placeholders.contains(c)) {
            // Drop-target placeholder — without this it stays at its
            // natural label-sized footprint and the rest of the splitter
            // slot shows 3D through.
            c.setPreferredSize(new Vector3f(w, h, 0));
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
        panelLabels.put(panel, "Command");

        outputModel = new VersionedList<>();
        outputList = panel.addChild(new ListBox<>(outputModel));
        // Custom cell renderer: dynamically truncates each line to fit
        // the current cell width. Lemur's TextComponent.reshape always
        // sets the BitmapText box to the layout-allocated width — so
        // a Label.setMaxWidth(0) doesn't actually disable wrap; reshape
        // overrides it. Truncating with ellipsis at render time
        // sidesteps the issue: lines never wrap because they never
        // exceed the cell width.
        outputList.setCellRenderer(new ValueRenderer<String>() {
            @Override
            public void configureStyle(com.simsilica.lemur.style.ElementId parent, String style) {
                // No-op — per-Label properties applied in getView.
            }
            @Override
            public Panel getView(String value, boolean selected, Panel existing) {
                Label lbl;
                if (existing instanceof Label l) {
                    lbl = l;
                } else {
                    lbl = new Label("");
                    lbl.setTextHAlignment(HAlignment.Left);
                }
                lbl.setText(truncateForOutput(value));
                return lbl;
            }
        });

        commandInput = panel.addChild(new TextField(""));
        commandInput.setSingleLine(true);
        // Inset so the caret at column 0 has breathing room from the field's
        // left edge (otherwise it sits flush against the bg and is hard to
        // spot in opaque themes).
        commandInput.setInsets(new com.simsilica.lemur.Insets3f(3, 6, 3, 6));

        // Focus indicator: clone the styled background so we own the
        // QuadBackgroundComponent (otherwise mutating its color would
        // tint every textField sharing the style instance). Hold the
        // resting + focused colors; update() polls focus and flips between
        // them so the user can tell when the command line is active.
        if (commandInput.getBackground() instanceof QuadBackgroundComponent themed) {
            ColorRGBA resting = themed.getColor().clone();
            commandInputBg = new QuadBackgroundComponent(resting.clone());
            commandInput.setBackground(commandInputBg);
            commandInputRestingBg = resting;
            // Subtle brighten — bigger amounts compounded through the
            // gamma chain and made the field look light grey when focused.
            commandInputFocusedBg = lighten(resting, 0.06f);
        }

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
        panelLabels.put(panel, "Cut Sheet");

        // Image holder gets no explicit background — when no cut sheet has
        // been rendered yet, the panel's glass style shows through, matching
        // the other panes. Once content lands, the QuadBackgroundComponent
        // is swapped to the rendered texture.
        cutSheetImageHolder = panel.addChild(new Container());
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
            // Leave the image transparent — the panel's glass background
            // shows through wherever the renderer doesn't draw, matching
            // the other panes' translucency.
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
        panelLabels.put(panel, "Parts");

        // Container-of-rows; FillMode.None on Y keeps each row at natural
        // Label height (no cell-stretching like ListBox's GridPanel does).
        // Each row is added by refreshPartsList() with its own click handler.
        // Trade-off: no built-in scrolling — backlog for when designs
        // routinely have more parts than the panel can show.
        partsBody = panel.addChild(new Container(
                new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.Last)));
        return panel;
    }

    private Container buildPropertiesPanel() {
        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panelLabels.put(panel, "Properties");
        // FillMode.None on the major (Y) axis stops the body from
        // stretching its rows to fill the panel's vertical space —
        // each property row stays at its natural Label height; extra
        // space falls below the last row.
        propertiesBody = panel.addChild(new Container(
                new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.Last)));
        return panel;
    }

    // -------------------------------------------------------------------
    // Drag-to-rearrange
    // -------------------------------------------------------------------

    /** Wrap a panel in its own single-tab TabHost. The TabHost provides
     *  the visible title (its tab button) AND the drag handle — pressing
     *  the tab button is what starts a drag. Always-tabbed model: every
     *  panel lives in a TabHost from the start so dropping a panel back
     *  to its original slot still leaves it tabbed (consistent UX,
     *  user-flagged decision 2026-05-14). */
    private LemurTabHost wrapInTabHost(Container panel, String label) {
        LemurTabHost host = new LemurTabHost(this::onChildReflow);
        allTabHosts.add(host);
        draggablePanels.add(host);
        host.addTab(label, panel);
        wireTabButtonDrag(host, 0);
        return host;
    }

    /** Common press-handling for drag handles (panel headers + tab
     *  buttons). Defensively clears any stale draggingPanel from a
     *  previous interaction the release listener might have missed,
     *  then arms the new candidate. */
    private void startDragCandidate(com.jme3.scene.Node panel, float x, float y) {
        if (draggingPanel != null) {
            clearDropHighlight();
            draggingPanel = null;
        }
        dragCandidate = panel;
        dragCandidateStart = new Vector2f(x, y);
    }

    /** Press AND release are handled per-widget by the same listener
     *  (CursorEventControl for header Labels, MouseEventControl for tab
     *  Buttons). Lemur's PickEventSession captures press-to-release on
     *  the source spatial — same pattern LemurSplitter uses for the
     *  divider — so release reliably fires on the same listener that
     *  saw the press, even if the cursor moved elsewhere. */
    private void handleDragRelease() {
        if (draggingPanel != null) {
            completeDrag();
        }
        dragCandidate = null;
        dragCandidateStart = null;
    }

    /** Per-frame drag bookkeeping called from {@link #update}. Promotes a
     *  press to a drag once the cursor moves past the threshold; tracks
     *  which panel the cursor is over during the drag and tints that
     *  panel as a drop-zone hint. */
    private void updateDragState(InputManager input) {
        Vector2f cursor = input.getCursorPosition();
        if (cursor == null) return;

        // Promote candidate → actual drag once cursor moved enough.
        if (dragCandidate != null && draggingPanel == null && dragCandidateStart != null) {
            float dx = cursor.x - dragCandidateStart.x;
            float dy = cursor.y - dragCandidateStart.y;
            if (dx * dx + dy * dy > DRAG_THRESHOLD_PX * DRAG_THRESHOLD_PX) {
                draggingPanel = dragCandidate;
                dragSourceHost = findTabHostFor(draggingPanel);
            }
        }

        // Update which drop target the cursor is currently over.
        // Skip: the dragging panel itself, the source TabHost (dropping
        // there would be a no-op), and detached nodes.
        if (draggingPanel != null) {
            com.jme3.scene.Node hover = null;
            for (com.jme3.scene.Node p : draggablePanels) {
                if (p == draggingPanel) continue;
                if (p == dragSourceHost) continue;
                if (p.getParent() == null) continue;
                if (cursorOverDropTarget(cursor, p)) {
                    hover = p;
                    break;
                }
            }
            if (hover != dragHoverTarget) {
                clearDropHighlight();
                if (hover != null) {
                    applyDropHighlight(hover);
                }
                dragHoverTarget = hover;
            }
        }
    }

    /** Tint a drop-target node by attaching a translucent overlay
     *  Container sized to match the target. Works for both TabHosts
     *  (which don't have setBackground) and placeholder Containers. */
    private void applyDropHighlight(com.jme3.scene.Node target) {
        if (dragHoverOverlay == null) {
            dragHoverOverlay = new Container();
            dragHoverOverlay.setBackground(new QuadBackgroundComponent(DROP_TARGET_BG));
        }
        float w = 0, h = 0;
        if (target instanceof LemurTabHost t) {
            w = t.getCurrentWidth();
            h = t.getCurrentHeight();
        } else if (target instanceof Container c) {
            GuiControl gc = c.getControl(GuiControl.class);
            if (gc != null) {
                Vector3f s = gc.getSize();
                w = s.x;
                h = s.y;
            }
        }
        dragHoverOverlay.setPreferredSize(new Vector3f(w, h, 0));
        dragHoverOverlay.setLocalTranslation(0, 0, 0.5f);
        target.attachChild(dragHoverOverlay);
    }

    private void clearDropHighlight() {
        if (dragHoverOverlay != null && dragHoverOverlay.getParent() != null) {
            dragHoverOverlay.removeFromParent();
        }
        dragHoverTarget = null;
    }

    /** Called on mouse-up after a drag. Moves the dragged panel onto the
     *  drop target — wrapping the target in a TabHost (or adding to an
     *  existing one) and leaving an empty placeholder in the source slot.
     *  No-op if the target is the panel's own source host. */
    private void completeDrag() {
        if (dragHoverTarget != null
                && dragHoverTarget != draggingPanel
                && dragHoverTarget != dragSourceHost) {
            movePanelOnto(draggingPanel, dragHoverTarget);
        }
        clearDropHighlight();
        draggingPanel = null;
        dragSourceHost = null;
    }

    /** Move {@code moving} (a panel content) into the drop {@code target}
     *  (a TabHost or placeholder). Always-tabbed model: every panel
     *  lives in a TabHost from initial layout onward, so the target is
     *  always either an existing TabHost (append as tab) or a
     *  placeholder Container in a Splitter slot (replace placeholder
     *  with a fresh single-tab TabHost wrapping moving). */
    private void movePanelOnto(com.jme3.scene.Node moving, com.jme3.scene.Node target) {
        String movingLabel = panelLabels.getOrDefault(moving, "Panel");

        // Pull moving out of its current TabHost. If that empties the
        // source TabHost, the empty host is replaced with a placeholder
        // (handled inside detachFromHost).
        detachFromHost(moving);

        // Target is an "(empty)" placeholder — replace it with a new
        // single-tab TabHost wrapping the moving panel.
        if (target instanceof Container tc && placeholders.contains(tc)) {
            com.jme3.scene.Node tcParent = tc.getParent();
            if (tcParent instanceof LemurSplitter splitter) {
                LemurTabHost newHost = new LemurTabHost(this::onChildReflow);
                newHost.setActiveChangeListener(this::saveLayoutProperties);
                allTabHosts.add(newHost);
                draggablePanels.add(newHost);
                gatedPanels.add(newHost);
                splitter.replaceChild(tc, newHost);
                newHost.addTab(movingLabel, moving);
                wireTabButtonDrag(newHost, 0);
            }
            placeholders.remove(tc);
            draggablePanels.remove(tc);
            gatedPanels.remove(tc);
            panelLabels.remove(tc);
            return;
        }

        // Target is an existing TabHost — append as another tab.
        if (target instanceof LemurTabHost existing) {
            existing.addTab(movingLabel, moving);
            wireTabButtonDrag(existing, existing.getTabCount() - 1);
            existing.setActive(existing.getTabCount() - 1);
        }
    }

    /** Remove a panel content from its current TabHost. If the TabHost
     *  is left empty (had only one tab), it gets replaced in its parent
     *  Splitter by a placeholder so the layout doesn't collapse to dead
     *  space. Handles both active and inactive tabs — an inactive tab's
     *  content has no scene-graph parent but is still in the TabHost's
     *  tabs list, so we locate the host via {@link #findTabHostFor}. */
    private void detachFromHost(com.jme3.scene.Node panel) {
        LemurTabHost host = findTabHostFor(panel);
        if (host == null) return;
        host.removeTab(panel);
        if (host.getTabCount() == 0) {
            com.jme3.scene.Node hostParent = host.getParent();
            if (hostParent instanceof LemurSplitter splitter) {
                Container placeholder = makePlaceholder();
                splitter.replaceChild(host, placeholder);
                allTabHosts.remove(host);
                draggablePanels.remove(host);
                gatedPanels.remove(host);
            }
        }
    }

    /** Find the TabHost that currently holds {@code content} as one of
     *  its tabs (active or inactive). Returns null if the content isn't
     *  in any tracked TabHost. */
    private LemurTabHost findTabHostFor(com.jme3.scene.Spatial content) {
        for (LemurTabHost h : allTabHosts) {
            if (h.indexOf(content) >= 0) return h;
        }
        return null;
    }

    /** Empty placeholder shown in a Splitter slot after the panel that
     *  was there has been dragged elsewhere. Holds the slot open until
     *  another panel is dropped on it. Registered in {@link #placeholders}
     *  and {@link #draggablePanels} so the drag-hover system recognises
     *  it as a drop target (but doesn't itself become draggable — it
     *  has no header). */
    private Container makePlaceholder() {
        Container c = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        // Explicit background sampled from the theme's container style.
        // The bare-Container path doesn't always pick up the styled bg
        // (depends on when in the construction cycle the style cascade
        // runs), so we force it here to make sure the placeholder doesn't
        // show 3D through.
        Object themedBg = GuiGlobals.getInstance().getStyles()
                .getSelector("container", "glass").get("background");
        if (themedBg instanceof QuadBackgroundComponent qbc) {
            c.setBackground(new QuadBackgroundComponent(qbc.getColor().clone()));
        }
        Label l = new Label("(drop a panel here)");
        l.setTextHAlignment(HAlignment.Center);
        c.addChild(l);
        placeholders.add(c);
        draggablePanels.add(c);
        gatedPanels.add(c);
        panelLabels.put(c, "(empty)");
        return c;
    }

    /** All TabHosts we've created. Tracked so future drag operations
     *  can recognise them as containers and add to them. */
    private final List<LemurTabHost> allTabHosts = new ArrayList<>();

    /** Containers that are "(empty)" placeholders left in a Splitter slot
     *  after a panel was dragged elsewhere. Tracked separately so the
     *  drop logic can recognise them and just replace them rather than
     *  wrap in a TabHost. */
    private final Set<Container> placeholders = new java.util.HashSet<>();

    /** Wire drag detection onto a tab button so the user can grab a tab
     *  by its button and move the panel elsewhere. The Button's own
     *  click command (switch active tab) still fires when the press is
     *  followed by a release without moving past the drag threshold —
     *  Lemur Buttons only fire click on release-over-button, which a
     *  drag-away naturally cancels. */
    private void wireTabButtonDrag(LemurTabHost host, int tabIndex) {
        com.simsilica.lemur.Button button = host.getTabButton(tabIndex);
        Spatial content = host.getTabContent(tabIndex);
        // Lemur's Button uses MouseEventControl/MouseListener for its
        // internal click handling. A CursorListener attached via
        // CursorEventControl on the same Button never fires — Button
        // appears to dispatch events through MouseEventControl
        // exclusively. So use the matching channel here.
        MouseEventControl.addListenersToSpatial(button, new MouseListener() {
            @Override
            public void mouseButtonEvent(com.jme3.input.event.MouseButtonEvent e,
                                         Spatial target, Spatial capture) {
                if (e.getButtonIndex() != 0) return;
                if (!(content instanceof com.jme3.scene.Node n)) return;
                if (e.isPressed()) {
                    startDragCandidate(n, e.getX(), e.getY());
                } else {
                    handleDragRelease();
                }
                // Don't consume — Button's own click handling needs the
                // release to fire for click-without-drag (tab switch).
            }
            @Override public void mouseEntered(com.jme3.input.event.MouseMotionEvent e,
                                                Spatial t, Spatial c) {}
            @Override public void mouseExited(com.jme3.input.event.MouseMotionEvent e,
                                               Spatial t, Spatial c) {}
            @Override public void mouseMoved(com.jme3.input.event.MouseMotionEvent e,
                                              Spatial t, Spatial c) {}
        });
    }

    /** Selection highlight tint applied to the currently-selected parts row. */
    private static final com.jme3.math.ColorRGBA ROW_SELECTED_BG =
            new com.jme3.math.ColorRGBA(0.20f, 0.40f, 0.65f, 0.55f);

    /** Repopulate the hierarchical parts tree from the scene. Assemblies
     *  appear first (alphabetical), each with a ▶ / ▼ caret reflecting
     *  expansion state. Expanded assemblies are followed by their child
     *  parts (indented). Standalone parts (in no assembly) come last,
     *  alphabetical.
     *
     *  <p>Each row becomes a child Container of {@link #partsBody} with a
     *  click handler installed; the selected row gets a tinted background. */
    private void refreshPartsList() {
        if (partsBody == null) return;
        SceneManager sceneManager = (SceneManager) getApplication();

        treeRows.clear();

        // Assemblies, alphabetical. Each with its parts when expanded.
        TreeMap<String, Assembly> sortedAssemblies = new TreeMap<>(sceneManager.getAllAssemblies());
        Set<String> assemblyMembers = new HashSet<>();
        for (Assembly a : sortedAssemblies.values()) {
            for (Part p : a.getParts()) {
                assemblyMembers.add(p.getName());
            }
        }
        for (var entry : sortedAssemblies.entrySet()) {
            String aName = entry.getKey();
            boolean expanded = expandedAssemblies.contains(aName);
            treeRows.add(new TreeRow((expanded ? "▼ " : "▶ ") + aName, aName, true));
            if (expanded) {
                List<Part> parts = new ArrayList<>(entry.getValue().getParts());
                parts.sort((a, b) -> a.getName().compareTo(b.getName()));
                for (Part p : parts) {
                    String fullName = p.getName();
                    int slash = fullName.indexOf('/');
                    String slug = slash >= 0 ? fullName.substring(slash + 1) : fullName;
                    treeRows.add(new TreeRow("    " + slug, fullName, false));
                }
            }
        }

        // Standalone parts (in no assembly) follow, alphabetical.
        List<String> standalone = new ArrayList<>(
                new TreeSet<>(sceneManager.getAllParts().keySet()));
        standalone.removeAll(assemblyMembers);
        for (String name : standalone) {
            treeRows.add(new TreeRow(name, name, false));
        }

        // Determine how many rows fit in the body's allocated height.
        // Lemur Containers don't clip overflow, so without truncation
        // the rows just keep rendering down past the panel boundary into
        // whatever's below — causing the parts/properties overlap the
        // user hit. Reserve one slot for the "... N more" indicator when
        // we actually truncate.
        float bodyH = (partsBody.getPreferredSize() != null)
                ? partsBody.getPreferredSize().y : 1000f;
        int maxRows = Math.max(1, (int) (bodyH / TARGET_ROW_H));
        boolean truncated = treeRows.size() > maxRows;
        int rendered = truncated ? Math.max(0, maxRows - 1) : treeRows.size();

        partsBody.clearChildren();
        for (int i = 0; i < rendered; i++) {
            final int rowIndex = i;
            TreeRow row = treeRows.get(i);
            Container rowC = new Container(
                    new SpringGridLayout(Axis.X, Axis.Y, FillMode.None, FillMode.Last));
            Label lbl = new Label(row.displayText);
            lbl.setTextHAlignment(HAlignment.Left);
            rowC.addChild(lbl);
            if (rowIndex == selectedRowIndex) {
                rowC.setBackground(new QuadBackgroundComponent(ROW_SELECTED_BG));
            }
            CursorEventControl.addListenersToSpatial(rowC, new DefaultCursorListener() {
                @Override
                public void cursorButtonEvent(CursorButtonEvent e,
                                              com.jme3.scene.Spatial t,
                                              com.jme3.scene.Spatial c) {
                    if (e.getButtonIndex() != 0 || !e.isPressed()) return;
                    handlePartsRowClick(rowIndex);
                    e.setConsumed();
                }
            });
            partsBody.addChild(rowC);
        }
        if (truncated) {
            int hidden = treeRows.size() - rendered;
            Container moreRow = new Container(
                    new SpringGridLayout(Axis.X, Axis.Y, FillMode.None, FillMode.Last));
            Label more = new Label("... " + hidden + " more");
            more.setTextHAlignment(HAlignment.Left);
            moreRow.addChild(more);
            partsBody.addChild(moreRow);
        }
    }

    /** Click dispatch for a parts-tree row. Assembly headers toggle
     *  expansion + select the assembly; part rows just select the part. */
    private void handlePartsRowClick(int idx) {
        if (idx < 0 || idx >= treeRows.size()) return;
        TreeRow row = treeRows.get(idx);
        if (row.isAssembly) {
            if (expandedAssemblies.contains(row.target)) {
                expandedAssemblies.remove(row.target);
            } else {
                expandedAssemblies.add(row.target);
            }
            if (!List.of(row.target).equals(selectionManager.getSelectedNames())) {
                selectionManager.selectAssembly(row.target, false);
                // selectionManager listener will refresh; nothing more here.
            } else {
                // Same assembly clicked → only the expansion toggled.
                refreshPartsList();
            }
        } else {
            if (!List.of(row.target).equals(selectionManager.getSelectedNames())) {
                selectionManager.selectByPartName(row.target, false);
            }
        }
    }

    /** Fired when SelectionManager's state changes (from any source — list
     *  click, 3D click, command). Auto-expands the parent assembly when a
     *  child part is selected from elsewhere, recomputes which tree row is
     *  highlighted, rebuilds the parts list so the tint moves, and refreshes
     *  the properties pane. */
    private void onSelectionChanged(SelectionManager.SelectionChange change) {
        if (partsBody == null) return;
        List<String> names = change.selectedNames();
        String first = names.isEmpty() ? null : names.get(0);

        if (first != null) {
            int slash = first.indexOf('/');
            if (slash > 0) {
                expandedAssemblies.add(first.substring(0, slash));
            }
        }
        // Predict the future tree-row layout (so we know which row index
        // will host the new selection), then rebuild with that index set
        // so the rebuilt rows get the tint on the right one.
        selectedRowIndex = (first != null) ? indexOfTargetIn(first, computeFutureRows()) : -1;
        refreshPartsList();
        refreshProperties();
    }

    /** Replicates the treeRows computation without mutating state, for the
     *  rare case we need to predict the new selectedRowIndex before
     *  {@link #refreshPartsList} runs. */
    private List<TreeRow> computeFutureRows() {
        SceneManager sm = (SceneManager) getApplication();
        List<TreeRow> rows = new ArrayList<>();
        TreeMap<String, Assembly> sortedAssemblies = new TreeMap<>(sm.getAllAssemblies());
        Set<String> assemblyMembers = new HashSet<>();
        for (Assembly a : sortedAssemblies.values()) {
            for (Part p : a.getParts()) assemblyMembers.add(p.getName());
        }
        for (var entry : sortedAssemblies.entrySet()) {
            String aName = entry.getKey();
            boolean expanded = expandedAssemblies.contains(aName);
            rows.add(new TreeRow((expanded ? "▼ " : "▶ ") + aName, aName, true));
            if (expanded) {
                List<Part> parts = new ArrayList<>(entry.getValue().getParts());
                parts.sort((a, b) -> a.getName().compareTo(b.getName()));
                for (Part p : parts) {
                    String full = p.getName();
                    int slash = full.indexOf('/');
                    String slug = slash >= 0 ? full.substring(slash + 1) : full;
                    rows.add(new TreeRow("    " + slug, full, false));
                }
            }
        }
        List<String> standalone = new ArrayList<>(new TreeSet<>(sm.getAllParts().keySet()));
        standalone.removeAll(assemblyMembers);
        for (String name : standalone) rows.add(new TreeRow(name, name, false));
        return rows;
    }

    private int indexOfTargetIn(String target, List<TreeRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).target.equals(target)) return i;
        }
        return -1;
    }

    /** Repopulate the Properties body from the current selection. Read-only
     *  v1 — Cadette is script-driven so values change via commands, not
     *  by editing in this panel. */
    private void refreshProperties() {
        if (propertiesBody == null) return;
        propertiesBody.clearChildren();

        SceneManager sm = (SceneManager) getApplication();
        List<String> names = selectionManager.getSelectedNames();
        if (names.isEmpty()) {
            propertiesBody.addChild(new Label("Nothing selected."));
            return;
        }
        if (names.size() > 1) {
            propertiesBody.addChild(new Label(names.size() + " items selected:"));
            for (String n : names) {
                propertiesBody.addChild(new Label("  " + n));
            }
            return;
        }
        String name = names.get(0);
        Assembly assembly = sm.getAssembly(name);
        if (assembly != null) {
            addProperty("Assembly", name);
            addProperty("Parts",    String.valueOf(assembly.getParts().size()));
            // List part names; truncate to keep the pane manageable.
            int shown = 0;
            for (Part p : assembly.getParts()) {
                if (shown++ >= 10) {
                    propertiesBody.addChild(new Label("  ... and "
                            + (assembly.getParts().size() - 10) + " more"));
                    break;
                }
                String slug = p.getName();
                int slash = slug.indexOf('/');
                if (slash >= 0) slug = slug.substring(slash + 1);
                propertiesBody.addChild(new Label("  " + slug));
            }
            return;
        }
        Part part = sm.getPart(name);
        if (part != null) {
            UnitSystem u = executor.getUnits();
            String displayName = name;
            int slash = displayName.indexOf('/');
            if (slash >= 0) displayName = displayName.substring(slash + 1);
            addProperty("Name",     displayName);
            addProperty("Material", part.getMaterial().getDisplayName());
            addProperty("Length",   formatMm(part.getCutWidthMm(), u));
            addProperty("Width",    formatMm(part.getCutHeightMm(), u));
            addProperty("Thickness", formatMm(part.getThicknessMm(), u));
            Vector3f pos = part.getPosition();
            addProperty("Position", String.format("(%s, %s, %s)",
                    formatMm(pos.x, u), formatMm(pos.y, u), formatMm(pos.z, u)));
            Vector3f rot = sm.getRotation(name);
            addProperty("Rotation", String.format("(%.0f°, %.0f°, %.0f°)",
                    rot.x, rot.y, rot.z));
            if (part.getGrainRequirement() != null) {
                addProperty("Grain", part.getGrainRequirement().name().toLowerCase());
            }
            return;
        }
        propertiesBody.addChild(new Label("Unknown: " + name));
    }

    /** Width of the title column in property rows. Fixed so titles line
     *  up vertically and values become a scannable column. */
    private static final float PROP_TITLE_W = 70f;

    private void addProperty(String label, String value) {
        // FillMode.None on the major (X) axis stops SpringGridLayout from
        // splitting the row 50/50 between title and value — without this,
        // the value's left-aligned text starts halfway across the row,
        // reading as "centered" to the user.
        Container row = new Container(
                new SpringGridLayout(Axis.X, Axis.Y, FillMode.None, FillMode.Last));
        Label titleLbl = new Label(label);
        titleLbl.setTextHAlignment(HAlignment.Left);
        titleLbl.setPreferredSize(new Vector3f(PROP_TITLE_W, 18f, 0));
        row.addChild(titleLbl);
        Label valueLbl = new Label(value);
        valueLbl.setTextHAlignment(HAlignment.Left);
        row.addChild(valueLbl);
        propertiesBody.addChild(row);
    }

    /** Format a mm length in the user's current display units. */
    private String formatMm(float mm, UnitSystem u) {
        float v = u.fromMm(mm);
        if (u == UnitSystem.MILLIMETERS) {
            return String.format("%.1f mm", v);
        }
        return String.format("%.3f %s", v, u.getAbbreviation());
    }

    /** Persist current splitter ratios + active-tab indices to
     *  {@code ~/.cadette/layout.properties}. Keys are positional
     *  ({@code splitter.<i>}, {@code tab.<i>}) so the encoding stays
     *  stable as long as the construction order in {@link #initialize}
     *  doesn't change. Called on drag-release and on tab activation.
     *  Silent on IO failure (it's a cosmetic preference, not data).
     *
     *  <p>Panel placement / tab grouping isn't persisted yet — that's
     *  a bigger refactor; see the layout-persistence backlog memory. */
    private void saveLayoutProperties() {
        java.util.Properties p = new java.util.Properties();
        for (int i = 0; i < allSplitters.size(); i++) {
            p.setProperty("splitter." + i,
                    String.valueOf(allSplitters.get(i).getRatio()));
        }
        for (int i = 0; i < allTabHosts.size(); i++) {
            p.setProperty("tab." + i,
                    String.valueOf(allTabHosts.get(i).getActive()));
        }
        java.nio.file.Path path = java.nio.file.Path.of(
                System.getProperty("user.home"), ".cadette", "layout.properties");
        try {
            java.nio.file.Files.createDirectories(path.getParent());
            try (var out = java.nio.file.Files.newBufferedWriter(path)) {
                p.store(out, "cadette layout — splitter ratios + active tabs");
            }
        } catch (java.io.IOException e) {
            System.err.println("[cadette] couldn't save layout: " + e.getMessage());
        }
    }

    /** Restore splitter ratios + active-tab indices from
     *  {@code ~/.cadette/layout.properties} over the hardcoded defaults.
     *  Missing file / missing keys are silently ignored — the panel
     *  just keeps its default. Tab listeners aren't wired until after
     *  this returns, so the setActive() calls here don't recursively
     *  trigger saveLayoutProperties(). */
    private void restoreLayoutProperties() {
        java.nio.file.Path path = java.nio.file.Path.of(
                System.getProperty("user.home"), ".cadette", "layout.properties");
        if (!java.nio.file.Files.isRegularFile(path)) return;
        java.util.Properties p = new java.util.Properties();
        try (var in = java.nio.file.Files.newBufferedReader(path)) {
            p.load(in);
        } catch (java.io.IOException e) {
            System.err.println("[cadette] couldn't load layout: " + e.getMessage());
            return;
        }
        for (int i = 0; i < allSplitters.size(); i++) {
            String v = p.getProperty("splitter." + i);
            if (v == null) continue;
            try {
                allSplitters.get(i).setRatio(Float.parseFloat(v));
            } catch (NumberFormatException ignored) { /* skip bad entry */ }
        }
        for (int i = 0; i < allTabHosts.size(); i++) {
            String v = p.getProperty("tab." + i);
            if (v == null) continue;
            try {
                allTabHosts.get(i).setActive(Integer.parseInt(v));
            } catch (NumberFormatException ignored) { /* skip bad entry */ }
        }
    }

    /** Move each channel a fraction of the way toward white. Used to derive
     *  the TextField's "focused" background from its theme-supplied resting
     *  color so themes don't have to declare both. */
    private static ColorRGBA lighten(ColorRGBA c, float amount) {
        float r = c.r + (1f - c.r) * amount;
        float g = c.g + (1f - c.g) * amount;
        float b = c.b + (1f - c.b) * amount;
        return new ColorRGBA(r, g, b, c.a);
    }

    @Override
    public void update(float tpf) {
        super.update(tpf);

        // Focus indicator on the command TextField. Poll instead of attaching
        // a focus listener because Lemur's FocusManagerState doesn't expose
        // an addListener for arbitrary spatials — only individual FocusTargets
        // get their own focusGained/focusLost via the FocusTarget interface,
        // and TextField hides that behind its internal TextEntryComponent.
        if (commandInput != null && commandInputBg != null) {
            boolean focused = GuiGlobals.getInstance().getFocusManagerState()
                    .getFocus() == commandInput;
            if (focused != commandInputFocused) {
                commandInputBg.setColor(focused ? commandInputFocusedBg : commandInputRestingBg);
                commandInputFocused = focused;
            }
        }

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

        // 0b. Detect drag-release: re-trigger reflow so ListBox
        //     visibleItems (which we skipped during drag to avoid
        //     stepping) snaps to its final value, and persist the new
        //     splitter ratios so they survive the next launch.
        boolean nowDragging = isAnySplitterDragging();
        if (wasDragging && !nowDragging) {
            applyRootSize(cam.getWidth(), cam.getHeight());
            saveLayoutProperties();
        }
        wasDragging = nowDragging;

        // 0c. Drag-to-rearrange state advance: promote candidate to drag
        //     once cursor moves past threshold, and update which panel
        //     is currently the drop-zone target.
        updateDragState(input);

        // 1. Refresh mouseOverUi from actual panel bounds. Panels live
        //    inside the splitter tree now so we use getWorldTranslation()
        //    rather than getLocalTranslation() — local would be relative
        //    to the splitter's coords.
        Vector2f cursor = input.getCursorPosition();
        boolean over = false;
        if (cursor != null) {
            for (Node panel : gatedPanels) {
                if (cursorOverDropTarget(cursor, panel)) {
                    over = true;
                    break;
                }
            }
        }
        mouseOverUi = over;

        // 2. (Parts row clicks are now handled directly by per-row
        //     CursorEventControl listeners installed in refreshPartsList;
        //     no polling needed here.)

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

    /** Cursor-bounds test for any drop-target node — TabHost (uses
     *  totalW/totalH it stores) or a placeholder Container (uses its
     *  GuiControl size). Panels live inside TabHosts so we don't hit-test
     *  them directly. */
    private boolean cursorOverDropTarget(Vector2f cursor, Node target) {
        Vector3f loc = target.getWorldTranslation();
        float w, h;
        if (target instanceof LemurTabHost tab) {
            w = tab.getCurrentWidth();
            h = tab.getCurrentHeight();
        } else if (target instanceof Container c) {
            GuiControl gc = c.getControl(GuiControl.class);
            if (gc == null) return false;
            Vector3f size = gc.getSize();
            w = size.x;
            h = size.y;
        } else {
            return false;
        }
        return cursor.x >= loc.x
            && cursor.x <= loc.x + w
            && cursor.y >= loc.y - h
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
            // ListBox doesn't auto-scroll on selection change — its slider
            // model is bound to a "topmost-visible-index" RangedValueModel.
            // Drive it to the minimum (which in Lemur's Y-up GUI means
            // bottom-of-list visible) so the most recent line is showing.
            scrollOutputToBottom();
        }
    }

    /** Scroll the command output ListBox to its last item. Lemur's Slider
     *  uses an inverted Y-up model (min = bottom, max = top), so setting
     *  the slider value to its minimum reveals the last items. */
    private void scrollOutputToBottom() {
        if (outputList == null || outputList.getSlider() == null) return;
        com.simsilica.lemur.RangedValueModel m = outputList.getSlider().getModel();
        if (m != null) m.setValue(m.getMinimum());
    }

    /** Trim {@code value} to fit the current {@link #outputCellWidth},
     *  adding "…" when truncated. Cap at 200 chars even if the cell is
     *  wide, just to keep render cost bounded for pathological lines. */
    private String truncateForOutput(String value) {
        if (value == null) return "";
        if (outputCellWidth <= 0) return value;
        int maxChars = Math.max(4, (int) (outputCellWidth / OUTPUT_GLYPH_W));
        maxChars = Math.min(maxChars, 200);
        if (value.length() <= maxChars) return value;
        return value.substring(0, Math.max(1, maxChars - 1)) + "…";
    }

    /** Force the output ListBox to re-call its cell renderer for every
     *  visible row. Used after a resize so existing rows get
     *  re-truncated to the new cell width. Implemented by bumping the
     *  VersionedList's version via a clear/addAll cycle on the same
     *  contents — Lemur's ListBox reacts to version bumps. */
    private void rerenderOutputCells() {
        if (outputModel == null || outputList == null) return;
        if (outputModel.isEmpty()) return;
        java.util.List<String> snapshot = new ArrayList<>(outputModel);
        outputModel.clear();
        outputModel.addAll(snapshot);
        // Restore the scroll-to-bottom selection (clear cleared it).
        outputList.getSelectionModel().setSelection(outputModel.size() - 1);
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

    /** One row in the parts tree. {@code displayText} is what the ListBox
     *  shows (already including caret/indent); {@code target} is the
     *  underlying name (assembly name or part name) for selection +
     *  expansion lookup. */
    private record TreeRow(String displayText, String target, boolean isAssembly) {}
}
