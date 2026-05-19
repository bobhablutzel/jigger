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

class ShowAboutCommandTest extends HeadlessTestBase {

    @Test
    void showAboutPrintsVersionAndCommit() {
        String out = exec("show about");
        assertTrue(out.contains("Cadette"), out);
        assertTrue(out.contains(BuildInfo.instance().getVersion()), out);
        assertTrue(out.contains(BuildInfo.instance().getCommit()),
                "expected commit hash in output: " + out);
        assertFalse(out.toLowerCase().contains("no viable alternative"), out);
    }
}
