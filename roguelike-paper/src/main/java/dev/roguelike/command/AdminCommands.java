package dev.roguelike.command;

import dev.roguelike.core.*;
import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import dev.roguelike.market.*;
import dev.roguelike.npc.NpcService;
import dev.roguelike.reset.ResetService;
import dev.roguelike.run.*;
import dev.roguelike.season.EventScheduler;
import dev.roguelike.unlock.*;
import dev.roguelike.world.WorldService;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class AdminCommands {
  private final JavaPlugin plugin;
  private final SqliteDatabase db;
  private final Dispatch dispatch;
  private final WorldService worlds;
  private final RunRepository runs;
  private final EconomyService money;
  private final UnlockService unlocks;
  private final UnlockAdapter restrictions;
  private final MarketService market;
  private final ResetService reset;
  private final EventScheduler events;
  private NpcService npcs;
  private final Map<UUID, Long> resetConfirmation = new HashMap<>();

  public AdminCommands(
      JavaPlugin plugin,
      SqliteDatabase db,
      Dispatch dispatch,
      WorldService worlds,
      RunRepository runs,
      EconomyService money,
      UnlockService unlocks,
      UnlockAdapter restrictions,
      MarketService market,
      ResetService reset,
      EventScheduler events) {
    this.plugin = plugin;
    this.db = db;
    this.dispatch = dispatch;
    this.worlds = worlds;
    this.runs = runs;
    this.money = money;
    this.unlocks = unlocks;
    this.restrictions = restrictions;
    this.market = market;
    this.reset = reset;
    this.events = events;
  }

  public void npcs(NpcService service) {
    npcs = service;
  }

  public void execute(Player p, String[] args) {
    require(args, 1, "/roguelike debug|npc|economy|reset|backup|world|event|reload");
    switch (args[0]) {
      case "debug" ->
          dispatch.result(
              p,
              reset.preview(),
              row -> p.sendMessage(row + " | loaded worlds=" + Bukkit.getWorlds().size()));
      case "backup" ->
          dispatch.result(
              p,
              db.backup(
                  plugin
                      .getDataFolder()
                      .toPath()
                      .resolve("backups/manual-" + System.currentTimeMillis() + ".db")),
              path -> p.sendMessage("Backup: " + path));
      case "reload" -> {
        dev.roguelike.config.Settings.load(
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.File(plugin.getDataFolder(), "config.yml")));
        p.sendMessage(
            "Konfiguration validiert. Fuer atomaren Wechsel aller Dienste ist ein Serverneustart"
                + " erforderlich.");
      }
      case "npc" -> {
        require(args, 3, "/roguelike npc create|move|remove <Rolle/ID>");
        switch (args[1]) {
          case "create" -> npcs.create(p, args[2]);
          case "move" -> npcs.move(p, args[2]);
          case "remove" -> npcs.remove(p, args[2]);
          default -> throw new IllegalArgumentException("create|move|remove");
        }
      }
      case "economy" -> {
        require(args, 4, "/roguelike economy add <Spieler> <Betrag>");
        if (!args[1].equals("add"))
          throw new IllegalArgumentException("Use add (negative amounts remove Coins)");
        dispatch.result(
            p,
            money.adjust(
                player(args[2]).getUniqueId(),
                Long.parseLong(args[3]),
                UUID.randomUUID().toString()),
            v -> p.sendMessage("Kontostand angepasst."));
      }
      case "event" -> {
        require(args, 3, "/roguelike event start|end <ID>");
        if (!Set.of("start", "end").contains(args[1]))
          throw new IllegalArgumentException("start|end");
        dispatch.result(
            p,
            events.set(args[2], args[1].equals("start")),
            v -> p.sendMessage("Event aktualisiert."));
      }
      case "season" -> p.sendMessage(events.season());
      case "world" -> {
        require(args, 3, "/roguelike world terminate <Spieler>");
        if (!args[1].equals("terminate")) throw new IllegalArgumentException("terminate");
        Player target = player(args[2]);
        dispatch.result(
            p,
            runs.active(target.getUniqueId()),
            run -> {
              if (run == null) return;
              worlds.terminate(target, run);
            });
      }
      case "unlock" -> {
        require(args, 3, "/roguelike unlock <Spieler> <ID>");
        if (unlocks.definitions().stream().noneMatch(d -> d.id().equals(args[2])))
          throw new IllegalArgumentException("Unknown unlock");
        Player target = player(args[1]);
        dispatch.result(
            p,
            db.transaction(
                c -> {
                  RunRepository.writable(c);
                  Sql.update(
                      c,
                      "INSERT OR IGNORE INTO unlocks VALUES(?,?,?)",
                      target.getUniqueId().toString(),
                      RunRepository.epoch(c),
                      args[2]);
                  return null;
                }),
            v -> restrictions.refresh(target));
      }
      case "reset" -> {
        require(args, 2, "/roguelike reset preview|confirm");
        if (args[1].equals("preview")) {
          resetConfirmation.put(p.getUniqueId(), System.currentTimeMillis() + 60000);
          dispatch.result(
              p,
              reset.preview(),
              row ->
                  p.sendMessage(
                      row
                          + " | Loescht Jahresfortschritt; behaelt Plots/Trophaeen. Innerhalb 60s:"
                          + " /roguelike reset confirm"));
        } else if (args[1].equals("confirm")) {
          Long expiry = resetConfirmation.remove(p.getUniqueId());
          if (expiry == null || expiry < System.currentTimeMillis())
            throw new IllegalArgumentException("First use reset preview");
          performReset("manual-" + UUID.randomUUID());
        }
      }
      default -> throw new IllegalArgumentException("Unbekannter Adminbefehl");
    }
  }

  public void performReset(String id) {
    worlds.frozen(true);
    Bukkit.broadcast(
        net.kyori.adventure.text.Component.text("Roguelike: Jahresreset wird vorbereitet."));
    for (Player p : Bukkit.getOnlinePlayers())
      p.kick(
          net.kyori.adventure.text.Component.text("Jahresreset. Bitte gleich erneut verbinden."));
    reset
        .begin(id)
        .thenCompose(v -> market.initialize())
        .whenComplete(
            (v, e) ->
                dispatch.main(
                    () -> {
                      if (e != null)
                        plugin
                            .getLogger()
                            .log(java.util.logging.Level.SEVERE, "Reset requires recovery", e);
                      else {
                        worlds.cleanup();
                        worlds.frozen(false);
                        plugin.getLogger().info("Reset complete: " + id);
                      }
                    }));
  }

  private static Player player(String name) {
    Player p = Bukkit.getPlayerExact(name);
    if (p == null) throw new IllegalArgumentException("Spieler muss online sein");
    return p;
  }

  private static void require(String[] args, int n, String usage) {
    if (args.length < n) throw new IllegalArgumentException(usage);
  }
}
