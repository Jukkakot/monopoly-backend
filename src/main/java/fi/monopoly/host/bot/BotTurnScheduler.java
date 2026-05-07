package fi.monopoly.host.bot;

import lombok.RequiredArgsConstructor;

import java.util.function.BooleanSupplier;

@RequiredArgsConstructor
public final class BotTurnScheduler {
    private static final int NO_ACTION_READY = -1;

    private final BooleanSupplier skipAnimations;
    private int nextReadyAt = NO_ACTION_READY;

    public boolean isWaiting(int now) {
        return !skipAnimations.getAsBoolean() && nextReadyAt != NO_ACTION_READY && now < nextReadyAt;
    }

    public void schedule(DelayKind delayKind, int now, SpeedMode speedMode, boolean allPlayersComputerControlled) {
        nextReadyAt = now + computeDelayMs(delayKind, speedMode, allPlayersComputerControlled);
    }

    public void markReadyNow(int now) {
        nextReadyAt = now;
    }

    public void applyAnimationFinishCooldownIfNeeded(
            boolean animationWasRunning,
            boolean animationsStillRunning,
            boolean currentTurnComputerControlled,
            int now,
            SpeedMode speedMode,
            boolean allPlayersComputerControlled
    ) {
        if (skipAnimations.getAsBoolean() || !animationWasRunning || animationsStillRunning || !currentTurnComputerControlled) {
            return;
        }
        schedule(DelayKind.ANIMATION_FINISH, now, speedMode, allPlayersComputerControlled);
    }

    private int computeDelayMs(DelayKind delayKind, SpeedMode speedMode, boolean allPlayersComputerControlled) {
        if (skipAnimations.getAsBoolean() || speedMode == SpeedMode.INSTANT) {
            return 0;
        }
        int baseDelayMs = switch (delayKind) {
            case ANIMATION_FINISH -> 260;
            case RESOLVE_POPUP, ACCEPT_POPUP, DECLINE_POPUP -> 220;
            case ROLL_DICE -> 240;
            case END_TURN -> 150;
            case BUILD_ROUND -> 700;
            case SELL_BUILDING -> 520;
            case MORTGAGE_PROPERTY, UNMORTGAGE_PROPERTY -> 480;
            case TRADE -> 850;
            case AUCTION_ACTION -> 260;
            case RETRY_DEBT_PAYMENT, DECLARE_BANKRUPTCY -> 650;
        };
        float multiplier = speedMode.delayMultiplier();
        if (allPlayersComputerControlled) {
            multiplier *= 0.7f;
        }
        int jitter = (int) ((Math.random() * 120) - 60);
        return Math.max(0, Math.round(baseDelayMs * multiplier) + jitter);
    }

    public enum DelayKind {
        ANIMATION_FINISH,
        RESOLVE_POPUP,
        ACCEPT_POPUP,
        DECLINE_POPUP,
        ROLL_DICE,
        END_TURN,
        BUILD_ROUND,
        SELL_BUILDING,
        MORTGAGE_PROPERTY,
        UNMORTGAGE_PROPERTY,
        TRADE,
        AUCTION_ACTION,
        RETRY_DEBT_PAYMENT,
        DECLARE_BANKRUPTCY
    }

    public enum SpeedMode {
        NORMAL("game.button.botSpeed.normal", 3.0f),
        FAST("game.button.botSpeed.fast", 1.0f),
        INSTANT("game.button.botSpeed.instant", 0.0f);

        private final String labelKey;
        private final float delayMultiplier;

        SpeedMode(String labelKey, float delayMultiplier) {
            this.labelKey = labelKey;
            this.delayMultiplier = delayMultiplier;
        }

        public String labelKey() {
            return labelKey;
        }

        public float delayMultiplier() {
            return delayMultiplier;
        }

        public SpeedMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }
}
