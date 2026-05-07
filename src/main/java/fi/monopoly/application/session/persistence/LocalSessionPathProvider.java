package fi.monopoly.application.session.persistence;

import java.nio.file.Path;

public interface LocalSessionPathProvider {
    Path resolvePath();
}
