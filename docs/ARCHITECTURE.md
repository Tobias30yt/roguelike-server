# Roguelike architecture and implementation plan

Target: Paper 26.3, Java 25, Maven, Ubuntu ARM64. No NMS. Official references:
https://docs.papermc.io/paper/dev/project-setup/
https://docs.papermc.io/paper/getting-started/
https://jd.papermc.io/paper/26.3/org/bukkit/WorldCreator.html

## Ordered delivery gates
1. Maven modules, lifecycle, validated configuration, asynchronous SQLite migrations; compile and test.
2. Hub, four NPC roles, persistent run lifecycle, private generated worlds; compile and test.
3. Durable idempotent settlement, inventory valuation and scoped achievements; compile and test.
4. Unlock dependency graph, content restrictions and deterministic custom terrain; compile and test.
5. Exact-money ledger, bounded market and two-party trading; compile and test.
6. Rebirth, season scheduler and recoverable annual reset; compile and test.
7. Persistent protected plots, trophies and historical rankings; compile and test.
8. Inventory menus, player/admin commands, permissions and configuration; compile and test.
9. Integration checks, operational documentation, ARM64 deployment and systemd unit.

## Boundaries
`roguelike-core`: platform-independent domain rules, repositories and migrations.
`roguelike-paper`: Paper adapters, events, commands, inventory menus, world/entity operations.
Dependencies point from Paper to core. Constructors provide dependencies. No static service locator.

One bounded database executor owns the SQLite connection. SQL runs only there. Transactions use exact integer Coins and idempotency keys. Main-thread adapters capture immutable inputs before dispatch and apply results on the server thread. Player sessions block conflicting actions while a mutation is in flight. SQLite is the initial repository implementation; services depend on a transaction/repository interface so PostgreSQL can supply another implementation.

## Durability and isolation
Runs persist a unique ID, owner, competitive epoch, seed, frozen generation profile, inventory snapshot and lifecycle state. Settlement records its reward and completes the run in the same database transaction. Replays cannot credit twice. World cleanup happens afterwards and is retryable. Inventory context switches save the outgoing context before applying the incoming context. Login restores authoritative snapshots before allowing gameplay.

Managed run worlds use the `roguelike` namespace. Actual world folders are obtained from Paper and checked against the configured managed namespace directory; deletion never follows links. Hub/plot worlds never enter the cleanup queue. Chunk generation uses a custom generator and biome provider; it does not mutate vanilla registries.

The annual reset freezes transactions, backs up the database, archives standings, closes runs, advances the epoch and relocates players. Persistent reset stages resume after restart. Epoch-scoped records cannot be spent in a newer epoch. Plots, trophy ownership, historical achievements and lifetime statistics are stored separately.

Market assets are virtual, nonredeemable securities. This prevents physical redemption duplication. Physical player trades require durable escrow before confirmation. No real-money features.

## Validation policy
Each phase must compile before the next. Unit tests exercise transactional invariants and domain rules. Paper runtime tests are separate from core unit tests. A successful Maven build does not establish runtime or production readiness. Remaining requirements and runtime checks are recorded explicitly in README and the test guide.
