package fi.monopoly.types;

public enum DiceState {
    NOREROLL, DOUBLES, JAIL, DEBUG_REROLL;

    public static DiceState valueOf(int pairCount) {
        if (pairCount == 0) {
            return NOREROLL;
        } else if (pairCount < 3) {
            return DOUBLES;
        } else {
            return JAIL;
        }
    }
}
