package fi.monopoly.server.bot;

import fi.monopoly.domain.session.GameEventEntry;
import fi.monopoly.domain.session.PlayerSnapshot;
import fi.monopoly.domain.session.SessionState;
import fi.monopoly.utils.RandomSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides when — and what — a bot says in the in-game chat. Bots occasionally comment on
 * notable game events (buying, building, receiving/paying rent, jail, bankruptcy, trades)
 * and drop the odd emoji reaction, so a table of bots feels alive rather than silent.
 *
 * <p>Design goals:
 * <ul>
 *   <li><b>Localised per viewer</b> — a message is emitted as a {@code (msgKey, variant)} pair,
 *       not literal text. Each client renders it in its own current language from a matching
 *       phrase table, so the same bot line reads Finnish for one viewer and English for another,
 *       and re-localises live when a viewer toggles language. The Finnish text is also carried as
 *       a fallback so an out-of-date client still shows something. Emoji reactions need no key.</li>
 *   <li><b>Variance</b> — every situation has a pool of phrasings; a random variant is picked so
 *       the same event never yields the same line twice in a row.</li>
 *   <li><b>Restraint</b> — a per-bot cooldown plus a global cooldown plus per-situation
 *       probabilities keep the chatter occasional, never spammy.</li>
 *   <li><b>Purity</b> — {@link #onNewEvents} is a plain function of (events, state, now); all
 *       mutable pacing state lives here and is only touched from the driver's single scheduler
 *       thread, so no synchronisation is needed.</li>
 * </ul>
 *
 * <p>The {@code msgKey} values and the per-key variant counts must stay in sync with the client's
 * {@code botChat} phrase tables (src/i18n/translations.ts). The client clamps the variant index
 * modulo its pool length, so a count mismatch degrades gracefully to a different phrasing rather
 * than a crash.</p>
 */
public final class BotChatter {

    /**
     * A queued bot utterance. For a MESSAGE, {@code msgKey}/{@code variant} localise it on the
     * client and {@code content} is the Finnish fallback; for a REACTION, {@code content} is the
     * emoji and {@code msgKey} is null.
     */
    public record ChatIntent(String botId, String kind, String content, String msgKey, int variant, long delayMs) {}

    // A bot won't chat again until this long after its own last line — keeps a single bot from
    // dominating. There is deliberately NO shared/global cooldown: each bot chatters on its own
    // schedule, so several can talk close together.
    private static final long PER_BOT_COOLDOWN_MS = 9_000L;
    // A "big" rent that's worth complaining / gloating about.
    private static final int BIG_RENT = 90;

    private final RandomSource rng;
    private final Map<String, Long> lastChatAtByBot = new HashMap<>();
    private long lastSeenEventId = Long.MIN_VALUE;
    private boolean seeded = false;
    private boolean greeted = false;

    public BotChatter(RandomSource rng) {
        this.rng = rng;
    }

    // ── Message situation keys (must match client botChat table) + Finnish fallback pools ────
    private static final String K_BOUGHT = "boughtProperty";
    private static final String K_HOTEL = "builtHotel";
    private static final String K_RENT_GLOAT = "rentGloat";
    private static final String K_RENT_PAIN = "rentPain";
    private static final String K_JAIL = "jail";
    private static final String K_OPP_BANKRUPT = "opponentBankrupt";
    private static final String K_SELF_BANKRUPT = "selfBankrupt";
    private static final String K_TRADE = "tradeDone";
    private static final String K_GREETING = "greeting";
    private static final String K_PASS_GO = "passedGo";
    private static final String K_JAIL_OUT = "releasedFromJail";
    private static final String K_MORTGAGE = "mortgaged";
    private static final String K_TRADE_NO = "tradeDeclined";
    private static final String K_HOUSE = "builtHouse";
    private static final String K_REDEEM = "redeemed";
    private static final String K_BANTER = "banter";
    private static final String K_CARD = "drewCard";
    private static final String K_SELL_BUILDING = "soldBuilding";
    private static final String K_JAIL_TAUNT = "jailTaunt";
    private static final String K_PLAYER_LEFT = "playerLeft";
    private static final String K_REJECT_OFFER = "rejectOffer";
    private static final String K_BANTER_LEAD = "banterLead";
    private static final String K_BANTER_TRAIL = "banterTrail";
    private static final String K_BANTER_LOW = "banterLow";
    private static final String K_SPECTATE = "spectateRent";
    private static final String K_TAX = "taxPaid";
    private static final String K_OPP_STRUGGLE = "opponentStruggle";

    private static final String[] BOUGHT = {
            "Joo-o, tää on nyt mun. 😎", "Hyvä läträys!", "Täst tulee kultakaivos. 💰",
            "Yks tontti listalt pois. 🏠", "Mun imperiumi kasvaa taas. 🏰", "Ei jätetä hyvii paloi muille.",
            "Tää täydentää setin. 🎯", "Halvalla sain, kiitti. 🤝",
            "Otin tän vaan et te ette saa. 😏", "Rautatiet on varmaa fyrkkaa. 🚂",
            "Vieres tontti — nyt on pari kasas. 👫", "Pakko ottaa, en voinu vastustaa.",
            "Sijainti ratkasee, aina. 📍", "Nam, hyvä lisä salkkuun. 💼",
            "Mine mine mine! 😆", "Täst tulee teille kallista. 😈",
            "Sori muut, tää oli liian hyvä. 🤷", "Instabuy, ei mietitty kauaa. ⚡",
            "Kelaa, tää oli halpa. 🤑", "Salkkuun vaan lisää. 📁", "Tää oli no-brainer. 🧠",
            "Yks askel lähempänä monopolii. 🎯", "Sori, mä olin nopeempi. ⚡", "Tää tontti puhuu mulle. 🏠",
            "Diilit diilit diilit. 💼", "Mun kartta laajenee. 🗺️", "Ei muuta ku ostoon. 🛒",
            "Tää oli pakko, sata lasissa. 💯", "Omistaminen on parasta. 😌", "Näppärä hankinta taas. 👌",
            "Ostin sen, älkää itkekö. 😜", "Tää oli steal. 🥷", "Mun salkku on tulessa. 🔥",
            "Yks helmi lisää. 💎", "Sori, first come first served. 🏃", "Tää nappaa hyvin. 👍",
            "Rahalla saa ja hevosella pääsee. 🐎", "Ostofiilis päällä. 🛍️",
    };
    private static final String[] BUILT_HOTEL = {
            "Hotelli pystys! 🏨", "Tervetuloa — ei oo halpaa lystii. 😏",
            "Nyt alkaa kilahtaa kassaan. 🤑", "Täst tuli kallis kulma teille.",
            "Maksaa ittensä takas ihan just. 💰", "Jos tänne osutte ni voi voi. 💸",
            "Superhotelli valmis. 🏨", "Tää ruutu on nyt puhdasta myrkkyy. ☠️",
            "Tällasii sijotuksii mä diggaan. 📈", "Älkää astuko tänne. Tai astukaa. 😈",
            "Viiden tähden ansa valmis. ⭐", "Kassakone käyntiin. 🤑",
            "Hotellibisnes rullaa. 🏨", "Rakennettu, valmis, kallis. 💸", "Nyt vuokrat tuplaantuu. 📈",
            "Tää kulma on nyt VIP. ⭐", "Muut, varokaa tätä ruutuu. ⚠️", "Kingin liike. 👑",
            "Isot rahat tulee tästä. 🤑", "Hotelli-imperiumi kasvaa. 🏰", "Tää oli hyvä sijotus. 📊",
            "Vieraita odotellessa... 😈",
    };
    private static final String[] RENT_GLOAT = {
            "Kiitti vuokrist! 💰", "Kassa kasvaa taas. 😎", "Nam, kivaa lisää.",
            "Vuokran periminen on kyl parasta. 🤑", "Näillä ostan lisää läträimii.",
            "Sijotus tuottaa, mitäs mä sanoin. 📈", "Passiivist tuloo, rakastan tätä.",
            "Kiitti vaan, maksa pois. 😌", "Tää tili paisuu mukavasti.",
            "Vuokralaiset on mun bestii. 🏦", "Cha-ching! 🤑",
            "Ez money. 😎", "Kiitos ku pysähdyit. 😏", "Mun lompsa kiittää. 💸",
            "Free real estate. 🏠", "Rakastan tätä peliä juuri nyt. 🥰",
            "Rahaa sataa taas. 💰", "Mun tili hymyilee. 😊", "Kiitos ku tulit kylään. 😏",
            "Tää on liian helppoo. 😎", "Vuokrakone rullaa. ⚙️", "Rakastan vuokrapäivii. ❤️",
            "Sun tappio, mun voitto. 🤑", "Jatka vaan kierroksii. 😌", "Tää tuottaa taas. 📈",
            "Ka-ching, osa kaks. 🤑", "Passiivinen tulo on kingi. 👑", "Mun kassa kiittää sua. 💵",
            "Kiitti ku rahoitat mua. 🙏", "Tililtä tilille, kiva. 💳", "Rakastan omistajuutta. 🏠",
            "Sun rahat, mun ilo. 😄", "Vuokra napsahti taas. 💰", "Kassavirta on kaunista. 📊",
            "Kiitti tuest. 😘", "Tää on bisnestä parhaimmillaan. 💼",
    };
    private static final String[] RENT_PAIN = {
            "Auts, kallis pysähdys. 😩", "No täähän sattu.", "Voi ei, melkein koko kassa meni. 😱",
            "Kallist lystii tää. 💸", "Nyt pitää alkaa myymään taloi...",
            "Tos meni budjetti. 😰", "Väärä ruutu, väärä hetki, äh.",
            "No siin meni säästöt, kiitti. 🙃", "Täst pitää toipuu äkkii.",
            "Kallis oppiläksy tää. 📚", "Auts, sattu kukkaroon. 😖",
            "F. 💀", "Rip mun kassa. ⚰️", "No tää oli tässä, kiitti vaan. 😤",
            "Miks aina mä?? 😭", "Ei nyt yhtään sopinut budjettiin. 🫠",
            "Noni, taas mä maksan. 😩", "Sattu, sattu, sattu. 😖", "Mun budjetti itkee. 😭",
            "Tää oli tyyris. 💸", "Voi pojat, kallista. 😱", "Mun kassa ei kestä tätä. 😰",
            "Ei taas vuokraa... 🫠", "Kohta oon konkkaan. 😨", "Nää vuokrat tappaa mut. ⚰️",
            "Isoveli otti taas. 😤", "Miten mä selviin tästä. 😥", "Kukkaro sanoo au. 😖",
            "Ai että. 😩", "Tää oli kyl paha. 💀", "Mun rahat sulaa. 🫠",
            "Ei kivaa yhtään. 😤", "Kassa hupenee, apua. 😱", "Voi ei ei ei. 😭",
            "Tää sattu ihan oikeesti. 😖", "No nyt meni lujaa. 💸",
    };
    private static final String[] JAIL = {
            "No niin, koppiin taas. 😅", "Nähää parin kierroksen päästä.",
            "Ei taas... äh.", "Vankila kutsuu. 🚔",
            "No tää meni hyvin. 🙃", "Linnaan siitä. 😔", "Vitsi et taas. 🤦",
            "Koppi taas, klassikko. 😅", "No nyt istutaan. 🪑", "Vankila, oma koti. 🏚️",
            "Kolme kierrosta lomaa. 🏖️", "Nonii, sinne meni vuoro. 😔", "Poliisi otti kii. 🚓",
            "Aika ottaa iisisti. 😌",
    };
    private static final String[] OPPONENT_BANKRUPT = {
            "Yks vähemmän. 😎", "Peli on peli. 🤝", "Hyvin pelattu silti!",
            "Sääli, mut bisnes on bisnestä.", "F sulle. 💀", "Nähää ens pelis! 👋",
            "Yks kilpailija pois laskuist. 😏", "GG, hyvä yritys. 🫡", "Kilpailu just kevens. 😏",
            "Yks pelaaja poistu pelistä. 👋", "No niin, seuraava? 😏", "Sori et voitin. 🏆",
            "Kova peli, hyvä yritys. 🤝", "Kilpailu ohenee mukavasti. 😎", "RIP, hyvin pelattu. 🪦",
            "Yks nurkka vapautu mulle. 😈",
    };
    private static final String[] SELF_BANKRUPT = {
            "Hyvää peliä kaikille! 💀", "No tähän se tyssäs — onnee muille!",
            "Konkkaan mentiin. Hyvin pelattu, muut.", "GG, mä oon out. 🫡",
            "No niin, mun peli päätty tähän. 😔",
            "Noni, tää oli tässä. 🫡", "Hyvä peli, mä luovutan. 🏳️", "Konkkaan meni, GG all. 💀",
            "Rahat loppu, kiitos pelistä. 🙏", "Mä lähen tästä. Onnee muille! 👋",
    };
    private static final String[] TRADE_DONE = {
            "Hyvä diili! 🤝", "Kaupat klaari.", "Molemmat voittaa — tai ainaki mä. 😏",
            "Tää kauppa vahvistaa mun asemaa. 💼", "Sain just sen tontin mitä tarvin. 🎯", "Jees, tästä on hyötyy.",
            "Win-win, painotus mun win. 😎", "Deal! 🤝", "Kiva tehä bisnest kaa. 😌",
            "Kaupankäynti on taidetta. 🎨", "Diili klaari, kiitti. 🤝", "Molemmat tyytyväisii — mä enemmän. 😏",
            "Bisnes on bisnestä. 💼", "Hyvä vaihtokauppa. 🔄", "Tää sopi mulle täydellisesti. 🎯",
            "Kaupat kiinni, eteenpäin. ✅",
    };
    private static final String[] REJECT_OFFER = {
            "Ei kiitti — ei hyödytä mua.", "En luovu täst tontist. 🚫",
            "Toi tarjous on ihan yksipuolinen. 🤨", "Pidän omani, kiitti.",
            "Tost mä häviäisin. Ei käy.", "Keksi parempi tarjous. 😏",
            "Nice try. 😂", "Luuletsä et mä oon tyhmä? 😅", "Ei todellakaan. 🙅",
            "Ei onnistu, sori. 🙅", "Toi on ryöstö, ei käy. 😤", "Mä en oo eilisen teeren poika. 😏",
            "Parempi tarjous tai ei mitään. 🤨", "En myy halvalla. 💰", "Höh, ei kiinnosta. 😐",
            "Pidä tarjoukses. 🚫",
    };
    private static final String[] GREETING = {
            "No niin, pistetään pystyyn! 🎲", "Tsemppii kaikille — mä en tarvii. 😏",
            "Antaa palaa, jäbät!", "Onnee vaan, kyl te sitä tarttette. 😎",
            "Mennääks? Mä oon valmis. 🔥", "Letsgooo! Valmiina häviää? 😜",
            "Nyt näytetään kuka on kingi. 👑", "Mä oon jo voittanu mieles. 😎",
            "Moro kaikki! 👋", "Peli käyntiin, jännittää. 😄", "Nyt mennään, tsemppi! 💪",
            "Terve terve, valmiina? 😎", "Mä oon täs voittaakseni. 🏆", "Hei, aletaan hommiin. 🎲",
            "Onnee, sitä tarvitte. 😏", "Lets go, mennään! 🔥",
            "Hei hei, aloitetaan! 👋", "Peli pystyyn, jännittää! 😄", "Valmiina taisteluun? ⚔️",
            "Nyt katotaan kuka on paras. 😏", "Morjens, pelataan! 🎲", "Mä tulin voittamaan. 🏆",
            "Otetaan iisisti mut voitetaan. 😎", "Go go go! 🚀",
    };
    private static final String[] PASS_GO = {
            "Kierros täys, +200! 💰", "Kiitti pankki. 💵", "Taas 200 taskuun. 😎",
            "Palkkapäivä! 🤑", "Startti maksaa, jees. 💵",
            "Rahaa tuli, jees jees. 🤑", "Kiva ku pankki maksaa. 💵",
            "200 lisää, jes! 💰", "Startin ohi, kassa kasvaa. 💵", "Palkka tuli, kiitti. 🤑",
            "Aina kiva pyörähtää startin kautta. 😌", "Kohta ostan tolla jotain. 🛒", "Rahaa taskuun, mennään. 💸",
            "Kierros valmis, +200. ✅",
    };
    private static final String[] JAIL_OUT = {
            "Vapaana taas! 🔓", "Takas peliin. 😎", "Ei mua kauaa pidellä.",
            "Ulkona koppist, nyt jyrätään. 💪",
            "Vapaus maistuu. 😎", "Takas hommiin. 🔥", "Koppi taakse, peli jatkuu. 🎲",
            "En viihtyny sellis. 😅", "Nyt revanssi. 😤", "Ulos ja eteenpäin. 💪",
    };
    private static final String[] MORTGAGE = {
            "Pakko kiinnittää, äh... 😬", "Tarviin cashii nyt heti.", "Ei muuta vaihtoehtoo.",
            "Kiinnitän tän, tarviin fyrkkaa ostoon.", "Väliaikanen juttu, nostan pian takas.",
            "Sori tontti, joudut pantiks hetkeks. 😔", "Hätäkassa auki, äh. 🏦",
            "Kiinnitys päälle, äh. 😬", "Cashii pakko saada. 💸", "Sori tontti, hetkeks pantiks. 😔",
            "Hätätila, kiinnitän. 🚨", "Ei kivaa mut pakko. 😞", "Väliaikanen ratkasu. ⏳",
            "Rahat kiinni tontissa, purku myöhemmin. 🏦",
    };
    private static final String[] TRADE_NO = {
            "No ei sitten. 🤷", "Harmi, ois ollu hyvä diili.", "Ehkä ens kerral. 🤔",
            "Sun tappio, ei mun. 😏", "No okei, pidä tonttis. 😌",
            "No ei sit. 🤷", "Harmi, ois voinu toimii. 😕", "Ehkä joskus toiste. 🤔",
            "Sun valinta, ei mun. 😏", "Okei, pidä omas. 😌", "Hylkäsit? No hyvä on. 😐",
            "Diili ei syntyny, harmi. 🙃",
    };
    private static final String[] BUILT_HOUSE = {
            "Talo nostaa vuokraa, jees. 🏠", "Rakennan täst vahvan.",
            "Pieni panostus, iso tuotto. 📈", "Talo kerrallaan kohti hotellii.",
            "Naapurusto kehittyy. 🔨", "Pikkuhiljaa vaan ylöspäin. 🏗️",
            "Talo pystyyn, vuokra nousee. 🏠", "Pikkuhiljaa rakennetaan. 🔨", "Kehitystä, kiva. 📈",
            "Yks talo lisää. 🏘️", "Kohti hotellii, askel kerrallaan. 🏗️", "Investointi kannattaa. 💰",
            "Tää nurkka kehittyy. 🚧", "Lisää vuokratuloo tulos. 📊",
    };
    private static final String[] REDEEM = {
            "Nostin kiinnityksen — kassa kestää taas. 💪", "Takas omaks ilman velkaa.",
            "Nyt tää tuottaa taas täydet. 💰", "Velat pois, taas mennään. 😎", "Kiinnitys purku, jees. ✅",
            "Velaton taas, hyvä fiilis. 😌", "Nyt täydet vuokrat taas. 💰", "Tontti takas mun. 💪",
            "Nostin lainan, kassa kestää. 😎", "Puhtaat paperit taas. 📄", "Ei enää pantissa. 🔓",
            "Kassa kunnossa, jatketaan. ✅",
    };
    private static final String[] BANTER = {
            "Katotaas mitä nopat antaa. 🎲", "Tuuria peliin!", "Kunhan en mee vankilaan... 🤞",
            "Fyrkkaa on, uskallan pelaa. 💰", "Tää kierros on mun. 😎",
            "Mietin seuraavaa siirtoo... 🤔", "Nyt kannattaa säästää cashii.", "Kohta iskee monopoli. 😏",
            "Ei kiirettä, peli on pitkä.", "Rento meininki, homma hanskas. 😎",
            "Katotaan mihin toi nappula päätyy.", "Pörssi nousee, ostan lisää. 📈",
            "Chillii vaan, ei stressii. 😌", "Nopat, älkää pettäkö. 🎲",
            "Hmm, minne täst... 🤔", "Vähän jännittää, mut hyväl taval. 😄",
            "Mitäs täs seuraavaks. 🤔", "Peli rullaa mukavasti. 😌", "Kohta tapahtuu jotain isoo. 👀",
            "Noppa, ole kiltti. 🎲", "Chillataan ja pelataan. 😎", "Tää on hauskaa. 😄",
            "Katotaan miten käy. 👀", "Nyt strategia käyntiin. 🧠", "Rauhassa vaan, ei paniikkii. 😌",
            "Mun vuoro loistaa. ✨", "Pelataan älyllä. 🧠", "Onni suosii rohkeeta. 😏",
            "Mennään fiiliksel. 🎲", "Tästä tulee hyvä kierros. 📈",
            "Antaa mennä vaan. 🎲", "Tänään on mun päivä. ☀️", "Fiilis on korkeel. 🚀",
            "Katotaan mitä tapahtuu. 👀", "Ei muuta ku eteenpäin. ➡️", "Peli on parhaimmillaan. 😎",
            "Nyt keskitytään. 🧠", "Vibet on kohillaan. ✨",
    };
    // Idle banter tuned to how the bot is doing, so a random line still fits the situation.
    private static final String[] BANTER_LEAD = {
            "Mä johdan — ja aion pitää sen niin. 😎", "Mun kassa on paksuin täs pöydäs. 💰",
            "Voitto haisee jo. 🏆", "Ei kukaan saa mua kii.",
            "Mun imperiumi vaan kasvaa. 🏰", "Te muut pelaatte kakkossijast. 😏",
            "Helppoo tää on. 😌", "Vuokrat virtaa, kiitti kaikille.",
            "Mä pidän ohjat käsis.", "Kohta koko lauta on mun. 🗺️",
            "Ez game, ez life. 😎", "Kukaa ei uhkaa mua just nyt. 👑",
            "Kärki on mun. 🥇", "Kukaa ei pysy perässä. 😎", "Voitto lähestyy. 🏆",
            "Mä diktoin tahdin. 😏", "Ykkössija, missäs muualla. 👑", "Muut kattoo perään. 😌",
            "Tää peli on jo mun. 💰", "En stressaa yhtään. 😎", "Johto kasvaa vaan. 📈",
            "Kingi pysyy kingi. 👑",
            "Ykkönen pysyy ykkösenä. 🥇", "Kattokaa ja oppikaa. 😎", "Tää on mun näytöstä. 🎭",
            "Voitto on käytännös taskus. 🏆", "En horju mihinkään. 🗿", "Muut vaan seuraa. 👀",
            "Kruunu istuu hyvin. 👑", "Dominointi jatkuu. 💪",
    };
    private static final String[] BANTER_TRAIL = {
            "En oo viel ulkona täst. 💪", "Kyl mä tän viel käännän.",
            "Yks hyvä diili ni oon taas mukan.", "Alakynnes ollaan, mut en luovuta.",
            "Täst noustaan viel. 📈", "Vähän tuuria kaipaisin nyt... 🍀",
            "Ei tää tähän lopu.", "Comeback tulee, uskokaa pois. 😤",
            "Pakko pelaa varovasti nyt.", "Odottakaa vaan, käännän tuulen. 🌬️",
            "Aliarvioitte mut viel. 😏", "Underdog-tarina alkakoon. 🐕",
            "Comeback moodi päällä. 😤", "En anna periks. 💪", "Käännetään tää peli. 🔄",
            "Alta pois, mä nousen. 📈", "Vielä ei o peli menetetty. 🙏", "Tuuria kaipais nyt. 🍀",
            "Odota vaan, mä palaan. 😏", "Underdog jyrää viel. 🐕", "En luovu vaikka takana. 💪",
            "Tästä lähtee nousu. 🚀",
            "En oo tappiolla, oon vaan latautumas. 🔋", "Kohta käännän tän. 🔄", "Usko mua: mä palaan. 😤",
            "Nyt vaan sisu peliin. 💪", "Ei paniikkii, plan B käyntiin. 🧠", "Vielä ei laulettu. 🎤",
            "Mä nousen tuhkasta. 🔥", "Katsokaa vaan tätä. 👀",
    };
    private static final String[] BANTER_LOW = {
            "Kassa hupenee ihan hurjaa vauhtii... 😰", "Nyt on tiukkaa, pitää säästää.",
            "Kunhan en osu kenenkään hotelliin. 😬", "Vähän rahaa, paljon riskii.",
            "Yks iso vuokra ni oon liemes.", "Pitää kai kiinnittää tontteja pian.",
            "Sydän hakkaa joka heitol. 💓", "Rukoilen pieniä vuokrii. 🙏",
            "Selviänks mä täst kierrokses?", "Kohta on kassakriisi... 😅",
            "Lompsa itkee. 😭", "Nyt ei oo varaa mokaa. 😨",
            "Kassa ohenee, huoli kasvaa. 😰", "Nyt tarkkana rahojen kaa. 😬", "Plis ei isoja vuokrii. 🙏",
            "Vähän puskuria jäljel. 😅", "Riski kasvaa joka kierros. 😨", "Selviänkö? Ei tietoo. 😰",
            "Kohta myydään taloja. 😔", "Lompsa hoikkenee. 📉", "Jännittää ihan sikana. 😥",
            "Yks moka ni peli loppuu. 😱",
            "Apua, kassa tyhjenee. 😱", "Nyt jännittää tosissaan. 😰", "Plis noppa armahda. 🙏",
            "Yks väärä liike ni loppu. 😨", "Mun rahat hupenee sikana. 📉", "Kohta itketään. 😭",
            "Selvitäänkö? Ei aavistust. 🫤", "Riskit kasvaa, apua. 😬",
    };
    private static final String[] DREW_CARD = {
            "Katotaas mitä kortti sanoo... 🃏", "Vähän sattumaa peliin!", "Toivotaan hyvää korttii. 🤞",
            "Kortit ratkasee. 🎴", "Plis oo hyvä kortti... 🙏", "Mitäköhän tää tuo tullessaan. 👀",
            "Kortti, oo kiltti. 🃏", "Mitäköhän täs on. 👀", "Sattuma ratkaisee. 🎴",
            "Plis hyvä juttu. 🤞", "Jännä nähä mitä tulee. 😄", "Onnenkortti, plis. 🍀",
            "Katotaas... 🃏", "Toivottavasti ei huono. 😬",
    };
    private static final String[] SOLD_BUILDING = {
            "Pakko myydä rakennuksii... 😔", "Harmi, talot lähtee.",
            "Tarviin cashii, myyn taloi. 💸", "Askel taakspäin, mut pakko.",
            "Sori talot, teitä tarvitaan rahana. 🏚️", "Ei muuta keinoo saada fyrkkaa. 😞",
            "Talot myyntiin, äh. 😔", "Pakko realisoida. 💸", "Sori talot, cashii tarvitaan. 🏚️",
            "Kehitys peruutusvaihteel. 📉", "Ei kivaa mut pakko myydä. 😞", "Rakennukset rahaks. 💰",
            "Askel taakse, mut selviydyn. 💪", "Myyn talot, hätä on. 😰",
    };
    private static final String[] JAIL_TAUNT = {
            "Hei, koppiin siitä! 😂", "Nauttikaa sellist. 😏", "Yks vähemmän liikkeel. 😎",
            "Hah, linnareissu! 😆", "Moro, nähää kolmen kierroksen päästä. 👋", "Sinne meni. 😏",
            "Sori et nauran. 😂", "Terkkuja sellist! 👋",
            "Hah, sinne meni! 😂", "Nauti reissust koppiin. 😏", "Yks vähemmän pyörii. 😎",
            "Terkkuja kaltereiden takaa! 👋", "Lomaa sulle, kiva. 🏖️", "Poliisi otti kii, lol. 🚓",
            "Istuppa siel rauhas. 😌", "Nähää parin kierroksen päästä! 😆",
    };
    private static final String[] PLAYER_LEFT = {
            "No sepä harmi, joku lähti.", "Yks vähemmän pöydäs.", "Peli jatkuu ilman sitä. 🤷",
            "Ai lähti? No lisää fyrkkaa mulle. 😎",
            "No moikka sit. 👋", "Yks pelaaja vähemmän. 🤷", "Peli jatkuu, lisää mulle. 😎",
            "Harmi, mut mennään. 😌", "Yks kilpailija katos. 😏", "No niin, jatketaan. 🎲",
    };
    // Bystander commentary: a bot enjoys the drama when two OTHER players trade a big rent.
    private static final String[] SPECTATE_RENT = {
            "Ohohoh, kallista! 😮", "Katotaas tätä draamaa. 🍿", "Auts, ei onneks mun rahat. 😅",
            "Popkornit esiin. 🍿", "Tosta se toinen kärsi. 😬", "Nam, tykkään ku muut maksaa. 😏",
            "Auts, toi sattu toiseen. 😬", "Popkornit ja show alkaa. 🍿", "Onneks en mä maksanu. 😅",
            "Katotaas tätä sirkust. 🎪", "Toi oli iso vuokra. 😮", "Mä vaan katon vieressä. 👀",
            "Draamaa pöydäl. 🍿", "Hyvä ku ei mun rahat. 😌",
    };
    // Own tax payment (MONEY_FLOW reason "vero").
    private static final String[] TAX = {
            "Verot vie taas. 😤", "Kiitti vaan verottaja. 🙄", "No siin meni rahat valtiolle. 💸",
            "Verotus on ryöstöö. 😑", "Äh, verolappu. 🧾", "Pakolliset menot, ikävää. 😔",
    };
    // An opponent is selling buildings — someone's in trouble, and a bot notices. 🍿
    private static final String[] OPP_STRUGGLE = {
            "Ohoh, myykö toi taloja? 👀", "Näyttää menevän huonosti jollain. 😏", "Joku on pulassa. 🍿",
            "Talot lähtee toiselta, kiva mulle. 😎", "Hmm, joku alkaa kärsii. 😈", "Vaikeuksia naapurilla? 🤭",
    };

    // ── Reaction pools (must be from the backend allow-list) ────────────────────────────────
    private static final String[] REACT_RENT = { "💰", "😎", "🔥" };
    private static final String[] REACT_BANKRUPT = { "😮", "😢", "😎" };
    private static final String[] REACT_HOTEL = { "😮", "🔥", "👏" };
    private static final String[] REACT_DOUBLES = { "🎲", "🍀", "😅" };
    private static final String[] REACT_TROUBLE = { "😎", "🔥", "🤔" };
    private static final String[] REACT_JAIL_TAUNT = { "😂", "😎", "👏" };
    private static final String[] REACT_OPP_BUY = { "🤔", "😎", "😮" };

    /**
     * Given the freshly-arrived events and current state, returns any chat lines bots should
     * post now. On the first call it silently seeds its event cursor to the current log tail so
     * a mid-game reconnect (which re-delivers up to 50 old events) doesn't trigger a chatter
     * burst.
     */
    public List<ChatIntent> onNewEvents(List<GameEventEntry> eventLog, SessionState state, Set<String> botIds, long nowMs) {
        List<ChatIntent> out = new ArrayList<>();
        if (eventLog == null || eventLog.isEmpty() || botIds.isEmpty()) return out;

        long maxId = Long.MIN_VALUE;
        for (GameEventEntry e : eventLog) maxId = Math.max(maxId, e.id());

        if (!seeded) {
            seeded = true;
            lastSeenEventId = maxId;
            return out;
        }
        if (maxId <= lastSeenEventId) return out;

        // Game-start greeting: once, from a random bot, shortly after the first real events flow.
        if (!greeted) {
            greeted = true;
            if (rng.nextDouble() < 0.80) {
                String greeter = pickEligibleBot(null, state, botIds, nowMs);
                if (greeter != null) {
                    int v = rng.nextInt(GREETING.length);
                    ChatIntent g = new ChatIntent(greeter, "MESSAGE", GREETING[v], K_GREETING, v, thinkDelay());
                    out.add(g);
                    lastChatAtByBot.put(greeter, nowMs + g.delayMs());
                }
            }
        }

        for (GameEventEntry e : eventLog) {
            if (e.id() <= lastSeenEventId) continue;
            ChatIntent intent = considerEvent(e, state, botIds, nowMs);
            if (intent != null) {
                out.add(intent);
                lastChatAtByBot.put(intent.botId(), nowMs + intent.delayMs());
            }
        }
        lastSeenEventId = maxId;
        return out;
    }

    private ChatIntent considerEvent(GameEventEntry e, SessionState state, Set<String> botIds, long nowMs) {
        String author = e.playerIds().isEmpty() ? null : e.playerIds().get(0);

        switch (e.type()) {
            case "BOUGHT_PROPERTY":
                if (isBot(author, botIds)) return maybeMessage(author, K_BOUGHT, BOUGHT, 0.30, nowMs);
                // A rival grabs a deed — a bot occasionally eyes it.
                return maybeReactionFromOther(author, REACT_OPP_BUY, 0.10, state, botIds, nowMs);
            case "BUILT_HOTEL":
                if (isBot(author, botIds)) return maybeMessage(author, K_HOTEL, BUILT_HOTEL, 0.55, nowMs);
                // A non-bot builds a hotel — a bot might react with awe.
                return maybeReactionFromOther(author, REACT_HOTEL, 0.20, state, botIds, nowMs);
            case "BUILT_HOUSE":
                // Frequent (each house), so keep it low — an occasional line on the investment.
                if (isBot(author, botIds)) return maybeMessage(author, K_HOUSE, BUILT_HOUSE, 0.14, nowMs);
                // A rival is developing — a wary glance now and then.
                return maybeReactionFromOther(author, REACT_OPP_BUY, 0.07, state, botIds, nowMs);
            case "REDEEMED":
                if (isBot(author, botIds)) return maybeMessage(author, K_REDEEM, REDEEM, 0.28, nowMs);
                break;
            case "SOLD_HOUSE":
            case "SOLD_HOTEL":
                // Selling buildings back means cash trouble — a rueful money-decision line.
                if (isBot(author, botIds)) return maybeMessage(author, K_SELL_BUILDING, SOLD_BUILDING, 0.30, nowMs);
                // A rival is liquidating — a bot smells blood. 🍿
                return maybeMessageFromOther(author, K_OPP_STRUGGLE, OPP_STRUGGLE, REACT_TROUBLE, 0.18, state, botIds, nowMs);
            case "MONEY_FLOW": {
                // Tax payment (reason "vero") — from == payer. A bot grumbles about the taxman.
                if ("vero".equals(e.data().get("reason")) && isBot(author, botIds)) {
                    return maybeMessage(author, K_TAX, TAX, 0.40, nowMs);
                }
                break;
            }
            case "DREW_CARD":
                if (isBot(author, botIds)) return maybeMessage(author, K_CARD, DREW_CARD, 0.14, nowMs);
                break;
            case "PLAYER_LEFT":
                // A player quit — a surviving bot remarks on it.
                return maybeMessageFromOther(author, K_PLAYER_LEFT, PLAYER_LEFT, REACT_TROUBLE, 0.50, state, botIds, nowMs);
            case "WENT_TO_JAIL":
                if (isBot(author, botIds)) return maybeMessage(author, K_JAIL, JAIL, 0.30, nowMs);
                // Someone else gets sent down — a bot enjoys it.
                return maybeMessageFromOther(author, K_JAIL_TAUNT, JAIL_TAUNT, REACT_JAIL_TAUNT, 0.25, state, botIds, nowMs);
            case "RELEASED_FROM_JAIL":
                if (isBot(author, botIds)) return maybeMessage(author, K_JAIL_OUT, JAIL_OUT, 0.28, nowMs);
                break;
            case "PASSED_GO":
                // Frequent event — keep the probability low so it stays a rare treat.
                if (isBot(author, botIds)) return maybeMessage(author, K_PASS_GO, PASS_GO, 0.15, nowMs);
                break;
            case "MORTGAGED":
                if (isBot(author, botIds)) return maybeMessage(author, K_MORTGAGE, MORTGAGE, 0.30, nowMs);
                // A non-bot mortgaging signals trouble — a bot might smell blood.
                return maybeReactionFromOther(author, REACT_TROUBLE, 0.14, state, botIds, nowMs);
            case "TRADE_DECLINED":
            case "TRADE_CANCELLED": {
                // playerIds: [initiator, recipient]. The recipient is the one who was asked, so
                // a bot recipient is the one saying no → a reasoning line about why. A bot whose
                // own offer got turned down instead just shrugs it off.
                String initiator = e.playerIds().isEmpty() ? null : e.playerIds().get(0);
                String recipient = e.playerIds().size() > 1 ? e.playerIds().get(1) : null;
                if (isBot(recipient, botIds)) return maybeMessage(recipient, K_REJECT_OFFER, REJECT_OFFER, 0.35, nowMs);
                if (isBot(initiator, botIds)) return maybeMessage(initiator, K_TRADE_NO, TRADE_NO, 0.28, nowMs);
                break;
            }
            case "PAID_RENT": {
                // playerIds: [payer, creditor]. amount in data.
                String creditor = e.playerIds().size() > 1 ? e.playerIds().get(1) : null;
                int amount = parseInt(e.data().get("amount"));
                if (isBot(creditor, botIds)) {
                    // Bot received rent — gloat on a big one, otherwise a small chance of a 💰.
                    if (amount >= BIG_RENT) return maybeMessage(creditor, K_RENT_GLOAT, RENT_GLOAT, 0.45, nowMs);
                    return maybeReaction(creditor, REACT_RENT, 0.18, state, nowMs);
                }
                if (isBot(author, botIds) && amount >= BIG_RENT) {
                    return maybeMessage(author, K_RENT_PAIN, RENT_PAIN, 0.40, nowMs);
                }
                // Neither party is a bot — on a big one, a bystander bot enjoys the drama. 🍿
                if (amount >= BIG_RENT && !isBot(author, botIds)) {
                    return maybeMessageFromOther(null, K_SPECTATE, SPECTATE_RENT, REACT_RENT, 0.22, state, botIds, nowMs);
                }
                break;
            }
            case "WENT_BANKRUPT":
                if (isBot(author, botIds)) return maybeMessage(author, K_SELF_BANKRUPT, SELF_BANKRUPT, 0.85, nowMs);
                // A human (or another already-processed player) went bankrupt — a surviving bot reacts.
                return maybeMessageFromOther(author, K_OPP_BANKRUPT, OPPONENT_BANKRUPT, REACT_BANKRUPT, 0.55, state, botIds, nowMs);
            case "TRADE_ACCEPTED": {
                for (String pid : e.playerIds()) {
                    if (isBot(pid, botIds)) return maybeMessage(pid, K_TRADE, TRADE_DONE, 0.30, nowMs);
                }
                break;
            }
            case "DICE_ROLLED": {
                int d1 = parseInt(e.data().get("d1"));
                int d2 = parseInt(e.data().get("d2"));
                if (d1 > 0 && d1 == d2 && isBot(author, botIds)) {
                    return maybeReaction(author, REACT_DOUBLES, 0.15, state, nowMs);
                }
                // Occasional idle banter on the bot's own roll, tuned to how the bot is doing so a
                // random line still fits the moment. Every roll fires this, so keep the probability
                // low; cooldowns thin it further.
                if (isBot(author, botIds)) {
                    String key = K_BANTER;
                    String[] pool = BANTER;
                    switch (cashStanding(author, state)) {
                        case LOW:   key = K_BANTER_LOW;   pool = BANTER_LOW;   break;
                        case LEAD:  key = K_BANTER_LEAD;  pool = BANTER_LEAD;  break;
                        case TRAIL: key = K_BANTER_TRAIL; pool = BANTER_TRAIL; break;
                        default: break;
                    }
                    return maybeMessage(author, key, pool, 0.09, nowMs);
                }
                break;
            }
            default:
                break;
        }
        return null;
    }

    // ── Emission helpers ────────────────────────────────────────────────────────────────────

    private ChatIntent maybeMessage(String botId, String msgKey, String[] pool, double probability, long nowMs) {
        if (!canChat(botId, nowMs) || rng.nextDouble() >= probability) return null;
        int variant = rng.nextInt(pool.length);
        return new ChatIntent(botId, "MESSAGE", pool[variant], msgKey, variant, thinkDelay());
    }

    private ChatIntent maybeReaction(String botId, String[] pool, double probability, SessionState state, long nowMs) {
        if (!isActive(botId, state) || !canChat(botId, nowMs) || rng.nextDouble() >= probability) return null;
        return new ChatIntent(botId, "REACTION", pick(pool), null, 0, thinkDelay());
    }

    /** A random surviving bot other than {@code excludeId} reacts with an emoji. */
    private ChatIntent maybeReactionFromOther(String excludeId, String[] pool, double probability, SessionState state, Set<String> botIds, long nowMs) {
        if (rng.nextDouble() >= probability) return null;
        String reactor = pickEligibleBot(excludeId, state, botIds, nowMs);
        if (reactor == null) return null;
        return new ChatIntent(reactor, "REACTION", pick(pool), null, 0, thinkDelay());
    }

    /** A random surviving bot other than {@code excludeId} comments — a message, or (30 %) an emoji reaction. */
    private ChatIntent maybeMessageFromOther(String excludeId, String msgKey, String[] msgPool, String[] reactPool, double probability, SessionState state, Set<String> botIds, long nowMs) {
        if (rng.nextDouble() >= probability) return null;
        String reactor = pickEligibleBot(excludeId, state, botIds, nowMs);
        if (reactor == null) return null;
        if (rng.nextDouble() < 0.30) {
            return new ChatIntent(reactor, "REACTION", pick(reactPool), null, 0, thinkDelay());
        }
        int variant = rng.nextInt(msgPool.length);
        return new ChatIntent(reactor, "MESSAGE", msgPool[variant], msgKey, variant, thinkDelay());
    }

    private String pickEligibleBot(String excludeId, SessionState state, Set<String> botIds, long nowMs) {
        List<String> eligible = new ArrayList<>();
        for (String id : botIds) {
            if (id.equals(excludeId)) continue;
            if (!isActive(id, state) || !canChat(id, nowMs)) continue;
            eligible.add(id);
        }
        if (eligible.isEmpty()) return null;
        return eligible.get(rng.nextInt(eligible.size()));
    }

    // ── Small utilities ─────────────────────────────────────────────────────────────────────

    private boolean canChat(String botId, long nowMs) {
        Long last = lastChatAtByBot.get(botId);
        return last == null || nowMs - last >= PER_BOT_COOLDOWN_MS;
    }

    /** A short, slightly random "typing" delay so lines land after the event's animation, staggered. */
    private long thinkDelay() {
        return 700L + rng.nextInt(1800);
    }

    private String pick(String[] pool) {
        return pool[rng.nextInt(pool.length)];
    }

    private static boolean isBot(String playerId, Set<String> botIds) {
        return playerId != null && botIds.contains(playerId);
    }

    private enum Standing { LEAD, TRAIL, LOW, MID }

    /** Classifies a bot's position by cash: worryingly low, clear leader, clear trailer, or mid-pack.
     *  Cash is a cheap proxy for standing that avoids computing full net worth every roll. */
    private static Standing cashStanding(String botId, SessionState state) {
        PlayerSnapshot me = find(botId, state);
        if (me == null) return Standing.MID;
        if (me.cash() < 150) return Standing.LOW;
        int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE, active = 0;
        for (PlayerSnapshot p : state.players()) {
            if (p.bankrupt() || p.eliminated()) continue;
            active++;
            max = Math.max(max, p.cash());
            min = Math.min(min, p.cash());
        }
        if (active < 2) return Standing.MID;
        if (me.cash() == max) return Standing.LEAD;
        if (me.cash() == min) return Standing.TRAIL;
        return Standing.MID;
    }

    private static boolean isActive(String playerId, SessionState state) {
        PlayerSnapshot p = find(playerId, state);
        return p != null && !p.bankrupt() && !p.eliminated();
    }

    private static PlayerSnapshot find(String playerId, SessionState state) {
        if (playerId == null) return null;
        for (PlayerSnapshot p : state.players()) if (p.playerId().equals(playerId)) return p;
        return null;
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
