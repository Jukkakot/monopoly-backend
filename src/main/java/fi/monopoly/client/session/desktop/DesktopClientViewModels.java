package fi.monopoly.client.session.desktop;

/**
 * Grouping for client-owned desktop session projections.
 *
 * <p>Keeping the snapshot and render models together makes it easier for the app shell and future
 * client transport adapters to depend on one client-side projection bundle instead of several
 * unrelated runtime callbacks.</p>
 */
public record DesktopClientViewModels(
        DesktopClientSessionModel sessionModel,
        DesktopClientRenderModel renderModel
) {
}
