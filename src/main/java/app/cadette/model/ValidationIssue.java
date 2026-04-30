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
 * A single problem found while validating a model element (currently a
 * {@link Joint}). Issues carry the offending element so a UI can attribute
 * the message back to a specific part / joint, and a {@link Severity} so
 * callers can split blocking errors from advisory warnings.
 *
 * <p>{@code message} is human-readable and self-contained — it should make
 * sense in a flat list view without any additional context.
 */
public record ValidationIssue(Joint joint, Severity severity, String message) {

    /** Validation severity. ERROR = definitely broken. WARNING = likely wrong but possibly intentional. */
    public enum Severity { ERROR, WARNING }
}
