package fi.monopoly.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class MonopolyUtils {

    public static String parseIllegalCharacters(String str) {
        return str.replace("ö", "o")
                .replace("ä", "a")
                .replace("Ö", "O")
                .replace("Ä", "A");
    }
}
