package fi.monopoly.application.session.turn;

public interface TurnActionGateway {
    boolean rollDice();

    boolean endTurn();

    boolean buyBuildingRound(String propertyId);

    boolean toggleMortgage(String propertyId);
}
