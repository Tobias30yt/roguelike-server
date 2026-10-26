package dev.roguelike.trade;

import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import dev.roguelike.run.RunRepository;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Two-party exchange with durable item escrow and deferred, idempotent delivery. */
public final class TradeService {
  private final Database db;
  private final EconomyService economy;

  public TradeService(Database db, EconomyService economy) {
    this.db = db;
    this.economy = economy;
  }

  public CompletableFuture<String> invite(UUID a, UUID b) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          if (a.equals(b)) throw new IllegalArgumentException("Cannot trade with yourself");
          for (UUID p : List.of(a, b))
            if (Sql.number(
                    c,
                    "SELECT COUNT(*) FROM trades WHERE (a=? OR b=?) AND state='OPEN'",
                    p.toString(),
                    p.toString())
                > 0) throw new IllegalStateException("Player already trading");
          String id = UUID.randomUUID().toString();
          Sql.update(
              c,
              "INSERT INTO trades(id,epoch,a,b,state) VALUES(?,?,?,?,'OPEN')",
              id,
              RunRepository.epoch(c),
              a.toString(),
              b.toString());
          return id;
        });
  }

  public CompletableFuture<Map<String, Object>> current(UUID p) {
    return db.read(
        c -> {
          var rows =
              Sql.rows(
                  c,
                  "SELECT * FROM trades WHERE (a=? OR b=?) AND state='OPEN'",
                  p.toString(),
                  p.toString());
          return rows.isEmpty() ? null : rows.getFirst();
        });
  }

  public CompletableFuture<Void> offer(UUID p, long coins) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          if (coins < 0) throw new IllegalArgumentException("Negative offer");
          Sql.update(
              c,
              "UPDATE trades SET coins_a=CASE WHEN a=? THEN ? ELSE coins_a END,coins_b=CASE WHEN"
                  + " b=? THEN ? ELSE coins_b END,accepted_a=0,accepted_b=0,revision=revision+1"
                  + " WHERE (a=? OR b=?) AND state='OPEN' AND epoch=?",
              p.toString(),
              coins,
              p.toString(),
              coins,
              p.toString(),
              p.toString(),
              RunRepository.epoch(c));
          return null;
        });
  }

  public CompletableFuture<Boolean> accept(UUID p) {
    return current(p)
        .thenCompose(
            row ->
                row == null
                    ? CompletableFuture.completedFuture(false)
                    : accept(p, ((Number) row.get("revision")).longValue()));
  }

  public CompletableFuture<Boolean> accept(UUID p, long revision) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          long epoch = RunRepository.epoch(c);
          if (Sql.update(
                  c,
                  "UPDATE trades SET accepted_a=CASE WHEN a=? THEN 1 ELSE accepted_a"
                      + " END,accepted_b=CASE WHEN b=? THEN 1 ELSE accepted_b END WHERE (a=? OR"
                      + " b=?) AND state='OPEN' AND epoch=? AND revision=?",
                  p.toString(),
                  p.toString(),
                  p.toString(),
                  p.toString(),
                  epoch,
                  revision)
              != 1) throw new IllegalStateException("Offer changed. Reopen trade and review it.");
          var rows =
              Sql.rows(
                  c,
                  "SELECT * FROM trades WHERE (a=? OR b=?) AND state='OPEN' AND accepted_a=1 AND"
                      + " accepted_b=1 AND epoch=?",
                  p.toString(),
                  p.toString(),
                  epoch);
          if (rows.isEmpty()) return false;
          var row = rows.getFirst();
          String a = (String) row.get("a"), b = (String) row.get("b"), id = (String) row.get("id");
          long ca = ((Number) row.get("coins_a")).longValue(),
              cb = ((Number) row.get("coins_b")).longValue();
          if (EconomyService.balance(c, a, epoch) < ca || EconomyService.balance(c, b, epoch) < cb)
            throw new IllegalArgumentException("Offer no longer funded");
          var offers = Sql.rows(c, "SELECT * FROM trade_assets WHERE trade_id=?", id);
          for (var offer : offers) {
            String owner = (String) offer.get("player"), asset = (String) offer.get("asset");
            long quantity = ((Number) offer.get("quantity")).longValue();
            if (Sql.number(
                    c,
                    "SELECT quantity FROM holdings WHERE player=? AND epoch=? AND asset=?",
                    owner,
                    epoch,
                    asset)
                < quantity) throw new IllegalArgumentException("Asset offer no longer funded");
          }
          for (var offer : offers) {
            String owner = (String) offer.get("player"),
                recipient = owner.equals(a) ? b : a,
                asset = (String) offer.get("asset");
            long quantity = ((Number) offer.get("quantity")).longValue();
            Sql.update(
                c,
                "UPDATE holdings SET quantity=quantity-? WHERE player=? AND epoch=? AND asset=?",
                quantity,
                owner,
                epoch,
                asset);
            Sql.update(
                c,
                "INSERT INTO holdings VALUES(?,?,?,?) ON CONFLICT(player,epoch,asset) DO UPDATE SET"
                    + " quantity=quantity+excluded.quantity",
                recipient,
                epoch,
                asset,
                quantity);
          }
          economy.post(c, id + ":a", a, epoch, Math.subtractExact(cb, ca), "player-trade");
          economy.post(c, id + ":b", b, epoch, Math.subtractExact(ca, cb), "player-trade");
          Sql.update(
              c,
              "UPDATE item_escrow SET recipient=CASE WHEN owner=? THEN ? ELSE ? END,state='READY'"
                  + " WHERE trade_id=? AND state='HELD'",
              a,
              b,
              a,
              id);
          Sql.update(c, "UPDATE trades SET state='COMPLETED' WHERE id=?", id);
          return true;
        });
  }

  public CompletableFuture<Void> offerAsset(UUID player, String asset, long quantity) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          if (quantity <= 0) throw new IllegalArgumentException("Positive quantity required");
          String p = player.toString();
          long epoch = RunRepository.epoch(c);
          String id =
              Sql.string(
                  c,
                  "SELECT id FROM trades WHERE (a=? OR b=?) AND state='OPEN' AND epoch=?",
                  p,
                  p,
                  epoch);
          if (id == null) throw new IllegalArgumentException("No open trade");
          if (Sql.number(
                  c,
                  "SELECT quantity FROM holdings WHERE player=? AND epoch=? AND asset=?",
                  p,
                  epoch,
                  asset)
              < quantity) throw new IllegalArgumentException("Insufficient assets");
          Sql.update(
              c,
              "INSERT INTO trade_assets VALUES(?,?,?,?) ON CONFLICT(trade_id,player,asset) DO"
                  + " UPDATE SET quantity=excluded.quantity",
              id,
              p,
              asset,
              quantity);
          Sql.update(
              c, "UPDATE trades SET accepted_a=0,accepted_b=0,revision=revision+1 WHERE id=?", id);
          return null;
        });
  }

  public CompletableFuture<Void> escrow(UUID player, byte[] item, byte[] hubAfter) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          String p = player.toString();
          long epoch = RunRepository.epoch(c);
          if (Sql.string(c, "SELECT active FROM contexts WHERE player=?", p) != null)
            throw new IllegalArgumentException("Trade items only from the hub");
          String id =
              Sql.string(
                  c,
                  "SELECT id FROM trades WHERE (a=? OR b=?) AND state='OPEN' AND epoch=?",
                  p,
                  p,
                  epoch);
          if (id == null) throw new IllegalArgumentException("No open trade");
          if (Sql.number(c, "SELECT COUNT(*) FROM item_escrow WHERE trade_id=?", id) >= 18)
            throw new IllegalArgumentException("Trade item limit reached");
          Sql.update(
              c,
              "INSERT INTO item_escrow VALUES(?,?,?,NULL,?,?,'HELD')",
              UUID.randomUUID().toString(),
              id,
              p,
              epoch,
              item);
          Sql.update(c, "UPDATE contexts SET hub=? WHERE player=?", hubAfter, p);
          Sql.update(
              c, "UPDATE trades SET accepted_a=0,accepted_b=0,revision=revision+1 WHERE id=?", id);
          return null;
        });
  }

  public CompletableFuture<List<Map<String, Object>>> items(String trade) {
    return db.read(
        c ->
            Sql.rows(
                c, "SELECT owner,item FROM item_escrow WHERE trade_id=? AND state='HELD'", trade));
  }

  public CompletableFuture<List<Map<String, Object>>> assets(String trade) {
    return db.read(
        c -> Sql.rows(c, "SELECT player,asset,quantity FROM trade_assets WHERE trade_id=?", trade));
  }

  public CompletableFuture<List<Map<String, Object>>> deliveries(UUID p) {
    return db.read(
        c ->
            Sql.rows(
                c,
                "SELECT id,item FROM item_escrow WHERE recipient=? AND state='READY' AND epoch=?"
                    + " LIMIT 36",
                p.toString(),
                RunRepository.epoch(c)));
  }

  public CompletableFuture<Void> claim(UUID player, List<String> ids, byte[] hubAfter) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          String p = player.toString();
          if (Sql.string(c, "SELECT active FROM contexts WHERE player=?", p) != null)
            throw new IllegalArgumentException("Claim only in hub");
          for (String id : ids)
            if (Sql.update(
                    c,
                    "UPDATE item_escrow SET state='CLAIMED' WHERE id=? AND recipient=? AND"
                        + " state='READY' AND epoch=?",
                    id,
                    p,
                    RunRepository.epoch(c))
                != 1) throw new IllegalStateException("Delivery already claimed");
          Sql.update(c, "UPDATE contexts SET hub=? WHERE player=?", hubAfter, p);
          return null;
        });
  }

  public CompletableFuture<Void> cancel(UUID p) {
    return db.transaction(
        c -> {
          Sql.update(
              c,
              "UPDATE item_escrow SET recipient=owner,state='READY' WHERE state='HELD' AND trade_id"
                  + " IN (SELECT id FROM trades WHERE (a=? OR b=?) AND state='OPEN')",
              p.toString(),
              p.toString());
          Sql.update(
              c,
              "UPDATE trades SET state='CANCELLED' WHERE (a=? OR b=?) AND state='OPEN'",
              p.toString(),
              p.toString());
          return null;
        });
  }

  public CompletableFuture<Void> recover() {
    return db.transaction(
        c -> {
          Sql.update(
              c,
              "UPDATE item_escrow SET recipient=owner,state='READY' WHERE state='HELD' AND trade_id"
                  + " IN (SELECT id FROM trades WHERE state='OPEN')");
          Sql.update(c, "UPDATE trades SET state='CANCELLED' WHERE state='OPEN'");
          return null;
        });
  }
}
