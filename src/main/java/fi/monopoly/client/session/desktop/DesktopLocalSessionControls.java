package fi.monopoly.client.session.desktop;

import fi.monopoly.application.session.persistence.LocalSessionPersistenceResult;

/**
 * Desktop-local session control port around embedded/local-only workflows.
 *
 * <p>Creating a fresh local session, saving/loading a local snapshot, and surfacing persistence
 * notices are useful desktop behaviors, but they are not part of the transport-neutral client
 * update/subscription API that a remote backend client should depend on.
 */
public interface DesktopLocalSessionControls {
    void startFreshSession();

    LocalSessionPersistenceResult saveLocalSession();

    LocalSessionPersistenceResult loadLocalSession();

    void showPersistenceNotice(String message);
}
