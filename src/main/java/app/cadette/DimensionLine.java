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

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.io.IOException;

/**
 * Architectural-style dimension annotations for the cut sheet (and
 * eventually the 3D-view overlays and detail sheets).
 *
 * <p>A dimension consists of:
 * <ul>
 *   <li><b>Extension lines</b> from the measured points, perpendicular to
 *       the dimension line, with a small gap from the measured feature.</li>
 *   <li>The <b>dimension line</b> itself, parallel to the measured edge,
 *       offset perpendicular by the caller-provided amount.</li>
 *   <li><b>End marks</b> at each end of the dimension line — either
 *       arrowheads ({@link Style#ARCHITECTURAL}, for export) or
 *       45° slash ticks ({@link Style#SIMPLIFIED}, for cramped
 *       on-screen rendering).</li>
 *   <li>A <b>numeric label</b> centered above the dimension line.</li>
 * </ul>
 *
 * <p>Two backends today: {@link Graphics2D} for the panel renderer
 * and {@link PDPageContentStream} for PDF export. The geometry is the
 * same in both; only the draw calls differ.
 *
 * <p>This class doesn't know about parts, sheets, or units — callers
 * pass pre-formatted label strings and pre-transformed coordinates.
 */
public final class DimensionLine {

    /** End-mark style at the witness/dim intersections. */
    public enum Style {
        /** Solid filled arrowheads. Conventional for export drawings. */
        ARCHITECTURAL,
        /** 45° slash ticks. Less visual weight; better when space is tight. */
        SIMPLIFIED
    }

    private DimensionLine() { }

    // Visual constants. Adjust together — they're tuned to look balanced
    // at small panel sizes.
    private static final float EXTENSION_GAP_PX = 2f;      // gap from feature to extension start
    private static final float EXTENSION_OVERRUN_PX = 3f;  // how far extension goes past dim line
    private static final float ARROWHEAD_LENGTH_PX = 6f;
    private static final float ARROWHEAD_HALF_WIDTH_PX = 2.5f;
    private static final float TICK_HALF_LENGTH_PX = 3f;
    private static final float LABEL_GAP_PX = 2f;

    // ---------- Graphics2D backend ------------------------------------

    /**
     * Draw a horizontal dimension between {@code x1} and {@code x2} along the
     * line {@code y = anchorY}. The dimension line sits at {@code anchorY +
     * offsetY}; positive offsetY places it below the feature, negative above.
     */
    public static void drawHorizontalG2(Graphics2D g2,
                                        float x1, float x2,
                                        float anchorY, float offsetY,
                                        String label, Style style) {
        if (Math.abs(x2 - x1) < 1f) return;  // too small to dimension
        if (x2 < x1) { float t = x1; x1 = x2; x2 = t; }

        Stroke origStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(1.2f));

        float dimY = anchorY + offsetY;
        float extGapSign = offsetY > 0 ? +1 : -1;
        float extStart1 = anchorY + extGapSign * EXTENSION_GAP_PX;
        float extEnd1   = dimY    + extGapSign * EXTENSION_OVERRUN_PX;

        // Extension lines (witness lines)
        g2.draw(new Line2D.Float(x1, extStart1, x1, extEnd1));
        g2.draw(new Line2D.Float(x2, extStart1, x2, extEnd1));

        // Dimension line
        g2.draw(new Line2D.Float(x1, dimY, x2, dimY));

        // End marks
        if (style == Style.ARCHITECTURAL) {
            fillArrowhead(g2, x1, dimY, +1, 0);
            fillArrowhead(g2, x2, dimY, -1, 0);
        } else {
            drawTick(g2, x1, dimY, true);
            drawTick(g2, x2, dimY, true);
        }

        // Label centered above the dim line
        if (label != null && !label.isEmpty()) {
            FontMetrics fm = g2.getFontMetrics();
            int labelW = fm.stringWidth(label);
            float labelX = (x1 + x2) / 2f - labelW / 2f;
            float labelY = dimY - LABEL_GAP_PX;
            g2.drawString(label, labelX, labelY);
        }
        g2.setStroke(origStroke);
    }

    /**
     * Draw a vertical dimension between {@code y1} and {@code y2} along
     * the column {@code x = anchorX}. The dimension line sits at
     * {@code anchorX + offsetX}; positive offsetX places it to the right.
     */
    public static void drawVerticalG2(Graphics2D g2,
                                      float anchorX, float offsetX,
                                      float y1, float y2,
                                      String label, Style style) {
        if (Math.abs(y2 - y1) < 1f) return;
        if (y2 < y1) { float t = y1; y1 = y2; y2 = t; }

        Stroke origStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(1.2f));

        float dimX = anchorX + offsetX;
        float extGapSign = offsetX > 0 ? +1 : -1;
        float extStart1 = anchorX + extGapSign * EXTENSION_GAP_PX;
        float extEnd1   = dimX    + extGapSign * EXTENSION_OVERRUN_PX;

        g2.draw(new Line2D.Float(extStart1, y1, extEnd1, y1));
        g2.draw(new Line2D.Float(extStart1, y2, extEnd1, y2));
        g2.draw(new Line2D.Float(dimX, y1, dimX, y2));

        if (style == Style.ARCHITECTURAL) {
            fillArrowhead(g2, dimX, y1, 0, +1);
            fillArrowhead(g2, dimX, y2, 0, -1);
        } else {
            drawTick(g2, dimX, y1, false);
            drawTick(g2, dimX, y2, false);
        }

        if (label != null && !label.isEmpty()) {
            FontMetrics fm = g2.getFontMetrics();
            int labelW = fm.stringWidth(label);
            float midY = (y1 + y2) / 2f;
            // Vertical text: rotate -90° around the midpoint and draw
            // centered on the rotated baseline.
            Graphics2D g2r = (Graphics2D) g2.create();
            g2r.rotate(-Math.PI / 2, dimX, midY);
            g2r.drawString(label, dimX - labelW / 2f, midY - LABEL_GAP_PX);
            g2r.dispose();
        }
        g2.setStroke(origStroke);
    }

    private static void fillArrowhead(Graphics2D g2, float tipX, float tipY,
                                      float dirX, float dirY) {
        // dir = unit vector along the dim line, pointing AWAY from the tip
        // (i.e. toward the dim line's center). Arrowhead body lies along dir.
        Path2D.Float arrow = new Path2D.Float();
        arrow.moveTo(tipX, tipY);
        // Base of the arrowhead is at tip + dir*ARROWHEAD_LENGTH
        float baseX = tipX + dirX * ARROWHEAD_LENGTH_PX;
        float baseY = tipY + dirY * ARROWHEAD_LENGTH_PX;
        // Perpendicular for the wings
        float perpX = -dirY;
        float perpY = +dirX;
        arrow.lineTo(baseX + perpX * ARROWHEAD_HALF_WIDTH_PX,
                     baseY + perpY * ARROWHEAD_HALF_WIDTH_PX);
        arrow.lineTo(baseX - perpX * ARROWHEAD_HALF_WIDTH_PX,
                     baseY - perpY * ARROWHEAD_HALF_WIDTH_PX);
        arrow.closePath();
        g2.fill(arrow);
    }

    private static void drawTick(Graphics2D g2, float cx, float cy,
                                 boolean horizontalDim) {
        // 45° tick: short diagonal slash through the intersection.
        float dx = TICK_HALF_LENGTH_PX, dy = TICK_HALF_LENGTH_PX;
        if (!horizontalDim) { dx = -dx; }  // mirror for visual balance
        g2.draw(new Line2D.Float(cx - dx, cy - dy, cx + dx, cy + dy));
    }

    // ---------- PDFBox backend ----------------------------------------

    /** Draw a horizontal dimension in PDF coordinates (Y is up). */
    public static void drawHorizontalPdf(PDPageContentStream cs,
                                         float x1, float x2,
                                         float anchorY, float offsetY,
                                         String label, Style style,
                                         PDFont font, float fontSize)
            throws IOException {
        if (Math.abs(x2 - x1) < 1f) return;
        if (x2 < x1) { float t = x1; x1 = x2; x2 = t; }

        float dimY = anchorY + offsetY;
        float extGapSign = offsetY > 0 ? +1 : -1;
        float extStart1 = anchorY + extGapSign * EXTENSION_GAP_PX;
        float extEnd1   = dimY    + extGapSign * EXTENSION_OVERRUN_PX;

        cs.setLineWidth(0.5f);
        line(cs, x1, extStart1, x1, extEnd1);
        line(cs, x2, extStart1, x2, extEnd1);
        line(cs, x1, dimY, x2, dimY);

        if (style == Style.ARCHITECTURAL) {
            fillArrowheadPdf(cs, x1, dimY, +1, 0);
            fillArrowheadPdf(cs, x2, dimY, -1, 0);
        } else {
            tickPdf(cs, x1, dimY, true);
            tickPdf(cs, x2, dimY, true);
        }

        if (label != null && !label.isEmpty()) {
            float labelW = font.getStringWidth(label) / 1000f * fontSize;
            float labelX = (x1 + x2) / 2f - labelW / 2f;
            float labelY = dimY + LABEL_GAP_PX;
            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(labelX, labelY);
            cs.showText(label);
            cs.endText();
        }
    }

    /** Draw a vertical dimension in PDF coordinates (Y is up). */
    public static void drawVerticalPdf(PDPageContentStream cs,
                                       float anchorX, float offsetX,
                                       float y1, float y2,
                                       String label, Style style,
                                       PDFont font, float fontSize)
            throws IOException {
        if (Math.abs(y2 - y1) < 1f) return;
        if (y2 < y1) { float t = y1; y1 = y2; y2 = t; }

        float dimX = anchorX + offsetX;
        float extGapSign = offsetX > 0 ? +1 : -1;
        float extStart1 = anchorX + extGapSign * EXTENSION_GAP_PX;
        float extEnd1   = dimX    + extGapSign * EXTENSION_OVERRUN_PX;

        cs.setLineWidth(0.5f);
        line(cs, extStart1, y1, extEnd1, y1);
        line(cs, extStart1, y2, extEnd1, y2);
        line(cs, dimX, y1, dimX, y2);

        if (style == Style.ARCHITECTURAL) {
            fillArrowheadPdf(cs, dimX, y1, 0, +1);
            fillArrowheadPdf(cs, dimX, y2, 0, -1);
        } else {
            tickPdf(cs, dimX, y1, false);
            tickPdf(cs, dimX, y2, false);
        }

        if (label != null && !label.isEmpty()) {
            float labelW = font.getStringWidth(label) / 1000f * fontSize;
            float midY = (y1 + y2) / 2f;
            cs.saveGraphicsState();
            cs.transform(org.apache.pdfbox.util.Matrix.getRotateInstance(
                    Math.PI / 2, dimX + LABEL_GAP_PX, midY - labelW / 2f));
            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(0, 0);
            cs.showText(label);
            cs.endText();
            cs.restoreGraphicsState();
        }
    }

    private static void line(PDPageContentStream cs,
                             float x1, float y1, float x2, float y2)
            throws IOException {
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private static void fillArrowheadPdf(PDPageContentStream cs,
                                         float tipX, float tipY,
                                         float dirX, float dirY)
            throws IOException {
        float baseX = tipX + dirX * ARROWHEAD_LENGTH_PX;
        float baseY = tipY + dirY * ARROWHEAD_LENGTH_PX;
        float perpX = -dirY;
        float perpY = +dirX;
        cs.moveTo(tipX, tipY);
        cs.lineTo(baseX + perpX * ARROWHEAD_HALF_WIDTH_PX,
                  baseY + perpY * ARROWHEAD_HALF_WIDTH_PX);
        cs.lineTo(baseX - perpX * ARROWHEAD_HALF_WIDTH_PX,
                  baseY - perpY * ARROWHEAD_HALF_WIDTH_PX);
        cs.closePath();
        cs.fill();
    }

    private static void tickPdf(PDPageContentStream cs,
                                float cx, float cy, boolean horizontalDim)
            throws IOException {
        float dx = TICK_HALF_LENGTH_PX, dy = TICK_HALF_LENGTH_PX;
        if (!horizontalDim) { dx = -dx; }
        line(cs, cx - dx, cy - dy, cx + dx, cy + dy);
    }

    /** Color hint commonly used for dim text/lines — caller can override
     *  by setting its own color before invoking the draw methods. */
    public static final Color DEFAULT_DIM_COLOR = new Color(0.35f, 0.45f, 0.55f);

    /** Conservative font for on-screen dim labels — small SANS so they
     *  don't crowd the part renderings. */
    public static final Font DEFAULT_DIM_FONT_SCREEN =
            new Font(Font.SANS_SERIF, Font.PLAIN, 9);
}
