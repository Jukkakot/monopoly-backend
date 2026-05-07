package fi.monopoly.application.result;

public record CommandRejection(
        String code,
        String message
) {
}
