package fi.monopoly.client.session.desktop;

import fi.monopoly.client.session.ClientSessionFeedbackSink;
import fi.monopoly.client.session.ClientSessionUpdates;
import lombok.NonNull;

/**
 * Thin desktop-client orchestrator around the client session seam.
 *
 * <p>{@code MonopolyApp} should not need to know how local save/load or frame advancement map to
 * the underlying session implementation. This controller keeps that client-side orchestration in
 * one place so the app shell stays closer to a pure Processing host.</p>
 */
public final class DesktopClientSessionController implements DesktopClientSessionRuntime {
    private final ClientSessionUpdates sessionUpdates;
    private final DesktopSessionFrameDriver frameDriver;
    private final DesktopSessionViewPort viewPort;
    private final DesktopClientSessionModel sessionModel;
    private final DesktopClientRenderModel renderModel;
    private final DesktopLocalSessionControls localSessionControls;
    private final ClientSessionFeedbackSink feedbackSink;

    public DesktopClientSessionController(
            @NonNull ClientSessionUpdates sessionUpdates,
            @NonNull DesktopSessionFrameDriver frameDriver,
            @NonNull DesktopSessionViewPort viewPort,
            @NonNull DesktopClientSessionModel sessionModel,
            @NonNull DesktopClientRenderModel renderModel,
            @NonNull DesktopLocalSessionControls localSessionControls,
            @NonNull ClientSessionFeedbackSink feedbackSink
    ) {
        this.sessionUpdates = sessionUpdates;
        this.frameDriver = frameDriver;
        this.viewPort = viewPort;
        this.sessionModel = sessionModel;
        this.renderModel = renderModel;
        this.localSessionControls = localSessionControls;
        this.feedbackSink = feedbackSink;
        this.sessionUpdates.addListener(this.sessionModel);
        refreshRenderModel();
    }

    @Override
    public void startFreshSession() {
        localSessionControls.startFreshSession();
        refreshRenderModel();
    }

    @Override
    public void advanceFrame() {
        frameDriver.advanceFrame();
        refreshRenderModel();
    }

    @Override
    public void saveLocalSession() {
        feedbackSink.showPersistenceResult(localSessionControls.saveLocalSession());
    }

    @Override
    public void loadLocalSession() {
        feedbackSink.showPersistenceResult(localSessionControls.loadLocalSession());
        refreshRenderModel();
    }

    private void refreshRenderModel() {
        renderModel.setCurrentView(viewPort.currentView());
    }
}
