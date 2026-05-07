package fi.monopoly.types;

import java.util.Arrays;
import java.util.List;

public enum CardType {
    MONEY, MOVE, MOVE_NEAREST, MOVE_BACK_3, GO_JAIL, OUT_OF_JAIL, REPAIR_PROPERTIES, ALL_PLAYERS_MONEY;

    public static List<CardType> getTypes() {
        return Arrays.asList(values());
    }
}
