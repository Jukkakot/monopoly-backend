package fi.monopoly.server.session;

import java.util.concurrent.ThreadLocalRandom;

final class SessionIdGenerator {

    private static final String[] ADJECTIVES = {
        "rohkea", "nopea", "vahva", "viisas", "reipas", "hurja", "hauska", "kiero",
        "komea", "laiha", "lyhyt", "pitkä", "pieni", "suuri", "leveä", "kapea",
        "vanha", "nuori", "villi", "kiltti", "ilkeä", "tarkka", "terävä", "pehmeä",
        "kova", "lämmin", "kylmä", "kuuma", "märkä", "kuiva", "kirkas", "tumma",
        "kevyt", "raskas", "nälkäinen", "uninen", "väsynyt", "iloinen", "surullinen",
        "utelias", "peloton", "uskallias", "ketterä", "töykeä", "siisti", "sotkuinen"
    };

    private static final String[] NOUNS = {
        "karhu", "kettu", "susi", "hirvi", "jänis", "orava", "majava", "peura",
        "poro", "ahma", "ilves", "leopardi", "tiikeri", "leijona", "gorilla", "apina",
        "kotka", "haukka", "pöllö", "varis", "harakka", "tavi", "joutsen", "kurki",
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
