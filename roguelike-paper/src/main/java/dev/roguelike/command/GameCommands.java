package dev.roguelike.command;

import dev.roguelike.core.*;
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
import dev.roguelike.season.EventScheduler;
import dev.roguelike.trade.TradeService;
import dev.roguelike.unlock.*;
import dev.roguelike.world.WorldService;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class GameCommands implements CommandExecutor, TabCompleter {
  private final JavaPlugin plugin;
  private final SqliteDatabase db;
  private final Dispatch dispatch;
  private final Menus menus;
  private final WorldService worlds;
  private final RunRepository runs;
  private final EconomyService money;
  private final UnlockService unlocks;
  private final UnlockAdapter restrictions;
  private final MarketService market;
  private final TradeService trades;
  private final RebirthService rebirth;
  private final ResetService reset;
  private final PlotService plots;
  private final LeaderboardService ranks;
  private final EventScheduler events;
  private final AdminCommands administration;
  private final GameMenus ui;

  private dev.roguelike.trade.ItemTradeAdapter itemTrades;
  private java.util.function.Consumer<Player> completeRun;

  public void completeRun(java.util.function.Consumer<Player> handler) {
    completeRun = handler;
  }

  public void itemTrades(dev.roguelike.trade.ItemTradeAdapter adapter) {
    itemTrades = adapter;
    ui.itemTrades(adapter);
  }

  public GameCommands(
      JavaPlugin plugin,
      SqliteDatabase db,
      Dispatch dispatch,
      Menus menus,
      WorldService worlds,
      RunRepository runs,
      EconomyService money,
      UnlockService unlocks,
      UnlockAdapter restrictions,
      MarketService market,
      TradeService trades,
      RebirthService rebirth,
      ResetService reset,
      PlotService plots,
      LeaderboardService ranks,
      EventScheduler events) {
    this.plugin = plugin;
    this.db = db;
    this.dispatch = dispatch;
    this.menus = menus;
    this.worlds = worlds;
    this.runs = runs;
    this.money = money;
    this.unlocks = unlocks;
    this.restrictions = restrictions;
    this.market = market;
    this.trades = trades;
    this.rebirth = rebirth;
    this.reset = reset;
    this.plots = plots;
    this.ranks = ranks;
    this.events = events;
    administration =
        new AdminCommands(
            plugin,
            db,
            dispatch,
            worlds,
            runs,
            money,
            unlocks,
            restrictions,
            market,
            reset,
            events);
    ui = new GameMenus(dispatch, menus, worlds, unlocks, restrictions, market, trades, rebirth);
  }

  public void npcs(NpcService npcs) {
    administration.npcs(npcs);
  }

  public void role(Player p, String role) {
    ui.role(p, role);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player p)) {
      if (command.getName().equals("roguelike")
          && args.length == 1
          && args[0].equals("selftest")
          && sender instanceof ConsoleCommandSender) {
        try {
          PaperSmokeTest.run(plugin);
        } catch (Exception e) {
          plugin.getLogger().log(java.util.logging.Level.SEVERE, "SMOKE failed", e);
        }
        return true;
      }
      sender.sendMessage("Diesen Befehl im Spiel verwenden. Konsole: /roguelike selftest");
      return true;
    }
    if (!p.hasPermission(
        command.getName().equals("roguelike") ? "roguelike.admin" : "roguelike.play")) {
      p.sendMessage("Keine Berechtigung.");
      return true;
    }
    if (worlds.locked(p)) {
      p.sendMessage("Spielerzustand wird gespeichert. Bitte kurz warten.");
      return true;
    }
    try {
      String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
      switch (command.getName()) {
        case "hub" -> worlds.leave(p);
        case "world" -> {
          switch (sub) {
            case "create" -> role(p, "create");
            case "rejoin" -> worlds.rejoin(p);
            case "leave" -> worlds.leave(p);
            case "complete" ->
                menus.confirm(p, "Run abschliessen und verwerten?", () -> completeRun.accept(p));
            default ->
                dispatch.result(
                    p,
                    runs.active(p.getUniqueId()),
                    r ->
                        p.sendMessage(
                            r == null
                                ? "Kein aktiver Run"
                                : r.id() + " | " + r.state() + " | Seed " + r.seed()));
          }
        }
        case "upgrades", "unlocks" -> ui.unlocks(p);
        case "rebirth" -> ui.rebirth(p);
        case "balance" ->
            dispatch.result(p, money.balance(p.getUniqueId()), n -> p.sendMessage(n + " Coins"));
        case "pay" -> {
          require(args, 2, "/pay <Spieler> <Coins>");
          Player other = player(args[0]);
          dispatch.result(
              p,
              money.transfer(
                  p.getUniqueId(),
                  other.getUniqueId(),
                  Money.positive(args[1]),
                  UUID.randomUUID().toString()),
              v -> p.sendMessage("Ueberweisung abgeschlossen."));
        }
        case "market" -> {
          if (sub.equals("orders"))
            dispatch.result(
                p, market.orders(p.getUniqueId()), rows -> ui.showRows(p, "Marktauftraege", rows));
          else if (sub.equals("cancel")) {
            require(args, 2, "/market cancel <ID>");
            dispatch.result(
                p,
                market.cancelOrder(p.getUniqueId(), args[1]),
                v -> p.sendMessage("Auftrag abgebrochen."));
          } else if (sub.equals("order")) {
            require(args, 5, "/market order buy|sell <Asset> <Anzahl> <Limit>");
            if (!Set.of("buy", "sell").contains(args[1]))
              throw new IllegalArgumentException("buy|sell");
            int quantity =
                Math.toIntExact(Money.positive(args[3])) * (args[1].equals("buy") ? 1 : -1);
            dispatch.result(
                p,
                market.order(p.getUniqueId(), args[2], quantity, Money.positive(args[4])),
                id -> p.sendMessage("Limitauftrag: " + id));
          } else if (sub.equals("portfolio"))
            dispatch.result(
                p, market.portfolio(p.getUniqueId()), rows -> ui.showRows(p, "Portfolio", rows));
          else if (sub.equals("buy") || sub.equals("sell")) {
            require(args, 3, "/market buy|sell <Asset> <Anzahl>");
            int n = Math.toIntExact(Money.positive(args[2]));
            ui.executeMarket(p, args[1], sub.equals("buy") ? n : -n);
          } else ui.market(p);
        }
        case "trade" -> {
          switch (sub) {
            case "cancel", "deny" ->
                dispatch.result(
                    p, trades.cancel(p.getUniqueId()), v -> p.sendMessage("Handel abgebrochen."));
            case "accept", "" -> ui.tradeMenu(p);
            case "item" -> itemTrades.offer(p);
            case "claim" -> itemTrades.claim(p);
            case "asset" -> {
              require(args, 3, "/trade asset <ID> <Anzahl>");
              dispatch.result(
                  p,
                  trades.offerAsset(p.getUniqueId(), args[1], Money.positive(args[2])),
                  v -> ui.tradeMenu(p));
            }
            case "coins" -> {
              require(args, 2, "/trade coins <Betrag>");
              dispatch.result(
                  p, trades.offer(p.getUniqueId(), Long.parseLong(args[1])), v -> ui.tradeMenu(p));
            }
            default -> {
              Player other = player(args[0]);
              dispatch.result(
                  p,
                  trades.invite(p.getUniqueId(), other.getUniqueId()),
                  id -> {
                    other.sendMessage(p.getName() + " bietet einen Handel an. /trade accept");
                    ui.tradeMenu(p);
                  });
            }
          }
        }
        case "plot" -> {
          if (sub.equals("home")) plots.home(p);
          else if (sub.equals("trust") || sub.equals("untrust")) {
            require(args, 2, "/plot trust|untrust <Spieler>");
            plots.trust(p, player(args[1]).getUniqueId(), sub.equals("trust"));
          } else if (sub.equals("visitors")) {
            require(args, 2, "/plot visitors on|off");
            if (!Set.of("on", "off").contains(args[1]))
              throw new IllegalArgumentException("on|off");
            plots.visitors(p, args[1].equals("on"));
          } else plots.menu(p);
        }
        case "trophies" ->
            dispatch.result(
                p,
                ranks.trophies(p.getUniqueId()),
                rows ->
                    menus.open(
                        p,
                        "Trophaeen",
                        rows.stream()
                            .map(
                                row ->
                                    new Menus.Entry(
                                        Material.NETHER_STAR,
                                        (String) row.get("id"),
                                        List.of(
                                            "Dauerhaft | "
                                                + java.time.Instant.ofEpochMilli(
                                                    ((Number) row.get("earned")).longValue()),
                                            "Klicken: auf deinem Plot anzeigen"),
                                        () -> plots.displayTrophy(p, (String) row.get("id"))))
                            .toList(),
                        0));
        case "stats" ->
            dispatch.result(
                p,
                ranks.stats(p.getUniqueId()),
                rows -> ui.showRows(p, "Lebenszeitstatistik", rows));
        case "leaderboard" ->
            dispatch.result(
                p,
                ranks.top(
                    args.length > 0 ? args[0] : "coins", args.length > 1 ? args[1] : "annual"),
                rows -> ui.showRows(p, "Rangliste", rows));
        case "season" -> p.sendMessage(events.season() + " | Events: " + events.active());
        case "roguelike" -> administration.execute(p, args);
        default -> throw new IllegalArgumentException("Unknown command");
      }
    } catch (Exception e) {
      p.sendMessage("Roguelike: " + e.getMessage());
    }
    return true;
  }

  public void performReset(String id) {
    administration.performReset(id);
  }

  private static Player player(String name) {
    Player p = Bukkit.getPlayerExact(name);
    if (p == null) throw new IllegalArgumentException("Spieler muss online sein");
    return p;
  }

  private static void require(String[] args, int n, String usage) {
    if (args.length < n) throw new IllegalArgumentException(usage);
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] args) {
    List<String> options =
        switch (command.getName()) {
          case "world" -> List.of("create", "rejoin", "leave", "complete", "info");
          case "market" -> List.of("portfolio", "buy", "sell", "order", "orders", "cancel");
          case "trade" -> List.of("accept", "deny", "cancel", "coins", "item", "claim", "asset");
          case "plot" -> List.of("home");
          case "leaderboard" ->
              args.length == 1
                  ? List.copyOf(LeaderboardService.METRICS)
                  : List.of("daily", "weekly", "monthly", "annual", "lifetime");
          case "roguelike" ->
              List.of(
                  "debug", "npc", "economy", "unlock", "world", "event", "season", "reset",
                  "backup", "reload");
          default -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        };
    String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    return options.stream().filter(x -> x.startsWith(prefix)).sorted().toList();
  }
}
