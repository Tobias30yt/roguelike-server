# Validation and acceptance

## Automated tests

Run `mvn clean package` with Java 25. JUnit 5 tests execute against actual temporary SQLite databases, not mocked repositories. Coverage includes exact money/overflow, transaction rollback, duplicate credits/payments/settlement, ownership and one-active-run enforcement, database reopen, path containment, unlock dependency validation/purchases, rebirth reset rules, market round-trip pricing, atomic trading, stale confirmations, escrow restart recovery, single-use deliveries, annual archival/trophy retention, reset crash recovery and date boundaries.

Test XML and text reports are under `roguelike-core/target/surefire-reports/`. The Paper module has a separate runtime smoke command because real item serialization, world creation and entities require a running server.

## Paper smoke test

Use a disposable or whitelisted Paper 26.3 build 223 server with Java 25. From the server console:

```text
roguelike selftest
```

It creates a fresh `roguelike:run/<UUID>` dimension with a fixed seed, checks a generated grass surface, serializes/deserializes items, checks armor/offhand valuation and trophy exclusion, creates/removes a villager, unloads the world and verifies safe managed deletion. It does not modify player accounts or existing runs. Inspect `SMOKE ... PASS` and the absence of `SMOKE failed` messages.

The local server startup and initial smoke tests were executed during development. Windows OSHI performance-counter warnings are host diagnostics, not Roguelike plugin errors. A repeated test against the final JAR is recorded in `docs/evidence/` when available.

## Required two-client acceptance (not yet executed)

1. Join two real accounts. Verify hub location, all four NPC actions, menu pagination, shift/number-key/offhand/double clicks and dragging. No menu item may enter a player inventory.
2. Create a run with no perks, with each perk and with both. Reject a second active world. Reject another player's teleport into that dimension. Confirm configured loaded-world cap.
3. Alter terrain and put distinguishable items in all main/armor/offhand slots. Set health, food, experience and potion effects. Leave, rejoin, disconnect and restart; verify each context and world changes.
4. Attempt locked crafting, smithing, anvils, villager trades, container extraction, item pickup, equipment dispensers, bow use and third-party command paths. Do not lose legitimate items.
5. Die with known item values. Confirm one payout, no run item drops, hub restoration, cleared advancements and eventual world deletion. Respawn both before and after the asynchronous commit. Repeat using `/world complete` and verify no death count.
6. Kill the **test** server at every context transition: before/after durable entry, before/after player save, during death settlement, before/after item escrow commit, before/after delivery, and at each reset stage. Reconnect and inspect database/player/world consistency and exact payout counts.
7. Trade Coins, assets and physical items. Change an offer after one player confirms; an old menu confirmation must fail. Disconnect either side. Claim with full inventory and with missing unlocks. Repeated claims must not duplicate items.
8. Execute buys/sells, round trips, bounds, invalid quantities, limit orders and no-funds orders. Verify fees, holdings and ledger sums. Test rebirth with and without an active run and with stale confirmation.
9. Build on both plots, cross borders, use buckets/pistons/explosions, trust/untrust, disable visitors, place trophies and restart. Confirm retained buildings and owned trophy count after a reset.
10. Run reset preview/confirm with online/offline players, open trades, pending deliveries, open market orders and loaded/unloaded worlds. Retry the same reset and restart midway. Old assets must not become usable; historical records must remain.
11. Exercise DST transitions, season boundaries, event overrides, new-year downtime and custom reset dates in a disposable configuration. Confirm that seasonal changes do not reset accounts.
12. Repeat on the Oracle ARM64 VM, verify SQLite native loading, measure heap/TPS/chunk generation with the planned player count, and restore a full backup into a second test directory.

Do not mark these manual cases passed based solely on unit-test results. The project remains a development build until this acceptance work and the README's outstanding master-spec features are complete.
