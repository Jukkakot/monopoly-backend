package fi.monopoly.application.session.persistence;

import java.nio.file.Path;

public final class SystemPropertyLocalSessionPathProvider implements LocalSessionPathProvider {
    private static final String LOCAL_SAVE_PATH_PROPERTY = "monopoly.localSavePath";
    private static final Path DEFAULT_PATH = Path.of("saves", "local-session.json");

    @Override
    public Path resolvePath() {
        String configured = System.getProperty(LOCAL_SAVE_PATH_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_PATH;
        }
        return Path.of(configured);
    }
}
