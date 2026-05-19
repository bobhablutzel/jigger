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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards that build-info.properties resource filtering ran and that
 * BuildInfo successfully reads the populated values. If the resources
 * config slips and build-info ships with raw {@code ${...}} placeholders,
 * these catch it.
 */
class BuildInfoTest {

    @Test
    void versionLooksPopulated() {
        String v = BuildInfo.instance().getVersion();
        assertNotEquals("unknown", v);
        // Should not contain unsubstituted placeholders.
        assertFalse(v.contains("${"),
                "build-info.properties version field wasn't filtered: " + v);
    }

    @Test
    void commitHashLooksReasonable() {
        String c = BuildInfo.instance().getCommit();
        // git short SHA is 7 hex chars. Also accept "unknown" only when
        // running in a place where git isn't available — but in our build
        // environment it should be there.
        assertNotEquals("unknown", c, "expected a git commit; got 'unknown'");
        assertTrue(c.matches("[0-9a-f]+"),
                "commit should be lowercase hex; got: " + c);
    }

    @Test
    void displayStringIsHumanReadable() {
        String s = BuildInfo.instance().getDisplayString();
        assertTrue(s.startsWith("Cadette "), s);
        assertTrue(s.contains("build "), s);
    }
}
