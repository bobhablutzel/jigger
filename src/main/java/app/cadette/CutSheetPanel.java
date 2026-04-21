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

import app.cadette.model.SheetLayout;
import app.cadette.model.SheetLayoutGenerator;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

/**
 * Swing panel that displays cut sheet layouts using Java2D.
 * Checks the SceneManager's dirty flag and lazy-recomputes layouts on paint.
 * Delegates all rendering to {@link CutSheetRenderer}.
 * Supports click-to-select with the same rules as the 3D viewport.
 * Implements Scrollable for vertical scrolling when content exceeds the viewport.
 */
public class CutSheetPanel extends JPanel implements javax.swing.Scrollable {

    private final SceneManager sceneManager;
    private final Supplier<UnitSystem> unitsSupplier;
    private List<SheetLayout> cachedLayouts = List.of();
    private Set<String> selectedParts = Set.of();
    private final List<CutSheetRenderer.PartRect> hitRects = new ArrayList<>();
    private SelectionManager selectionManager;

    // Hand-coded: sets the panel background and wires both a scene-change
    // listener and a mouse listener. Not a @RequiredArgsConstructor candidate.
    public CutSheetPanel(SceneManager sceneManager, Supplier<UnitSystem> unitsSupplier) {
        this.sceneManager = sceneManager;
        this.unitsSupplier = unitsSupplier;
        setBackground(CutSheetRenderer.BACKGROUND);
        sceneManager.addSceneChangeListener(() -> SwingUtilities.invokeLater(this::refreshLayouts));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleClick(e);
            }
        });
    }

    public void setSelectionManager(SelectionManager selectionManager) {
        this.selectionManager = selectionManager;
        selectionManager.addSelectionListener(event -> {
            // Update highlighted parts and repaint
            selectedParts = new HashSet<>(selectionManager.getSelectedPartNames());
            SwingUtilities.invokeLater(CutSheetPanel.this::repaint);
        });
    }

    private void handleClick(MouseEvent e) {
        if (selectionManager == null) return;

        boolean shiftDown = e.isShiftDown();
        int mx = e.getX(), my = e.getY();

        // Find which part rectangle was clicked (iterate in reverse for topmost)
        for (int i = hitRects.size() - 1; i >= 0; i--) {
            CutSheetRenderer.PartRect pr = hitRects.get(i);
            if (pr.rect().contains(mx, my)) {
                selectionManager.selectByPartName(pr.partName(), shiftDown);
                return;
            }
        }

        // Clicked empty space
        if (!shiftDown) {
            selectionManager.deselect();
        }
    }

    /** Regenerate the cached layouts if the scene has been marked dirty. Returns true if regenerated. */
    private boolean rebuildCachedLayoutsIfDirty() {
        if (!sceneManager.isCutSheetDirty()) return false;
        cachedLayouts = SheetLayoutGenerator.generateLayouts(
                sceneManager.getAllParts(), sceneManager.getKerfMm());
        sceneManager.clearCutSheetDirty();
        return true;
    }

    private void refreshLayouts() {
        if (rebuildCachedLayoutsIfDirty()) {
            revalidate();
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        rebuildCachedLayoutsIfDirty();

        hitRects.clear();
        Graphics2D g2 = (Graphics2D) g.create();
        CutSheetRenderer.render(g2, getWidth(), getHeight(), cachedLayouts,
                unitsSupplier.get(), false, selectedParts, hitRects);
        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getParent() != null ? getParent().getWidth() : 800;
        int contentHeight = CutSheetRenderer.computeTotalHeight(width, cachedLayouts);
        int viewportHeight = getParent() != null ? getParent().getHeight() : contentHeight;
        return new Dimension(width, Math.max(contentHeight, viewportHeight));
    }

    // -- Scrollable: fill viewport width, scroll vertically only when needed --

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
        return 40;
    }

    @Override
    public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height - 40 : visibleRect.width - 40;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        if (getParent() instanceof javax.swing.JViewport viewport) {
            int contentHeight = CutSheetRenderer.computeTotalHeight(viewport.getWidth(), cachedLayouts);
            return contentHeight <= viewport.getHeight();
        }
        return true;
    }
}
