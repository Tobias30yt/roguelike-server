package dev.roguelike.season;

import dev.roguelike.achievement.AchievementService;
import dev.roguelike.core.Dispatch;
import dev.roguelike.database.*;
import dev.roguelike.world.WorldService;
import java.time.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class EventScheduler implements Listener {
  private record Event(
      String id, ZonedDateTime start, ZonedDateTime end, long coins, String trophy) {}

  private final ZoneId zone;
  private final Database db;
  private final Dispatch dispatch;
  private final AchievementService achievements;
  private final WorldService worlds;
  private final List<Event> events;
  private Map<String, Boolean> overrides = Map.of();

  public EventScheduler(
      JavaPlugin plugin,
      ZoneId zone,
      Database db,
      Dispatch dispatch,
      AchievementService achievements,
      WorldService worlds,
      YamlConfiguration config) {
    this.zone = zone;
    this.db = db;
    this.dispatch = dispatch;
    this.achievements = achievements;
    this.worlds = worlds;
    var list = new ArrayList<Event>();
    var root = config.getConfigurationSection("events");
    if (root != null)
      for (String id : root.getKeys(false)) {
        var e = root.getConfigurationSection(id);
        var start = LocalDateTime.parse(e.getString("start")).atZone(zone);
        var end = LocalDateTime.parse(e.getString("end")).atZone(zone);
        if (!end.isAfter(start) || e.getLong("coins") < 0)
          throw new IllegalArgumentException("Invalid event " + id);
        list.add(new Event(id, start, end, e.getLong("coins"), e.getString("trophy", "")));
      }
    events = List.copyOf(list);
    refresh();
    plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 1200, 1200);
  }

  private void refresh() {
    db.read(c -> Sql.rows(c, "SELECT * FROM event_state"))
        .thenAccept(
            rows ->
                dispatch.main(
                    () -> {
                      var map = new HashMap<String, Boolean>();
                      for (var row : rows)
                        map.put(
                            (String) row.get("id"), ((Number) row.get("enabled")).intValue() != 0);
                      overrides = Map.copyOf(map);
                    }));
  }

  public String season() {
    return Seasons.at(ZonedDateTime.now(zone));
  }

  public List<String> active() {
    var now = ZonedDateTime.now(zone);
    return events.stream()
        .filter(
            e -> overrides.getOrDefault(e.id(), !now.isBefore(e.start()) && now.isBefore(e.end())))
        .map(Event::id)
        .toList();
  }

  public java.util.concurrent.CompletableFuture<Void> set(String id, boolean enabled) {
    if (events.stream().noneMatch(e -> e.id().equals(id)))
      throw new IllegalArgumentException("Unknown event");
    return db.transaction(
            c -> {
              Sql.update(
                  c,
                  "INSERT INTO event_state VALUES(?,?) ON CONFLICT(id) DO UPDATE SET"
                      + " enabled=excluded.enabled",
                  id,
                  enabled ? 1 : 0);
              return (Void) null;
            })
        .thenRun(this::refresh);
  }

  public void runCompleted(UUID player) {
    for (Event e : events)
      if (active().contains(e.id()))
        achievements.award(
            player.toString(), "event:" + e.id(), "run_complete", e.coins(), e.trophy());
  }

  @EventHandler
  public void advancement(PlayerAdvancementDoneEvent e) {
    var run = worlds.session(e.getPlayer());
    if (run == null || e.getAdvancement().getKey().getKey().startsWith("recipes/")) return;
    achievements.award(
        run.player(), "run:" + run.id(), e.getAdvancement().getKey().toString(), 0, "");
  }
}
