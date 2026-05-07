package fi.monopoly.application.result;

public record DomainEvent(
        String eventType,
        String actorPlayerId,
        String summary
) {
}
