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
        boolean joined,
        BotDifficulty botDifficulty
) {
    /** Backward-compatible constructor — all existing sessions are fully joined, no difficulty stored. */
    public SeatState(String seatId, int seatIndex, String playerId,
                     SeatKind seatKind, ControlMode controlMode,
                     String displayName, String controllerProfileId, String tokenColorHex) {
        this(seatId, seatIndex, playerId, seatKind, controlMode,
             displayName, controllerProfileId, tokenColorHex, true, null);
    }

    /** Backward-compatible constructor — explicit joined flag, no difficulty stored. */
    public SeatState(String seatId, int seatIndex, String playerId,
                     SeatKind seatKind, ControlMode controlMode,
                     String displayName, String controllerProfileId, String tokenColorHex,
                     boolean joined) {
        this(seatId, seatIndex, playerId, seatKind, controlMode,
             displayName, controllerProfileId, tokenColorHex, joined, null);
    }
}
