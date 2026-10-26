package dev.roguelike.run;

import dev.roguelike.core.Dispatch;
import dev.roguelike.economy.InventoryValuation;
import dev.roguelike.world.WorldService;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class DeathListener implements Listener {
  private final WorldService worlds;
  private final SettlementService settlement;
  private final RunRepository runs;
  private final InventoryValuation valuation;
  private final Dispatch dispatch;
  private final Map<UUID, byte[]> restore = new HashMap<>();
  private java.util.function.Consumer<UUID> completed = id -> {};

  public DeathListener(
      WorldService worlds,
      SettlementService settlement,
      RunRepository runs,
      InventoryValuation valuation,
      Dispatch dispatch) {
    this.worlds = worlds;
    this.settlement = settlement;
    this.runs = runs;
    this.valuation = valuation;
    this.dispatch = dispatch;
  }

  public void onCompleted(java.util.function.Consumer<UUID> callback) {
    completed = callback;
  }

  @EventHandler
  public void quit(org.bukkit.event.player.PlayerQuitEvent e) {
    restore.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void death(PlayerDeathEvent e) {
    Player p = e.getEntity();
    Run run = worlds.session(p);
    if (run == null) return;
    e.getDrops().clear();
    e.setDroppedExp(0);
    e.setKeepInventory(true);
    begin(p, run, true);
  }

  public void complete(Player p) {
    Run run = worlds.session(p);
    if (run == null || worlds.locked(p)) return;
    begin(p, run, false);
  }

  private void begin(Player p, Run run, boolean death) {
    worlds.lock(p);
    p.getPersistentDataContainer()
        .set(
            new org.bukkit.NamespacedKey("roguelike", "pending_death"),
            org.bukkit.persistence.PersistentDataType.STRING,
            run.id());
    p.getPersistentDataContainer()
        .set(
            new org.bukkit.NamespacedKey("roguelike", "death_outcome"),
            org.bukkit.persistence.PersistentDataType.BYTE,
            (byte) (death ? 1 : 0));
    p.saveData();
    recover(p, run);
  }

  public void recover(Player p, Run run) {
    long value = valuation.value(p.getInventory().getContents());
    boolean died =
        p.getPersistentDataContainer()
                .getOrDefault(
                    new org.bukkit.NamespacedKey("roguelike", "death_outcome"),
                    org.bukkit.persistence.PersistentDataType.BYTE,
                    (byte) 1)
            != 0;
    settlement
        .settle(run.id(), run.player(), value, died)
        .thenCompose(
            reward -> runs.hub(p.getUniqueId()).thenApply(bytes -> new Object[] {reward, bytes}))
        .whenComplete(
            (result, error) ->
                dispatch.main(
                    () -> {
                      if (error != null) {
                        p.kick(
                            net.kyori.adventure.text.Component.text(
                                "Settlement failed; administrator recovery required."));
                        return;
                      }
                      worlds.removeSession(p);
                      byte[] bytes = (byte[]) result[1];
                      if (worlds.current(p) && p.isOnline() && !p.isDead()) {
                        PlayerSnapshot.restore(p, worlds.hub(), bytes);
                        p.teleport(worlds.hub().getSpawnLocation());
                        p.getPersistentDataContainer()
                            .remove(new org.bukkit.NamespacedKey("roguelike", "pending_death"));
                        p.saveData();
                        worlds.unlock(p);
                      } else if (worlds.current(p) && p.isOnline())
                        restore.put(p.getUniqueId(), bytes == null ? new byte[0] : bytes);
                      p.sendMessage("Run beendet: +" + result[0] + " Coins");
                      if (p.isOnline()) WorldService.resetAdvancements(p);
                      completed.accept(p.getUniqueId());
                      worlds.cleanup();
                    }));
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent e) {
    e.setRespawnLocation(worlds.hub().getSpawnLocation());
    Player p = e.getPlayer();
    if (restore.containsKey(p.getUniqueId())) {
      byte[] bytes = restore.remove(p.getUniqueId());
      dispatch.main(
          () -> {
            PlayerSnapshot.restore(p, worlds.hub(), bytes.length == 0 ? null : bytes);
            p.getPersistentDataContainer()
                .remove(new org.bukkit.NamespacedKey("roguelike", "pending_death"));
            p.saveData();
            worlds.unlock(p);
          });
    } else if (worlds.session(p) == null) {
      worlds.lock(p);
      runs.hub(p.getUniqueId())
          .whenComplete(
              (bytes, error) ->
                  dispatch.main(
                      () -> {
                        if (!worlds.current(p) || !p.isOnline()) return;
                        if (error != null) {
                          p.kick(
                              net.kyori.adventure.text.Component.text(
                                  "Hub recovery failed. Please reconnect."));
                          return;
                        }
                        PlayerSnapshot.restore(p, worlds.hub(), bytes);
                        p.getPersistentDataContainer()
                            .remove(new org.bukkit.NamespacedKey("roguelike", "pending_death"));
                        p.saveData();
                        worlds.unlock(p);
                      }));
    }
  }
}
