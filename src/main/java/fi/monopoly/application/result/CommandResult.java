package fi.monopoly.application.result;

import fi.monopoly.domain.session.SessionState;

import java.util.List;

public record CommandResult(
        boolean accepted,
        SessionState sessionState,
        List<DomainEvent> events,
        List<CommandRejection> rejections,
        List<ViewHint> viewHints
) {
    public CommandResult {
        events = List.copyOf(events);
        rejections = List.copyOf(rejections);
        viewHints = List.copyOf(viewHints);
    }
}
