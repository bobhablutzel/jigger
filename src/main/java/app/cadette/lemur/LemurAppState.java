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

import app.cadette.command.CommandExecutor;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.ListBox;
import com.simsilica.lemur.TextField;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.core.GuiControl;
import com.simsilica.lemur.core.VersionedList;
import com.simsilica.lemur.event.KeyAction;
import com.simsilica.lemur.event.KeyActionListener;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * First real UI panel for the Lemur port. Hosts a Command panel — text input
 * for command entry, scrolling output for results, command history with
 * cross-session persistence at {@code ~/.cadette/cmd_history}. Wires
 * directly to the supplied {@link CommandExecutor}, so commands typed here
 * mutate the same {@code SceneManager} that's driving the 3D viewport
 * underneath.
 *
 * <p>Carries forward the history semantics from the ImGui spike (commit
 * 45680d1 in {@code ImGuiAppState}): up/down arrow recall, dedup of
 * consecutive duplicates, 200-entry cap, save-after-every-command (the
 * file is small enough that rewriting is cheaper than partial-write
 * complexity).
 */
@RequiredArgsConstructor
public class LemurAppState extends BaseAppState {

    private static final Path HISTORY_FILE =
            Path.of(System.getProperty("user.home"), ".cadette", "cmd_history");
    private static final int HISTORY_MAX = 200;
    private static final float PANEL_W = 460;
    private static final float PANEL_PAD = 10;

    private final CommandExecutor executor;

    private final List<String> history = new ArrayList<>();
    /** -1 means "not recalling — typing fresh input"; ≥0 indexes into history. */
    private int historyPos = -1;
    /** What the user had typed when up-arrow first fired, restored on
     *  down-past-newest so partial input survives a recall round-trip. */
    private String preRecallBuffer = "";

    private Container commandPanel;
    private TextField commandInput;
    private VersionedList<String> outputModel;
    private ListBox<String> outputList;

    /**
     * True while the cursor is over any Lemur panel managed by this state.
     * Used by the camera controller as an input gate so scroll-zoom and
     * click-orbit don't fire when the user is interacting with the UI.
     * <p>{@link #isMouseOverUi()} (Lombok-generated) is the public read.
     */
    @Getter
    private boolean mouseOverUi = false;

    @Override
    protected void initialize(Application app) {
        loadHistory();

        SimpleApplication simpleApp = (SimpleApplication) app;
        AppSettings settings = simpleApp.getContext().getSettings();
        float winW = settings.getWidth();
        float winH = settings.getHeight();

        commandPanel = buildCommandPanel(winW, winH);
        simpleApp.getGuiNode().attachChild(commandPanel);

        appendOutput("CADette (Lemur UI). Type 'help' or start adding parts.");
    }

    @Override
    protected void cleanup(Application app) {
        if (commandPanel != null) {
            commandPanel.removeFromParent();
        }
    }

    @Override
    protected void onEnable() { /* no-op */ }

    @Override
    protected void onDisable() { /* no-op */ }

    private Container buildCommandPanel(float winW, float winH) {
        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.addChild(new Label("Command"));

        outputModel = new VersionedList<>();
        outputList = panel.addChild(new ListBox<>(outputModel));
        // Reserve a fixed slice; rest is for the title label + input field.
        outputList.setPreferredSize(new Vector3f(PANEL_W - 20, winH - 130, 0));

        commandInput = panel.addChild(new TextField(""));
        commandInput.setPreferredWidth(PANEL_W - 20);
        commandInput.setSingleLine(true);

        KeyActionListener submit = (src, key) -> runCurrentInput();
        commandInput.getActionMap().put(new KeyAction(KeyInput.KEY_RETURN), submit);
        commandInput.getActionMap().put(new KeyAction(KeyInput.KEY_NUMPADENTER), submit);

        commandInput.getActionMap().put(new KeyAction(KeyInput.KEY_UP),
                (src, key) -> recallHistory(-1));
        commandInput.getActionMap().put(new KeyAction(KeyInput.KEY_DOWN),
                (src, key) -> recallHistory(+1));

        // Right-anchor: panel's top-left at (winW - PANEL_W - pad, winH - pad).
        panel.setLocalTranslation(winW - PANEL_W - PANEL_PAD, winH - PANEL_PAD, 0);

        GuiGlobals.getInstance().requestFocus(commandInput);
        return panel;
    }

    @Override
    public void update(float tpf) {
        super.update(tpf);
        if (commandPanel == null) {
            return;
        }
        // Poll cursor-vs-panel hit test each frame. The earlier
        // MouseEventControl enter/exit approach didn't fire on the
        // parent Container in practice, so do the bounds test directly
        // — no Lemur event-dispatch assumptions, just geometry.
        InputManager input = getApplication().getInputManager();
        Vector2f cursor = input.getCursorPosition();
        Vector3f loc = commandPanel.getLocalTranslation();
        GuiControl gc = commandPanel.getControl(GuiControl.class);
        if (gc == null || cursor == null) {
            return;
        }
        Vector3f size = gc.getSize();
        // Lemur Containers anchor by top-left in jME3 GUI coords (Y up):
        // panel occupies [loc.x, loc.x + size.x] × [loc.y - size.y, loc.y].
        boolean over = cursor.x >= loc.x
                    && cursor.x <= loc.x + size.x
                    && cursor.y >= loc.y - size.y
                    && cursor.y <= loc.y;
        if (over != mouseOverUi) {
            mouseOverUi = over;
            System.err.println("[lemur-app] mouseOverUi=" + over);
        }
    }

    private void runCurrentInput() {
        String cmd = commandInput.getText();
        if (cmd == null || cmd.isBlank()) {
            return;
        }
        appendOutput("> " + cmd);
        pushHistory(cmd);
        historyPos = -1;
        try {
            String result = executor.execute(cmd);
            if (result != null && !result.isEmpty()) {
                for (String line : result.split("\n", -1)) {
                    appendOutput(line);
                }
            }
        } catch (Throwable t) {
            appendOutput("Error: " + t.getMessage());
        }
        commandInput.setText("");
    }

    private void appendOutput(String line) {
        outputModel.add(line);
        // Selecting the last row also scrolls the ListBox to it.
        if (outputList != null) {
            outputList.getSelectionModel().setSelection(outputModel.size() - 1);
        }
    }

    private void recallHistory(int direction) {
        if (history.isEmpty()) {
            return;
        }
        if (historyPos == -1) {
            // First recall press — save whatever the user had typed so we
            // can restore it on down-past-newest.
            preRecallBuffer = commandInput.getText() == null ? "" : commandInput.getText();
            if (direction < 0) {
                historyPos = history.size() - 1;
            } else {
                return; // down with nothing recalled = no-op
            }
        } else {
            historyPos += direction;
            if (historyPos < 0) {
                historyPos = 0;
            }
            if (historyPos >= history.size()) {
                historyPos = -1;
                commandInput.setText(preRecallBuffer);
                return;
            }
        }
        commandInput.setText(history.get(historyPos));
    }

    private void pushHistory(String cmd) {
        if (!history.isEmpty() && history.get(history.size() - 1).equals(cmd)) {
            return;
        }
        history.add(cmd);
        if (history.size() > HISTORY_MAX) {
            history.subList(0, history.size() - HISTORY_MAX).clear();
        }
        saveHistory();
    }

    private void loadHistory() {
        try {
            if (Files.exists(HISTORY_FILE)) {
                List<String> lines = Files.readAllLines(HISTORY_FILE);
                for (String line : lines) {
                    if (!line.isEmpty()) history.add(line);
                }
                if (history.size() > HISTORY_MAX) {
                    history.subList(0, history.size() - HISTORY_MAX).clear();
                }
            }
        } catch (Exception ignored) { /* fresh start */ }
    }

    private void saveHistory() {
        try {
            Files.createDirectories(HISTORY_FILE.getParent());
            Files.write(HISTORY_FILE, history);
        } catch (Exception ignored) { /* non-fatal */ }
    }
}
