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

import app.cadette.model.Cutout;
import app.cadette.model.GrainRequirement;
import app.cadette.model.SheetLayout;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders cut sheet layouts to any Graphics2D target.
 * Used by CutSheetPanel for on-screen display and by CutSheetExporter
 * for PDF/image export.
 */
public class CutSheetRenderer {

    static final Color BACKGROUND = new Color(45, 45, 50);
    static final Color SHEET_FILL = new Color(210, 185, 140);
    static final Color SHEET_BORDER = new Color(120, 100, 70);
    static final Color PART_FILL = new Color(255, 228, 170);
    static final Color PART_BORDER = new Color(100, 80, 50);
    static final Color PART_ROTATED_FILL = new Color(200, 220, 255);
    static final Color GRAIN_COLOR = new Color(180, 155, 110, 80);
    static final Color TEXT_COLOR = new Color(40, 30, 20);
    static final Color DIM_COLOR = new Color(80, 80, 80);
    static final Color EMPTY_TEXT_COLOR = new Color(140, 140, 140);
    static final Color SELECTION_BORDER = new Color(40, 120, 255);
    // Cutout overlays — dashed lines distinct from grain and part borders.
    static final Color CUTOUT_COLOR = new Color(180, 50, 40);
    static final Stroke CUTOUT_STROKE = new BasicStroke(
            1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            10f, new float[]{4f, 3f}, 0f);

    /** Screen rectangle for a rendered part, used for click hit-testing. */
    public record PartRect(String partName, Rectangle2D.Float rect) {}

    // For export: use a light background instead of the dark UI background
    static final Color EXPORT_BACKGROUND = Color.WHITE;

    static final int SHEET_MARGIN = 30;
    static final int SHEET_GAP = 40;
    static final int HEADER_HEIGHT = 24;

    /** Render without selection, hit-testing, or cutout overlays (for export defaults). */
    public static void render(Graphics2D g2, int width, int height,
                              List<SheetLayout> layouts, UnitSystem units,
                              boolean forExport) {
        render(g2, width, height, layouts, units, forExport, Set.of(), null, Map.of());
    }

    /** Render with selection + hit-testing but no cutout overlays. */
    public static void render(Graphics2D g2, int width, int height,
                              List<SheetLayout> layouts, UnitSystem units,
                              boolean forExport, Set<String> selectedParts,
                              List<PartRect> hitRects) {
        render(g2, width, height, layouts, units, forExport, selectedParts, hitRects, Map.of());
    }

    /**
     * Render all sheet layouts onto the given Graphics2D.
     *
     * @param g2            the graphics context to draw on
     * @param width         available width in pixels
     * @param height        available height in pixels
     * @param layouts       the sheet layouts to render
     * @param units         the unit system for dimension labels
     * @param forExport     if true, use export-friendly colors (white background)
     * @param selectedParts part names to highlight with a selection border
     * @param hitRects      if non-null, populated with screen rectangles for hit-testing
     * @param partCutouts   cutouts (explicit + joint-inferred) keyed by part name;
     *                      drawn as dashed-line overlays on each placed part. Pass
     *                      an empty map to skip cutout rendering.
     */
    public static void render(Graphics2D g2, int width, int height,
                              List<SheetLayout> layouts, UnitSystem units,
                              boolean forExport, Set<String> selectedParts,
                              List<PartRect> hitRects,
                              Map<String, List<Cutout>> partCutouts) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        if (forExport) {
            g2.setColor(EXPORT_BACKGROUND);
            g2.fillRect(0, 0, width, height);
        }

        if (layouts.isEmpty()) {
            drawEmptyState(g2, width, height);
            return;
        }

        int availableWidth = width - 2 * SHEET_MARGIN;
        int availableHeight = height - 2 * SHEET_MARGIN;

        // Layout sheets in a flow: left to right, wrapping to next row
        int x = SHEET_MARGIN;
        int y = SHEET_MARGIN;
        int rowHeight = 0;

        for (int i = 0; i < layouts.size(); i++) {
            SheetLayout layout = layouts.get(i);
            float scale = computeScale(layout, availableWidth, availableHeight, layouts.size());
            int sheetW = (int) (layout.getSheetWidthMm() * scale);
            int sheetH = (int) (layout.getSheetHeightMm() * scale) + HEADER_HEIGHT;

            // Wrap to next row if needed
            if (x + sheetW > width - SHEET_MARGIN && x > SHEET_MARGIN) {
                x = SHEET_MARGIN;
                y += rowHeight + SHEET_GAP;
                rowHeight = 0;
            }

            drawSheet(g2, layout, x, y, scale, units, i + 1, forExport, selectedParts, hitRects, partCutouts);

            x += sheetW + SHEET_GAP;
            rowHeight = Math.max(rowHeight, sheetH);
        }
    }

    /**
     * Compute the total height needed to render all layouts at the given width.
     * Used by exporters to determine image/page dimensions.
     */
    public static int computeTotalHeight(int width, List<SheetLayout> layouts) {
        if (layouts.isEmpty()) return 200;

        int availableWidth = width - 2 * SHEET_MARGIN;
        int x = SHEET_MARGIN;
        int y = SHEET_MARGIN;
        int rowHeight = 0;

        for (SheetLayout layout : layouts) {
            float scale = computeScale(layout, availableWidth, 600, layouts.size());
            int sheetW = (int) (layout.getSheetWidthMm() * scale);
            int sheetH = (int) (layout.getSheetHeightMm() * scale) + HEADER_HEIGHT;

            if (x + sheetW > width - SHEET_MARGIN && x > SHEET_MARGIN) {
                x = SHEET_MARGIN;
                y += rowHeight + SHEET_GAP;
                rowHeight = 0;
            }

            x += sheetW + SHEET_GAP;
            rowHeight = Math.max(rowHeight, sheetH);
        }

        // Add bottom margin + room for dimension labels
        return y + rowHeight + SHEET_MARGIN + 20;
    }

    private static float computeScale(SheetLayout layout, int availableWidth,
                                       int availableHeight, int sheetCount) {
        int maxW = Math.max(200, availableWidth / Math.min(sheetCount, 3));
        int maxH = Math.max(150, availableHeight - HEADER_HEIGHT);
        float scaleX = maxW / layout.getSheetWidthMm();
        float scaleY = maxH / layout.getSheetHeightMm();
        return Math.min(scaleX, scaleY);
    }

    private static void drawSheet(Graphics2D g2, SheetLayout layout, int ox, int oy,
                                   float scale, UnitSystem units, int sheetNumber,
                                   boolean forExport, Set<String> selectedParts,
                                   List<PartRect> hitRects,
                                   Map<String, List<Cutout>> partCutouts) {
        float sw = layout.getSheetWidthMm() * scale;
        float sh = layout.getSheetHeightMm() * scale;

        // Header
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g2.setColor(forExport ? TEXT_COLOR : EMPTY_TEXT_COLOR);
        String header = String.format("Sheet %d — %s  (%.0f%% offcut)",
                sheetNumber,
                layout.getMaterial().getDisplayName(),
                layout.getOffcutPercent());
        g2.drawString(header, ox, oy + 14);

        int sheetY = oy + HEADER_HEIGHT;

        // Sheet background
        g2.setColor(SHEET_FILL);
        g2.fill(new Rectangle2D.Float(ox, sheetY, sw, sh));
        g2.setColor(SHEET_BORDER);
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(new Rectangle2D.Float(ox, sheetY, sw, sh));

        // Parts
        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        for (SheetLayout.PlacedPart part : layout.getPlacements()) {
            float px = ox + part.getX() * scale;
            float py = sheetY + part.getY() * scale;
            float pw = part.getWidthOnSheet() * scale;
            float ph = part.getHeightOnSheet() * scale;

            // Fill (same color for all parts)
            g2.setColor(PART_FILL);
            g2.fill(new Rectangle2D.Float(px, py, pw, ph));

            // Grain lines (if grain-constrained)
            if (part.getGrainRequirement() != GrainRequirement.ANY) {
                drawGrainLines(g2, px, py, pw, ph, part.isRotated());
            }

            // Rotation indicator
            if (part.isRotated() && pw > 14 && ph > 14) {
                g2.setColor(DIM_COLOR);
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
                g2.drawString("\u21BA", px + 3, py + 12);
            }

            // Border (thicker + blue if selected)
            boolean isSelected = selectedParts.contains(part.getPartName());
            if (isSelected) {
                g2.setColor(SELECTION_BORDER);
                g2.setStroke(new BasicStroke(3f));
            } else {
                g2.setColor(PART_BORDER);
                g2.setStroke(new BasicStroke(1f));
            }
            g2.draw(new Rectangle2D.Float(px, py, pw, ph));

            // Cutout overlays (dashed) — drawn on top of the part fill but
            // under the label so labels stay legible when overlapping cuts.
            drawCutoutOverlays(g2, part, px, py, scale, partCutouts);

            // Label: part name + dimensions
            drawPartLabel(g2, part, px, py, pw, ph, units);

            // Record hit rect for click detection
            if (hitRects != null) {
                hitRects.add(new PartRect(part.getPartName(),
                        new Rectangle2D.Float(px, py, pw, ph)));
            }
        }

        // Sheet dimensions along edges
        drawSheetDimensions(g2, layout, ox, sheetY, sw, sh, units);
    }

    /**
     * Draw dashed-line overlays for each rectangular cutout on the placed part.
     * Non-rect cutout variants (Circle, Polygon, Spline) are skipped — they'll
     * land here when their respective Phase E renderers are wired up.
     */
    private static void drawCutoutOverlays(Graphics2D g2, SheetLayout.PlacedPart part,
                                            float px, float py, float scale,
                                            Map<String, List<Cutout>> partCutouts) {
        List<Cutout> cutouts = partCutouts.getOrDefault(part.getPartName(), List.of());
        if (cutouts.isEmpty()) return;

        Stroke originalStroke = g2.getStroke();
        Color originalColor = g2.getColor();

        g2.setColor(CUTOUT_COLOR);
        g2.setStroke(CUTOUT_STROKE);

        for (Cutout c : cutouts) {
            if (c instanceof Cutout.Rect r) {
                Rectangle2D.Float rect = cutoutToSheetRect(r, part, px, py, scale);
                // Skip vanishingly small overlays so we don't draw noise pixels.
                if (rect.width < 0.5f || rect.height < 0.5f) continue;
                g2.draw(rect);
            } else if (c instanceof Cutout.Circle ci) {
                Ellipse2D.Float ellipse = circleCutoutToSheetEllipse(ci, part, px, py, scale);
                if (ellipse.width < 0.5f || ellipse.height < 0.5f) continue;
                g2.draw(ellipse);
            }
            // Cutout.Polygon / Spline: skip until those variants land.
        }

        g2.setStroke(originalStroke);
        g2.setColor(originalColor);
    }

    /**
     * Map a {@link Cutout.Rect} from part-local cut-face space into the
     * sheet's pixel space. When the part is rotated 90° on the sheet
     * (layout rotation, not 3D scene rotation), local X and Y axes swap —
     * a cutout at part-local (cx, cy) lands at sheet ({@code px + cy*scale},
     * {@code py + cx*scale}) with width and height also swapped.
     *
     * <p>Package-private for unit testing.
     */
    static Rectangle2D.Float cutoutToSheetRect(Cutout.Rect c, SheetLayout.PlacedPart part,
                                                float px, float py, float scale) {
        if (part.isRotated()) {
            return new Rectangle2D.Float(
                    px + c.yMm() * scale,
                    py + c.xMm() * scale,
                    c.heightMm() * scale,
                    c.widthMm() * scale);
        }
        return new Rectangle2D.Float(
                px + c.xMm() * scale,
                py + c.yMm() * scale,
                c.widthMm() * scale,
                c.heightMm() * scale);
    }

    /**
     * Map a {@link Cutout.Circle} from part-local cut-face space into the
     * sheet's pixel space. A circle is rotation-symmetric, so layout
     * rotation only swaps the center coordinates — the radius is unchanged.
     * The returned {@link Ellipse2D.Float} is positioned at the bounding
     * top-left, matching Java2D's convention.
     *
     * <p>Package-private for unit testing.
     */
    static Ellipse2D.Float circleCutoutToSheetEllipse(Cutout.Circle c, SheetLayout.PlacedPart part,
                                                       float px, float py, float scale) {
        float r = c.radiusMm() * scale;
        float cx, cy;
        if (part.isRotated()) {
            cx = px + c.cyMm() * scale;
            cy = py + c.cxMm() * scale;
        } else {
            cx = px + c.cxMm() * scale;
            cy = py + c.cyMm() * scale;
        }
        return new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r);
    }

    private static void drawGrainLines(Graphics2D g2, float px, float py, float pw, float ph,
                                        boolean rotated) {
        g2.setColor(GRAIN_COLOR);
        g2.setStroke(new BasicStroke(0.5f));
        if (!rotated) {
            for (float x = px + 6; x < px + pw; x += 8) {
                g2.draw(new Line2D.Float(x, py + 2, x, py + ph - 2));
            }
        } else {
            for (float y = py + 6; y < py + ph; y += 8) {
                g2.draw(new Line2D.Float(px + 2, y, px + pw - 2, y));
            }
        }
    }

    private static void drawPartLabel(Graphics2D g2, SheetLayout.PlacedPart part,
                                       float px, float py, float pw, float ph, UnitSystem units) {
        g2.setColor(TEXT_COLOR);
        FontMetrics fm = g2.getFontMetrics();

        String name = part.getPartName();
        float dimW = part.getWidthOnSheet();
        float dimH = part.getHeightOnSheet();
        if (part.isRotated()) {
            float tmp = dimW;
            dimW = dimH;
            dimH = tmp;
        }
        String dims = String.format("%.1f x %.1f %s",
                units.fromMm(dimW), units.fromMm(dimH), units.getAbbreviation());

        int textW = fm.stringWidth(name);
        int dimsW = fm.stringWidth(dims);

        if (pw > 20 && ph > 16) {
            float cx = px + pw / 2f;
            float cy = py + ph / 2f;

            if (ph > fm.getHeight() * 2 + 4) {
                g2.drawString(name, cx - textW / 2f, cy - 2);
                g2.setFont(g2.getFont().deriveFont(Font.ITALIC, 9f));
                g2.setColor(DIM_COLOR);
                g2.drawString(dims, cx - dimsW / 2f, cy + fm.getHeight());
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
            } else {
                g2.drawString(name, cx - textW / 2f, cy + fm.getAscent() / 2f);
            }
        }
    }

    private static void drawSheetDimensions(Graphics2D g2, SheetLayout layout,
                                             int ox, int sheetY, float sw, float sh,
                                             UnitSystem units) {
        g2.setColor(DIM_COLOR);
        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        FontMetrics fm = g2.getFontMetrics();

        // Width along bottom
        String wText = String.format("%.1f %s", units.fromMm(layout.getSheetWidthMm()), units.getAbbreviation());
        int wTextW = fm.stringWidth(wText);
        g2.drawString(wText, ox + sw / 2f - wTextW / 2f, sheetY + sh + fm.getHeight() + 2);

        // Height along right side
        String hText = String.format("%.1f %s", units.fromMm(layout.getSheetHeightMm()), units.getAbbreviation());
        Graphics2D g2r = (Graphics2D) g2.create();
        g2r.rotate(-Math.PI / 2, ox + sw + fm.getHeight() + 2, sheetY + sh / 2f);
        g2r.drawString(hText, ox + sw + fm.getHeight() + 2 - fm.stringWidth(hText) / 2f, sheetY + sh / 2f);
        g2r.dispose();
    }

    private static void drawEmptyState(Graphics2D g2, int width, int height) {
        g2.setColor(EMPTY_TEXT_COLOR);
        g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        String msg = "No sheet layouts — add parts with sheet materials to see cut sheets.";
        FontMetrics fm = g2.getFontMetrics();
        int x = (width - fm.stringWidth(msg)) / 2;
        int y = height / 2;
        g2.drawString(msg, x, y);
    }
}
