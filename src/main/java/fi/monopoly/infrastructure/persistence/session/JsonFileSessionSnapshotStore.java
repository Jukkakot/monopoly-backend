package fi.monopoly.infrastructure.persistence.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonFileSessionSnapshotStore implements SessionSnapshotStore {
    private final ObjectMapper objectMapper;

    public JsonFileSessionSnapshotStore() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public void write(Path path, SessionSnapshot snapshot) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            objectMapper.writeValue(path.toFile(), snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write session snapshot to " + path, e);
        }
    }

    @Override
    public SessionSnapshot read(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), SessionSnapshot.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read session snapshot from " + path, e);
        }
    }
}
