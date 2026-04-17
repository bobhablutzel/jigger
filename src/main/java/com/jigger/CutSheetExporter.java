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
 * Source: https://github.com/bobhablutzel/jigger
 */

package com.jigger;

import com.jigger.model.GrainRequirement;
import com.jigger.model.SheetLayout;

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

    /**
     * Export layouts as a PNG or JPEG image.
     */
    public static void exportImage(List<SheetLayout> layouts, UnitSystem units,
                                   Path outputPath) throws IOException {
        String ext = getExtension(outputPath).toLowerCase();
        if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) {
            throw new IOException("Unsupported image format: " + ext + ". Use png, jpg, or jpeg.");
        }

        int height = CutSheetRenderer.computeTotalHeight(IMAGE_WIDTH, layouts);
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        CutSheetRenderer.render(g2, IMAGE_WIDTH, height, layouts, units, true);
        g2.dispose();

        String formatName = ext.equals("jpg") ? "jpeg" : ext;
        ImageIO.write(image, formatName, outputPath.toFile());
    }

    /**
     * Export layouts as a multi-page PDF with vector graphics.
     * Each sheet of material gets its own PDF page for clear printing.
     */
    public static void exportPdf(List<SheetLayout> layouts, UnitSystem units,
                                 Path outputPath) throws IOException {
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
                            fontBold, fontNormal, fontItalic);
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
                                     PDType1Font fontItalic) throws IOException {
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
