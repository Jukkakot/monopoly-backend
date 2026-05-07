package fi.monopoly.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import fi.monopoly.application.command.*;
import fi.monopoly.domain.session.TradeEditPatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionCommandMapperTest {

    private SessionCommandMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new SessionCommandMapper(new ObjectMapper());
    }

    @Test
    void rollDice() throws IOException {
        var cmd = (RollDiceCommand) mapper.fromJson("""
                {"type":"RollDice","sessionId":"s1","actorPlayerId":"p1"}
                """.getBytes());
        assertEquals("s1", cmd.sessionId());
        assertEquals("p1", cmd.actorPlayerId());
    }

    @Test
    void endTurn() throws IOException {
        var cmd = (EndTurnCommand) mapper.fromJson("""
                {"type":"EndTurn","sessionId":"s1","actorPlayerId":"p1"}
                """.getBytes());
        assertEquals("s1", cmd.sessionId());
        assertEquals("p1", cmd.actorPlayerId());
    }

    @Test
    void buyProperty() throws IOException {
        var cmd = (BuyPropertyCommand) mapper.fromJson("""
                {"type":"BuyProperty","sessionId":"s1","actorPlayerId":"p1","decisionId":"d1","propertyId":"MAYFAIR"}
                """.getBytes());
        assertEquals("d1", cmd.decisionId());
        assertEquals("MAYFAIR", cmd.propertyId());
    }

    @Test
    void declineProperty() throws IOException {
        var cmd = (DeclinePropertyCommand) mapper.fromJson("""
                {"type":"DeclineProperty","sessionId":"s1","actorPlayerId":"p1","decisionId":"d1","propertyId":"MAYFAIR"}
                """.getBytes());
        assertEquals("d1", cmd.decisionId());
        assertEquals("MAYFAIR", cmd.propertyId());
    }

    @Test
    void payDebt() throws IOException {
        var cmd = (PayDebtCommand) mapper.fromJson("""
                {"type":"PayDebt","sessionId":"s1","actorPlayerId":"p1","debtId":"debt1"}
                """.getBytes());
        assertEquals("debt1", cmd.debtId());
    }

    @Test
    void mortgagePropertyForDebt() throws IOException {
        var cmd = (MortgagePropertyForDebtCommand) mapper.fromJson("""
                {"type":"MortgagePropertyForDebt","sessionId":"s1","actorPlayerId":"p1","debtId":"d1","propertyId":"PROP1"}
                """.getBytes());
        assertEquals("d1", cmd.debtId());
        assertEquals("PROP1", cmd.propertyId());
    }

    @Test
    void sellBuildingForDebt() throws IOException {
        var cmd = (SellBuildingForDebtCommand) mapper.fromJson("""
                {"type":"SellBuildingForDebt","sessionId":"s1","actorPlayerId":"p1","debtId":"d1","propertyId":"PROP1","count":2}
                """.getBytes());
        assertEquals(2, cmd.count());
    }

    @Test
    void declareBankruptcy() throws IOException {
        var cmd = (DeclareBankruptcyCommand) mapper.fromJson("""
                {"type":"DeclareBankruptcy","sessionId":"s1","actorPlayerId":"p1","debtId":"d1"}
                """.getBytes());
        assertEquals("d1", cmd.debtId());
    }

    @Test
    void sellBuildingRoundsAcrossSetForDebt() throws IOException {
        var cmd = (SellBuildingRoundsAcrossSetForDebtCommand) mapper.fromJson("""
                {"type":"SellBuildingRoundsAcrossSetForDebt","sessionId":"s1","actorPlayerId":"p1","debtId":"d1","propertyId":"PROP1","rounds":3}
                """.getBytes());
        assertEquals(3, cmd.rounds());
    }

    @Test
    void passAuction() throws IOException {
        var cmd = (PassAuctionCommand) mapper.fromJson("""
                {"type":"PassAuction","sessionId":"s1","actorPlayerId":"p1","auctionId":"a1"}
                """.getBytes());
        assertEquals("a1", cmd.auctionId());
    }

    @Test
    void placeAuctionBid() throws IOException {
        var cmd = (PlaceAuctionBidCommand) mapper.fromJson("""
                {"type":"PlaceAuctionBid","sessionId":"s1","actorPlayerId":"p1","auctionId":"a1","amount":500}
                """.getBytes());
        assertEquals(500, cmd.amount());
    }

    @Test
    void finishAuctionResolution() throws IOException {
        var cmd = (FinishAuctionResolutionCommand) mapper.fromJson("""
                {"type":"FinishAuctionResolution","sessionId":"s1","auctionId":"a1"}
                """.getBytes());
        assertEquals("s1", cmd.sessionId());
        assertEquals("a1", cmd.auctionId());
    }

    @Test
    void acceptTrade() throws IOException {
        var cmd = (AcceptTradeCommand) mapper.fromJson("""
                {"type":"AcceptTrade","sessionId":"s1","actorPlayerId":"p1","tradeId":"t1"}
                """.getBytes());
        assertEquals("t1", cmd.tradeId());
    }

    @Test
    void editTradeOffer() throws IOException {
        var cmd = (EditTradeOfferCommand) mapper.fromJson("""
                {"type":"EditTradeOffer","sessionId":"s1","actorPlayerId":"p1","tradeId":"t1",
                 "patch":{"reversePerspective":true,"offeredSide":false,"replaceMoneyAmount":200,
                          "propertyIdsToAdd":["P1"],"propertyIdsToRemove":[],"toggleJailCard":null}}
                """.getBytes());
        TradeEditPatch patch = cmd.patch();
        assertTrue(patch.reversePerspective());
        assertEquals(200, patch.replaceMoneyAmount());
        assertEquals(List.of("P1"), patch.propertyIdsToAdd());
    }

    @Test
    void openTrade() throws IOException {
        var cmd = (OpenTradeCommand) mapper.fromJson("""
                {"type":"OpenTrade","sessionId":"s1","actorPlayerId":"p1","recipientPlayerId":"p2"}
                """.getBytes());
        assertEquals("p2", cmd.recipientPlayerId());
    }

    @Test
    void submitTradeOffer() throws IOException {
        var cmd = (SubmitTradeOfferCommand) mapper.fromJson("""
                {"type":"SubmitTradeOffer","sessionId":"s1","actorPlayerId":"p1","tradeId":"t1"}
                """.getBytes());
        assertEquals("t1", cmd.tradeId());
    }

    @Test
    void cancelTrade() throws IOException {
        var cmd = (CancelTradeCommand) mapper.fromJson("""
                {"type":"CancelTrade","sessionId":"s1","actorPlayerId":"p1","tradeId":"t1"}
                """.getBytes());
        assertEquals("t1", cmd.tradeId());
    }

    @Test
    void counterTrade() throws IOException {
        var cmd = (CounterTradeCommand) mapper.fromJson("""
                {"type":"CounterTrade","sessionId":"s1","actorPlayerId":"p1","tradeId":"t1"}
                """.getBytes());
        assertEquals("t1", cmd.tradeId());
    }

    @Test
    void declineTrade() throws IOException {
        var cmd = (DeclineTradeCommand) mapper.fromJson("""
                {"type":"DeclineTrade","sessionId":"s1","actorPlayerId":"p1","tradeId":"t1"}
                """.getBytes());
        assertEquals("t1", cmd.tradeId());
    }

    @Test
    void buyBuildingRound() throws IOException {
        var cmd = (BuyBuildingRoundCommand) mapper.fromJson("""
                {"type":"BuyBuildingRound","sessionId":"s1","actorPlayerId":"p1","propertyId":"PROP1"}
                """.getBytes());
        assertEquals("PROP1", cmd.propertyId());
    }

    @Test
    void toggleMortgage() throws IOException {
        var cmd = (ToggleMortgageCommand) mapper.fromJson("""
                {"type":"ToggleMortgage","sessionId":"s1","actorPlayerId":"p1","propertyId":"PROP1"}
                """.getBytes());
        assertEquals("PROP1", cmd.propertyId());
    }

    @Test
    void refreshSessionView() throws IOException {
        var cmd = (RefreshSessionViewCommand) mapper.fromJson("""
                {"type":"RefreshSessionView","sessionId":"s1"}
                """.getBytes());
        assertEquals("s1", cmd.sessionId());
    }

    @Test
    void unknownTypeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                mapper.fromJson("{\"type\":\"UnknownCommand\",\"sessionId\":\"s1\"}".getBytes()));
    }
}
