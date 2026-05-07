package fi.monopoly.types;

import fi.monopoly.text.UiTexts;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpotTypeLocalizationTest {

    @Test
    void spotTypeNameUsesActiveLocale() {
        UiTexts.setLocale(Locale.forLanguageTag("fi"));
        assertEquals("TULOVERO", SpotType.TAX1.getStringProperty("name"));
        UiTexts.setLocale(Locale.ENGLISH);
        assertEquals("INCOME TAX", SpotType.TAX1.getStringProperty("name"));
    }

    @Test
    void spotTypeFallsBackToEnglishWhenLocalizedPropertyIsMissing() {
        UiTexts.setLocale(Locale.forLanguageTag("fi"));
        assertEquals("60", SpotType.B1.getStringProperty("price"));
        UiTexts.setLocale(Locale.ENGLISH);
    }
}
