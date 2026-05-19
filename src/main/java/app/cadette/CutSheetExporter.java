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
import app.cadette.model.PartMeshBuilder;
import app.cadette.model.SheetLayout;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

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
        exportImage(layouts, units, outputPath, java.util.Map.of(), java.util.Map.of());
    }

    /**
     * Export layouts as a PNG or JPEG image, with optional per-part cutout
     * overlays on each sheet (use {@link SceneManager#getEffectiveCutouts()}
     * at the call site to populate the map).
     */
    public static void exportImage(List<SheetLayout> layouts, UnitSystem units,
                                   Path outputPath,
                                   java.util.Map<String, List<app.cadette.model.Cutout>> partCutouts,
                                   java.util.Map<String, List<app.cadette.model.Cutout>> partKeeps)
            throws IOException {
        String ext = getExtension(outputPath).toLowerCase();
        if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) {
            throw new IOException("Unsupported image format: " + ext + ". Use png, jpg, or jpeg.");
        }

        int height = CutSheetRenderer.computeTotalHeight(IMAGE_WIDTH, layouts);
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        CutSheetRenderer.render(g2, IMAGE_WIDTH, height, layouts, units, true,
                java.util.Set.of(), null, partCutouts, partKeeps);
        g2.dispose();

        String formatName = ext.equals("jpg") ? "jpeg" : ext;
        ImageIO.write(image, formatName, outputPath.toFile());
    }

    /** Export PDF without cutout overlays (legacy callers). */
    public static void exportPdf(List<SheetLayout> layouts, UnitSystem units,
                                 Path outputPath) throws IOException {
        exportPdf(layouts, units, outputPath, java.util.Map.of(), java.util.Map.of());
    }

    /**
     * Export layouts as a multi-page PDF with vector graphics.
     * Each sheet of material gets its own PDF page for clear printing.
     * Per-part rect cutouts are overlaid as dashed red outlines when supplied;
     * keep regions outline what to retain (X marker on the discarded part).
     */
    public static void exportPdf(List<SheetLayout> layouts, UnitSystem units,
                                 Path outputPath,
                                 java.util.Map<String, List<app.cadette.model.Cutout>> partCutouts,
                                 java.util.Map<String, List<app.cadette.model.Cutout>> partKeeps)
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
                            fontBold, fontNormal, fontItalic, partCutouts, partKeeps);
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
                                     java.util.Map<String, List<app.cadette.model.Cutout>> partCutouts,
                                     java.util.Map<String, List<app.cadette.model.Cutout>> partKeeps)
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

            // Cutout overlays (dashed) + X marker on discarded regions —
            // same treatment as CutSheetRenderer's Java2D path, using
            // PDFBox's path API and PDF's bottom-left origin.
            drawPdfCutoutOverlays(cs, part, px, py, pw, ph, scale,
                    partCutouts.getOrDefault(part.getPartName(), List.of()),
                    partKeeps.getOrDefault(part.getPartName(), List.of()));

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
                                              float px, float py, float pw, float ph, float scale,
                                              List<app.cadette.model.Cutout> cutouts,
                                              List<app.cadette.model.Cutout> keeps)
            throws IOException {
        if (cutouts.isEmpty() && keeps.isEmpty()) return;

        // ---- Per-operation dashed outlines (machining instructions). ----
        cs.saveGraphicsState();
        cs.addRect(px, py, pw, ph);
        cs.clip();
        setStrokeColor(cs, CutSheetRenderer.CUTOUT_COLOR);
        cs.setLineWidth(0.5f);
        cs.setLineDashPattern(new float[]{2.5f, 2f}, 0);
        for (app.cadette.model.Cutout c : cutouts) drawPdfCutoutShape(cs, c, part, px, py, ph, scale);
        for (app.cadette.model.Cutout k : keeps)   drawPdfCutoutShape(cs, k, part, px, py, ph, scale);
        cs.setLineDashPattern(new float[]{}, 0);
        cs.restoreGraphicsState();

        // ---- Solid final-profile outline (the saw boundary). ----
        float[] localSize = pdfPartLocalSize(part);
        Geometry profile = PartMeshBuilder.computeFinalProfile(
                localSize[0], localSize[1], cutouts, keeps);
        if (!profile.isEmpty()) {
            cs.saveGraphicsState();
            setStrokeColor(cs, CutSheetRenderer.PROFILE_COLOR);
            cs.setLineWidth(1.0f);
            emitPdfGeometryPath(cs, profile, part, px, py, ph, scale);
            cs.stroke();
            cs.restoreGraphicsState();
        }

        // ---- X marker per discarded component. Walk the JTS discard
        //      (Polygon or MultiPolygon); each Polygon is one connected
        //      waste piece. Draw an X sized to its bbox, clipped to its
        //      outline so diagonals stay inside the waste. Skip components
        //      below MIN_X_MARKER_PX to suppress kerf-thin slivers. ----
        Geometry discard = PartMeshBuilder.computeDiscard(localSize[0], localSize[1], profile);
        for (int i = 0; i < discard.getNumGeometries(); i++) {
            Geometry component = discard.getGeometryN(i);
            if (!(component instanceof org.locationtech.jts.geom.Polygon)) continue;
            org.locationtech.jts.geom.Envelope env = component.getEnvelopeInternal();
            float[] cornerA = partLocalToPdf(env.getMinX(), env.getMinY(), part, px, py, ph, scale);
            float[] cornerB = partLocalToPdf(env.getMaxX(), env.getMaxY(), part, px, py, ph, scale);
            float bx = Math.min(cornerA[0], cornerB[0]);
            float by = Math.min(cornerA[1], cornerB[1]);
            float ex = Math.max(cornerA[0], cornerB[0]);
            float ey = Math.max(cornerA[1], cornerB[1]);
            if ((ex - bx) < CutSheetRenderer.MIN_X_MARKER_PX
                    || (ey - by) < CutSheetRenderer.MIN_X_MARKER_PX) continue;

            cs.saveGraphicsState();
            emitPdfGeometryPath(cs, component, part, px, py, ph, scale);
            cs.clip();
            setStrokeColor(cs, CutSheetRenderer.CUTOUT_COLOR);
            cs.setLineWidth(0.5f);
            cs.moveTo(bx, by); cs.lineTo(ex, ey); cs.stroke();
            cs.moveTo(bx, ey); cs.lineTo(ex, by); cs.stroke();
            cs.restoreGraphicsState();
        }
    }

    /** Part dimensions in part-local mm, regardless of sheet rotation. */
    private static float[] pdfPartLocalSize(SheetLayout.PlacedPart part) {
        if (part.isRotated()) {
            return new float[] { part.getHeightOnSheet(), part.getWidthOnSheet() };
        }
        return new float[] { part.getWidthOnSheet(), part.getHeightOnSheet() };
    }

    /** Emit a JTS geometry as a PDF path (move/line/close) without stroking
     *  or filling. Caller decides what to do with it (stroke, clip, etc.). */
    private static void emitPdfGeometryPath(PDPageContentStream cs, Geometry g,
                                            SheetLayout.PlacedPart part,
                                            float px, float py, float ph, float scale)
            throws IOException {
        if (g.isEmpty()) return;
        if (g instanceof org.locationtech.jts.geom.Polygon p) {
            emitPdfRing(cs, p.getExteriorRing().getCoordinates(), part, px, py, ph, scale);
            for (int i = 0; i < p.getNumInteriorRing(); i++) {
                emitPdfRing(cs, p.getInteriorRingN(i).getCoordinates(), part, px, py, ph, scale);
            }
        } else {
            for (int i = 0; i < g.getNumGeometries(); i++) {
                emitPdfGeometryPath(cs, g.getGeometryN(i), part, px, py, ph, scale);
            }
        }
    }

    private static void emitPdfRing(PDPageContentStream cs, Coordinate[] coords,
                                    SheetLayout.PlacedPart part,
                                    float px, float py, float ph, float scale) throws IOException {
        if (coords.length == 0) return;
        float[] first = partLocalToPdf(coords[0].x, coords[0].y, part, px, py, ph, scale);
        cs.moveTo(first[0], first[1]);
        for (int i = 1; i < coords.length; i++) {
            float[] pt = partLocalToPdf(coords[i].x, coords[i].y, part, px, py, ph, scale);
            cs.lineTo(pt[0], pt[1]);
        }
        cs.closePath();
    }

    /** Part-local (x, y) → PDF pixel space, applying rotation and Y inversion
     *  (PDF Y is bottom-up). Matches the transform in {@link #pdfRectXY}. */
    private static float[] partLocalToPdf(double localX, double localY,
                                          SheetLayout.PlacedPart part,
                                          float px, float py, float ph, float scale) {
        if (part.isRotated()) {
            return new float[] {
                    px + (float) localY * scale,
                    py + ph - (float) localX * scale
            };
        }
        return new float[] {
                px + (float) localX * scale,
                py + ph - (float) localY * scale
        };
    }

    /** Dashed outline for one cutout shape. */
    private static void drawPdfCutoutShape(PDPageContentStream cs,
                                           app.cadette.model.Cutout c,
                                           SheetLayout.PlacedPart part,
                                           float px, float py, float ph, float scale)
            throws IOException {
        if (c instanceof app.cadette.model.Cutout.Rect r) {
            float[] xy = pdfRectXY(r, part, px, py, ph, scale);
            if (xy[2] < 0.5f || xy[3] < 0.5f) return;
            cs.addRect(xy[0], xy[1], xy[2], xy[3]);
            cs.stroke();
        } else if (c instanceof app.cadette.model.Cutout.Circle ci) {
            float radius = ci.radiusMm() * scale;
            if (radius < 0.5f) return;
            float[] cxcy = pdfCircleCenter(ci, part, px, py, ph, scale);
            drawPdfCircle(cs, cxcy[0], cxcy[1], radius);
        } else if (c instanceof app.cadette.model.Cutout.Polygon poly) {
            drawPdfVertexPath(cs, poly.vertices(), part, px, py, ph, scale);
        } else if (c instanceof app.cadette.model.Cutout.Spline sp) {
            drawPdfVertexPath(cs,
                    app.cadette.model.SplineTessellator.tessellateCatmullRom(sp.controlPoints()),
                    part, px, py, ph, scale);
        }
        // Cutout.Curve (Bezier) still skipped — same reason as the
        // Graphics2D renderer; punt to a shared Bezier utility when
        // someone draws a sled handle on a part with PDF output.
    }

    /** Stroke a closed dashed path through {@code vertices} in PDF space.
     *  Degenerate inputs (< 3 vertices) are silently dropped — matches the
     *  Graphics2D renderer's defense. */
    private static void drawPdfVertexPath(PDPageContentStream cs,
                                          java.util.List<app.cadette.model.Point2D> vertices,
                                          SheetLayout.PlacedPart part,
                                          float px, float py, float ph, float scale)
            throws IOException {
        if (vertices == null || vertices.size() < 3) return;
        float[] first = partLocalToPdf(
                vertices.get(0).xMm(), vertices.get(0).yMm(),
                part, px, py, ph, scale);
        cs.moveTo(first[0], first[1]);
        for (int i = 1; i < vertices.size(); i++) {
            float[] pt = partLocalToPdf(
                    vertices.get(i).xMm(), vertices.get(i).yMm(),
                    part, px, py, ph, scale);
            cs.lineTo(pt[0], pt[1]);
        }
        cs.closePath();
        cs.stroke();
    }

    /** PDF rect coords (cx, cy, cw, ch) for a {@link app.cadette.model.Cutout.Rect}. */
    private static float[] pdfRectXY(app.cadette.model.Cutout.Rect r,
                                     SheetLayout.PlacedPart part,
                                     float px, float py, float ph, float scale) {
        if (part.isRotated()) {
            return new float[] {
                    px + r.yMm() * scale,
                    py + ph - (r.xMm() + r.widthMm()) * scale,
                    r.heightMm() * scale,
                    r.widthMm() * scale
            };
        }
        return new float[] {
                px + r.xMm() * scale,
                py + ph - (r.yMm() + r.heightMm()) * scale,
                r.widthMm() * scale,
                r.heightMm() * scale
        };
    }

    /** PDF circle centre for a {@link app.cadette.model.Cutout.Circle}. */
    private static float[] pdfCircleCenter(app.cadette.model.Cutout.Circle ci,
                                           SheetLayout.PlacedPart part,
                                           float px, float py, float ph, float scale) {
        if (part.isRotated()) {
            return new float[] {
                    px + ci.cyMm() * scale,
                    py + ph - ci.cxMm() * scale
            };
        }
        return new float[] {
                px + ci.cxMm() * scale,
                py + ph - ci.cyMm() * scale
        };
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
