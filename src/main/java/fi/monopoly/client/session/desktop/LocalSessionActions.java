package fi.monopoly.client.session.desktop;

/**
 * Client-side desktop session control callbacks used by the currently hosted local game.
 *
 * <p>The hosted desktop game still needs a narrow way to trigger save/load flows through the
 * client shell, but these actions are client-session controls rather than presentation-session
 * bridge state. Keeping them under the client session package makes that ownership explicit.</p>
 */
public record LocalSessionActions(
        Runnable saveSession,
        Runnable loadSession
) {
    private static final Runnable NO_OP = () -> { };
    public static final LocalSessionActions NO_OP_ACTIONS = new LocalSessionActions(NO_OP, NO_OP);

    public LocalSessionActions {
        saveSession = saveSession != null ? saveSession : NO_OP;
        loadSession = loadSession != null ? loadSession : NO_OP;
    }
}
