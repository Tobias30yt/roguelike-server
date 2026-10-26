package dev.roguelike.core;

import dev.roguelike.achievement.AchievementService;
import dev.roguelike.command.GameCommands;
import dev.roguelike.config.Settings;
import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import dev.roguelike.gui.Menus;
import dev.roguelike.leaderboard.LeaderboardService;
import dev.roguelike.market.*;
import dev.roguelike.npc.NpcService;
import dev.roguelike.plot.PlotService;
import dev.roguelike.rebirth.RebirthService;
import dev.roguelike.reset.ResetService;
import dev.roguelike.run.*;
import dev.roguelike.season.*;
import dev.roguelike.trade.TradeService;
import dev.roguelike.unlock.*;
import dev.roguelike.world.WorldService;
import java.time.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class GameApplication {
  private final JavaPlugin plugin;
  private final SqliteDatabase db;
  private final Settings settings;
  private WorldService worlds;

  public GameApplication(JavaPlugin plugin, SqliteDatabase db, Settings settings) {
    this.plugin = plugin;
    this.db = db;
    this.settings = settings;
  }

  private YamlConfiguration config(String name) {
    java.io.File file = new java.io.File(plugin.getDataFolder(), name);
    if (!file.exists()) plugin.saveResource(name, false);
    return YamlConfiguration.loadConfiguration(file);
  }

  public void start() {
    Dispatch dispatch = new Dispatch(plugin);
    var runs = new RunRepository(db);
    worlds = new WorldService(plugin, settings, runs, db, dispatch);
    worlds.start();
    var menus = new Menus();
    var generation = config("generation.yml");
    worlds.generationSuffix(
        ";height="
            + generation.getInt("base-height")
            + ";amplitude="
            + generation.getInt("amplitude")
            + ";gap="
            + generation.getDouble("island-gap-threshold")
            + ";coal="
            + generation.getInt("coal-rarity")
            + ";iron="
            + generation.getInt("iron-rarity")
            + ";diamond="
            + generation.getInt("diamond-rarity"));
    var economyConfig = config("economy.yml");
    var values = new EnumMap<Material, Long>(Material.class);
    for (String key :
        Objects.requireNonNull(economyConfig.getConfigurationSection("values")).getKeys(false))
      values.put(Material.valueOf(key), economyConfig.getLong("values." + key));
    var money = new EconomyService(db, settings.maxBalance(), settings.timezone());
    var achievements = new AchievementService(db, money);
    var settlement =
        new SettlementService(db, money, economyConfig.getLong("rebirth-bonus-basis-points", 1000));
    var achievementRegistry =
        new dev.roguelike.achievement.AchievementRegistry(
            db, achievements, config("achievements.yml"));
    config("trophies.yml");
    var unlockConfig = config("unlocks.yml");
    var unlocks = new UnlockService(db, money, UnlockAdapter.load(unlockConfig));
    var restrictions =
        new UnlockAdapter(
            unlocks, dispatch, worlds, Set.copyOf(unlockConfig.getStringList("starter-materials")));
    var marketConfig = config("market.yml");
    var definitions = new ArrayList<AssetDefinition>();
    for (String id :
        Objects.requireNonNull(marketConfig.getConfigurationSection("assets")).getKeys(false)) {
      var c = marketConfig.getConfigurationSection("assets." + id);
      definitions.add(
          new AssetDefinition(
              id,
              c.getLong("price"),
              c.getLong("minimum"),
              c.getLong("maximum"),
              c.getInt("liquidity"),
              c.getInt("impact"),
              c.getLong("fee-basis-points")));
    }
    var market = new MarketService(db, money, definitions);
    market.initialize();
    var trades = new TradeService(db, money);
    trades.recover();
    int marketPeriod = marketConfig.getInt("tick-seconds", 60),
        volatility = marketConfig.getInt("volatility-basis-points", 100);
    if (marketPeriod < 10 || volatility < 0 || volatility > 500)
      throw new IllegalArgumentException("Invalid market timing/volatility");
    plugin
        .getServer()
        .getScheduler()
        .runTaskTimer(
            plugin,
            () ->
                market
                    .tick(volatility)
                    .exceptionally(
                        e -> {
                          plugin.getLogger().warning("Market tick failed: " + e.getMessage());
                          return null;
                        }),
            marketPeriod * 20L,
            marketPeriod * 20L);
    var rebirthConfig = config("rebirths.yml");
    var rebirth =
        new RebirthService(
            db,
            money,
            rebirthConfig.getLong("base-cost"),
            rebirthConfig.getLong("cost-per-level"),
            rebirthConfig.getLong("completed-runs-per-level"),
            rebirthConfig.getBoolean("reset-unlocks"),
            rebirthConfig.getBoolean("reset-balance"));
    var reset = new ResetService(db, plugin.getDataFolder().toPath().resolve("backups"));
    var ranks = new LeaderboardService(db, settings.timezone());
    var plots =
        new PlotService(
            db,
            dispatch,
            worlds,
            menus,
            config("plots.yml").getStringList("palette").stream().map(Material::valueOf).toList());
    plots.load();
    new dev.roguelike.leaderboard.HubLeaderboard(plugin, worlds.hub(), ranks, dispatch);
    var events =
        new EventScheduler(
            plugin, settings.timezone(), db, dispatch, achievements, worlds, config("events.yml"));
    var commands =
        new GameCommands(
            plugin,
            db,
            dispatch,
            menus,
            worlds,
            runs,
            money,
            unlocks,
            restrictions,
            market,
            trades,
            rebirth,
            reset,
            plots,
            ranks,
            events);
    commands.itemTrades(
        new dev.roguelike.trade.ItemTradeAdapter(trades, worlds, restrictions, dispatch));
    var npcs = new NpcService(plugin, db, dispatch, commands::role);
    commands.npcs(npcs);
    npcs.defaults();
    plots.permissions();
    var death =
        new DeathListener(worlds, settlement, runs, new InventoryValuation(values), dispatch);
    commands.completeRun(death::complete);
    worlds.deathRecovery(death::recover);
    death.onCompleted(
        id -> {
          events.runCompleted(id);
          achievementRegistry
              .evaluate(id)
              .exceptionally(
                  e -> {
                    plugin
                        .getLogger()
                        .warning("Achievement evaluation deferred: " + e.getMessage());
                    return null;
                  });
        });
    for (Listener listener :
        List.of(menus, new SessionListener(worlds), restrictions, plots, events, npcs, death))
      plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    plugin
        .getServer()
        .getPluginManager()
        .registerEvents(
            new Listener() {
              @EventHandler
              public void quit(PlayerQuitEvent e) {
                trades.cancel(e.getPlayer().getUniqueId());
              }
            },
            plugin);
    for (String command :
        List.of(
            "hub",
            "world",
            "upgrades",
            "unlocks",
            "rebirth",
            "balance",
            "pay",
            "market",
            "trade",
            "plot",
            "trophies",
            "stats",
            "leaderboard",
            "season",
            "roguelike")) {
      var c = Objects.requireNonNull(plugin.getCommand(command));
      c.setExecutor(commands);
      c.setTabCompleter(commands);
    }
    for (var p : Bukkit.getOnlinePlayers()) {
      worlds.login(p);
      restrictions.refresh(p);
    }
    int month = plugin.getConfig().getInt("reset.month", 1),
        day = plugin.getConfig().getInt("reset.day", 1);
    java.time.MonthDay.of(month, day);
    if (plugin.getConfig().getBoolean("reset.automatic", true))
      plugin
          .getServer()
          .getScheduler()
          .runTaskTimer(
              plugin,
              () -> {
                int cycle = Seasons.cycle(ZonedDateTime.now(settings.timezone()), month, day);
                db.transaction(
                        c -> {
                          String previous =
                              Sql.string(
                                  c, "SELECT value FROM settings WHERE key='calendar-cycle'");
                          if (previous == null) {
                            Sql.update(
                                c,
                                "INSERT INTO settings VALUES('calendar-cycle',?)",
                                Integer.toString(cycle));
                            return false;
                          }
                          return Integer.parseInt(previous) < cycle;
                        })
                    .thenAccept(
                        due -> {
                          if (due) dispatch.main(() -> commands.performReset("annual-" + cycle));
                        });
              },
              20,
              1200);
    plugin.getServer().getScheduler().runTaskTimer(plugin, worlds::cleanup, 1200, 1200);
  }

  public void close() {
    if (worlds != null) worlds.close();
  }
}
