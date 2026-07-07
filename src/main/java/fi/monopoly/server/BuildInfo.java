package fi.monopoly.server;

import java.io.InputStream;
import java.util.Properties;

/**
 * Build metadata baked into the jar by Maven resource filtering
 * (see build-info.properties). Lets the backend report its own version and
 * build timestamp so clients can display which backend build they are talking to.
 */
public final class BuildInfo {

    public static final String VERSION;
    /** ISO-8601 UTC timestamp of when the jar was built, e.g. "2026-07-07T06:55:12Z". */
    public static final String BUILD_TIME;

    static {
        String version = "dev";
        String buildTime = "";
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version", "").trim();
                String t = props.getProperty("buildTime", "").trim();
                // Guard against an unfiltered file (placeholders left literal) in dev/IDE runs.
                if (!v.isEmpty() && !v.startsWith("${")) version = v;
                if (!t.isEmpty() && !t.startsWith("${")) buildTime = t;
            }
        } catch (Exception ignored) {
            // Fall back to defaults — build info is informational only.
        }
        VERSION = version;
        BUILD_TIME = buildTime;
    }

    private BuildInfo() {}
}
