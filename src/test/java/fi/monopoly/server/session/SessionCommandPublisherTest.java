package fi.monopoly.server.session;

import fi.monopoly.application.command.SessionCommand;
import fi.monopoly.application.result.CommandResult;
import fi.monopoly.client.session.ClientSessionListener;
import fi.monopoly.client.session.ClientSessionSnapshot;
import fi.monopoly.client.session.SessionCommandPort;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.domain.session.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionCommandPublisherTest {

    private static final SessionCommand DUMMY_COMMAND = () -> "test-session";

    private StubCommandPort stub;
    private SessionCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        stub = new StubCommandPort();
        publisher = new SessionCommandPublisher(stub);
    }

    @Test
    void acceptedCommandPublishesSnapshotToListeners() {
        List<ClientSessionSnapshot> received = new ArrayList<>();
        publisher.addListener(received::add);

        stub.nextAccepted = true;
        publisher.handle(DUMMY_COMMAND);

        assertEquals(1, received.size());
        assertEquals("test-session", received.getFirst().sessionId());
    }

    @Test
    void rejectedCommandDoesNotPublish() {
        List<ClientSessionSnapshot> received = new ArrayList<>();
        publisher.addListener(received::add);

        stub.nextAccepted = false;
        publisher.handle(DUMMY_COMMAND);

        assertTrue(received.isEmpty());
    }

    @Test
    void removedListenerReceivesNoMoreSnapshots() {
        List<ClientSessionSnapshot> received = new ArrayList<>();
        ClientSessionListener listener = received::add;
        publisher.addListener(listener);
        publisher.removeListener(listener);

        stub.nextAccepted = true;
        publisher.handle(DUMMY_COMMAND);

        assertTrue(received.isEmpty());
    }

    @Test
    void multipleListenersAllReceiveSnapshot() {
        List<ClientSessionSnapshot> first = new ArrayList<>();
        List<ClientSessionSnapshot> second = new ArrayList<>();
        publisher.addListener(first::add);
        publisher.addListener(second::add);

        stub.nextAccepted = true;
        publisher.handle(DUMMY_COMMAND);

        assertEquals(1, first.size());
        assertEquals(1, second.size());
    }

    @Test
    void currentSnapshotReflectsDelegateState() {
        ClientSessionSnapshot snapshot = publisher.currentSnapshot();
        assertEquals("test-session", snapshot.sessionId());
        assertTrue(snapshot.viewAvailable());
    }

    @Test
    void faultyListenerDoesNotPreventOtherListeners() {
        List<ClientSessionSnapshot> received = new ArrayList<>();
        publisher.addListener(s -> { throw new RuntimeException("boom"); });
        publisher.addListener(received::add);

        stub.nextAccepted = true;
        publisher.handle(DUMMY_COMMAND);

        assertEquals(1, received.size());
    }

    // -------------------------------------------------------------------------
    // handleIdempotent
    // -------------------------------------------------------------------------

    @Test
    void handleIdempotent_sameCommandId_returnsCachedResultWithoutReexecuting() {
        AtomicInteger calls = new AtomicInteger(0);
        SessionCommandPublisher p = new SessionCommandPublisher(new StubCommandPort() {
            @Override
            public CommandResult handle(SessionCommand command) {
                calls.incrementAndGet();
                return super.handle(command);
            }
        });

        CommandResult first  = p.handleIdempotent(DUMMY_COMMAND, "cmd-1");
        CommandResult second = p.handleIdempotent(DUMMY_COMMAND, "cmd-1");

        assertTrue(first.accepted());
        assertSame(first, second, "second call must return same object from cache");
        assertEquals(1, calls.get(), "delegate must be called exactly once");
    }

    @Test
    void handleIdempotent_differentCommandIds_executesBoth() {
        AtomicInteger calls = new AtomicInteger(0);
        SessionCommandPublisher p = new SessionCommandPublisher(new StubCommandPort() {
            @Override
            public CommandResult handle(SessionCommand command) {
                calls.incrementAndGet();
                return super.handle(command);
            }
        });

        p.handleIdempotent(DUMMY_COMMAND, "cmd-a");
        p.handleIdempotent(DUMMY_COMMAND, "cmd-b");

        assertEquals(2, calls.get());
    }

    @Test
    void handleIdempotent_nullCommandId_alwaysExecutes() {
        AtomicInteger calls = new AtomicInteger(0);
        SessionCommandPublisher p = new SessionCommandPublisher(new StubCommandPort() {
            @Override
            public CommandResult handle(SessionCommand command) {
                calls.incrementAndGet();
                return super.handle(command);
            }
        });

        p.handleIdempotent(DUMMY_COMMAND, null);
        p.handleIdempotent(DUMMY_COMMAND, null);
        p.handleIdempotent(DUMMY_COMMAND, null);

        assertEquals(3, calls.get(), "null commandId must never use cache");
    }

    @Test
    void handleIdempotent_listenerFiredOnlyOnceForCachedCommand() {
        AtomicInteger snapshots = new AtomicInteger(0);
        publisher.addListener(snap -> snapshots.incrementAndGet());
        stub.nextAccepted = true;

        publisher.handleIdempotent(DUMMY_COMMAND, "cmd-once");
        publisher.handleIdempotent(DUMMY_COMMAND, "cmd-once"); // cache hit — no publish

        assertEquals(1, snapshots.get(), "snapshot listener should fire only once for a cached command");
    }

    // --- stub ---

    private static class StubCommandPort implements SessionCommandPort {
        boolean nextAccepted = true;

        @Override
        public CommandResult handle(SessionCommand command) {
            SessionState state = minimalState();
            return new CommandResult(nextAccepted, state, List.of(), List.of(), List.of());
        }

        @Override
        public SessionState currentState() {
            return minimalState();
        }

        private static SessionState minimalState() {
            return SessionState.builder()
                    .sessionId("test-session")
                    .version(1L)
                    .status(SessionStatus.IN_PROGRESS)
                    .seats(List.of())
                    .players(List.of())
                    .properties(List.of())
                    .build();
        }
    }
}
