package dev.roguelike.reset;

import dev.roguelike.database.*;
import dev.roguelike.run.RunRepository;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Durable PREPARED -> BACKED_UP -> COMPLETE sequence. Each stage is replayable. */
public final class ResetService {
  private final SqliteDatabase db;
  private final Path backups;

  public ResetService(SqliteDatabase db, Path backups) {
    this.db = db;
    this.backups = backups;
  }

  public CompletableFuture<Map<String, Object>> preview() {
    return db.read(
        c -> {
          var result = new LinkedHashMap<String, Object>();
          result.put("epoch", RunRepository.epoch(c));
          result.put("players", Sql.number(c, "SELECT COUNT(*) FROM players"));
          result.put(
              "active-runs",
              Sql.number(c, "SELECT COUNT(*) FROM runs WHERE state IN ('ACTIVE','PREPARING')"));
          result.put(
              "coins",
              Sql.number(
                  c,
                  "SELECT COALESCE(SUM(balance),0) FROM accounts WHERE epoch=?",
                  RunRepository.epoch(c)));
          return result;
        });
  }

  public CompletableFuture<String> begin(String id) {
    if (!id.matches("[a-zA-Z0-9_-]+"))
      return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid reset ID"));
    return db.transaction(
            c -> {
              String pending = Sql.string(c, "SELECT id FROM resets WHERE stage!='COMPLETE'");
              if (pending != null) return pending;
              if (Sql.number(c, "SELECT COUNT(*) FROM resets WHERE id=?", id) > 0) return id;
              long epoch = RunRepository.epoch(c);
              Sql.update(c, "UPDATE settings SET value='1' WHERE key='frozen'");
              Sql.update(
                  c,
                  "INSERT INTO resets(id,old_epoch,new_epoch,stage,created)"
                      + " VALUES(?,?,?,'PREPARED',?)",
                  id,
                  epoch,
                  epoch + 1,
                  System.currentTimeMillis());
              return id;
            })
        .thenCompose(this::resume);
  }

  public CompletableFuture<String> recover() {
    return db.read(c -> Sql.string(c, "SELECT id FROM resets WHERE stage!='COMPLETE'"))
        .thenCompose(id -> id == null ? CompletableFuture.completedFuture(null) : resume(id));
  }

  private CompletableFuture<String> resume(String id) {
    return db.read(c -> Sql.string(c, "SELECT stage FROM resets WHERE id=?", id))
        .thenCompose(
            stage -> {
              if ("COMPLETE".equals(stage)) return CompletableFuture.completedFuture(id);
              if ("PREPARED".equals(stage)) {
                Path path = backups.resolve("before-reset-" + id + "-" + UUID.randomUUID() + ".db");
                return db.backup(path)
                    .thenCompose(
                        v ->
                            db.transaction(
                                c -> {
                                  Sql.update(
                                      c,
                                      "UPDATE resets SET stage='BACKED_UP',backup=? WHERE id=? AND"
                                          + " stage='PREPARED'",
                                      path.toString(),
                                      id);
                                  return null;
                                }))
                    .thenCompose(v -> resume(id));
              }
              return db.transaction(
                  c -> {
                    var rows =
                        Sql.rows(c, "SELECT * FROM resets WHERE id=? AND stage='BACKED_UP'", id);
                    if (rows.isEmpty()) return id;
                    var row = rows.getFirst();
                    long old = ((Number) row.get("old_epoch")).longValue(),
                        next = ((Number) row.get("new_epoch")).longValue();
                    if (RunRepository.epoch(c) != old)
                      throw new IllegalStateException("Reset epoch mismatch");
                    long now = System.currentTimeMillis();
                    Sql.update(
                        c,
                        "INSERT OR IGNORE INTO leaderboard_history SELECT"
                            + " epoch,player,metric,SUM(value),? FROM statistics WHERE epoch=?"
                            + " GROUP BY epoch,player,metric",
                        now,
                        old);
                    Sql.update(
                        c,
                        "INSERT OR IGNORE INTO trophies SELECT player,?,? FROM accounts WHERE"
                            + " epoch=? ORDER BY balance DESC,player LIMIT 1",
                        "year_" + old + "_winner",
                        now,
                        old);
                    Sql.update(c, "UPDATE epochs SET ended=? WHERE id=?", now, old);
                    Sql.update(c, "INSERT INTO epochs VALUES(?,?,NULL)", next, now);
                    Sql.update(
                        c,
                        "UPDATE runs SET state='COMPLETED',ended=?,reward=0 WHERE state IN"
                            + " ('PREPARING','ACTIVE','SETTLING')",
                        now);
                    Sql.update(c, "UPDATE trades SET state='CANCELLED' WHERE state='OPEN'");
                    Sql.update(c, "UPDATE market_orders SET state='CANCELLED' WHERE state='OPEN'");
                    Sql.update(
                        c,
                        "UPDATE item_escrow SET state='EXPIRED' WHERE state IN ('HELD','READY')");
                    Sql.update(
                        c, "INSERT INTO accounts(player,epoch) SELECT id,? FROM players", next);
                    Sql.update(c, "UPDATE contexts SET active=NULL,hub=NULL");
                    Sql.update(
                        c, "UPDATE settings SET value=? WHERE key='epoch'", Long.toString(next));
                    Sql.update(c, "UPDATE resets SET stage='COMPLETE' WHERE id=?", id);
                    if (id.startsWith("annual-"))
                      Sql.update(
                          c,
                          "UPDATE settings SET value=? WHERE key='calendar-cycle'",
                          id.substring(7));
                    Sql.update(c, "UPDATE settings SET value='0' WHERE key='frozen'");
                    return id;
                  });
            });
  }
}
