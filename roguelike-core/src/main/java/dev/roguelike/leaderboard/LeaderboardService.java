package dev.roguelike.leaderboard;

import dev.roguelike.database.*;
import dev.roguelike.run.RunRepository;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class LeaderboardService {
  private final Database db;
  private final ZoneId zone;
  public static final Set<String> METRICS =
      Set.of("coins", "runs", "deaths", "rebirths", "achievements", "survival");

  public LeaderboardService(Database db, ZoneId zone) {
    this.db = db;
    this.zone = zone;
  }

  public static LocalDate start(LocalDate now, String period) {
    return switch (period) {
      case "daily" -> now;
      case "weekly" -> now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      case "monthly" -> now.withDayOfMonth(1);
      case "annual", "lifetime" -> LocalDate.of(1970, 1, 1);
      default -> throw new IllegalArgumentException("daily|weekly|monthly|annual|lifetime");
    };
  }

  public CompletableFuture<List<Map<String, Object>>> top(String metric, String period) {
    if (!METRICS.contains(metric))
      return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown metric"));
    LocalDate from = start(LocalDate.now(zone), period);
    return db.read(
        c ->
            Sql.rows(
                c,
                "SELECT p.name,s.player,SUM(s.value) AS score FROM statistics s JOIN players p ON"
                    + " p.id=s.player WHERE s.metric=? AND s.day>=? AND (?='lifetime' OR s.epoch=?)"
                    + " GROUP BY s.player,p.name ORDER BY score DESC,s.player LIMIT 100",
                metric,
                from.toString(),
                period,
                RunRepository.epoch(c)));
  }

  public CompletableFuture<List<Map<String, Object>>> stats(UUID player) {
    return db.read(
        c ->
            Sql.rows(
                c,
                "SELECT metric,SUM(value) AS score FROM statistics WHERE player=? GROUP BY metric",
                player.toString()));
  }

  public CompletableFuture<List<Map<String, Object>>> trophies(UUID player) {
    return db.read(
        c ->
            Sql.rows(
                c,
                "SELECT id,earned FROM trophies WHERE player=? ORDER BY earned",
                player.toString()));
  }

  public CompletableFuture<Void> snapshot() {
    return db.transaction(
        c -> {
          long epoch = RunRepository.epoch(c);
          LocalDate now = LocalDate.now(zone);
          for (String period : List.of("daily", "weekly", "monthly", "annual", "lifetime")) {
            String scope =
                period.equals("annual")
                    ? "epoch:" + epoch
                    : period.equals("lifetime") ? "lifetime" : start(now, period).toString();
            Sql.update(
                c,
                "INSERT INTO leaderboard_snapshots SELECT ?,?,metric,player,SUM(value),? FROM"
                    + " statistics WHERE day>=? AND (?='lifetime' OR epoch=?) GROUP BY"
                    + " metric,player ON CONFLICT(period,scope,metric,player) DO UPDATE SET"
                    + " value=excluded.value,captured=excluded.captured",
                period,
                scope,
                System.currentTimeMillis(),
                start(now, period).toString(),
                period,
                epoch);
          }
          return null;
        });
  }
}
