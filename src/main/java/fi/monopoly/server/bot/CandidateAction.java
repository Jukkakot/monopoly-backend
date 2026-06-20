package fi.monopoly.server.bot;

/**
 * A typed candidate action the utility model scores.
 *
 * <p>One {@code CandidateAction} is created per option the bot is considering.
 * A {@link Consideration} inspects both the action and the board state to return
 * a [0,1] utility score; the combiner multiplies all scores.</p>
 *
 * <p>Phase 3 covers property purchase decisions; later phases extend the set.</p>
 */
public sealed interface CandidateAction permits
        CandidateAction.BuyProperty,
        CandidateAction.DeclineProperty,
        CandidateAction.BuildHouses,
        CandidateAction.Unmortgage,
        CandidateAction.EndTurn,
        CandidateAction.AuctionBid,
        CandidateAction.AuctionPass {

    /** Buy a property at face price from the bank. */
    record BuyProperty(String propertyId, int price) implements CandidateAction {}

    /** Decline to buy — triggers an auction at the starting bid. */
    record DeclineProperty(String propertyId) implements CandidateAction {}

    /**
     * Build one house/hotel round on the property in {@code propertyId}'s color group.
     *
     * @param propertyId  the specific property to build on
     * @param buildCost   cost of one house for this group
     * @param currentMaxLevel maximum current house count in the group (0–5)
     */
    record BuildHouses(String propertyId, int buildCost, int currentMaxLevel) implements CandidateAction {}

    /** Unmortgage a mortgaged property the bot owns. */
    record Unmortgage(String propertyId) implements CandidateAction {}

    /** End the turn without taking a management action. Baseline for end-turn comparisons. */
    record EndTurn() implements CandidateAction {}

    /** Place a bid of {@code amount} in an active auction. */
    record AuctionBid(String propertyId, int amount) implements CandidateAction {}

    /** Pass this auction round without bidding. */
    record AuctionPass(String propertyId) implements CandidateAction {}
}
