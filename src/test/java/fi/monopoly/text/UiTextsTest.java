package fi.monopoly.text;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UiTextsTest {

    @Test
    void textReplacesIndexedParameters() {
        assertEquals(
                "Arrived at Baltic Avenue. Do you want to buy it for M60?",
                UiTexts.text("property.offerToBuy", "Baltic Avenue", 60)
        );
    }

    @Test
    void textUsesFinnishLocaleWhenSelected() {
        UiTexts.setLocale(Locale.forLanguageTag("fi"));
        assertEquals(
                "Saavuit ruutuun Baltic Avenue. Haluatko ostaa sen hintaan M60?",
                UiTexts.text("property.offerToBuy", "Baltic Avenue", 60)
        );
        UiTexts.setLocale(Locale.ENGLISH);
    }
}
