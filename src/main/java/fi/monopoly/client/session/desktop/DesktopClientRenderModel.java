package fi.monopoly.client.session.desktop;

/**
 * Client-owned desktop render model for the current embedded live view.
 *
 * <p>The embedded host still produces a process-local render view, but the desktop app should not
 * poll that view directly from the runtime port. This model gives the client its own render-state
 * holder so the app shell can read one client-owned projection surface for both session metadata
 * and the current render target.</p>
 */
public final class DesktopClientRenderModel {
    private DesktopSessionRenderView currentView;

    public DesktopSessionRenderView currentView() {
        return currentView;
    }

    public void setCurrentView(DesktopSessionRenderView currentView) {
        this.currentView = currentView;
    }
}
