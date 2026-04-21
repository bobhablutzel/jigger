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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the `define ... end define` flow.
 * Covers the multi-line recording state machine, param declarations with
 * aliases, and recursive instantiation through the standard executor path.
 */
class DefineTemplateTest extends HeadlessTestBase {

    @BeforeEach
    void clean() {
        resetScene();
    }

    @Test
    void defineAndInstantiate() {
        exec("define simple_box params width, height, depth");
        exec("create part \"panel\" size $width, $height at 0, 0, 0");
        String end = exec("end define");
        assertTrue(end.contains("simple_box"), "finish should mention template name: " + end);

        Template template = TemplateRegistry.instance().get("simple_box");
        assertNotNull(template, "template should be registered");
        assertEquals(3, template.getParamNames().size());
        assertEquals(1, template.getBodyLines().size());

        String result = exec("create simple_box inst w 500 h 600 d 18");
        assertTrue(result.contains("simple_box"), "should confirm creation: " + result);
        assertNotNull(sceneManager.getObjectRecord("inst/panel"),
                "instantiated part should exist with prefix");
    }

    @Test
    void defineWithParamAliases() {
        exec("define aliased params width(w), height(h)");
        exec("create part \"p\" size $width, $height at 0, 0, 0");
        exec("end define");

        // Invoke via both short aliases
        String result = exec("create aliased a1 w 100 h 200");
        assertTrue(result.contains("aliased"), "short aliases should resolve: " + result);
        assertNotNull(sceneManager.getObjectRecord("a1/p"));
    }

    @Test
    void commentsInsideDefineAreRecordedButSkippedAtInstantiation() {
        exec("define comm_tmpl params width");
        exec("# a comment inside the body");
        exec("create part \"p\" size $width, 1 at 0, 0, 0");
        exec("end define");

        Template t = TemplateRegistry.instance().get("comm_tmpl");
        assertNotNull(t);
        // Comment line is stored verbatim in the body but produces no command
        // at instantiation time because LINE_COMMENT is skipped by the lexer.
        assertEquals(2, t.getBodyLines().size(), "body includes the comment line");

        exec("create comm_tmpl x w 10");
        assertNotNull(sceneManager.getObjectRecord("x/p"),
                "part created despite comment in body");
    }

    @Test
    void defineInstantiationReplacesPartNamesWithPrefix() {
        exec("define prefix_test params width");
        exec("create part \"inner\" size $width, 1 at 0, 0, 0");
        exec("end define");

        exec("create prefix_test out w 50");
        assertNotNull(sceneManager.getObjectRecord("out/inner"), "prefixed part should exist");
        assertNull(sceneManager.getObjectRecord("inner"), "unprefixed part should NOT exist");
    }
}
