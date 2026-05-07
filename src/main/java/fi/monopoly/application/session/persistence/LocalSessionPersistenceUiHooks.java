package fi.monopoly.application.session.persistence;

/**
 * Minimal UI feedback surface needed by local save/load.
 *
 * <p>Persistence should not depend on concrete popup or sidebar implementations, so the
 * coordinator reports user-visible feedback through this tiny adapter instead.</p>
 */
public interface LocalSessionPersistenceUiHooks {
    void showPopup(String message);

    void showPersistenceNotice(String message);
}
