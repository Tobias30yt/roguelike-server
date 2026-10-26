package dev.roguelike.config;

import java.time.ZoneId;
import org.bukkit.configuration.file.FileConfiguration;

public record Settings(
    ZoneId timezone,
    String database,
    int maxLoaded,
    int maxActive,
    int border,
    int checkpoint,
    long maxBalance) {
  public static Settings load(FileConfiguration c) {
    var s =
        new Settings(
            ZoneId.of(c.getString("timezone", "Europe/Berlin")),
            c.getString("database", "roguelike.db"),
            c.getInt("worlds.max-loaded", 4),
            c.getInt("worlds.max-active", 100),
            c.getInt("worlds.border", 512),
            c.getInt("worlds.checkpoint-seconds", 30),
            c.getLong("economy.max-balance", 1_000_000_000_000L));
    if (!s.database.matches("[a-zA-Z0-9_-]+\\.db")
        || s.maxLoaded < 1
        || s.maxActive < s.maxLoaded
        || s.border < 64
        || s.border > 4096
        || s.checkpoint < 5
        || s.maxBalance < 1) throw new IllegalArgumentException("Invalid config.yml limits");
    return s;
  }
}
