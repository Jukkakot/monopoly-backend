package fi.monopoly.infrastructure.persistence.session;

import java.nio.file.Path;

public interface SessionSnapshotStore {
    boolean exists(Path path);

    void write(Path path, SessionSnapshot snapshot);

    SessionSnapshot read(Path path);
}
