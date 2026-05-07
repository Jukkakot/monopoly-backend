package fi.monopoly.client.session.desktop;

import java.util.List;

/**
 * Desktop-local live render surface for embedded session rendering.
 *
 * <p>This intentionally lives under the desktop session package instead of the transport-neutral
 * client session package. A remote-ready client session should not expose a process-local draw
 * callback object as part of its core API.</p>
 */
public interface DesktopSessionRenderView {
    void draw();

    List<String> debugPerformanceLines(float fps);
}
