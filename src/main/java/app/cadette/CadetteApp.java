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
import com.jme3.system.AppSettings;
import com.jme3.system.JmeCanvasContext;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;



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

        // -- jME3 canvas setup --
        AppSettings settings = new AppSettings(true);
        settings.setWidth(1280);
        settings.setHeight(600);
        settings.setFrameRate(60);
        // MSAA: try 4x, but can be disabled with -Dcadette.msaa=0 for GPUs that don't support it
        int msaa = Integer.getInteger("cadette.msaa", 4);
        settings.setSamples(msaa);
        settings.setAudioRenderer(null);  // no audio needed for a CAD app

        sceneManager = new SceneManager();
        sceneManager.setPauseOnLostFocus(false);
        sceneManager.setSettings(settings);
        sceneManager.createCanvas();
        // startCanvas() is called after frame.setVisible() — see below.
        // LWJGL3 on Windows needs a realized native window before it can bind
        // an OpenGL context; starting too early produces a blank canvas.

        JmeCanvasContext ctx = (JmeCanvasContext) sceneManager.getContext();
        Canvas canvas = ctx.getCanvas();
        canvas.setPreferredSize(new Dimension(1280, 600));

        // -- Command panel --
        CommandExecutor executor = new CommandExecutor(sceneManager);
        executor.setOnExit(() -> shutdown(frame));

        // File chooser for "run" command with no argument
        executor.setFileChooser(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Open CADette Script");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "CADette Scripts (*.cds)", "cds"));
            int result = chooser.showOpenDialog(frame);
            if (result == JFileChooser.APPROVE_OPTION) {
                return chooser.getSelectedFile().toPath();
            }
            return null;
        });

        // Save file chooser for "export" commands
        executor.setSaveFileChooser((description, extensions) -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Export Cut Sheet");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    description, extensions));
            int result = chooser.showSaveDialog(frame);
            if (result == JFileChooser.APPROVE_OPTION) {
                java.io.File file = chooser.getSelectedFile();
                // Add extension if the user didn't type one
                String name = file.getName();
                if (!name.contains(".") && extensions.length > 0) {
                    file = new java.io.File(file.getParentFile(), name + "." + extensions[0]);
                }
                return file.toPath();
            }
            return null;
        });

        CommandPanel commandPanel = new CommandPanel(executor);

        // -- Selection manager --
        SelectionManager selectionManager = new SelectionManager(sceneManager);

        // Track previously highlighted parts for cleanup
        final java.util.List<String> previousHighlights = new java.util.ArrayList<>();
        selectionManager.addSelectionListener(event -> {
            // Remove old highlights
            for (String name : previousHighlights) {
                sceneManager.setHighlight(name, false);
            }
            previousHighlights.clear();

            // Apply new highlights
            java.util.List<String> parts = selectionManager.getSelectedPartNames();
            for (String name : parts) {
                sceneManager.setHighlight(name, true);
            }
            previousHighlights.addAll(parts);

            // Show selection info in command output
            java.util.List<String> names = event.selectedNames();
            if (!names.isEmpty()) {
                String info = names.size() == 1
                        ? "Selected '" + names.getFirst() + "'"
                        : "Selected " + names.size() + " objects: " + String.join(", ", names);
                javax.swing.SwingUtilities.invokeLater(
                        () -> commandPanel.appendOutput(info + "\n"));
            }
        });

        // -- Settings panel (bottom-right of viewport) --
        JPanel viewportPanel = new JPanel(new BorderLayout());
        viewportPanel.add(canvas, BorderLayout.CENTER);

        Color panelBg = new Color(40, 40, 48);
        Color labelColor = new Color(180, 180, 180);
        Font labelFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

        // Guard flags to prevent action listener re-entry loops
        final boolean[] updatingUnits = {false};
        final boolean[] updatingMaterial = {false};

        JPanel settingsGrid = new JPanel(new java.awt.GridBagLayout());
        settingsGrid.setOpaque(true);
        settingsGrid.setBackground(panelBg);
        var gbc = new java.awt.GridBagConstraints();
        gbc.insets = new Insets(2, 6, 2, 6);
        gbc.anchor = java.awt.GridBagConstraints.WEST;

        // -- Units row --
        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0;
        JLabel unitsLabel = new JLabel("Units:");
        unitsLabel.setForeground(labelColor);
        unitsLabel.setFont(labelFont);
        settingsGrid.add(unitsLabel, gbc);

        gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
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
            if (selected != null) {
                executor.setUnits(selected);
            }
        });
        settingsGrid.add(unitsCombo, gbc);

        // -- Material row --
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = java.awt.GridBagConstraints.NONE; gbc.weightx = 0;
        JLabel matLabel = new JLabel("Material:");
        matLabel.setForeground(labelColor);
        matLabel.setFont(labelFont);
        settingsGrid.add(matLabel, gbc);

        gbc.gridx = 1; gbc.fill = java.awt.GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        JComboBox<Material> materialCombo = new JComboBox<>();
        materialCombo.setFocusable(false);

        MaterialCatalog catalog = MaterialCatalog.instance();
        final int[] separatorIndex = { catalog.preferredCount(executor.getUnits().getMeasurementSystem()) };

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

        // Populate materials sorted for current units (with guard to suppress action events)
        Runnable refreshMaterials = () -> {
            updatingMaterial[0] = true;
            try {
                var ms = executor.getUnits().getMeasurementSystem();
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
            if (selected != null) {
                executor.setDefaultMaterial(selected);
            }
        });
        settingsGrid.add(materialCombo, gbc);

        // Sync dropdowns when values change via commands
        executor.addUnitChangeListener(u -> SwingUtilities.invokeLater(() -> {
            updatingUnits[0] = true;
            try {
                unitsCombo.setSelectedItem(u);
            } finally {
                updatingUnits[0] = false;
            }
            // Re-sort materials and switch default for the new measurement system
            var newDefault = catalog.getDefaultFor(u.getMeasurementSystem());
            executor.setDefaultMaterial(newDefault);
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

        // Wrap in a right-aligned container so it doesn't stretch across the full width
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(true);
        bottomBar.setBackground(panelBg);
        bottomBar.add(settingsGrid, BorderLayout.EAST);

        viewportPanel.add(bottomBar, BorderLayout.SOUTH);

        // -- Cut sheet panel (scrollable) --
        CutSheetPanel cutSheetPanel = new CutSheetPanel(sceneManager, executor::getUnits);
        cutSheetPanel.setSelectionManager(selectionManager);
        JScrollPane cutSheetScroll = new JScrollPane(cutSheetPanel);
        cutSheetScroll.setBorder(null);
        cutSheetScroll.getVerticalScrollBar().setUnitIncrement(40);

        // -- View layout: permanent horizontal JSplitPane --
        // The jME3 AWT Canvas cannot be reparented (moving it between containers
        // invalidates the OpenGL context and crashes GPU drivers). So we use a
        // permanent JSplitPane and control the divider position to show/hide panels.
        JSplitPane hSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewportPanel, cutSheetScroll);
        hSplitPane.setResizeWeight(0.6);
        hSplitPane.setContinuousLayout(true);

        // Tab-style toggle bar for tabbed mode
        Color tabBg = new Color(50, 50, 58);
        Color tabActiveFg = new Color(220, 220, 220);
        Color tabInactiveFg = new Color(120, 120, 120);
        Font tabFont = new Font(Font.SANS_SERIF, Font.BOLD, 11);

        JButton viewportTab = new JButton("3D Viewport");
        JButton cutSheetTab = new JButton("Cut Sheets");
        for (JButton btn : new JButton[]{viewportTab, cutSheetTab}) {
            btn.setFont(tabFont);
            btn.setFocusable(false);
            btn.setBorderPainted(false);
            btn.setContentAreaFilled(false);
            btn.setOpaque(true);
            btn.setBackground(tabBg);
        }

        JPanel tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabBar.setBackground(tabBg);
        tabBar.add(viewportTab);
        tabBar.add(cutSheetTab);

        // Tab button state: tracks which tab is active in tabbed mode
        final boolean[] showingViewport = {true};

        Runnable updateTabColors = () -> {
            viewportTab.setForeground(showingViewport[0] ? tabActiveFg : tabInactiveFg);
            cutSheetTab.setForeground(showingViewport[0] ? tabInactiveFg : tabActiveFg);
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

        viewContainer.add(tabBar, BorderLayout.NORTH);
        executor.addLayoutChangeListener(mode -> SwingUtilities.invokeLater(applyLayout));
        // Initial layout applied after frame is visible (see below)

        // -- Layout: view container on top, command line on bottom --
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, viewContainer, commandPanel);
        splitPane.setResizeWeight(0.75);
        splitPane.setDividerLocation(620);

        frame.getContentPane().add(splitPane, BorderLayout.CENTER);

        // -- Escape key: toggle between viewport and command panel --
        // When command panel releases focus, give it to the canvas
        commandPanel.setOnFocusToggle(() -> canvas.requestFocusInWindow());

        // AWT KeyListener on the jME3 canvas (AWT Canvas doesn't participate
        // in Swing's KeyboardFocusManager dispatch).
        canvas.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    SwingUtilities.invokeLater(commandPanel::activate);
                } else if (e.getKeyCode() == KeyEvent.VK_Z && e.isControlDown()) {
                    String msg = e.isShiftDown() ? executor.redo() : executor.undo();
                    SwingUtilities.invokeLater(() -> commandPanel.appendOutput(msg + "\n"));
                } else if (e.getKeyCode() == KeyEvent.VK_R && !e.isControlDown() && !e.isAltDown()) {
                    SwingUtilities.invokeLater(() -> {
                        String result = executor.execute("run");
                        commandPanel.appendOutput(result + "\n");
                    });
                } else if (e.getKeyCode() == KeyEvent.VK_Q && !e.isControlDown() && !e.isAltDown()) {
                    shutdown(frame);
                } else if (e.getKeyCode() == KeyEvent.VK_L && !e.isControlDown() && !e.isAltDown()) {
                    SwingUtilities.invokeLater(() -> {
                        String result = executor.execute("list");
                        commandPanel.appendOutput(result + "\n");
                    });
                }
            }
        });

        // Catch-all for Escape when focus is on any other Swing component
        // (e.g., split pane divider, toolbar, etc.)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                if (!commandPanel.isCommandActive()) {
                    SwingUtilities.invokeLater(commandPanel::activate);
                    return true;
                }
            }
            return false;
        });

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
        applyLayout.run();

        // Run startup script if it exists (~/.cadette/startup.cds)
        String startupResult = executor.runStartupScript();
        if (startupResult != null) {
            commandPanel.appendOutput(startupResult + "\n");
        }
    }

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
