package fi.monopoly.domain.session;

/** Difficulty level for a bot seat in a game session. */
public enum BotDifficulty {
    /** Bot always makes the greedy-optimal decision. */
    NORMAL,
    /** Bot occasionally skips property purchases and auctions, behaving more randomly. */
    EASY,
    /** Bot makes greedy decisions AND proactively initiates property trades to complete color groups. */
    STRONG
}
