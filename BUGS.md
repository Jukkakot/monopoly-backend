# Known Bugs

## [INVESTIGATE] Bot GO_JAIL card incorrectly jails human player

**Reported:** User observed bot drawing a Chance/Community Chest "Go to jail" card, but the human player ended up in jail instead.

**Backend code review:** `applyGoToJail(state, playerId)` is called with correct playerId throughout. No obvious wrong-player reference found.

**Possible causes to investigate:**
1. Frontend `useTokenAnimation.ts`: `_jailingPlayers`/`_animatingPlayers` are module-level sets — check if any race condition causes wrong player to get jail animation
2. Event log (`events.ts`): might show wrong player name if `np.inJail && !pp.inJail` fires unexpectedly
3. Race condition: two near-simultaneous state updates (bot jailed, then turn changes) arriving in wrong order on client SSE stream
4. `applyCardMove` to GO_TO_JAIL spot goes through CORNER handler (does NOT jail) — verify this case separately

**Steps to reproduce:** Play with a NORMAL/STRONG bot, wait for bot to draw a GO_JAIL card.
