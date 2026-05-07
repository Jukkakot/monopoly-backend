package fi.monopoly.client.session;

import fi.monopoly.application.session.persistence.LocalSessionPersistenceResult;

/**
 * Client-side presentation sink for host-originated session feedback.
 *
 * <p>This lets the client session controller forward user-visible results without depending on a
 * specific popup/sidebar implementation.</p>
 */
public interface ClientSessionFeedbackSink {
    void showPersistenceResult(LocalSessionPersistenceResult result);
}
