package dev.roguelike.leaderboard;

import dev.roguelike.core.Dispatch;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class HubLeaderboard {
  public HubLeaderboard(JavaPlugin plugin, World hub, LeaderboardService ranks, Dispatch dispatch) {
    Location at = new Location(hub, 0, 68, -5);
    at.getChunk().load();
    NamespacedKey tag = new NamespacedKey("roguelike", "leaderboard");
    for (Entity e : hub.getEntities()) if (e.getPersistentDataContainer().has(tag)) e.remove();
    TextDisplay display =
        hub.spawn(
            at,
            TextDisplay.class,
            e -> {
              e.setPersistent(false);
              e.setBillboard(Display.Billboard.CENTER);
              e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            });
    plugin
        .getServer()
        .getScheduler()
        .runTaskTimer(
            plugin,
            () -> {
              ranks.snapshot();
              ranks
                  .top("coins", "annual")
                  .thenAccept(
                      rows ->
                          dispatch.main(
                              () -> {
                                StringBuilder text =
                                    new StringBuilder("Roguelike | Coins dieses Jahr");
                                for (int i = 0; i < Math.min(10, rows.size()); i++) {
                                  var row = rows.get(i);
                                  text.append('\n')
                                      .append(i + 1)
                                      .append(". ")
                                      .append(row.get("name"))
                                      .append(" - ")
                                      .append(row.get("score"));
                                }
                                display.text(Component.text(text.toString()));
                              }));
            },
            20,
            1200);
  }
}
