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

import app.cadette.model.Template;
import app.cadette.model.TemplateRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TemplateRegistry name validation.
 * Template names must be Java-identifier-like:
 *   start with a letter, followed by letters, digits, or underscores.
 */
class TemplateRegistryTest {

    private static Template template(String name) {
        return new Template(name, List.of("width"), Map.of(),
                List.of("create part \"x\" size $width, 1 at 0, 0, 0"), false);
    }

    @Test
    void acceptsValidName() {
        assertDoesNotThrow(() -> TemplateRegistry.instance().register(template("valid_name42")));
    }

    @Test
    void rejectsHyphenatedName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TemplateRegistry.instance().register(template("has-hyphen")));
        assertTrue(ex.getMessage().contains("has-hyphen"), ex.getMessage());
    }

    @Test
    void rejectsLeadingDigit() {
        assertThrows(IllegalArgumentException.class,
                () -> TemplateRegistry.instance().register(template("1cabinet")));
    }

    @Test
    void rejectsLeadingUnderscore() {
        // Spec: must start with a letter (stricter than Java).
        assertThrows(IllegalArgumentException.class,
                () -> TemplateRegistry.instance().register(template("_private")));
    }

    @Test
    void rejectsEmptyName() {
        assertThrows(IllegalArgumentException.class,
                () -> TemplateRegistry.instance().register(template("")));
    }

    @Test
    void rejectsSpaceInName() {
        assertThrows(IllegalArgumentException.class,
                () -> TemplateRegistry.instance().register(template("has space")));
    }
}
