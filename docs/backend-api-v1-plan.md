# Backend API v1 — Toteutussuunnitelma

## Tavoite

Lisätään kolme puuttuvaa asiaa ennen kuin uuden client-sovelluksen kehittäminen voidaan aloittaa:

1. Dynaaminen session luominen (`POST /sessions`)
2. Multi-session registry
3. CORS-headerit web-clientiä varten

Palvelimen domain-puoli (`PureDomainSessionFactory`, kaikki 6 gatewayta, SSE-push, Jackson-serialisointi) on jo toimiva. Nämä muutokset ovat pelkästään uusi kerros nykyisen päälle — ei arkkitehtuurimuutoksia.

---

## 1. `SessionRegistry` — uusi luokka

**Paketti:** `fi.monopoly.server.session`

Thread-safe rekisteri aktiivisille sessioille.

```
SessionRegistry
  create(List<String> names, List<String> colors) → String sessionId
  get(String sessionId) → Optional<SessionCommandPublisher>
  list() → List<SessionSummary>
  remove(String sessionId)
```

Sisäisesti: `ConcurrentHashMap<String, SessionCommandPublisher>`.

`SessionSummary` on kevyt record:
```
record SessionSummary(String sessionId, List<String> playerNames, SessionStatus status)
```

---

## 2. HTTP-endpointit

Nykyiset `/command`, `/snapshot`, `/events` jäävät ennalleen backward-compat-aliaksiksi (käytetään `StartSessionServer`-käynnistyksessä).

Uudet session-kohtaiset endpointit:

| Endpoint | Metodi | Body / Parametrit | Vastaus |
|---|---|---|---|
| `POST /sessions` | — | `{"names":["P1","P2"], "colors":["#E63946","#2A9D8F"]}` | `{"sessionId":"..."}` |
| `GET /sessions` | — | — | `[{"sessionId":"...","playerNames":[...],"status":"IN_PROGRESS"}]` |
| `POST /sessions/{id}/command` | — | SessionCommand JSON | `{"accepted":true}` / 422 |
| `GET /sessions/{id}/snapshot` | — | — | ClientSessionSnapshot JSON |
| `GET /sessions/{id}/events` | — | — | SSE-stream |

Path-parametrin `{id}` parsinta tehdään itse (`exchange.getRequestURI().getPath()`).

---

## 3. CORS-headerit

Lisätään kaikkiin vastauksiin sekä OPTIONS preflight -käsittely:

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Content-Type
```

OPTIONS-pyyntö palauttaa 204 No Content ilman body:a.

Helpoin toteutus: erillinen `addCorsHeaders(HttpExchange)` -apumetodi jota kutsutaan jokaisen handlerin alussa.

---

## 4. `StartSessionServer` — päivitys

Kaksi käyttötapaa säilytetään:

**Ilman parametreja:** Käynnistää tyhjän palvelimen, sessiot luodaan `POST /sessions` -rajapinnan kautta.

```bash
java -cp monopoly.jar fi.monopoly.server.session.StartSessionServer 8080
```

**Parametreilla:** Luo oletussession suoraan käynnistyksen yhteydessä (backward-compat).

```bash
java -cp monopoly.jar fi.monopoly.server.session.StartSessionServer 8080 Pelaaja1 Pelaaja2
```

---

## Tiedostot joita muutetaan / luodaan

| Tiedosto | Muutos |
|---|---|
| `server/session/SessionRegistry.java` | **Uusi** — session rekisteri |
| `server/session/SessionSummary.java` | **Uusi** — kevyt listausta varten |
| `server/transport/SessionHttpServer.java` | Lisätään uudet endpointit + CORS |
| `server/session/StartSessionServer.java` | Päivitetään tukemaan tyhjää käynnistystä |

Arvioidut koodirivit: ~200 uutta / muutettua riviä yhteensä.

---

## Ei-tavoitteet tässä vaiheessa

- Autentikointi / istunnon omistajuus
- Botti-automaatio palvelimella (Phase 2, erillinen työ)
- Persistointi palvelimella
- WebSocket-tuki (SSE riittää MVP:hen)
