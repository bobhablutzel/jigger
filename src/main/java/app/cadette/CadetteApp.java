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
import app.cadette.model.Material;
import app.cadette.model.MaterialCatalog;
import app.cadette.model.MeasurementSystem;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeCanvasContext;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Main entry point. Creates a Swing frame with a jME3 3D canvas on top
 * and a command-line panel at the bottom.
 */
public class CadetteApp {

    private static SceneManager sceneManager;
    private static volatile boolean shuttingDown = false;

    public static void main(String[] args) {
        // Prevent Java2D from using DirectDraw/D3D on Windows, which can conflict
        // with LWJGL3's OpenGL context in the embedded canvas.
        System.setProperty("sun.java2d.noddraw", "true");
        SwingUtilities.invokeLater(CadetteApp::createAndShowGUI);
    }

    private static void createAndShowGUI() {
        JFrame frame = new JFrame("CADette - 3D Command Shell");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1280, 900);
        frame.setLocationRelativeTo(null);

        sceneManager = buildSceneManager();
        Canvas canvas = ((JmeCanvasContext) sceneManager.getContext()).getCanvas();
        canvas.setPreferredSize(new Dimension(1280, 600));

        CommandExecutor executor = new CommandExecutor(sceneManager);
        executor.setOnExit(() -> shutdown(frame));
        executor.setFileChooser(scriptOpenChooser(frame));
        executor.setSaveFileChooser(exportSaveChooser(frame));

        CommandPanel commandPanel = new CommandPanel(executor);
        SelectionManager selectionManager = new SelectionManager(sceneManager);
        wireSelectionHighlights(selectionManager, commandPanel);

        // Viewport panel = canvas + settings bar along the bottom edge
        JPanel viewportPanel = new JPanel(new BorderLayout());
        viewportPanel.add(canvas, BorderLayout.CENTER);
        viewportPanel.add(buildSettingsBar(executor), BorderLayout.SOUTH);

        // Cut sheet panel (scrollable)
        CutSheetPanel cutSheetPanel = new CutSheetPanel(sceneManager, executor::getUnits);
        cutSheetPanel.setSelectionManager(selectionManager);
        JScrollPane cutSheetScroll = new JScrollPane(cutSheetPanel);
        cutSheetScroll.setBorder(null);
        cutSheetScroll.getVerticalScrollBar().setUnitIncrement(40);

        // View layout: permanent horizontal JSplitPane.
        // The jME3 AWT Canvas cannot be reparented (moving it between containers
        // invalidates the OpenGL context and crashes GPU drivers), so we use a
        // permanent split and drive the divider to show/hide panels.
        JSplitPane hSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewportPanel, cutSheetScroll);
        hSplitPane.setResizeWeight(0.6);
        hSplitPane.setContinuousLayout(true);

        ViewContainer view = buildViewContainer(hSplitPane, cutSheetPanel, executor, frame);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, view.panel(), commandPanel);
        splitPane.setResizeWeight(0.75);
        splitPane.setDividerLocation(620);
        frame.getContentPane().add(splitPane, BorderLayout.CENTER);

        // Escape handoff: command panel releases focus → give it to the canvas
        commandPanel.setOnFocusToggle(canvas::requestFocusInWindow);
        canvas.addKeyListener(canvasKeyAdapter(executor, commandPanel, frame));
        installGlobalEscapeKey(commandPanel);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown(frame);
            }
        });

        frame.setVisible(true);

        // Start the jME3 canvas now that the frame is visible and the native
        // window peer exists. This ordering is required on Windows where LWJGL3
        // cannot bind an OpenGL context to an unrealized AWT canvas.
        sceneManager.startCanvas();
        sceneManager.setSelectionManager(selectionManager);

        // Apply initial layout now that the frame is realized
        // (setDividerLocation with proportional values requires a non-zero width)
        view.applyLayout().run();

        // Run startup script if it exists (~/.cadette/startup.cds)
        String startupResult = executor.runStartupScript();
        if (startupResult != null) {
            commandPanel.appendOutput(startupResult + "\n");
        }
    }

    // ---------------------------------------------------------------------
    // Scene manager setup
    // ---------------------------------------------------------------------

    private static SceneManager buildSceneManager() {
        AppSettings settings = new AppSettings(true);
        settings.setWidth(1280);
        settings.setHeight(600);
        settings.setFrameRate(60);
        // MSAA: try 4x, but can be disabled with -Dcadette.msaa=0 for GPUs that don't support it
        settings.setSamples(Integer.getInteger("cadette.msaa", 4));
        settings.setAudioRenderer(null);  // no audio needed for a CAD app

        SceneManager sm = new SceneManager();
        sm.setPauseOnLostFocus(false);
        sm.setSettings(settings);
        sm.createCanvas();
        // startCanvas() is called after frame.setVisible() in createAndShowGUI.
        return sm;
    }

    // ---------------------------------------------------------------------
    // File choosers
    // ---------------------------------------------------------------------

    /** Chooser for the `run` command when invoked with no path argument. */
    private static Supplier<Path> scriptOpenChooser(JFrame frame) {
        return () -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Open CADette Script");
            chooser.setFileFilter(new FileNameExtensionFilter("CADette Scripts (*.cds)", "cds"));
            int result = chooser.showOpenDialog(frame);
            return result == JFileChooser.APPROVE_OPTION
                    ? chooser.getSelectedFile().toPath()
                    : null;
        };
    }

    /** Chooser for `export` commands that need a target path. */
    private static BiFunction<String, String[], Path> exportSaveChooser(JFrame frame) {
        return (description, extensions) -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Export Cut Sheet");
            chooser.setFileFilter(new FileNameExtensionFilter(description, extensions));
            if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return null;
            File file = chooser.getSelectedFile();
            // Auto-append extension if the user didn't type one
            if (!file.getName().contains(".") && extensions.length > 0) {
                file = new File(file.getParentFile(), file.getName() + "." + extensions[0]);
            }
            return file.toPath();
        };
    }

    // ---------------------------------------------------------------------
    // Selection highlight wiring
    // ---------------------------------------------------------------------

    /**
     * Subscribe to selection changes: toggle scene highlights for selected parts
     * and echo a summary to the command output.
     */
    private static void wireSelectionHighlights(SelectionManager selectionManager,
                                                 CommandPanel commandPanel) {
        final List<String> previousHighlights = new ArrayList<>();
        selectionManager.addSelectionListener(event -> {
            for (String name : previousHighlights) {
                sceneManager.setHighlight(name, false);
            }
            previousHighlights.clear();

            List<String> parts = selectionManager.getSelectedPartNames();
            for (String name : parts) {
                sceneManager.setHighlight(name, true);
            }
            previousHighlights.addAll(parts);

            List<String> names = event.selectedNames();
            if (!names.isEmpty()) {
                String info = names.size() == 1
                        ? "Selected '" + names.getFirst() + "'"
                        : "Selected " + names.size() + " objects: " + String.join(", ", names);
                SwingUtilities.invokeLater(() -> commandPanel.appendOutput(info + "\n"));
            }
        });
    }

    // ---------------------------------------------------------------------
    // Settings bar (units + material dropdowns)
    // ---------------------------------------------------------------------

    private static final Color PANEL_BG = new Color(40, 40, 48);
    private static final Color LABEL_FG = new Color(180, 180, 180);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

    /**
     * Build the settings row containing the units and material combo boxes,
     * wired bidirectionally to the executor so command changes sync to the UI
     * and vice versa. Re-entry guards prevent listener loops.
     */
    private static JPanel buildSettingsBar(CommandExecutor executor) {
        final boolean[] updatingUnits = {false};
        final boolean[] updatingMaterial = {false};
        MaterialCatalog catalog = MaterialCatalog.instance();
        final int[] separatorIndex = { catalog.preferredCount(executor.getUnits().getMeasurementSystem()) };

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(true);
        grid.setBackground(PANEL_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 6, 2, 6);
        gbc.anchor = GridBagConstraints.WEST;

        // Units row
        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        grid.add(styledLabel("Units:"), gbc);

        JComboBox<UnitSystem> unitsCombo = new JComboBox<>(UnitSystem.values());
        unitsCombo.setSelectedItem(executor.getUnits());
        unitsCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof UnitSystem u) {
                    setText(u.name().toLowerCase() + " (" + u.getAbbreviation() + ")");
                }
                return this;
            }
        });
        unitsCombo.setFocusable(false);
        unitsCombo.addActionListener(e -> {
            if (updatingUnits[0]) return;
            UnitSystem selected = (UnitSystem) unitsCombo.getSelectedItem();
            if (selected != null) executor.setUnits(selected);
        });
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        grid.add(unitsCombo, gbc);

        // Material row
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        grid.add(styledLabel("Material:"), gbc);

        JComboBox<Material> materialCombo = new JComboBox<>();
        materialCombo.setFocusable(false);
        materialCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Material m) {
                    label.setText(m.getDisplayName());
                    if (index >= separatorIndex[0] && index >= 0 && !isSelected) {
                        label.setForeground(new Color(140, 140, 140));
                    }
                }
                if (index == separatorIndex[0] && index > 0) {
                    label.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(100, 100, 100)),
                            BorderFactory.createEmptyBorder(2, 0, 0, 0)));
                } else {
                    label.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
                }
                return label;
            }
        });

        Runnable refreshMaterials = () -> {
            updatingMaterial[0] = true;
            try {
                MeasurementSystem ms = executor.getUnits().getMeasurementSystem();
                separatorIndex[0] = catalog.preferredCount(ms);
                Material currentSelection = executor.getDefaultMaterial();
                materialCombo.removeAllItems();
                for (Material m : catalog.getSortedFor(ms)) {
                    materialCombo.addItem(m);
                }
                materialCombo.setSelectedItem(currentSelection);
            } finally {
                updatingMaterial[0] = false;
            }
        };
        refreshMaterials.run();

        materialCombo.addActionListener(e -> {
            if (updatingMaterial[0]) return;
            Material selected = (Material) materialCombo.getSelectedItem();
            if (selected != null) executor.setDefaultMaterial(selected);
        });
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        grid.add(materialCombo, gbc);

        // Sync UI when values change via commands
        executor.addUnitChangeListener(u -> SwingUtilities.invokeLater(() -> {
            updatingUnits[0] = true;
            try {
                unitsCombo.setSelectedItem(u);
            } finally {
                updatingUnits[0] = false;
            }
            executor.setDefaultMaterial(catalog.getDefaultFor(u.getMeasurementSystem()));
            refreshMaterials.run();
        }));
        executor.addMaterialChangeListener(m -> SwingUtilities.invokeLater(() -> {
            updatingMaterial[0] = true;
            try {
                materialCombo.setSelectedItem(m);
            } finally {
                updatingMaterial[0] = false;
            }
        }));

        // Right-align the grid so it doesn't stretch across the full width
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(true);
        bottomBar.setBackground(PANEL_BG);
        bottomBar.add(grid, BorderLayout.EAST);
        return bottomBar;
    }

    private static JLabel styledLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(LABEL_FG);
        label.setFont(LABEL_FONT);
        return label;
    }

    // ---------------------------------------------------------------------
    // View container (tab bar + split-pane layout toggle)
    // ---------------------------------------------------------------------

    private static final Color TAB_BG = new Color(50, 50, 58);
    private static final Color TAB_ACTIVE_FG = new Color(220, 220, 220);
    private static final Color TAB_INACTIVE_FG = new Color(120, 120, 120);
    private static final Font TAB_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);

    /** The view container plus the Runnable that applies the initial layout. */
    private record ViewContainer(JPanel panel, Runnable applyLayout) {}

    /**
     * Build the container around {@code hSplitPane} (viewport + cut sheet) with a
     * tab bar for tabbed mode. The caller must invoke {@link ViewContainer#applyLayout()}
     * after the frame is realized (proportional divider locations need a non-zero width).
     */
    private static ViewContainer buildViewContainer(JSplitPane hSplitPane,
                                                     CutSheetPanel cutSheetPanel,
                                                     CommandExecutor executor,
                                                     JFrame frame) {
        JButton viewportTab = tabButton("3D Viewport");
        JButton cutSheetTab = tabButton("Cut Sheets");

        JPanel tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabBar.setBackground(TAB_BG);
        tabBar.add(viewportTab);
        tabBar.add(cutSheetTab);

        final boolean[] showingViewport = {true};
        Runnable updateTabColors = () -> {
            viewportTab.setForeground(showingViewport[0] ? TAB_ACTIVE_FG : TAB_INACTIVE_FG);
            cutSheetTab.setForeground(showingViewport[0] ? TAB_INACTIVE_FG : TAB_ACTIVE_FG);
        };

        viewportTab.addActionListener(e -> {
            showingViewport[0] = true;
            hSplitPane.setResizeWeight(1.0);
            hSplitPane.setDividerLocation(hSplitPane.getWidth() - hSplitPane.getDividerSize());
            updateTabColors.run();
        });
        cutSheetTab.addActionListener(e -> {
            showingViewport[0] = false;
            hSplitPane.setResizeWeight(0.0);
            hSplitPane.setDividerLocation(2);
            cutSheetPanel.repaint();
            updateTabColors.run();
        });

        JPanel viewContainer = new JPanel(new BorderLayout());
        viewContainer.add(hSplitPane, BorderLayout.CENTER);
        viewContainer.add(tabBar, BorderLayout.NORTH);

        Runnable applyLayout = () -> {
            if (executor.getLayoutMode() == ViewLayoutMode.SPLIT_PANE) {
                tabBar.setVisible(false);
                hSplitPane.setResizeWeight(0.6);
                hSplitPane.setDividerLocation((int) (frame.getWidth() * 0.6));
            } else {
                tabBar.setVisible(true);
                showingViewport[0] = true;
                hSplitPane.setResizeWeight(1.0);
                hSplitPane.setDividerLocation(1.0);
                updateTabColors.run();
            }
        };
        executor.addLayoutChangeListener(mode -> SwingUtilities.invokeLater(applyLayout));

        return new ViewContainer(viewContainer, applyLayout);
    }

    private static JButton tabButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(TAB_FONT);
        btn.setFocusable(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(true);
        btn.setBackground(TAB_BG);
        return btn;
    }

    // ---------------------------------------------------------------------
    // Key handling
    // ---------------------------------------------------------------------

    /** Hotkeys applied to the jME3 canvas (AWT Canvas bypasses Swing's KeyboardFocusManager). */
    private static KeyAdapter canvasKeyAdapter(CommandExecutor executor,
                                                CommandPanel commandPanel,
                                                JFrame frame) {
        return new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_ESCAPE) {
                    SwingUtilities.invokeLater(commandPanel::activate);
                } else if (code == KeyEvent.VK_Z && e.isControlDown()) {
                    String msg = e.isShiftDown() ? executor.redo() : executor.undo();
                    SwingUtilities.invokeLater(() -> commandPanel.appendOutput(msg + "\n"));
                } else if (code == KeyEvent.VK_R && !e.isControlDown() && !e.isAltDown()) {
                    SwingUtilities.invokeLater(() ->
                            commandPanel.appendOutput(executor.execute("run") + "\n"));
                } else if (code == KeyEvent.VK_Q && !e.isControlDown() && !e.isAltDown()) {
                    shutdown(frame);
                } else if (code == KeyEvent.VK_L && !e.isControlDown() && !e.isAltDown()) {
                    SwingUtilities.invokeLater(() ->
                            commandPanel.appendOutput(executor.execute("list") + "\n"));
                }
            }
        };
    }

    /**
     * Catch-all for Escape when focus is on any other Swing component (e.g. split
     * pane divider). The canvas key listener handles Escape while the canvas has
     * focus; this picks up the cases where it doesn't.
     */
    private static void installGlobalEscapeKey(CommandPanel commandPanel) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                if (!commandPanel.isCommandActive()) {
                    SwingUtilities.invokeLater(commandPanel::activate);
                    return true;
                }
            }
            return false;
        });
    }

    // ---------------------------------------------------------------------
    // Shutdown
    // ---------------------------------------------------------------------

    private static void shutdown(JFrame frame) {
        if (shuttingDown) return;
        shuttingDown = true;

        // Run shutdown off the EDT to avoid deadlocking with jME3's render thread.
        // Use a daemon thread + Runtime halt as a fallback if stop() hangs.
        Thread t = new Thread(() -> {
            try {
                sceneManager.stop();
            } catch (Exception ignored) {
            }
            // Force-terminate — jME3/LWJGL can leave non-daemon threads alive
            Runtime.getRuntime().halt(0);
        }, "cadette-shutdown");
        t.setDaemon(true);
        t.start();
    }
}
