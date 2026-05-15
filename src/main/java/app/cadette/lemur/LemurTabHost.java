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
17 */

package app.cadette.lemur;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.style.ElementId;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Tabbed-content layout primitive — holds N named children, exactly one
 * visible at a time. The tab strip across the top has one Button per tab;
 * clicking a button activates that tab (attaches its content, detaches
 * the previous content's tree).
 *
 * <p>Like {@link LemurSplitter}, sizing is parent-driven via
 * {@link #setSize(float, float)} and per-active-child resizing is delegated
 * via a {@code reflowCallback}.
 *
 * <p>Not yet wired into the real layout — built this session for use in
 * the next, when we move panes between regions. The class is otherwise
 * self-contained; instantiate one with two-plus children, call setSize,
 * and call setActive(0 / 1 / ...) to switch.
 */
public class LemurTabHost extends Node {

    private static final float TAB_BAR_HEIGHT = 26f;

    private final BiConsumer<Spatial, float[]> reflowCallback;
    private final List<Tab> tabs = new ArrayList<>();
    private final Container tabBar;
    /** Sized to the full TabHost area; sits behind tabBar + active content
     *  so the slot fills with theme color rather than 3D-scene-through
     *  whenever no tab is active (e.g. during a drag-detach). */
    private final Container bgFill;

    private int activeIndex = -1;
    private float totalW = 0f;
    private float totalH = 0f;

    /** Fires after {@link #setActive} successfully changes the active tab.
     *  Used by LemurAppState to persist the active index across launches. */
    private Runnable activeChangeListener;

    public LemurTabHost(BiConsumer<Spatial, float[]> reflowCallback) {
        super("LemurTabHost");
        this.reflowCallback = reflowCallback;

        // bgFill attached FIRST so it sits behind everything in the GUI Z
        // order. Element-id "container" so it picks up the theme's panel
        // background — fills the area when no tab is active.
        bgFill = new Container(new ElementId("container"));
        // Belt-and-braces: explicitly copy the theme's container bg so we
        // don't depend on Lemur's auto-style cascade firing at construction.
        // In practice the auto-cascade seems to miss bgFill in some cases
        // (the panel rendered transparent — observed 2026-05-15).
        Object themedBg = GuiGlobals.getInstance().getStyles()
                .getSelector("container", "glass").get("background");
        if (themedBg instanceof QuadBackgroundComponent qbc) {
            bgFill.setBackground(new QuadBackgroundComponent(qbc.getColor().clone()));
        }
        attachChild(bgFill);

        // ElementId "tab-bar" so themes can style the tab strip distinctly
        // from generic containers — typically the same color as the panel
        // body (so the strip merges visually) but themable separately.
        tabBar = new Container(
                new SpringGridLayout(Axis.X, Axis.Y, FillMode.None, FillMode.Last),
                new ElementId("tab-bar"));
        attachChild(tabBar);
    }

    /** Add a tab. The first tab added becomes active automatically. */
    public void addTab(String label, Spatial content) {
        Button button = new Button(label);
        // Give the button its own QuadBackgroundComponent (cloned from the
        // styled one) so we can flip its color when the tab activates
        // without mutating the shared style-default instance — that would
        // tint every other button in the app.
        ColorRGBA baseBg;
        if (button.getBackground() instanceof QuadBackgroundComponent qbc) {
            baseBg = qbc.getColor().clone();
        } else {
            baseBg = new ColorRGBA(0.18f, 0.18f, 0.20f, 1f);
        }
        QuadBackgroundComponent ownBg = new QuadBackgroundComponent(baseBg.clone());
        button.setBackground(ownBg);
        // Prefer explicit `activeBackground` from the theme if provided —
        // necessary for high-contrast themes where lightening pure black
        // produces a barely-visible shift. Fall back to the derived
        // lighten for normal themes.
        Object themeActive = GuiGlobals.getInstance().getStyles()
                .getSelector("button", "glass").get("activeBackground");
        ColorRGBA activeBg = (themeActive instanceof ColorRGBA c)
                ? c.clone()
                : lighten(baseBg, 0.12f);
        // Optional `activeColor` swaps the text color when active — useful
        // for HC inverted treatments (e.g. white bg + black text active).
        ColorRGBA baseColor = button.getColor() != null ? button.getColor().clone() : null;
        Object themeActiveColor = GuiGlobals.getInstance().getStyles()
                .getSelector("button", "glass").get("activeColor");
        ColorRGBA activeColor = (themeActiveColor instanceof ColorRGBA cc) ? cc.clone() : null;

        // Hold onto the Tab reference rather than the index — indices
        // shift when tabs are removed, so a captured-at-add-time index
        // would point at the wrong tab after a removal.
        Tab tab = new Tab(label, content, button, ownBg,
                baseBg, activeBg, baseColor, activeColor);
        button.addClickCommands(b -> setActive(tabs.indexOf(tab)));
        tabBar.addChild(button);

        tabs.add(tab);
        if (activeIndex < 0) {
            setActive(0);
        } else {
            applyLayout(); // tab bar grew — re-layout
        }
    }

    /** Remove the tab whose content matches the given spatial. If the
     *  removed tab was active, switches to the next available tab (or
     *  goes to no-active if none remain). Called during drag-detach
     *  when a tabbed panel is being moved elsewhere. */
    public void removeTab(Spatial content) {
        int idx = indexOf(content);
        if (idx < 0) return;
        Tab tab = tabs.remove(idx);
        tabBar.removeChild(tab.button);
        if (tab.content.getParent() == this) {
            detachChild(tab.content);
        }
        if (idx == activeIndex) {
            int previousActive = activeIndex;
            activeIndex = -1;
            if (!tabs.isEmpty()) {
                setActive(Math.min(previousActive, tabs.size() - 1));
            }
        } else if (idx < activeIndex) {
            activeIndex--;
        }
        applyLayout();
    }

    public void setActive(int index) {
        if (index < 0 || index >= tabs.size() || index == activeIndex) {
            return;
        }
        // Detach previous content + revert its tab button to inactive color
        if (activeIndex >= 0) {
            Tab prev = tabs.get(activeIndex);
            if (prev.content.getParent() == this) {
                detachChild(prev.content);
            }
            prev.bg.setColor(prev.baseBg);
            if (prev.activeColor != null && prev.baseColor != null) {
                prev.button.setColor(prev.baseColor);
            }
        }
        activeIndex = index;
        Tab now = tabs.get(activeIndex);
        attachChild(now.content);
        now.bg.setColor(now.activeBg);
        if (now.activeColor != null) {
            now.button.setColor(now.activeColor);
        }
        applyLayout();
        if (activeChangeListener != null) {
            activeChangeListener.run();
        }
    }

    /** Register a callback fired after a successful tab switch. Null
     *  unregisters. Listener is null at construction so the initial
     *  setActive(0) during addTab doesn't trigger persistence before
     *  the host is even wired into the layout. */
    public void setActiveChangeListener(Runnable r) {
        this.activeChangeListener = r;
    }

    public int getActive() {
        return activeIndex;
    }

    public String getActiveLabel() {
        return activeIndex >= 0 ? tabs.get(activeIndex).label : null;
    }

    public int getTabCount() {
        return tabs.size();
    }

    public Button getTabButton(int index) {
        return tabs.get(index).button;
    }

    public Spatial getTabContent(int index) {
        return tabs.get(index).content;
    }

    /** Find which tab index hosts the given content spatial; -1 if not
     *  in this TabHost. Used during drag-detach to locate the source. */
    public int indexOf(Spatial content) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).content == content) return i;
        }
        return -1;
    }

    public void setSize(float w, float h) {
        this.totalW = w;
        this.totalH = h;
        applyLayout();
    }

    public float getCurrentWidth()  { return totalW; }
    public float getCurrentHeight() { return totalH; }

    private void applyLayout() {
        if (totalW <= 0 || totalH <= 0) {
            return;
        }
        // Background fill spans the whole TabHost — always present so the
        // panel doesn't show through to 3D when no tab is active.
        bgFill.setLocalTranslation(0, 0, 0);
        bgFill.setPreferredSize(new Vector3f(totalW, totalH, 0));

        if (tabs.isEmpty()) {
            return;
        }
        // Tab bar across the top, fills width.
        tabBar.setLocalTranslation(0, 0, 0.1f); // small Z so it draws on top of bgFill
        tabBar.setPreferredSize(new Vector3f(totalW, TAB_BAR_HEIGHT, 0));

        // Active content occupies the remaining space below the tab bar.
        if (activeIndex >= 0) {
            Spatial content = tabs.get(activeIndex).content;
            float contentH = totalH - TAB_BAR_HEIGHT;
            content.setLocalTranslation(0, -TAB_BAR_HEIGHT, 0.1f);
            reflowCallback.accept(content, new float[]{totalW, contentH});
        }
    }

    /** Move each channel a fraction of the way toward white. Used to derive
     *  the active-tab highlight color from each button's resting color. */
    private static ColorRGBA lighten(ColorRGBA c, float amount) {
        float r = c.r + (1f - c.r) * amount;
        float g = c.g + (1f - c.g) * amount;
        float b = c.b + (1f - c.b) * amount;
        return new ColorRGBA(r, g, b, c.a);
    }

    /** Per-tab state. Owns the QuadBackgroundComponent attached to the
     *  button so we can mutate its color on active/inactive transitions
     *  without bleeding to other widgets sharing the style instance. */
    private static final class Tab {
        final String label;
        final Spatial content;
        final Button button;
        final QuadBackgroundComponent bg;
        final ColorRGBA baseBg;
        final ColorRGBA activeBg;
        /** Resting text color, captured at construction so we can revert
         *  on tab-deactivate when the theme also overrides activeColor.
         *  Null when the theme doesn't override the text color. */
        final ColorRGBA baseColor;
        /** Text color when this tab is active. Null = leave text color
         *  alone on activate (most themes; only HC needs the swap). */
        final ColorRGBA activeColor;
        Tab(String label, Spatial content, Button button,
            QuadBackgroundComponent bg, ColorRGBA baseBg, ColorRGBA activeBg,
            ColorRGBA baseColor, ColorRGBA activeColor) {
            this.label = label;
            this.content = content;
            this.button = button;
            this.bg = bg;
            this.baseBg = baseBg;
            this.activeBg = activeBg;
            this.baseColor = baseColor;
            this.activeColor = activeColor;
        }
    }
}
