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
