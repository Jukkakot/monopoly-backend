package fi.monopoly.application.session.turn;

public interface TurnActionGateway {
    boolean rollDice();

    boolean endTurn();

    boolean buyBuildingRound(String propertyId);

    boolean sellBuildingRound(String propertyId);

    boolean toggleMortgage(String actorPlayerId, String propertyId);

    boolean useGetOutOfJailCard();

    boolean payJailFine();

    boolean acknowledgeCard();
}
