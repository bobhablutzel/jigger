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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build-time metadata: version, git commit, build timestamp.
 *
 * <p>Read once at first access from {@code /build-info.properties} on
 * the classpath, which is filled in by the maven build via
 * {@code git-commit-id-maven-plugin} + resource filtering. Values are
 * immutable for the JVM's lifetime.
 *
 * <p>Designed for low-traffic surfaces: the {@code show about} command,
 * crash reports, the eventual About dialog. Don't put this in a hot path.
 */
public final class BuildInfo {

    private static final String RESOURCE = "/build-info.properties";
    private static final BuildInfo INSTANCE = load();

    public static BuildInfo instance() {
        return INSTANCE;
    }

    private final String version;
    private final String commit;
    private final String commitFull;
    private final String commitTime;
    private final String buildTime;
    private final String branch;

    private BuildInfo(String version, String commit, String commitFull,
                      String commitTime, String buildTime, String branch) {
        this.version = version;
        this.commit = commit;
        this.commitFull = commitFull;
        this.commitTime = commitTime;
        this.buildTime = buildTime;
        this.branch = branch;
    }

    public String getVersion()    { return version; }
    public String getCommit()     { return commit; }
    public String getCommitFull() { return commitFull; }
    public String getCommitTime() { return commitTime; }
    public String getBuildTime()  { return buildTime; }
    public String getBranch()     { return branch; }

    /** One-line summary suitable for log banners + the About dialog
     *  header. Includes the full build timestamp because we may cycle
     *  through multiple builds per day during F&F iteration. */
    public String getDisplayString() {
        return String.format("Cadette %s (build %s · %s)",
                version, commit,
                buildTime == null ? "?" : buildTime);
    }

    private static BuildInfo load() {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {
            // Resource missing or malformed — fall through to "unknown".
        }
        return new BuildInfo(
                p.getProperty("cadette.version", "unknown"),
                p.getProperty("cadette.build.commit", "unknown"),
                p.getProperty("cadette.build.commit.full", "unknown"),
                p.getProperty("cadette.build.commit.time", "unknown"),
                p.getProperty("cadette.build.time", "unknown"),
                p.getProperty("cadette.build.branch", "unknown"));
    }
}
