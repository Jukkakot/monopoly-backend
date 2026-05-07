package fi.monopoly.client.session.desktop;

/**
 * Desktop-local frame/tick driver used by the Processing client runtime.
 *
 * <p>This is intentionally separate from the client update gateway. Advancing one embedded desktop
 * frame is a local hosting concern, not part of the long-term transport-neutral client session
 * API that should also work against a remote backend.</p>
 */
public interface DesktopSessionFrameDriver {
    void advanceFrame();
}
