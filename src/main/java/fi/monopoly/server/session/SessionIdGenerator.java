package fi.monopoly.server.session;

import java.util.concurrent.ThreadLocalRandom;

final class SessionIdGenerator {

    private static final String[] ADJECTIVES = {
        "rohkea", "nopea", "vahva", "viisas", "reipas", "hurja", "hauska", "kiero",
        "komea", "laiha", "lyhyt", "pitka", "pieni", "suuri", "levea", "kapea",
        "vanha", "nuori", "villi", "kiltti", "ilkea", "tarkka", "terava", "pehmea",
        "kova", "lammin", "kylma", "kuuma", "marka", "kuiva", "kirkas", "tumma",
        "kevyt", "raskas", "nalkainen", "uninen", "vasynyt", "iloinen", "surullinen",
        "utelias", "peloton", "uskallias", "kettera", "toykea", "siisti", "sotkuinen"
    };

    private static final String[] NOUNS = {
        "karhu", "kettu", "susi", "hirvi", "janis", "orava", "majava", "peura",
        "poro", "ahma", "ilves", "leopardi", "tiikeri", "leijona", "gorilla", "apina",
        "kotka", "haukka", "pollo", "varis", "harakka", "tavi", "joutsen", "kurki",
        "hauki", "ahven", "lohi", "siika", "muikku", "sarvi", "torni", "laiva",
        "linna", "paavi", "ritari", "kuningas", "kuningatar", "joukko", "sankari",
        "soturi", "taikuri", "seikkailija", "kapteeni", "amiraali", "kenraali"
    };

    private SessionIdGenerator() {}

    static String generate() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String adj = ADJECTIVES[rng.nextInt(ADJECTIVES.length)];
        String noun = NOUNS[rng.nextInt(NOUNS.length)];
        int num = rng.nextInt(10, 100);
        return adj + "-" + noun + "-" + num;
    }
}
