package dev.roguelike.economy;

import dev.roguelike.database.*;
import dev.roguelike.run.RunRepository;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class EconomyService {
  private final Database db;
  private final long cap;
  private final ZoneId zone;

  public EconomyService(Database db, long cap, ZoneId zone) {
    this.db = db;
    this.cap = cap;
    this.zone = zone;
  }

  public long cap() {
    return cap;
  }

  public CompletableFuture<Long> balance(UUID id) {
    return db.read(c -> balance(c, id.toString(), RunRepository.epoch(c)));
  }

  public static long balance(Connection c, String player, long epoch) throws SQLException {
    return Sql.number(c, "SELECT balance FROM accounts WHERE player=? AND epoch=?", player, epoch);
  }

  public boolean post(Connection c, String id, String player, long epoch, long delta, String reason)
      throws SQLException {
    if (Sql.number(c, "SELECT COUNT(*) FROM ledger WHERE id=?", id) > 0) return false;
    if (epoch != RunRepository.epoch(c)) throw new IllegalStateException("Stale competitive year");
    long next = Math.addExact(balance(c, player, epoch), delta);
    if (next < 0 || next > cap)
      throw new IllegalArgumentException("Insufficient funds or balance limit reached");
    if (Sql.update(
            c, "UPDATE accounts SET balance=? WHERE player=? AND epoch=?", next, player, epoch)
        != 1) throw new IllegalStateException("Account not registered");
    Sql.update(
        c,
        "INSERT INTO ledger VALUES(?,?,?,?,?,?)",
        id,
        epoch,
        player,
        delta,
        reason,
        System.currentTimeMillis());
    return true;
  }

  public CompletableFuture<Boolean> transfer(UUID from, UUID to, long amount, String tx) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          if (from.equals(to) || amount <= 0)
            throw new IllegalArgumentException("Invalid transfer");
          long epoch = RunRepository.epoch(c);
          if (Sql.number(c, "SELECT COUNT(*) FROM ledger WHERE id=?", tx + ":debit") > 0)
            return false;
          post(c, tx + ":debit", from.toString(), epoch, -amount, "transfer");
          post(c, tx + ":credit", to.toString(), epoch, amount, "transfer");
          return true;
        });
  }

  public CompletableFuture<Boolean> adjust(UUID player, long delta, String tx) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          return post(c, tx, player.toString(), RunRepository.epoch(c), delta, "admin");
        });
  }

  public void statistic(Connection c, String player, long epoch, String metric, long amount)
      throws SQLException {
    String day = LocalDate.now(zone).toString();
    Sql.update(
        c,
        "INSERT INTO statistics VALUES(?,?,?,?,?) ON CONFLICT(player,epoch,metric,day) DO UPDATE"
            + " SET value=value+excluded.value",
        player,
        epoch,
        metric,
        amount,
        day);
  }
}
