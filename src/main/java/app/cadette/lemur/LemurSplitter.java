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

package app.cadette.lemur;

import com.jme3.input.InputManager;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.event.CursorButtonEvent;
import com.simsilica.lemur.event.CursorEventControl;
import com.simsilica.lemur.event.CursorMotionEvent;
import com.simsilica.lemur.event.DefaultCursorListener;

import java.util.function.BiConsumer;

/**
 * Two-child split layout primitive — horizontal (left/right) or vertical
 * (top/bottom). The divider between the two children is draggable; LMB-press
 * on the divider starts a drag, subsequent cursor motion updates the split
 * ratio, LMB-release ends it.
 *
 * <p>Use case: build the Lemur UI as a tree of these (and TabHosts) instead
 * of absolute-positioned panels, so resizing one region pushes against the
 * dividers and the user can lay out their workspace.
 *
 * <p>Coordinates: the Splitter is positioned in its parent like any Lemur
 * panel — {@code setLocalTranslation} puts its top-left at the given point
 * in parent space (jME3 GUI Y-up convention). Children are then arranged
 * locally so the first child fills the top/left portion and the second the
 * bottom/right.
 *
 * <p>Reflow callback: each time the splitter's size or ratio changes, the
 * supplied {@code reflowCallback} is invoked once per child with the
 * child's new (width, height). The callback owns telling that child's
 * inner widgets to resize — different panels need different sizing rules
 * (a Container with a ListBox vs an image holder vs another Splitter), so
 * we keep that logic external.
 */
public class LemurSplitter extends Node {

    public enum Orient { HORIZONTAL, VERTICAL }

    private static final float DIVIDER_THICKNESS = 6f;
    private static final ColorRGBA DIVIDER_COLOR        = new ColorRGBA(0.25f, 0.25f, 0.28f, 1f);
    private static final ColorRGBA DIVIDER_HOVER_COLOR  = new ColorRGBA(0.45f, 0.55f, 0.70f, 1f);

    private final Orient orient;
    private final Spatial firstChild;
    private final Spatial secondChild;
    private final Container divider;
    private final QuadBackgroundComponent dividerBg;
    private final BiConsumer<Spatial, float[]> reflowCallback;

    private float ratio = 0.5f;
    private float totalW = 0f;
    private float totalH = 0f;

    /** Minimum pixel size for each child along the split axis. Drag-resize
     *  is clamped so neither child goes below its min. Use
     *  {@link #setMinSizes} to set; defaults below are conservative. */
    private float firstMinSize = 60f;
    private float secondMinSize = 60f;

    private boolean dragging = false;
    /** Cursor coord (X for horizontal, Y for vertical) at drag start. */
    private float dragStartCursor = 0f;
    /** Ratio at drag start; current ratio = dragStartRatio + delta/totalSize. */
    private float dragStartRatio = 0f;

    public LemurSplitter(Orient orient,
                         Spatial firstChild,
                         Spatial secondChild,
                         BiConsumer<Spatial, float[]> reflowCallback) {
        super("LemurSplitter[" + orient + "]");
        this.orient = orient;
        this.firstChild = firstChild;
        this.secondChild = secondChild;
        this.reflowCallback = reflowCallback;

        this.divider = new Container();
        this.dividerBg = new QuadBackgroundComponent(DIVIDER_COLOR);
        divider.setBackground(dividerBg);

        // Divider attached LAST and with a slightly forward Z so it's
        // always on top of either child — important when window/min-size
        // constraints force the panels to visually overlap (otherwise
        // the divider becomes hidden under a panel and unclickable).
        attachChild(firstChild);
        attachChild(secondChild);
        attachChild(divider);

        CursorEventControl.addListenersToSpatial(divider, new DragListener());
    }

    /** Set the divider position as a fraction (0..1) of the total split axis.
     *  Clamped both by a hardcoded 5%/95% safety range and by the
     *  per-child minimum-pixel sizes (see {@link #setMinSizes}).
     *
     *  <p>When the splitter is too small to satisfy BOTH children's
     *  minimums, falls back to a proportional split (each child gets a
     *  fraction proportional to its declared min). This still produces
     *  smaller-than-ideal panels but avoids the prior bug where the
     *  safety 5%/95% clamp let the divider end up far past one
     *  child's min, producing visible content overlap. */
    public void setRatio(float r) {
        float clamped = Math.max(0.05f, Math.min(0.95f, r));
        float total = (orient == Orient.HORIZONTAL) ? totalW : totalH;
        if (total > 0) {
            float minRatio = (firstMinSize + DIVIDER_THICKNESS / 2f) / total;
            float maxRatio = 1f - (secondMinSize + DIVIDER_THICKNESS / 2f) / total;
            if (minRatio < maxRatio) {
                clamped = Math.max(minRatio, Math.min(maxRatio, clamped));
            } else if (firstMinSize + secondMinSize > 0) {
                clamped = firstMinSize / (firstMinSize + secondMinSize);
            }
        }
        ratio = clamped;
        applyLayout();
    }

    /** Lower bounds on each child's pixel size along the split axis.
     *  Prevents the user from dragging a panel into illegibility (e.g.,
     *  shrinking properties so values wrap). */
    public void setMinSizes(float first, float second) {
        this.firstMinSize = first;
        this.secondMinSize = second;
        setRatio(ratio); // re-clamp with the new bounds
    }

    public float getRatio() {
        return ratio;
    }

    public Orient getOrient() {
        return orient;
    }

    /** Resize the splitter and re-layout. Call this whenever the parent's
     *  bounds change (e.g., window resize) or after constructing the tree. */
    public void setSize(float w, float h) {
        this.totalW = w;
        this.totalH = h;
        applyLayout();
    }

    /** Per-frame poll for drag updates. Called from the AppState's update().
     *  Polling instead of relying on cursorMoved lets the drag continue
     *  smoothly even when the cursor wanders off the divider mid-drag. */
    public void updateDrag(InputManager input) {
        if (!dragging) {
            return;
        }
        Vector2f cursor = input.getCursorPosition();
        if (cursor == null) {
            return;
        }
        float delta;
        if (orient == Orient.HORIZONTAL) {
            delta = (cursor.x - dragStartCursor) / totalW;
        } else {
            // jME3 GUI Y grows up. For a vertical split (top child / bottom
            // child), cursor moving DOWN should shrink the top child. So
            // the sign matches: cursor.y decreases → delta is positive only
            // if we invert. ratio is "fraction of the top child", so:
            delta = (dragStartCursor - cursor.y) / totalH;
        }
        setRatio(dragStartRatio + delta);
    }

    /** Z offset given to the divider so it renders on top of any
     *  overflowing panel content. Otherwise content that spills past
     *  its panel's allocated bounds can obscure the divider hit-target. */
    private static final float DIVIDER_Z = 0.1f;

    private void applyLayout() {
        if (totalW <= 0 || totalH <= 0) {
            return; // not yet sized
        }
        if (orient == Orient.HORIZONTAL) {
            float firstW = totalW * ratio - DIVIDER_THICKNESS / 2f;
            float secondW = totalW - firstW - DIVIDER_THICKNESS;

            firstChild.setLocalTranslation(0, 0, 0);
            reflowCallback.accept(firstChild, new float[]{firstW, totalH});

            divider.setLocalTranslation(firstW, 0, DIVIDER_Z);
            divider.setPreferredSize(new Vector3f(DIVIDER_THICKNESS, totalH, 0));

            secondChild.setLocalTranslation(firstW + DIVIDER_THICKNESS, 0, 0);
            reflowCallback.accept(secondChild, new float[]{secondW, totalH});
        } else {
            float topH = totalH * ratio - DIVIDER_THICKNESS / 2f;
            float bottomH = totalH - topH - DIVIDER_THICKNESS;

            firstChild.setLocalTranslation(0, 0, 0);
            reflowCallback.accept(firstChild, new float[]{totalW, topH});

            divider.setLocalTranslation(0, -topH, DIVIDER_Z);
            divider.setPreferredSize(new Vector3f(totalW, DIVIDER_THICKNESS, 0));

            secondChild.setLocalTranslation(0, -topH - DIVIDER_THICKNESS, 0);
            reflowCallback.accept(secondChild, new float[]{totalW, bottomH});
        }
    }

    /** Cursor listener on the divider — toggles {@link #dragging} on
     *  press/release and tints the divider on hover. */
    private final class DragListener extends DefaultCursorListener {
        @Override
        public void cursorButtonEvent(CursorButtonEvent e, Spatial t, Spatial c) {
            if (e.getButtonIndex() != 0) return; // left button only
            if (e.isPressed()) {
                dragging = true;
                if (orient == Orient.HORIZONTAL) {
                    dragStartCursor = e.getX();
                } else {
                    dragStartCursor = e.getY();
                }
                dragStartRatio = ratio;
                e.setConsumed();
            } else {
                dragging = false;
                e.setConsumed();
            }
        }

        @Override
        public void cursorEntered(CursorMotionEvent e, Spatial t, Spatial c) {
            dividerBg.setColor(DIVIDER_HOVER_COLOR);
        }

        @Override
        public void cursorExited(CursorMotionEvent e, Spatial t, Spatial c) {
            if (!dragging) {
                dividerBg.setColor(DIVIDER_COLOR);
            }
        }
    }

    /** True while a divider drag is in progress. Used by the gating logic
     *  in AppState so the camera doesn't get scroll/click events mid-drag. */
    public boolean isDragging() {
        return dragging;
    }
}
