package fi.monopoly.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import fi.monopoly.application.command.*;
import fi.monopoly.domain.session.TradeEditPatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionCommandSerializerTest {

    private SessionCommandSerializer serializer;
    private SessionCommandMapper mapper;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper();
        serializer = new SessionCommandSerializer(om);
        mapper = new SessionCommandMapper(om);
    }

    @Test
    void rollDiceRoundTrip() throws Exception {
        var original = new RollDiceCommand("s1", "p1");
        var restored = mapper.fromJson(serializer.toJson(original).getBytes());
        assertEquals(original, restored);
    }

    @Test
    void endTurnRoundTrip() throws Exception {
        assertEquals(new EndTurnCommand("s1", "p1"),
                mapper.fromJson(serializer.toJson(new EndTurnCommand("s1", "p1")).getBytes()));
    }

    @Test
    void buyPropertyRoundTrip() throws Exception {
        var original = new BuyPropertyCommand("s1", "p1", "d1", "MAYFAIR");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void declinePropertyRoundTrip() throws Exception {
        var original = new DeclinePropertyCommand("s1", "p1", "d1", "MAYFAIR");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void payDebtRoundTrip() throws Exception {
        var original = new PayDebtCommand("s1", "p1", "debt1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void mortgagePropertyForDebtRoundTrip() throws Exception {
        var original = new MortgagePropertyForDebtCommand("s1", "p1", "d1", "PROP1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void sellBuildingForDebtRoundTrip() throws Exception {
        var original = new SellBuildingForDebtCommand("s1", "p1", "d1", "PROP1", 2);
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void declareBankruptcyRoundTrip() throws Exception {
        var original = new DeclareBankruptcyCommand("s1", "p1", "d1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void sellBuildingRoundsAcrossSetForDebtRoundTrip() throws Exception {
        var original = new SellBuildingRoundsAcrossSetForDebtCommand("s1", "p1", "d1", "PROP1", 3);
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void passAuctionRoundTrip() throws Exception {
        var original = new PassAuctionCommand("s1", "p1", "a1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void placeAuctionBidRoundTrip() throws Exception {
        var original = new PlaceAuctionBidCommand("s1", "p1", "a1", 500);
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void finishAuctionResolutionRoundTrip() throws Exception {
        var original = new FinishAuctionResolutionCommand("s1", "a1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void acceptTradeRoundTrip() throws Exception {
        var original = new AcceptTradeCommand("s1", "p1", "t1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void editTradeOfferRoundTrip() throws Exception {
        var patch = new TradeEditPatch(true, false, 200, List.of("P1"), List.of(), null);
        var original = new EditTradeOfferCommand("s1", "p1", "t1", patch);
        var restored = (EditTradeOfferCommand) mapper.fromJson(serializer.toJson(original).getBytes());
        assertEquals(original.sessionId(), restored.sessionId());
        assertEquals(original.tradeId(), restored.tradeId());
        assertEquals(original.patch().replaceMoneyAmount(), restored.patch().replaceMoneyAmount());
        assertEquals(original.patch().propertyIdsToAdd(), restored.patch().propertyIdsToAdd());
    }

    @Test
    void openTradeRoundTrip() throws Exception {
        var original = new OpenTradeCommand("s1", "p1", "p2");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void submitTradeOfferRoundTrip() throws Exception {
        var original = new SubmitTradeOfferCommand("s1", "p1", "t1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void cancelTradeRoundTrip() throws Exception {
        var original = new CancelTradeCommand("s1", "p1", "t1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void counterTradeRoundTrip() throws Exception {
        var original = new CounterTradeCommand("s1", "p1", "t1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void declineTradeRoundTrip() throws Exception {
        var original = new DeclineTradeCommand("s1", "p1", "t1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void buyBuildingRoundRoundTrip() throws Exception {
        var original = new BuyBuildingRoundCommand("s1", "p1", "PROP1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void toggleMortgageRoundTrip() throws Exception {
        var original = new ToggleMortgageCommand("s1", "p1", "PROP1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }

    @Test
    void refreshSessionViewRoundTrip() throws Exception {
        var original = new RefreshSessionViewCommand("s1");
        assertEquals(original, mapper.fromJson(serializer.toJson(original).getBytes()));
    }
}
