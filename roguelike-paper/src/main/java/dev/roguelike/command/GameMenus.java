package dev.roguelike.command;

import dev.roguelike.core.*;
import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import dev.roguelike.gui.Menus;
import dev.roguelike.market.*;
import dev.roguelike.rebirth.RebirthService;
import dev.roguelike.run.*;
import dev.roguelike.trade.TradeService;
import dev.roguelike.unlock.*;
import dev.roguelike.world.WorldService;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class GameMenus {
  private final Dispatch dispatch;
  private final Menus menus;
  private final WorldService worlds;
  private final UnlockService unlocks;
  private final UnlockAdapter restrictions;
  private final MarketService market;
  private final TradeService trades;
  private final RebirthService rebirth;
  private dev.roguelike.trade.ItemTradeAdapter itemTrades;

  public GameMenus(
      Dispatch dispatch,
      Menus menus,
      WorldService worlds,
      UnlockService unlocks,
      UnlockAdapter restrictions,
      MarketService market,
      TradeService trades,
      RebirthService rebirth) {
    this.dispatch = dispatch;
    this.menus = menus;
    this.worlds = worlds;
    this.unlocks = unlocks;
    this.restrictions = restrictions;
    this.market = market;
    this.trades = trades;
    this.rebirth = rebirth;
  }

  public void itemTrades(dev.roguelike.trade.ItemTradeAdapter adapter) {
    itemTrades = adapter;
  }

  public void role(Player p, String role) {
    if (worlds.locked(p)) return;
    switch (role) {
      case "create" -> creationMenu(p, new HashSet<>(restrictions.owned(p)));
      case "rejoin" -> worlds.rejoin(p);
      case "upgrades" -> unlocks(p);
      case "rebirth" -> rebirth(p);
      default -> throw new IllegalArgumentException("Unknown role");
    }
  }

  public void creationMenu(Player p, Set<String> selected) {
    var entries = new ArrayList<Menus.Entry>();
    for (var definition : unlocks.definitions())
      if (definition.category().equals("starting_perks")
          && restrictions.owned(p).contains(definition.id()))
        entries.add(
            new Menus.Entry(
                Material.BREAD,
                (selected.contains(definition.id()) ? "AN: " : "AUS: ") + definition.id(),
                List.of(definition.description()),
                () -> {
                  if (!selected.remove(definition.id())) selected.add(definition.id());
                  creationMenu(p, selected);
                }));
    entries.add(
        new Menus.Entry(
            Material.GRASS_BLOCK,
            "Welt erstellen",
            List.of(
                "Profil: " + unlocks.profile(selected),
                "Zufaelliger Gelaendemodifikator",
                "Nur ein aktiver Run erlaubt"),
            () ->
                menus.confirm(
                    p, "Run starten", () -> worlds.create(p, unlocks.profile(selected)))));
    menus.open(p, "Welt & Startvorteile", entries, 0);
  }

  public void unlocks(Player p) {
    var entries = new ArrayList<Menus.Entry>();
    for (var d : unlocks.definitions())
      entries.add(
          new Menus.Entry(
              Material.BOOK,
              d.id() + " | " + d.price() + " Coins",
              List.of(
                  d.category(),
                  d.description(),
                  "Voraussetzungen: " + d.requires(),
                  restrictions.owned(p).contains(d.id()) ? "Freigeschaltet" : "Gesperrt"),
              () ->
                  menus.confirm(
                      p,
                      "Freischalten?",
                      () ->
                          dispatch.result(
                              p,
                              unlocks.buy(p.getUniqueId(), d.id()),
                              v -> {
                                restrictions.refresh(p);
                                p.sendMessage("Freigeschaltet: " + d.id());
                              }))));
    menus.open(p, "Freischaltungen", entries, 0);
  }

  public void rebirth(Player p) {
    dispatch.result(
        p,
        rebirth.level(p.getUniqueId()),
        level ->
            menus.open(
                p,
                "Rebirth " + level,
                List.of(
                    new Menus.Entry(
                        Material.NETHER_STAR,
                        "Kosten: " + rebirth.cost(level),
                        List.of(
                            "Aktueller Bonus: " + (level * 10) + "%",
                            "Anforderungen in rebirths.yml",
                            "Bestaetigung setzt konfigurierten Fortschritt zurueck"),
                        () ->
                            menus.confirm(
                                p,
                                "Rebirth bestaetigen",
                                () ->
                                    dispatch.result(
                                        p,
                                        rebirth.rebirth(p.getUniqueId(), level),
                                        next -> {
                                          restrictions.refresh(p);
                                          p.sendMessage("Rebirth-Level: " + next);
                                        })))),
                0));
  }

  public void market(Player p) {
    dispatch.result(
        p,
        market.prices(),
        rows -> {
          var entries = new ArrayList<Menus.Entry>();
          for (var row : rows) {
            String asset = (String) row.get("id");
            AssetDefinition def =
                market.definitions().stream()
                    .filter(a -> a.id().equals(asset))
                    .findFirst()
                    .orElseThrow();
            long supply = ((Number) row.get("supply")).longValue(),
                anchor = ((Number) row.get("price")).longValue();
            entries.add(
                new Menus.Entry(
                    Material.EMERALD,
                    asset,
                    List.of(
                        "Kurs: " + def.marginal(anchor, supply),
                        "Liquiditaet: " + supply,
                        "Klicke fuer Kauf/Verkauf"),
                    () ->
                        menus.open(
                            p,
                            asset,
                            List.of(
                                new Menus.Entry(
                                    Material.LIME_CONCRETE,
                                    "1 kaufen",
                                    () -> executeMarket(p, asset, 1)),
                                new Menus.Entry(
                                    Material.RED_CONCRETE,
                                    "1 verkaufen",
                                    () -> executeMarket(p, asset, -1)),
                                new Menus.Entry(
                                    Material.PAPER,
                                    "Kursverlauf",
                                    () ->
                                        dispatch.result(
                                            p,
                                            market.history(asset),
                                            history -> showRows(p, "Handelshistorie", history)))),
                            0)));
          }
          menus.open(p, "Markt", entries, 0);
        });
  }

  public void executeMarket(Player p, String asset, int quantity) {
    menus.confirm(
        p,
        "Marktauftrag bestaetigen",
        () ->
            dispatch.result(
                p,
                market.trade(p.getUniqueId(), asset, quantity, UUID.randomUUID().toString()),
                total ->
                    p.sendMessage(
                        "Ausgefuehrt: "
                            + Math.abs(quantity)
                            + " "
                            + asset
                            + " / "
                            + total
                            + " Coins inklusive Gebuehr")));
  }

  public void showRows(Player p, String title, List<Map<String, Object>> rows) {
    menus.open(
        p,
        title,
        rows.stream()
            .map(
                row ->
                    new Menus.Entry(
                        Material.PAPER,
                        row.values().iterator().next().toString(),
                        row.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList(),
                        () -> {}))
            .toList(),
        0);
  }

  public void tradeMenu(Player p) {
    dispatch.result(
        p,
        trades.current(p.getUniqueId()),
        row -> {
          if (row == null) {
            p.sendMessage("Kein Handel. /trade <Spieler> | Depot: /trade claim");
            return;
          }
          String id = (String) row.get("id");
          dispatch.result(
              p,
              trades
                  .items(id)
                  .thenCombine(trades.assets(id), (items, assets) -> List.of(items, assets)),
              offers -> {
                var entries = new ArrayList<Menus.Entry>();
                entries.add(
                    new Menus.Entry(
                        Material.GOLD_INGOT,
                        "Coin-Angebote",
                        List.of(
                            "A: " + row.get("a"),
                            "B: " + row.get("b"),
                            "A bietet: " + row.get("coins_a"),
                            "B bietet: " + row.get("coins_b"),
                            "/trade coins <Betrag>"),
                        () -> {}));
                for (var item : offers.get(0)) {
                  var stack =
                      org.bukkit.inventory.ItemStack.deserializeBytes((byte[]) item.get("item"));
                  entries.add(
                      new Menus.Entry(
                          stack.getType(),
                          stack.getAmount() + "x " + stack.getType(),
                          List.of("Von: " + item.get("owner"), "Gesichertes Depot"),
                          () -> {}));
                }
                for (var asset : offers.get(1))
                  entries.add(
                      new Menus.Entry(
                          Material.EMERALD,
                          asset.get("quantity") + "x " + asset.get("asset"),
                          List.of("Von: " + asset.get("player")),
                          () -> {}));
                entries.add(
                    new Menus.Entry(
                        Material.CHEST, "Item aus Haupthand anbieten", () -> itemTrades.offer(p)));
                entries.add(
                    new Menus.Entry(
                        Material.LIME_CONCRETE,
                        "Angebot bestaetigen",
                        () ->
                            dispatch.result(
                                p,
                                trades.accept(
                                    p.getUniqueId(), ((Number) row.get("revision")).longValue()),
                                done -> {
                                  p.closeInventory();
                                  p.sendMessage(
                                      done
                                          ? "Handel abgeschlossen. Items: /trade claim"
                                          : "Bestaetigt. Warte auf Gegenpartei.");
                                })));
                entries.add(
                    new Menus.Entry(
                        Material.RED_CONCRETE,
                        "Abbrechen",
                        () ->
                            dispatch.result(
                                p,
                                trades.cancel(p.getUniqueId()),
                                v -> {
                                  p.closeInventory();
                                  p.sendMessage("Items zurueckholen: /trade claim");
                                })));
                menus.open(p, "Handel", entries, 0);
              });
        });
  }
}
