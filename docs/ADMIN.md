# Administration

`roguelike.play` defaults to everyone; `roguelike.admin` defaults to operators. Do not give normal players vanilla item/world/teleport administration or other plugins that bypass the inventory model.

| Command | Behaviour |
| --- | --- |
| `/roguelike debug` | Current epoch, balances, run counts and loaded worlds |
| `/roguelike npc create create\|rejoin\|rebirth\|upgrades` | Create at your location; prints UUID |
| `/roguelike npc move UUID` | Move to your location |
| `/roguelike npc remove UUID` | Delete registration and loaded NPC entity |
| `/roguelike economy add Player Amount` | Ledger adjustment; negative amount removes Coins |
| `/roguelike unlock Player ID` | Grant a configured unlock |
| `/roguelike world terminate Player` | End that online player's run without a reward |
| `/roguelike event start\|end ID` | Persist an event override |
| `/roguelike season` | Current calendar season |
| `/roguelike reset preview` | Preview totals and arm confirmation for 60 seconds |
| `/roguelike reset confirm` | Freeze gameplay, disconnect players, back up database and advance epoch |
| `/roguelike backup` | Online SQLite backup using `VACUUM INTO` |
| `/roguelike reload` | Validate main settings; restart required to apply changes |
| `roguelike selftest` (server console only) | Create/check/unload/delete a UUID test world |

Four default NPCs appear in the hub when the NPC registry is empty. NPCs use the supported villager API and persistent tags. Hub infrastructure is protected. Plots are decorative, and the palette intentionally excludes storage, machinery, explosives and liquids.

## Configuration

`config.yml`: timezone, database filename, world limits, reset calendar. `generation.yml`: terrain and ore rates, frozen into each new run. `unlocks.yml`: allowlist, prices, dependencies, categories and generation features. `economy.yml`: item liquidation values and rebirth basis-point bonus. `market.yml`: assets, liquidity, impact, fee, tick interval and bounded volatility. `rebirths.yml`: costs, minimum completed runs and what resets. `plots.yml`: decorative material palette. `events.yml`: dated event definitions and rewards. `achievements.yml`: metric thresholds and scopes. `trophies.yml`: trophy names/rarities for catalogue expansion.

All authoritative amounts are integer Coins. A basis point is 1/100 of a percent; `1000` means 10%. Do not change existing asset IDs/liquidity or unlock dependencies during a competitive year without a migration. Definitions are loaded at startup. Prices and permissions should be balanced on a whitelisted staging server before opening publicly.

## Recovery

Run settlement uses `settle:<run UUID>` as its unique ledger ID. A persisted player marker and retained death inventory allow a reconnect to retry a failed settlement. Never manually add a second payout to fix a pending settlement without inspecting the existing ledger. Restore/correct the failed storage condition and let the player reconnect.

The annual reset stages are `PREPARED`, `BACKED_UP`, and `COMPLETE`. Startup resumes an interrupted reset before enabling gameplay. Old epoch records remain for audit/history; current services filter by the active epoch. World cleanup is retried each minute after settlement/reset and refuses paths outside the exact managed UUID directory or any symbolic-link path.

If cleanup reports a refusal, inspect the logged actual world folder and Paper storage configuration; do not broaden the deletion guard to the server root. Do not delete a hub or plot directory. If a reset fails, transactions remain frozen until recovery succeeds.

Database backups alone do not capture world/player data. Use the deployment full-backup script for recovery points. Backups contain UUIDs, names and game history; limit access and decide your retention policy.

## Operational limits

This version does not implement physical seasonal asset expiry, a full permission node per individual command, every NPC/plot configuration action, or hot reload of all service definitions. The PostgreSQL implementation is future work. Do not use server `/reload` or plugin hot-unload utilities; stop/start Paper.
