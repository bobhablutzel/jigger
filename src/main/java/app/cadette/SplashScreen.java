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

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.FloatSize;
import com.github.weisj.jsvg.view.ViewBox;

import javax.swing.*;
import java.awt.*;
import java.net.URL;

/**
 * Borderless splash window shown while the main frame initializes.
 * Renders {@code cadette-logo-and-text.svg} on a white background.
 */
public final class SplashScreen {

    private static final String SVG_PATH = "/cadette-logo-and-text.svg";
    private static final int WINDOW_WIDTH = 500;
    private static final int WINDOW_HEIGHT = 260;
    private static final int PADDING = 30;

    private SplashScreen() {}

    public static JWindow show() {
        SVGDocument document = loadDocument();

        JWindow window = new JWindow();
        window.setBackground(Color.WHITE);
        window.setContentPane(new SvgPanel(document));
        window.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        window.setLocationRelativeTo(null);
        window.setAlwaysOnTop(true);
        window.setVisible(true);
        return window;
    }

    private static SVGDocument loadDocument() {
        URL url = SplashScreen.class.getResource(SVG_PATH);
        if (url == null) {
            throw new IllegalStateException("Splash asset missing on classpath: " + SVG_PATH);
        }
        SVGDocument doc = new SVGLoader().load(url);
        if (doc == null) {
            throw new IllegalStateException("Failed to parse splash SVG: " + SVG_PATH);
        }
        return doc;
    }

    private static final class SvgPanel extends JPanel {
        private final SVGDocument document;

        SvgPanel(SVGDocument document) {
            this.document = document;
            setOpaque(true);
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(220, 220, 220)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                FloatSize size = document.size();
                float availW = getWidth() - 2f * PADDING;
                float availH = getHeight() - 2f * PADDING;
                float scale = Math.min(availW / size.width, availH / size.height);
                float drawW = size.width * scale;
                float drawH = size.height * scale;
                float offX = (getWidth() - drawW) / 2f;
                float offY = (getHeight() - drawH) / 2f;
                document.render(this, g2, new ViewBox(offX, offY, drawW, drawH));
            } finally {
                g2.dispose();
            }
        }
    }
}
