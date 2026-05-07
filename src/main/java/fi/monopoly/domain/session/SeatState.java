package fi.monopoly.domain.session;

public record SeatState(
        String seatId,
        int seatIndex,
        String playerId,
        SeatKind seatKind,
        ControlMode controlMode,
        String displayName,
        String controllerProfileId,
        String tokenColorHex
) {
}
