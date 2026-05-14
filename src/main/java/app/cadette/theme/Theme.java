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

package app.cadette.theme;

import java.util.Map;

/**
 * A loaded theme — the in-memory representation of a {@code .cdt} YAML file.
 * After loading, {@link ThemeRegistry#applyTheme} translates each entry in
 * {@link #elements} into a Lemur {@code Styles.getSelector(element-id,
 * style-name).set(attribute, value)} call.
 *
 * <p>Themes can chain via {@link #extendsFrom} (one parent) — the
 * registry merges parent's element maps under the child's overrides,
 * so a high-contrast theme can {@code extends: dark} and override only
 * the colors that differ.
 *
 * @param name        Theme identifier, matches the YAML {@code name:} field
 *                    and the {@code .cdt} filename stem.
 * @param description Optional one-line description for {@code show themes}.
 * @param extendsFrom Optional parent theme name; null for root themes.
 * @param elements    {@code element-id → {attribute-name → value}}.
 *                    Values come straight from YAML (Strings, Numbers,
 *                    Lists); the registry converts them to Lemur-typed
 *                    objects (ColorRGBA, BitmapFont, etc.) at apply time.
 */
public record Theme(String name,
                    String description,
                    String extendsFrom,
                    Map<String, Map<String, Object>> elements) {
}
