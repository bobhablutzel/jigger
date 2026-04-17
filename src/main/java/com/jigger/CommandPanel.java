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

import com.jigger.command.CommandExecutor;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Swing panel with a scrollable output area and a command input field.
 * Escape toggles focus between this panel and the 3D viewport.
 */
public class CommandPanel extends JPanel {

    private final JTextArea outputArea;
    private final JTextField inputField;
    private final CommandExecutor executor;
    private final JLabel prompt;
    private static final int MAX_HISTORY = 500;
    private static final Path HISTORY_FILE =
            Path.of(System.getProperty("user.home"), ".jigger", "history");

    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private boolean commandActive = true;

    public CommandPanel(CommandExecutor executor) {
        this.executor = executor;
        setLayout(new BorderLayout());

        // Output area
        outputArea = new JTextArea();
        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        outputArea.setBackground(new Color(30, 30, 35));
        outputArea.setForeground(new Color(200, 200, 200));
        outputArea.setCaretColor(Color.WHITE);
        DefaultCaret caret = (DefaultCaret) outputArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scrollPane = new JScrollPane(outputArea);
        scrollPane.setPreferredSize(new Dimension(1280, 220));

        // Input field
        inputField = new JTextField();
        inputField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        inputField.setBackground(new Color(40, 40, 48));
        inputField.setForeground(Color.WHITE);
        inputField.setCaretColor(Color.WHITE);

        prompt = new JLabel(" > ");
        prompt.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        prompt.setForeground(new Color(100, 200, 100));
        prompt.setOpaque(true);
        prompt.setBackground(new Color(40, 40, 48));

        JPanel inputRow = new JPanel(new BorderLayout());
        inputRow.add(prompt, BorderLayout.WEST);
        inputRow.add(inputField, BorderLayout.CENTER);

        add(scrollPane, BorderLayout.CENTER);
        add(inputRow, BorderLayout.SOUTH);

        // Submit on Enter
        inputField.addActionListener(this::onSubmit);

        // Command history navigation (Up/Down)
        inputField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "historyUp");
        inputField.getActionMap().put("historyUp", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { navigateHistory(-1); }
        });
        inputField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "historyDown");
        inputField.getActionMap().put("historyDown", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { navigateHistory(1); }
        });

        // Escape toggles focus away from command panel
        inputField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "toggleFocus");
        inputField.getActionMap().put("toggleFocus", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { deactivate(); }
        });

        // Ctrl+Z = undo, Ctrl+Shift+Z = redo (when command panel is focused)
        inputField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), "undo");
        inputField.getActionMap().put("undo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                appendOutput(executor.undo() + "\n");
            }
        });
        inputField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                        KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), "redo");
        inputField.getActionMap().put("redo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                appendOutput(executor.redo() + "\n");
            }
        });

        loadHistory();
        appendOutput("Jigger 3D Command Shell — type 'help' for available commands.\n");
        appendOutput("Press Escape to toggle between viewport and command line.\n");
    }

    /** Called when the viewport wants to hand focus back to the command panel. */
    public void activate() {
        commandActive = true;
        inputField.setEnabled(true);
        inputField.requestFocusInWindow();
        prompt.setForeground(new Color(100, 200, 100));
    }

    /** Release focus to the viewport. */
    private void deactivate() {
        commandActive = false;
        inputField.setEnabled(false);
        prompt.setForeground(new Color(120, 120, 120));
        // Transfer focus to the canvas (parent will handle this)
        if (onFocusToggle != null) {
            onFocusToggle.run();
        }
    }

    public boolean isCommandActive() {
        return commandActive;
    }

    private Runnable onFocusToggle;

    public void setOnFocusToggle(Runnable onFocusToggle) {
        this.onFocusToggle = onFocusToggle;
    }

    private void onSubmit(ActionEvent e) {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        // Avoid consecutive duplicates
        if (history.isEmpty() || !history.getLast().equals(text)) {
            history.add(text);
            saveHistory();
        }
        historyIndex = history.size();

        appendOutput("> " + text + "\n");
        inputField.setText("");

        String result = executor.execute(text);
        appendOutput(result + "\n");
    }

    private void navigateHistory(int direction) {
        int newIndex = historyIndex + direction;
        if (newIndex < 0 || newIndex > history.size()) return;
        historyIndex = newIndex;
        if (historyIndex == history.size()) {
            inputField.setText("");
        } else {
            inputField.setText(history.get(historyIndex));
        }
    }

    public void appendOutput(String text) {
        outputArea.append(text);
    }

    private void loadHistory() {
        if (Files.exists(HISTORY_FILE)) {
            try {
                List<String> lines = Files.readAllLines(HISTORY_FILE);
                // Keep only the last MAX_HISTORY entries
                int start = Math.max(0, lines.size() - MAX_HISTORY);
                history.addAll(lines.subList(start, lines.size()));
                historyIndex = history.size();
            } catch (IOException ignored) {
            }
        }
    }

    private void saveHistory() {
        try {
            Files.createDirectories(HISTORY_FILE.getParent());
            // Trim to MAX_HISTORY before saving
            List<String> toSave = history.size() > MAX_HISTORY
                    ? history.subList(history.size() - MAX_HISTORY, history.size())
                    : history;
            Files.write(HISTORY_FILE, toSave);
        } catch (IOException ignored) {
        }
    }
}
