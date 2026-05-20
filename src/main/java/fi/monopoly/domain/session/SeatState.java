package fi.monopoly.domain.session;

public record SeatState(
        String seatId,
        int seatIndex,
        String playerId,
        SeatKind seatKind,
        ControlMode controlMode,
        String displayName,
        String controllerProfileId,
        String tokenColorHex,
        boolean joined
) {
    /** Backward-compatible constructor — all existing sessions are fully joined. */
    public SeatState(String seatId, int seatIndex, String playerId,
                     SeatKind seatKind, ControlMode controlMode,
                     String displayName, String controllerProfileId, String tokenColorHex) {
        this(seatId, seatIndex, playerId, seatKind, controlMode,
             displayName, controllerProfileId, tokenColorHex, true);
    }
}
