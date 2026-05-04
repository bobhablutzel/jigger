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

import app.cadette.model.GrainRequirement;
import app.cadette.model.SheetLayout;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports cut sheet layouts to PDF and image files.
 * Image export uses the shared {@link CutSheetRenderer} via BufferedImage.
 * PDF export uses PDFBox's native vector API for crisp output.
 *
 * <p>For-loops here are deliberately not rewritten as streams: every loop
 * body calls PDFBox APIs that throw the checked {@link IOException}, and
 * propagating checked exceptions out of a stream pipeline forces either
 * sneaky-throw tricks or wrapping in a runtime exception — the classic
 * "streams would require gymnastics" case. See feedback_prefer_streams.md.
 */
public class CutSheetExporter {

    private static final int IMAGE_WIDTH = 1600;

    // PDF constants (points, 72 per inch)
    private static final float PDF_MARGIN = 36;  // 0.5 inch
    private static final float PDF_HEADER_SIZE = 12;
    private static final float PDF_LABEL_SIZE = 8;
    private static final float PDF_DIM_SIZE = 7;
    private static final float PDF_SHEET_GAP = 30;
    private static final float PDF_HEADER_GAP = 16;

    /** Export layouts as a PNG or JPEG image without cutout overlays (legacy callers). */
    public static void exportImage(List<SheetLayout> layouts, UnitSystem units,
                                   Path outputPath) throws IOException {
        exportImage(layouts, units, outputPath, java.util.Map.of());
    }

    /**
     * Export layouts as a PNG or JPEG image, with optional per-part cutout
     * overlays on each sheet (use {@link SceneManager#getEffectiveCutouts()}
     * at the call site to populate the map).
     */
    public static void exportImage(List<SheetLayout> layouts, UnitSystem units,
                                   Path outputPath,
                                   java.util.Map<String, List<app.cadette.model.Cutout>> partCutouts)
            throws IOException {
        String ext = getExtension(outputPath).toLowerCase();
        if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) {
            throw new IOException("Unsupported image format: " + ext + ". Use png, jpg, or jpeg.");
        }

        int height = CutSheetRenderer.computeTotalHeight(IMAGE_WIDTH, layouts);
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        CutSheetRenderer.render(g2, IMAGE_WIDTH, height, layouts, units, true,
                java.util.Set.of(), null, partCutouts);
        g2.dispose();

        String formatName = ext.equals("jpg") ? "jpeg" : ext;
        ImageIO.write(image, formatName, outputPath.toFile());
    }

    /** Export PDF without cutout overlays (legacy callers). */
    public static void exportPdf(List<SheetLayout> layouts, UnitSystem units,
                                 Path outputPath) throws IOException {
        exportPdf(layouts, units, outputPath, java.util.Map.of());
    }

    /**
     * Export layouts as a multi-page PDF with vector graphics.
     * Each sheet of material gets its own PDF page for clear printing.
     * Per-part rect cutouts are overlaid as dashed red outlines when supplied.
     */
    public static void exportPdf(List<SheetLayout> layouts, UnitSystem units,
                                 Path outputPath,
                                 java.util.Map<String, List<app.cadette.model.Cutout>> partCutouts)
            throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontNormal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font fontItalic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

            for (int i = 0; i < layouts.size(); i++) {
                SheetLayout layout = layouts.get(i);
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);

                float pageW = page.getMediaBox().getWidth();
                float pageH = page.getMediaBox().getHeight();

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    drawPdfSheet(cs, layout, i + 1, units, pageW, pageH,
                            fontBold, fontNormal, fontItalic, partCutouts);
                }
            }

            // If no layouts, add an empty page with a message
            if (layouts.isEmpty()) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(fontNormal, 14);
                    cs.newLineAtOffset(PDF_MARGIN, page.getMediaBox().getHeight() - PDF_MARGIN - 14);
                    cs.showText("No sheet layouts to export.");
                    cs.endText();
                }
            }

            doc.save(outputPath.toFile());
        }
    }

    private static void drawPdfSheet(PDPageContentStream cs, SheetLayout layout,
                                     int sheetNumber, UnitSystem units,
                                     float pageW, float pageH,
                                     PDType1Font fontBold, PDType1Font fontNormal,
                                     PDType1Font fontItalic,
                                     java.util.Map<String, List<app.cadette.model.Cutout>> partCutouts)
            throws IOException {
        // Compute scale to fit sheet within page margins
        float drawableW = pageW - 2 * PDF_MARGIN;
        float drawableH = pageH - 2 * PDF_MARGIN - PDF_HEADER_GAP - PDF_HEADER_SIZE - 20;

        float scaleX = drawableW / layout.getSheetWidthMm();
        float scaleY = drawableH / layout.getSheetHeightMm();
        float scale = Math.min(scaleX, scaleY);

        float sheetW = layout.getSheetWidthMm() * scale;
        float sheetH = layout.getSheetHeightMm() * scale;

        // PDF origin is bottom-left; we draw top-down
        float headerY = pageH - PDF_MARGIN;
        float sheetTop = headerY - PDF_HEADER_SIZE - PDF_HEADER_GAP;
        float sheetLeft = PDF_MARGIN;

        // Header text
        String header = String.format("Sheet %d — %s  (%.0f%% offcut)",
                sheetNumber,
                layout.getMaterial().getDisplayName(),
                layout.getOffcutPercent());
        cs.beginText();
        cs.setFont(fontBold, PDF_HEADER_SIZE);
        cs.newLineAtOffset(sheetLeft, headerY - PDF_HEADER_SIZE);
        cs.showText(header);
        cs.endText();

        // Sheet outline (filled)
        float sheetBottomY = sheetTop - sheetH;
        setFillColor(cs, CutSheetRenderer.SHEET_FILL);
        cs.addRect(sheetLeft, sheetBottomY, sheetW, sheetH);
        cs.fill();

        setStrokeColor(cs, CutSheetRenderer.SHEET_BORDER);
        cs.setLineWidth(1.0f);
        cs.addRect(sheetLeft, sheetBottomY, sheetW, sheetH);
        cs.stroke();

        // Parts
        for (SheetLayout.PlacedPart part : layout.getPlacements()) {
            float px = sheetLeft + part.getX() * scale;
            // PDF Y is inverted: top of sheet is sheetTop, part Y grows downward
            float py = sheetTop - part.getY() * scale - part.getHeightOnSheet() * scale;
            float pw = part.getWidthOnSheet() * scale;
            float ph = part.getHeightOnSheet() * scale;

            // Part fill (same color for all parts)
            setFillColor(cs, CutSheetRenderer.PART_FILL);
            cs.addRect(px, py, pw, ph);
            cs.fill();

            // Grain lines
            if (part.getGrainRequirement() != GrainRequirement.ANY) {
                drawPdfGrainLines(cs, px, py, pw, ph, part.isRotated());
            }

            // Rotation indicator (text fallback — standard PDF fonts lack U+21BA)
            if (part.isRotated() && pw > 20 && ph > 14) {
                setFillColor(cs, CutSheetRenderer.DIM_COLOR);
                cs.beginText();
                cs.setFont(fontItalic, 6);
                cs.newLineAtOffset(px + 2, py + ph - 8);
                cs.showText("rot");
                cs.endText();
            }

            // Part border
            setStrokeColor(cs, CutSheetRenderer.PART_BORDER);
            cs.setLineWidth(0.5f);
            cs.addRect(px, py, pw, ph);
            cs.stroke();

            // Cutout overlays (dashed) — same dashed-red treatment as
            // CutSheetRenderer's Java2D path, but using PDFBox's line API
            // and PDF's bottom-left origin (so the part-local Y → PDF Y
            // mapping is inverted relative to the Java2D version).
            drawPdfCutoutOverlays(cs, part, px, py, ph, scale,
                    partCutouts.getOrDefault(part.getPartName(), List.of()));

            // Part label
            drawPdfPartLabel(cs, part, px, py, pw, ph, units,
                    fontNormal, fontItalic);
        }

        // Sheet dimensions
        String abbr = units.getAbbreviation();
        String wText = String.format("%.1f %s", units.fromMm(layout.getSheetWidthMm()), abbr);
        String hText = String.format("%.1f %s", units.fromMm(layout.getSheetHeightMm()), abbr);

        setFillColor(cs, CutSheetRenderer.DIM_COLOR);

        // Width below sheet
        float wTextWidth = fontNormal.getStringWidth(wText) / 1000f * PDF_DIM_SIZE;
        cs.beginText();
        cs.setFont(fontNormal, PDF_DIM_SIZE);
        cs.newLineAtOffset(sheetLeft + sheetW / 2f - wTextWidth / 2f, sheetBottomY - 12);
        cs.showText(wText);
        cs.endText();

        // Height to right of sheet (rotated)
        float hTextWidth = fontNormal.getStringWidth(hText) / 1000f * PDF_DIM_SIZE;
        cs.saveGraphicsState();
        cs.transform(new org.apache.pdfbox.util.Matrix(
                0, 1, -1, 0,
                sheetLeft + sheetW + 12, sheetBottomY + sheetH / 2f - hTextWidth / 2f));
        cs.beginText();
        cs.setFont(fontNormal, PDF_DIM_SIZE);
        cs.newLineAtOffset(0, 0);
        cs.showText(hText);
        cs.endText();
        cs.restoreGraphicsState();
    }

    /**
     * Dashed-line cutout overlays in PDF space. The math mirrors
     * {@link CutSheetRenderer#cutoutToSheetRect} but inverts Y because
     * PDF's origin is bottom-left and the placed-rect's {@code (px, py)}
     * here is its bottom-left corner — so part-local cy=0 (top of the
     * placed rect in display) maps to PDF y = py + ph, with cy growing
     * downward in display = decreasing PDF y.
     */
    private static void drawPdfCutoutOverlays(PDPageContentStream cs,
                                              SheetLayout.PlacedPart part,
                                              float px, float py, float ph, float scale,
                                              List<app.cadette.model.Cutout> cutouts)
            throws IOException {
        if (cutouts.isEmpty()) return;
        setStrokeColor(cs, CutSheetRenderer.CUTOUT_COLOR);
        cs.setLineWidth(0.5f);
        cs.setLineDashPattern(new float[]{2.5f, 2f}, 0);
        for (app.cadette.model.Cutout c : cutouts) {
            if (c instanceof app.cadette.model.Cutout.Rect r) {
                float cx, cy, cw, ch;
                if (part.isRotated()) {
                    cx = px + r.yMm() * scale;
                    cy = py + ph - (r.xMm() + r.widthMm()) * scale;
                    cw = r.heightMm() * scale;
                    ch = r.widthMm() * scale;
                } else {
                    cx = px + r.xMm() * scale;
                    cy = py + ph - (r.yMm() + r.heightMm()) * scale;
                    cw = r.widthMm() * scale;
                    ch = r.heightMm() * scale;
                }
                if (cw < 0.5f || ch < 0.5f) continue;
                cs.addRect(cx, cy, cw, ch);
                cs.stroke();
            } else if (c instanceof app.cadette.model.Cutout.Circle ci) {
                // PDF Y is bottom-up; for circles we just need the center
                // and radius. Rotation only swaps cx/cy of the center.
                float r = ci.radiusMm() * scale;
                if (r < 0.5f) continue;
                float centerX, centerY;
                if (part.isRotated()) {
                    centerX = px + ci.cyMm() * scale;
                    centerY = py + ph - ci.cxMm() * scale;
                } else {
                    centerX = px + ci.cxMm() * scale;
                    centerY = py + ph - ci.cyMm() * scale;
                }
                drawPdfCircle(cs, centerX, centerY, r);
            }
            // Cutout.Polygon / Spline: skip until those variants land.
        }
        // Reset dash pattern so subsequent strokes (label baselines, etc.) are solid.
        cs.setLineDashPattern(new float[]{}, 0);
    }

    /**
     * Draw a circle on the PDF using four cubic Bézier arcs. PDFBox has no
     * circle primitive; the four-arc approximation with kappa = 4*(√2−1)/3
     * (≈ 0.5522847) is visually indistinguishable from a real circle at any
     * sane print resolution.
     */
    private static void drawPdfCircle(PDPageContentStream cs,
                                      float centerX, float centerY, float radius) throws IOException {
        float k = 0.5522847498f * radius;
        // Start at the rightmost point, sweep counterclockwise.
        cs.moveTo(centerX + radius, centerY);
        cs.curveTo(centerX + radius, centerY + k,  centerX + k, centerY + radius,  centerX, centerY + radius);
        cs.curveTo(centerX - k, centerY + radius,  centerX - radius, centerY + k,  centerX - radius, centerY);
        cs.curveTo(centerX - radius, centerY - k,  centerX - k, centerY - radius,  centerX, centerY - radius);
        cs.curveTo(centerX + k, centerY - radius,  centerX + radius, centerY - k,  centerX + radius, centerY);
        cs.stroke();
    }

    private static void drawPdfGrainLines(PDPageContentStream cs,
                                           float px, float py, float pw, float ph,
                                           boolean rotated) throws IOException {
        cs.setStrokingColor(0.7f, 0.6f, 0.45f);
        cs.setLineWidth(0.3f);
        if (!rotated) {
            for (float x = px + 4; x < px + pw; x += 6) {
                cs.moveTo(x, py + 2);
                cs.lineTo(x, py + ph - 2);
                cs.stroke();
            }
        } else {
            for (float y = py + 4; y < py + ph; y += 6) {
                cs.moveTo(px + 2, y);
                cs.lineTo(px + pw - 2, y);
                cs.stroke();
            }
        }
    }

    private static void drawPdfPartLabel(PDPageContentStream cs, SheetLayout.PlacedPart part,
                                          float px, float py, float pw, float ph,
                                          UnitSystem units,
                                          PDType1Font fontNormal,
                                          PDType1Font fontItalic) throws IOException {
        if (pw < 20 || ph < 14) return;

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

        float nameWidth = fontNormal.getStringWidth(name) / 1000f * PDF_LABEL_SIZE;
        float dimsWidth = fontItalic.getStringWidth(dims) / 1000f * (PDF_LABEL_SIZE - 1);

        float cx = px + pw / 2f;
        float cy = py + ph / 2f;

        setFillColor(cs, CutSheetRenderer.TEXT_COLOR);

        if (ph > 24) {
            // Two lines: name + dimensions
            cs.beginText();
            cs.setFont(fontNormal, PDF_LABEL_SIZE);
            cs.newLineAtOffset(cx - nameWidth / 2f, cy + 2);
            cs.showText(name);
            cs.endText();

            setFillColor(cs, CutSheetRenderer.DIM_COLOR);
            cs.beginText();
            cs.setFont(fontItalic, PDF_LABEL_SIZE - 1);
            cs.newLineAtOffset(cx - dimsWidth / 2f, cy - PDF_LABEL_SIZE);
            cs.showText(dims);
            cs.endText();
        } else {
            // Single line: just the name
            cs.beginText();
            cs.setFont(fontNormal, PDF_LABEL_SIZE);
            cs.newLineAtOffset(cx - nameWidth / 2f, cy - PDF_LABEL_SIZE / 2f);
            cs.showText(name);
            cs.endText();
        }
    }

    private static void setFillColor(PDPageContentStream cs, Color c) throws IOException {
        cs.setNonStrokingColor(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);
    }

    private static void setStrokeColor(PDPageContentStream cs, Color c) throws IOException {
        cs.setStrokingColor(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);
    }

    private static String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }
}
