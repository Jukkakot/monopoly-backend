package fi.monopoly.client.session.desktop;

/**
 * Client-owned desktop session runtime port used by the Processing app shell.
 *
 * <p>This keeps the app talking to one stable desktop-session runtime abstraction instead of a set
 * of embedded-host-specific forwarding methods. Embedded local mode can still back this runtime
 * with an in-process host, but the app itself only depends on one client-side session control and
 * state surface.</p>
 */
public interface DesktopClientSessionRuntime {
    void startFreshSession();

    void advanceFrame();

    void saveLocalSession();

    void loadLocalSession();
}
