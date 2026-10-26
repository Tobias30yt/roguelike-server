# Roguelike

Modular Minecraft Paper plugin: Java **25**, Paper **26.3 build 223**, Maven, SQLite. This repository contains a working development build, not a claim that every requirement in the original master specification has passed production acceptance.

## Ubuntu-Schnellinstallation

[Release v0.1.0 herunterladen](https://github.com/Tobias30yt/roguelike-server/releases/tag/v0.1.0). Für einen neuen Ubuntu-Server (ARM64 oder x86_64):

```bash
curl -fL --proto '=https' --proto-redir '=https' https://github.com/Tobias30yt/roguelike-server/releases/download/v0.1.0/install.sh -o install.sh
sudo bash install.sh --accept-eula --start
```

`--accept-eula` bestätigt die [Minecraft-EULA](https://aka.ms/MinecraftEULA). Java, Paper, Plugin und systemd-Dienst werden installiert; Download-Prüfsummen werden geprüft. Die Whitelist ist aktiv. Folge der [deutschen Startanleitung](docs/START-HIER.md), um deinen Minecraft-Namen freizugeben. Der Installer verändert keine Firewall-Regeln und überschreibt keine bestehenden Server.

Für spätere Veröffentlichungen: `./scripts/publish-release.ps1` baut, testet und veröffentlicht ein neues GitHub-Prerelease. Version und Release-Notizen zuerst aktualisieren. Eine optionale GitHub-Actions-Vorlage liegt unter `docs/ci/build.yml`; im Repository ist noch kein Workflow aktiviert.

## Build

```bash
mvn clean package
```

Use JDK 25 and Maven 3.9+. The output is `roguelike-paper/target/Roguelike-0.1.0.jar`. SQLite is bundled; Paper is provided by the server. No Citizens, Vault, NMS or Gradle dependency is required. On this development machine, `scripts/build.ps1` selects the locally downloaded JDK 25 when available.

Verified upstream sources: [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/), [Java requirements](https://docs.papermc.io/paper/getting-started/), [26.3 WorldCreator API](https://jd.papermc.io/paper/26.3/org/bukkit/WorldCreator.html). The exact Paper API artifact was downloaded and compiled, rather than inferred from an old snapshot version convention.

## Implemented gameplay

- Persistent hub and four interactive villager NPC roles, with operator creation, movement and removal.
- One private run per player, seeded island generation, frozen resource/biome/ruin profiles, configurable world limits, rejoin/leave and voluntary completion.
- Separate hub/run inventory contexts, health/food/experience/effect restoration, death settlement with an idempotent ledger key and persisted player recovery marker.
- Configurable unlock prices and dependency graphs, immediate content restrictions, generation unlocks for future runs and optional starting perks.
- Exact integer Coins, atomic payments, transaction history, bounded supply-curve markets, fees, portfolios, price history and queued buy/sell limit orders.
- Two-party Coins/asset/item trading. Offers invalidate confirmations; physical items use database escrow and a one-time delivery mailbox.
- Configurable rebirth costs and reset categories; seasonal calendar and dated events; recoverable annual reset with a database backup and historical records.
- Persistent 16×16 decorative plots, building/visitor permissions, permanent trophy ownership/displays, scoped achievements, statistics and period leaderboards.
- Inventory menus with pagination and confirmation; player commands, operator commands and tab completion.
- Three SQLite migrations, automated domain/storage tests, Paper runtime smoke command, Ubuntu ARM64 installer, systemd unit and full-backup script.

## What is not yet a production-complete master-spec implementation

The default content catalogue is deliberately small. It does **not** unlock every vanilla item/recipe or provide arbitrary vanilla structures/registries. Netherite processing can be unlocked but ancient debris does not yet generate in the custom world. Detailed per-enchantment levels, a full ability system, upgrade levels and seasonal physical item/asset expiration rules remain to be implemented. Seasonal rewards currently use virtual Coins and permanent trophy IDs.

The market is an issuer liquidity pool, not a player order-matching exchange. Limit orders are checked on each market tick and do not reserve funds; unfunded/illiquid orders are rejected. There is transaction history but no rendered price-chart GUI or trading-profit leaderboard. Plot building uses a controlled decorative palette; container storage and unrestricted survival building in the hub are outside this version. Trophy rarity definitions are provided, but the current trophy menu displays IDs rather than all catalogue metadata.

Configuration changes require a restart; `/roguelike reload` validates the main settings and explains this. Full message localization, all operator configuration subcommands, a PostgreSQL dialect/repository implementation and an automated Minecraft-client integration harness remain open. The JDBC execution boundary exists, but PostgreSQL is not currently supported.

Paper startup, generation, NPC creation, item serialization and managed deletion can be tested locally without a player. Actual two-client gameplay, injected crashes at inventory transitions, ARM64 deployment and load/performance acceptance must still be completed. See [TESTING.md](docs/TESTING.md). Do not treat a passing Maven build as proof of exploit-free public-server readiness.

## Documentation

- [Deutsche VM-Startanleitung](docs/START-HIER.md)
- [Player guide](docs/PLAYER.md)
- [Administrator guide](docs/ADMIN.md)
- [Oracle Ubuntu deployment](docs/DEPLOYMENT.md)
- [Architecture and phase plan](docs/ARCHITECTURE.md)
- [Tests and acceptance checklist](docs/TESTING.md)

The SQLite data file, plugin configuration and backups live in `plugins/Roguelike/`. Paper 26.3 run dimensions live under the `roguelike:run/<UUID>` key. Hub/plot data is never included in the run-deletion queue. Use a dedicated server: the plugin controls player inventories and advancements and is not intended to coexist with another inventory/world-management plugin without compatibility testing.
