package dev.roguelike.trade;

import dev.roguelike.core.Dispatch;
import dev.roguelike.run.PlayerSnapshot;
import dev.roguelike.unlock.UnlockAdapter;
import dev.roguelike.world.WorldService;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ItemTradeAdapter {
  private final TradeService trades;
  private final WorldService worlds;
  private final UnlockAdapter unlocks;
  private final Dispatch dispatch;

  public ItemTradeAdapter(
      TradeService trades, WorldService worlds, UnlockAdapter unlocks, Dispatch dispatch) {
    this.trades = trades;
    this.worlds = worlds;
    this.unlocks = unlocks;
    this.dispatch = dispatch;
  }

  private boolean lock(Player p) {
    if (worlds.session(p) != null || p.getWorld() != worlds.hub()) {
      p.sendMessage("Itemhandel nur im Hub.");
      return false;
    }
    return worlds.lock(p);
  }

  public void offer(Player p) {
    if (!lock(p)) return;
    ItemStack item = p.getInventory().getItemInMainHand().clone();
    if (item.isEmpty()
        || !item.getItemMeta().getPersistentDataContainer().isEmpty()
        || !unlocks.allowed(p, item)) {
      worlds.unlock(p);
      p.sendMessage("Dieses Item kann nicht gehandelt werden.");
      return;
    }
    trades
        .current(p.getUniqueId())
        .whenComplete(
            (row, error) ->
                dispatch.main(
                    () -> {
                      if (error != null || row == null) {
                        worlds.unlock(p);
                        p.sendMessage("Kein offener Handel.");
                        return;
                      }
                      UUID otherId =
                          UUID.fromString(
                              (String)
                                  row.get(
                                      p.getUniqueId().toString().equals(row.get("a")) ? "b" : "a"));
                      Player other = Bukkit.getPlayer(otherId);
                      if (other == null || !unlocks.allowed(other, item)) {
                        worlds.unlock(p);
                        p.sendMessage("Gegenpartei hat dieses Item nicht freigeschaltet.");
                        return;
                      }
                      byte[] before = PlayerSnapshot.capture(p);
                      p.getInventory().setItemInMainHand(ItemStack.empty());
                      byte[] after = PlayerSnapshot.capture(p);
                      PlayerSnapshot.restore(p, worlds.hub(), before);
                      trades
                          .escrow(p.getUniqueId(), item.serializeAsBytes(), after)
                          .whenComplete(
                              (v, failure) ->
                                  dispatch.main(
                                      () -> {
                                        if (failure == null && p.isOnline()) {
                                          PlayerSnapshot.restore(p, worlds.hub(), after);
                                          p.saveData();
                                          p.sendMessage("Item im gesicherten Handelsdepot.");
                                        } else if (p.isOnline())
                                          p.sendMessage("Itemangebot fehlgeschlagen.");
                                        worlds.unlock(p);
                                      }));
                    }));
  }

  public void claim(Player p) {
    if (!lock(p)) return;
    trades
        .deliveries(p.getUniqueId())
        .whenComplete(
            (rows, error) ->
                dispatch.main(
                    () -> {
                      if (error != null || !p.isOnline()) {
                        worlds.unlock(p);
                        return;
                      }
                      byte[] before = PlayerSnapshot.capture(p);
                      var ids = new ArrayList<String>();
                      for (var row : rows) {
                        ItemStack item = ItemStack.deserializeBytes((byte[]) row.get("item"));
                        if (!unlocks.allowed(p, item)) continue;
                        int slot = p.getInventory().firstEmpty();
                        if (slot < 0 || slot >= 36) break;
                        p.getInventory().setItem(slot, item);
                        ids.add((String) row.get("id"));
                      }
                      byte[] after = PlayerSnapshot.capture(p);
                      PlayerSnapshot.restore(p, worlds.hub(), before);
                      if (ids.isEmpty()) {
                        worlds.unlock(p);
                        p.sendMessage("Keine abholbaren Items oder kein freier Platz.");
                        return;
                      }
                      trades
                          .claim(p.getUniqueId(), ids, after)
                          .whenComplete(
                              (v, failure) ->
                                  dispatch.main(
                                      () -> {
                                        if (failure == null && p.isOnline()) {
                                          PlayerSnapshot.restore(p, worlds.hub(), after);
                                          p.saveData();
                                          p.sendMessage(ids.size() + " Items abgeholt.");
                                        } else if (p.isOnline())
                                          p.sendMessage(
                                              "Abholung fehlgeschlagen; Depot bleibt erhalten.");
                                        worlds.unlock(p);
                                      }));
                    }));
  }
}
