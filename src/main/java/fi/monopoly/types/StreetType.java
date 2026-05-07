package fi.monopoly.types;

public enum StreetType {

    BROWN(PlaceType.STREET),
    LIGHT_BLUE(PlaceType.STREET),
    PURPLE(PlaceType.STREET),
    ORANGE(PlaceType.STREET),
    RED(PlaceType.STREET),
    YELLOW(PlaceType.STREET),
    GREEN(PlaceType.STREET),
    DARK_BLUE(PlaceType.STREET),
    RAILROAD(PlaceType.RAILROAD),
    UTILITY(PlaceType.UTILITY),
    CHANCE(PlaceType.PICK_CARD),
    COMMUNITY(PlaceType.PICK_CARD),
    TAX(PlaceType.TAX),
    CORNER(PlaceType.CORNER);

    public final PlaceType placeType;

    StreetType(PlaceType placeType) {
        this.placeType = placeType;
    }
}
