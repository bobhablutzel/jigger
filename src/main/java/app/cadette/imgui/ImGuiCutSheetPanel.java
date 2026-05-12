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

package app.cadette.imgui;

import app.cadette.CutSheetRenderer;
import app.cadette.SceneManager;
import app.cadette.UnitSystem;
import app.cadette.model.SheetLayout;
import app.cadette.model.SheetLayoutGenerator;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Cut-sheet rendering inside the ImGui window. Renders the existing
 * Java2D {@link CutSheetRenderer} output to a {@link BufferedImage},
 * uploads it as a GL texture, and draws it via {@code ImGui.image()}.
 *
 * <p>Re-renders only when the scene marks the cut sheet dirty OR the
 * panel content area's pixel size changes. That keeps the redraw off
 * the hot path during orbit / pan etc.
 *
 * <p>Texture origin convention: BufferedImage rows are top-down, GL
 * textures default to bottom-up. We flip via UV coords on the
 * {@code ImGui.image()} call rather than reordering pixel data.
 */
public class ImGuiCutSheetPanel {

    private final SceneManager scene;
    private final Supplier<UnitSystem> unitsSupplier;

    private int textureId = -1;
    private int textureW = -1;
    private int textureH = -1;
    private List<SheetLayout> cachedLayouts = List.of();
    /** True while the scene-dirty bit had already been cleared by the previous
     *  render — used so a manual resize re-renders even when the scene hasn't
     *  changed. */
    private boolean firstRender = true;

    public ImGuiCutSheetPanel(SceneManager scene, Supplier<UnitSystem> unitsSupplier) {
        this.scene = scene;
        this.unitsSupplier = unitsSupplier;
    }

    public void draw() {
        ImGui.begin("Cut Sheet", ImGuiWindowFlags.NoCollapse);

        var avail = ImGui.getContentRegionAvail();
        int w = Math.max(100, (int) avail.x);
        int h = Math.max(100, (int) avail.y);

        boolean sizeChanged = (w != textureW || h != textureH);
        boolean sceneDirty = scene.isCutSheetDirty();
        if (firstRender || sceneDirty || sizeChanged) {
            renderToTexture(w, h);
            firstRender = false;
        }

        if (textureId != -1) {
            // ImGui's image() with default UVs (0,0)→(1,1) samples the
            // texture top-left-first, matching BufferedImage's row order.
            // No UV flip needed.
            ImGui.image(textureId, w, h);
        }

        ImGui.end();
    }

    private void renderToTexture(int w, int h) {
        if (scene.isCutSheetDirty() || cachedLayouts.isEmpty()) {
            cachedLayouts = SheetLayoutGenerator.generateLayouts(
                    scene.getAllParts(), scene.getKerfMm());
            scene.clearCutSheetDirty();
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Match the ImGui dark-theme background so the rendered tile
            // blends with surrounding panels.
            g2.setColor(new Color(33, 33, 33));
            g2.fillRect(0, 0, w, h);
            CutSheetRenderer.render(g2, w, h, cachedLayouts,
                    unitsSupplier.get(), false, Set.of(), null,
                    scene.getEffectiveCutouts(), scene.getEffectiveKeeps());
        } finally {
            g2.dispose();
        }

        uploadToTexture(img, w, h);
    }

    /** Convert BufferedImage to RGBA bytes and push to a GL2D texture. */
    private void uploadToTexture(BufferedImage img, int w, int h) {
        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);

        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int p : pixels) {
            buf.put((byte) ((p >> 16) & 0xFF));  // R
            buf.put((byte) ((p >> 8)  & 0xFF));  // G
            buf.put((byte) ( p        & 0xFF));  // B
            buf.put((byte) ((p >> 24) & 0xFF));  // A
        }
        buf.flip();

        if (textureId == -1) {
            textureId = GL11.glGenTextures();
        }
        // Save / restore the current binding so we don't disturb ImGui's
        // texture state when this fires mid-frame.
        int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBinding);
        textureW = w;
        textureH = h;
    }

    public void dispose() {
        if (textureId != -1) {
            GL11.glDeleteTextures(textureId);
            textureId = -1;
        }
    }
}
