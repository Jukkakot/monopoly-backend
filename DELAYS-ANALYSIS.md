# Backend Delay Analysis

## Summary
Viiveitä (delays) on backend-projektissa kahdella tavalla:
1. **Botin pelaamisen viiveet** - nämä muuttuvat kun botin nopeutta muutetaan
2. **Järjestelmä/verkko viiveet** - nämä EIVÄT muutu kun botin nopeutta muutetaan

---

## 1. BOTIN NOPEUDESTA RIIPPUVAT VIIVEET ✅ (vaihtelevat)

### BotTurnScheduler.java (fi.monopoly.host.bot)

Käytetään alkuperäisessä desktop-sovelluksessa. Kaikki nämä viiveet riippuvat SpeedMode-enumeraatiosta ja sen delayMultiplier-arvoista:

| SpeedMode | Multiplier | Vaikutus |
|-----------|-----------|----------|
| NORMAL    | 3.0f      | Kolme kertaa pidemmät viiveet |
| FAST      | 1.0f      | Perusviiveet |
| INSTANT   | 0.0f      | Ei viiveitä |

**Viiveet ja niiden perusarvot (millisekunteja):**

```java
case ANIMATION_FINISH -> 260;              // Animaation lopun jälkeen
case RESOLVE_POPUP, ACCEPT_POPUP, DECLINE_POPUP -> 220;  // Popup-päätökset
case ROLL_DICE -> 240;                     // Nopan heitto
case END_TURN -> 150;                      // Vuoron lopetus
case BUILD_ROUND -> 700;                   // Rakennukset
case SELL_BUILDING -> 520;                 // Rakennuksen myynti
case MORTGAGE_PROPERTY, UNMORTGAGE_PROPERTY -> 480;  // Kiinnitysohjaukset
case TRADE -> 850;                         // Kaupankäynti
case AUCTION_ACTION -> 260;                // Huutokauppa
case RETRY_DEBT_PAYMENT, DECLARE_BANKRUPTCY -> 650;  // Velka-ja konkurssi
```

**Lisäkertoimen soveltaminen:**
- Jos kaikki pelaajat ovat tietokoneohjauksessa: multiplier × 0.7f
- Kaikille lisätään jitter: ±60ms

**Paikannus:** `src/main/java/fi/monopoly/host/bot/BotTurnScheduler.java`

---

### PureDomainBotDriver.java (fi.monopoly.server.session)

Käytetään multi-session HTTP-serverissa. Tämä on kehittyneempi kuin BotTurnScheduler ja tukee:

#### Konfiguroitavat parametrit:
- `-Dmonopoly.bot.think.delay.ms` → Oletusarvo: 900ms (perusviive)
- `speedMultiplier` → Ajoissa asettavissa (oletusarvo: 1.0)

#### Viiveisiin vaikuttavat tekijät:

1. **Botin vaikeusaste (BotDifficulty):**
   - EASY: 1.25x (hitaampi)
   - NORMAL: 1.0x (perustaso)
   - STRONG: 0.80x (nopeampi)

2. **Pelin tila (contextual delays):**

```java
// Kaupankäynti: vastapuolen tarjouksen evaluointi
if (state.tradeState() != null && trade.decisionRequiredFromPlayerId() != null) {
    return 2900;  // pisin viive
}

// Velka/konkurssi: stressitaso perustuu saatavilla oleviin vaihtoehtoihin
// Eri viiveet eri tilanteille (200ms - 2500ms)

// Huutokauppa, rakentaminen, kiinnitysohjaukset
// Perustason viiveet 300-1500ms välillä
```

**Formula:**
```
scaled = max(floor, base_delay × difficulty_multiplier × speed_multiplier)
jitter = scaled × 0.20
final_delay = scaled + random(-jitter, +jitter)
```

**Paikannus:** `src/main/java/fi/monopoly/server/session/PureDomainBotDriver.java:445`

---

## 2. BOTIN NOPEUDESTA RIIPPUMATTOMAT VIIVEET ❌ (kiinteät)

### SSE-yhteyden uudelleenmuodostus

**Viive:** 2000ms (2 sekuntia)
**Missä:** HttpClientSessionUpdates.java:115
**Miksi kiinteä?** Tämä on verkkoprotokollaan liittyvä viive, ei pelilogiikka

```java
if (running.get()) {
    log.warn("SSE connection lost, reconnecting in 2s: {}", e.getMessage());
    try {
        Thread.sleep(2000);  // ❌ Kiinteä, ei muutu nopeudella
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
    }
}
```

**Paikannus:** `src/main/java/fi/monopoly/server/transport/HttpClientSessionUpdates.java:110-120`

---

### Istunnon TTL (Time-To-Live)

**Viive:** 120 minuuttia (oletusarvo)
**Konfigurointi:** `-Dmonopoly.session.ttl.minutes`
**Miksi kiinteä?** Tämä on palvelimen resurssien hallintaan liittyvä, ei pelilogiikka

```java
private static final long TTL_MINUTES =
        Long.getLong("monopoly.session.ttl.minutes", 120L);
```

**Puhdistusaikataulu:**
- Suoritetaan joka 5. minuutti (CLEANUP_INTERVAL_MINUTES = 5)
- Poistaa istunnot, joilla ei ole aktiviteettia TTL:n ajan

**Paikannus:** `src/main/java/fi/monopoly/server/session/SessionRegistry.java:25-27`

---

### Testiaikakatkaisu (@Timeout)

**Missä:** PureDomainGameSimulationTest.java
**Arvo:** 10 sekuntia
**Miksi kiinteä?** Turva, jotta testit eivät jää jumiin ikuisesti

```java
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
```

**Paikannus:** `src/test/java/fi/monopoly/server/session/PureDomainGameSimulationTest.java:45, 61`

---

## Suositeltu muutos

Jos haluat että SSE-uudelleenmuodostus noudattaisi botin nopeutta, voisi:

1. **Tapa A:** Konfiguroida se system property:n kautta
```java
private static final long RECONNECT_DELAY_MS =
        Long.getLong("monopoly.sse.reconnect.delay.ms", 2000L);
```

2. **Tapa B:** Linkittää se botin nopeuskertoimen kanssa (vaatii arvon siirtämisen)
- Ei suositeltava, koska SSE on verkon taso-ongelma, ei pelilogiikka

## Yhteenveto

| Viiventyyppi | Arvo | Muuttuva? | Sijainti |
|---|---|---|---|
| Botin pelilogiikan viiveet | 150-2500ms | ✅ Kyllä | BotTurnScheduler / PureDomainBotDriver |
| SSE uudelleenmuodostus | 2000ms | ❌ Ei | HttpClientSessionUpdates:115 |
| Istunnon TTL | 120 min | ❌ Ei | SessionRegistry:26 |
| Testiaikakatkaistu | 10s | ❌ Ei (testi) | PureDomainGameSimulationTest |
