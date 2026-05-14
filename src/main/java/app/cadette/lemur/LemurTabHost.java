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
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

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
    private static final ColorRGBA TAB_BAR_BG = new ColorRGBA(0.18f, 0.18f, 0.20f, 1f);

    private final BiConsumer<Spatial, float[]> reflowCallback;
    private final List<Tab> tabs = new ArrayList<>();
    private final Container tabBar;

    private int activeIndex = -1;
    private float totalW = 0f;
    private float totalH = 0f;

    public LemurTabHost(BiConsumer<Spatial, float[]> reflowCallback) {
        super("LemurTabHost");
        this.reflowCallback = reflowCallback;

        tabBar = new Container(new SpringGridLayout(Axis.X, Axis.Y, FillMode.None, FillMode.Last));
        tabBar.setBackground(new QuadBackgroundComponent(TAB_BAR_BG));
        attachChild(tabBar);
    }

    /** Add a tab. The first tab added becomes active automatically. */
    public void addTab(String label, Spatial content) {
        Button button = new Button(label);
        // Hold onto the Tab reference rather than the index — indices
        // shift when tabs are removed, so a captured-at-add-time index
        // would point at the wrong tab after a removal.
        Tab tab = new Tab(label, content, button);
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
        // Detach previous content
        if (activeIndex >= 0) {
            Spatial prev = tabs.get(activeIndex).content;
            if (prev.getParent() == this) {
                detachChild(prev);
            }
        }
        activeIndex = index;
        attachChild(tabs.get(activeIndex).content);
        applyLayout();
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
        if (totalW <= 0 || totalH <= 0 || tabs.isEmpty()) {
            return;
        }
        // Tab bar across the top, fills width.
        tabBar.setLocalTranslation(0, 0, 0);
        tabBar.setPreferredSize(new Vector3f(totalW, TAB_BAR_HEIGHT, 0));

        // Active content occupies the remaining space below the tab bar.
        if (activeIndex >= 0) {
            Spatial content = tabs.get(activeIndex).content;
            float contentH = totalH - TAB_BAR_HEIGHT;
            content.setLocalTranslation(0, -TAB_BAR_HEIGHT, 0);
            reflowCallback.accept(content, new float[]{totalW, contentH});
        }
    }

    private record Tab(String label, Spatial content, Button button) {}
}
