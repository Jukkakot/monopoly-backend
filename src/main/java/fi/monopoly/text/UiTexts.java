package fi.monopoly.text;

import java.util.*;

public final class UiTexts {
    private static final String BUNDLE_NAME = "UiTexts";
    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("fi");
    private static final List<Runnable> CHANGE_LISTENERS = new ArrayList<>();
    private static Locale locale = DEFAULT_LOCALE;
    private static ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, DEFAULT_LOCALE);

    private UiTexts() {
    }

    public static String text(String key, Object... args) {
        String result = bundle.getString(key);
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", Objects.toString(args[i]));
        }
        return result;
    }

    public static Locale getLocale() {
        return locale;
    }

    public static void setLocale(Locale newLocale) {
        Locale targetLocale = newLocale == null ? DEFAULT_LOCALE : newLocale;
        if (locale.equals(targetLocale)) {
            return;
        }
        bundle = ResourceBundle.getBundle(BUNDLE_NAME, targetLocale);
        locale = targetLocale;
        CHANGE_LISTENERS.forEach(Runnable::run);
    }

    public static void addChangeListener(Runnable listener) {
        CHANGE_LISTENERS.add(listener);
    }
}
