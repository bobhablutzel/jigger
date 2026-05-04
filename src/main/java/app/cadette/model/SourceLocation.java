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

package app.cadette.model;

/**
 * Where a model element came from in script — file marker plus line number.
 * Used to attribute validation diagnostics back to the source that
 * defined the element, and (eventually) to dedup messages across multiple
 * instantiations of the same template.
 *
 * <p>{@code source} matches the existing {@code currentLoadingSource}
 * conventions in {@link app.cadette.command.CommandExecutor}:
 * "classpath:&lt;path&gt;" for bundled templates, an absolute path for
 * filesystem scripts, "interactive" for the REPL, or the source string
 * carried on a {@link Template} when the element was defined inside a
 * template body. {@code line} is 1-based as ANTLR reports it; 0 means
 * "no specific line" (e.g. the {@link #INTERACTIVE} sentinel).
 */
public record SourceLocation(String source, int line) {

    /** Sentinel for elements created interactively with no specific line. */
    public static final SourceLocation INTERACTIVE = new SourceLocation("interactive", 0);

    @Override
    public String toString() {
        return line <= 0 ? source : source + ":" + line;
    }
}
