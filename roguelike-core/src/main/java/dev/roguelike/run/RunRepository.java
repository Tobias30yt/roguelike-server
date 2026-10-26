package dev.roguelike.run;

import dev.roguelike.database.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class RunRepository {
  private final Database db;

  public RunRepository(Database db) {
    this.db = db;
  }

  public static long epoch(Connection c) throws SQLException {
    return Sql.number(c, "SELECT value FROM settings WHERE key='epoch'");
  }

  public static void writable(Connection c) throws SQLException {
    if (Sql.number(c, "SELECT value FROM settings WHERE key='frozen'") != 0)
      throw new IllegalStateException("Transactions frozen during reset");
  }

  public CompletableFuture<Void> register(UUID player, String name) {
    return db.transaction(
        c -> {
          String id = player.toString();
          Sql.update(
              c,
              "INSERT INTO players VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name",
              id,
              name,
              System.currentTimeMillis());
          Sql.update(c, "INSERT OR IGNORE INTO accounts(player,epoch) VALUES(?,?)", id, epoch(c));
          Sql.update(c, "INSERT OR IGNORE INTO contexts(player) VALUES(?)", id);
          Sql.update(c, "INSERT OR IGNORE INTO plots(player) VALUES(?)", id);
          return null;
        });
  }

  public CompletableFuture<Run> create(UUID player, int limit, String profile) {
    return db.transaction(
        c -> {
          writable(c);
          if (Sql.number(c, "SELECT COUNT(*) FROM runs WHERE state IN ('PREPARING','ACTIVE')")
              >= limit) throw new IllegalStateException("Active world limit reached");
          if (active(c, player.toString()) != null)
            throw new IllegalStateException("You already have an active run");
          String id = UUID.randomUUID().toString();
          long seed = new java.security.SecureRandom().nextLong();
          long now = System.currentTimeMillis();
          long epoch = epoch(c);
          Sql.update(
              c,
              "INSERT INTO runs(id,player,epoch,seed,profile,state,created)"
                  + " VALUES(?,?,?,?,?,'PREPARING',?)",
              id,
              player.toString(),
              epoch,
              seed,
              profile,
              now);
          return new Run(id, player.toString(), epoch, seed, profile, "PREPARING", null, null, now);
        });
  }

  public static Run active(Connection c, String player) throws SQLException {
    try (var s =
            Sql.prepare(
                c,
                "SELECT * FROM runs WHERE player=? AND state IN ('PREPARING','ACTIVE','SETTLING')",
                player);
        var r = s.executeQuery()) {
      return r.next() ? map(r) : null;
    }
  }

  public CompletableFuture<Run> active(UUID player) {
    return db.read(c -> active(c, player.toString()));
  }

  public CompletableFuture<List<Run>> pendingCleanup() {
    return db.read(
        c -> {
          var result = new ArrayList<Run>();
          try (var s = Sql.prepare(c, "SELECT * FROM runs WHERE state='COMPLETED'");
              var r = s.executeQuery()) {
            while (r.next()) result.add(map(r));
          }
          return result;
        });
  }

  public CompletableFuture<Void> activated(Run run, String folder) {
    return db.transaction(
        c -> {
          writable(c);
          if (Sql.update(
                  c,
                  "UPDATE runs SET state='ACTIVE',folder=? WHERE id=? AND state IN"
                      + " ('PREPARING','ACTIVE') AND epoch=?",
                  folder,
                  run.id(),
                  epoch(c))
              != 1) throw new IllegalStateException("Run no longer active");
          return null;
        });
  }

  public CompletableFuture<byte[]> hub(UUID player) {
    return db.read(
        c -> {
          try (var s =
                  Sql.prepare(c, "SELECT hub FROM contexts WHERE player=?", player.toString());
              var r = s.executeQuery()) {
            return r.next() ? r.getBytes(1) : null;
          }
        });
  }

  public CompletableFuture<String> context(UUID player) {
    return db.read(
        c -> Sql.string(c, "SELECT active FROM contexts WHERE player=?", player.toString()));
  }

  public CompletableFuture<Void> saveHub(UUID player, byte[] snapshot) {
    return db.transaction(
        c -> {
          Sql.update(
              c,
              "UPDATE contexts SET hub=? WHERE player=? AND active IS NULL",
              snapshot,
              player.toString());
          return null;
        });
  }

  public CompletableFuture<Void> enter(Run run, byte[] hub) {
    return db.transaction(
        c -> {
          writable(c);
          Run active = active(c, run.player());
          if (active == null || !active.id().equals(run.id()) || run.epoch() != epoch(c))
            throw new IllegalStateException("Run expired");
          Sql.update(
              c,
              "UPDATE contexts SET hub=?,active=? WHERE player=?",
              hub,
              "enter:" + run.id(),
              run.player());
          return null;
        });
  }

  public CompletableFuture<Void> entered(Run run) {
    return db.transaction(
        c -> {
          writable(c);
          if (epoch(c) != run.epoch()) throw new IllegalStateException("Run expired");
          Run active = active(c, run.player());
          if (active == null || !active.id().equals(run.id()))
            throw new IllegalStateException("Run no longer active");
          if (Sql.update(
                  c,
                  "UPDATE contexts SET active=? WHERE player=? AND active IN (?,?)",
                  run.id(),
                  run.player(),
                  "enter:" + run.id(),
                  run.id())
              != 1) throw new IllegalStateException("Run entry was superseded");
          return null;
        });
  }

  public CompletableFuture<byte[]> leave(Run run, byte[] snapshot) {
    return db.transaction(
        c -> {
          writable(c);
          Run active = active(c, run.player());
          if (run.epoch() != epoch(c) || active == null || !active.id().equals(run.id()))
            throw new IllegalStateException("Run no longer active");
          Sql.update(
              c, "UPDATE runs SET snapshot=? WHERE id=? AND state='ACTIVE'", snapshot, run.id());
          Sql.update(c, "UPDATE contexts SET active=NULL WHERE player=?", run.player());
          try (var s = Sql.prepare(c, "SELECT hub FROM contexts WHERE player=?", run.player());
              var r = s.executeQuery()) {
            return r.next() ? r.getBytes(1) : null;
          }
        });
  }

  /** Administrator cancellation, without settlement or rewards. */
  public CompletableFuture<Void> terminate(Run run) {
    return db.transaction(
        c -> {
          writable(c);
          if (run.epoch() != epoch(c)
              || Sql.update(
                      c,
                      "UPDATE runs SET state='COMPLETED',ended=? WHERE id=? AND player=? AND state"
                          + " IN ('PREPARING','ACTIVE')",
                      System.currentTimeMillis(),
                      run.id(),
                      run.player())
                  != 1) throw new IllegalStateException("Run no longer active");
          Sql.update(
              c,
              "UPDATE contexts SET active=NULL WHERE player=? AND active IN (?,?)",
              run.player(),
              run.id(),
              "enter:" + run.id());
          return null;
        });
  }

  public CompletableFuture<Void> checkpoint(Run run, byte[] snapshot) {
    return db.transaction(
        c -> {
          Sql.update(
              c, "UPDATE runs SET snapshot=? WHERE id=? AND state='ACTIVE'", snapshot, run.id());
          return null;
        });
  }

  public CompletableFuture<Void> cleaned(String id) {
    return db.transaction(
        c -> {
          Sql.update(c, "UPDATE runs SET state='DELETED' WHERE id=? AND state='COMPLETED'", id);
          return null;
        });
  }

  private static Run map(ResultSet r) throws SQLException {
    return new Run(
        r.getString("id"),
        r.getString("player"),
        r.getLong("epoch"),
        r.getLong("seed"),
        r.getString("profile"),
        r.getString("state"),
        r.getBytes("snapshot"),
        r.getString("folder"),
        r.getLong("created"));
  }
}
