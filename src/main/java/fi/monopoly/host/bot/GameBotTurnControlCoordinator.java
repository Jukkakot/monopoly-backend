package fi.monopoly.host.bot;

import fi.monopoly.types.DiceState;

/**
 * Derives which primary turn control should be available for a computer-controlled turn.
 *
 * <p>This logic used to live directly inside {@code Game}. Extracting it keeps the desktop shell
 * thinner and makes the bot control recovery rules explicit and unit-testable without the full
 * Processing runtime.</p>
 */
public final class GameBotTurnControlCoordinator {

    public BotPrimaryAction projectedAction(Hooks hooks, boolean playerPresent, boolean isComputer) {
        if (!playerPresent
                || !isComputer
                || hooks.popupVisible()
                || hooks.debtActive()
                || hooks.auctionOverrideActive()
                || hooks.tradeOverrideActive()
                || hooks.pendingDecisionOverrideActive()) {
            return BotPrimaryAction.NONE;
        }
        if (hooks.currentDiceState() == null || hooks.currentDiceState() == DiceState.DOUBLES) {
            return BotPrimaryAction.ROLL_DICE;
        }
        return BotPrimaryAction.END_TURN;
    }

    public boolean restoreControlsIfNeeded(Hooks hooks, boolean playerPresent, boolean isComputer) {
        if (!playerPresent || !isComputer) {
            return false;
        }
        if (hooks.gameOver()
                || hooks.popupVisible()
                || hooks.debtActive()
                || hooks.animationsRunning()
                || hooks.activeAuctionOpen()
                || hooks.activeTradeOpen()) {
            return false;
        }
        if (hooks.rollDiceActionAlreadyAvailable() || hooks.endTurnActionAlreadyAvailable()) {
            return false;
        }
        BotPrimaryAction projectedAction = projectedAction(hooks, true, true);
        if (projectedAction == BotPrimaryAction.ROLL_DICE) {
            hooks.showRollDiceControl();
            return true;
        }
        if (projectedAction == BotPrimaryAction.END_TURN) {
            hooks.showEndTurnControl();
            return true;
        }
        return false;
    }

    public interface Hooks {
        boolean gameOver();

        boolean popupVisible();

        boolean debtActive();

        boolean animationsRunning();

        boolean activeAuctionOpen();

        boolean activeTradeOpen();

        boolean auctionOverrideActive();

        boolean tradeOverrideActive();

        boolean pendingDecisionOverrideActive();

        DiceState currentDiceState();

        boolean rollDiceActionAlreadyAvailable();

        boolean endTurnActionAlreadyAvailable();

        void showRollDiceControl();

        void showEndTurnControl();
    }

    public enum BotPrimaryAction {
        NONE,
        ROLL_DICE,
        END_TURN
    }
}
