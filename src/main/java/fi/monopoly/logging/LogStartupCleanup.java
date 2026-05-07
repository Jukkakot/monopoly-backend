package fi.monopoly.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

public final class LogStartupCleanup {

    private LogStartupCleanup() {
    }

    public static void deleteTodayAppLogs() {
        String today = LocalDate.now().toString();
        deleteTodayLogsInDirectory(Path.of(System.getProperty("user.dir"), "logs", "app"), today, "monopoly");
        deleteTodayLogsInDirectory(Path.of(System.getProperty("user.dir"), "logs", "test"), today, "monopoly-test");
    }

    private static void deleteTodayLogsInDirectory(Path logDir, String today, String baseFileName) {
        deleteIfExists(logDir.resolve(baseFileName + "." + today + ".log"));
        deleteIfExists(logDir.resolve("ERROR-" + baseFileName + "." + today + ".log"));
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("Failed to delete startup log file: " + path + " (" + e.getMessage() + ")");
        }
    }
}
