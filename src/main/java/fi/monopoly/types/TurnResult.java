package fi.monopoly.types;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class TurnResult {
    Object nextSpotCriteria;
    PathMode pathMode;
    boolean shouldGoToJail;

    public static TurnResult copyOf(TurnResult turnResult) {
        if (turnResult == null) {
            return null;
        }
        return TurnResult.builder().nextSpotCriteria(turnResult.nextSpotCriteria)
                .pathMode(turnResult.pathMode)
                .shouldGoToJail(turnResult.shouldGoToJail)
                .build();
    }
}
