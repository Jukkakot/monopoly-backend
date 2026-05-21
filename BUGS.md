# Known Bugs

## [FIXED] MOVE card targeting GO_TO_JAIL spot did not jail the player

**Root cause:** `applyLandingEffects` handled all CORNER spots with a generic advance-turn fallthrough.
The `GO_TO_JAIL` spot-type check only existed in `rollDice` (before calling `applyLandingEffects`),
so a card MOVE to GO_TO_JAIL would merely stand the player on spot 30 without jailing them.

**Fix:** Added explicit `landedSpot == SpotType.GO_TO_JAIL` check inside `applyLandingEffects`
so the guard applies regardless of how the player arrived (dice, MOVE card, MOVE_NEAREST card).

---

## [LIKELY NON-BUG] Bot GO_JAIL card appears to jail human player

**Reported:** User observed bot drawing a GO_JAIL card; human appeared to end up in jail.

**Analysis:**
- Backend: `CardType.GO_JAIL` → `applyGoToJail(state, playerId)` with correct botPlayerId. No wrong-player path found.
- Frontend animation: jail fly check is per-player-ID; only fires if `player.inJail` is true for that specific player.
- Most likely explanation: human was **visiting jail** (boardIndex 10, inJail: false) when the bot flew in,
  and the user mistook the bot token's jail-fly animation as affecting the human token at the same spot.

**No code fix needed** unless a concrete reproduction with logs (debug-level now enabled) proves otherwise.
