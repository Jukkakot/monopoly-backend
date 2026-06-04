package fi.monopoly.domain.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Static helpers for appending {@link GameEventEntry} records to {@link SessionState}.
 *
 * <p>Designed for use via {@code import static fi.monopoly.domain.session.GameEventHelper.*}
 * inside domain gateway classes.</p>
 */
public final class GameEventHelper {

    private static final Logger logger = LoggerFactory.getLogger(GameEventHelper.class);

    static final int MAX_EVENT_LOG = 30;

    private GameEventHelper() {}

    /**
     * Returns a new state with the given events appended to the event log.
     * IDs are assigned sequentially from {@code s.nextEventId()}.
     * The log is trimmed to {@link #MAX_EVENT_LOG} entries if needed.
     */
    public static SessionState appendEvents(SessionState s, GameEventEntry... newEvents) {
        if (newEvents.length == 0) return s;
        List<GameEventEntry> log = new ArrayList<>(s.eventLog());
        long id = s.nextEventId();
        long now = System.currentTimeMillis();
        MDC.put("audit", "true");
        try {
            for (GameEventEntry e : newEvents) {
                GameEventEntry entry = new GameEventEntry(id++, now, e.type(), e.playerIds(), e.data());
                logger.debug("[event] id={} type={} players={} data={}", entry.id(), entry.type(), entry.playerIds(), entry.data());
                log.add(entry);
            }
        } finally {
            MDC.remove("audit");
        }
        if (log.size() > MAX_EVENT_LOG) {
            log = new ArrayList<>(log.subList(log.size() - MAX_EVENT_LOG, log.size()));
        }
        return s.toBuilder()
                .eventLog(Collections.unmodifiableList(log))
                .nextEventId(id)
                .build();
    }

    /** Create an event template (id/timestamp assigned later by {@link #appendEvents}). */
    public static GameEventEntry ev(String type, String playerId, Map<String, String> data) {
        return new GameEventEntry(0, 0, type, List.of(playerId), data);
    }

    public static GameEventEntry ev(String type, String playerId) {
        return ev(type, playerId, Map.of());
    }

    public static GameEventEntry ev(String type, List<String> playerIds, Map<String, String> data) {
        return new GameEventEntry(0, 0, type, playerIds, data);
    }
}
