package fi.monopoly.application.session.persistence;

/**
 * User-facing result payload for a local persistence operation.
 *
 * <p>The host owns the persistence operation and returns the resulting feedback as plain data. The
 * client can then decide how to present that feedback without persistence orchestration depending
 * on concrete popup or sidebar implementations.</p>
 */
public record LocalSessionPersistenceResult(
        boolean success,
        String popupMessage,
        String persistenceNotice
) {
}
