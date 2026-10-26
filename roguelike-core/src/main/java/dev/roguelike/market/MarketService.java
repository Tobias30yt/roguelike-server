package dev.roguelike.market;

import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import dev.roguelike.run.RunRepository;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class MarketService {
  private final Database db;
  private final EconomyService economy;
  private final Map<String, AssetDefinition> assets;

  public MarketService(Database db, EconomyService economy, List<AssetDefinition> assets) {
    this.db = db;
    this.economy = economy;
    var map = new LinkedHashMap<String, AssetDefinition>();
    for (var asset : assets)
      if (map.put(asset.id(), asset) != null) throw new IllegalArgumentException("Duplicate asset");
    this.assets = Map.copyOf(map);
  }

  public Collection<AssetDefinition> definitions() {
    return assets.values();
  }

  public CompletableFuture<Void> initialize() {
    return db.transaction(
        c -> {
          long epoch = RunRepository.epoch(c);
          for (var a : assets.values())
            Sql.update(
                c,
                "INSERT OR IGNORE INTO assets VALUES(?,?,?,?)",
                epoch,
                a.id(),
                a.initialPrice(),
                a.liquidity());
          return null;
        });
  }

  public static long quote(AssetDefinition a, long anchor, long supply, int quantity) {
    if (quantity == 0 || Math.abs((long) quantity) > 1000)
      throw new IllegalArgumentException("Quantity must be 1..1000");
    if (quantity > 0 && supply < quantity || quantity < 0 && supply - quantity > a.liquidity())
      throw new IllegalArgumentException("Market liquidity exceeded");
    long total = 0;
    for (int i = 0; i < Math.abs(quantity); i++)
      total = Math.addExact(total, a.marginal(anchor, quantity > 0 ? supply - i : supply + i + 1));
    long fee = Math.max(1, Money.multiply(total, a.feeBasisPoints()));
    return quantity > 0 ? Math.addExact(total, fee) : Math.max(0, total - fee);
  }

  public CompletableFuture<Long> trade(UUID player, String asset, int quantity, String tx) {
    return db.transaction(c -> execute(c, player, asset, quantity, tx));
  }

  private long execute(java.sql.Connection c, UUID player, String asset, int quantity, String tx)
      throws Exception {
    RunRepository.writable(c);
    long epoch = RunRepository.epoch(c);
    if (Sql.number(c, "SELECT COUNT(*) FROM market_history WHERE id=?", tx) > 0)
      return Sql.number(c, "SELECT price FROM market_history WHERE id=?", tx);
    AssetDefinition a = Objects.requireNonNull(assets.get(asset), "Unknown asset");
    long supply = Sql.number(c, "SELECT supply FROM assets WHERE epoch=? AND id=?", epoch, asset);
    long anchor = Sql.number(c, "SELECT price FROM assets WHERE epoch=? AND id=?", epoch, asset);
    long total = quote(a, anchor, supply, quantity);
    String p = player.toString();
    long owned =
        Sql.number(
            c,
            "SELECT quantity FROM holdings WHERE player=? AND epoch=? AND asset=?",
            p,
            epoch,
            asset);
    long next = Math.addExact(owned, quantity);
    if (next < 0) throw new IllegalArgumentException("Not enough assets");
    economy.post(c, tx, p, epoch, quantity > 0 ? -total : total, "market:" + asset);
    Sql.update(
        c,
        "INSERT INTO holdings VALUES(?,?,?,?) ON CONFLICT(player,epoch,asset) DO UPDATE SET"
            + " quantity=excluded.quantity",
        p,
        epoch,
        asset,
        next);
    Sql.update(
        c, "UPDATE assets SET supply=supply-? WHERE epoch=? AND id=?", quantity, epoch, asset);
    Sql.update(
        c,
        "INSERT INTO market_history VALUES(?,?,?,?,?,?,?)",
        tx,
        epoch,
        asset,
        p,
        quantity,
        total,
        System.currentTimeMillis());
    return total;
  }

  public CompletableFuture<String> order(UUID player, String asset, int quantity, long limit) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          if (!assets.containsKey(asset)
              || quantity == 0
              || Math.abs((long) quantity) > 1000
              || limit < 1
              || limit > assets.get(asset).maximum())
            throw new IllegalArgumentException("Invalid limit order");
          if (Sql.number(
                  c,
                  "SELECT COUNT(*) FROM market_orders WHERE player=? AND state='OPEN'",
                  player.toString())
              >= 20) throw new IllegalArgumentException("Maximum 20 open orders");
          String id = UUID.randomUUID().toString();
          Sql.update(
              c,
              "INSERT INTO market_orders VALUES(?,?,?,?,?,?,?,'OPEN')",
              id,
              RunRepository.epoch(c),
              player.toString(),
              asset,
              quantity > 0 ? "BUY" : "SELL",
              Math.abs(quantity),
              limit);
          return id;
        });
  }

  public CompletableFuture<Void> cancelOrder(UUID player, String id) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          Sql.update(
              c,
              "UPDATE market_orders SET state='CANCELLED' WHERE id=? AND player=? AND state='OPEN'",
              id,
              player.toString());
          return null;
        });
  }

  public CompletableFuture<List<Map<String, Object>>> orders(UUID player) {
    return db.read(
        c ->
            Sql.rows(
                c,
                "SELECT id,asset,side,quantity,limit_price,state FROM market_orders WHERE player=?"
                    + " AND epoch=? ORDER BY rowid DESC LIMIT 100",
                player.toString(),
                RunRepository.epoch(c)));
  }

  public CompletableFuture<Void> tick(int volatilityBasisPoints) {
    if (volatilityBasisPoints < 0 || volatilityBasisPoints > 500)
      throw new IllegalArgumentException("Volatility must be 0..500 basis points");
    return db.transaction(
        c -> {
          if (Sql.number(c, "SELECT value FROM settings WHERE key='frozen'") != 0) return null;
          long epoch = RunRepository.epoch(c);
          var random = new java.security.SecureRandom();
          for (var a : assets.values()) {
            long price =
                Sql.number(c, "SELECT price FROM assets WHERE epoch=? AND id=?", epoch, a.id());
            long move = Money.multiply(price, volatilityBasisPoints);
            long next =
                Math.clamp(
                    price + (move == 0 ? 0 : random.nextLong(-move, move + 1)),
                    a.minimum(),
                    a.maximum());
            Sql.update(c, "UPDATE assets SET price=? WHERE epoch=? AND id=?", next, epoch, a.id());
          }
          for (var row :
              Sql.rows(
                  c,
                  "SELECT * FROM market_orders WHERE epoch=? AND state='OPEN' ORDER BY rowid LIMIT"
                      + " 100",
                  epoch)) {
            String id = (String) row.get("id"), asset = (String) row.get("asset");
            int quantity =
                ((Number) row.get("quantity")).intValue()
                    * (row.get("side").equals("BUY") ? 1 : -1);
            var savepoint = c.setSavepoint();
            try {
              var a = assets.get(asset);
              if (a == null) throw new IllegalArgumentException("Asset removed");
              long
                  supply =
                      Sql.number(
                          c, "SELECT supply FROM assets WHERE epoch=? AND id=?", epoch, asset),
                  anchor =
                      Sql.number(
                          c, "SELECT price FROM assets WHERE epoch=? AND id=?", epoch, asset);
              long total = quote(a, anchor, supply, quantity);
              long limit =
                  Math.multiplyExact(
                      ((Number) row.get("limit_price")).longValue(), Math.abs(quantity));
              if (quantity > 0 ? total <= limit : total >= limit) {
                execute(
                    c, UUID.fromString((String) row.get("player")), asset, quantity, "order:" + id);
                Sql.update(c, "UPDATE market_orders SET state='FILLED' WHERE id=?", id);
              }
            } catch (IllegalArgumentException | IllegalStateException e) {
              c.rollback(savepoint);
              Sql.update(c, "UPDATE market_orders SET state='REJECTED' WHERE id=?", id);
            } finally {
              c.releaseSavepoint(savepoint);
            }
          }
          return null;
        });
  }

  public CompletableFuture<List<Map<String, Object>>> prices() {
    return db.read(c -> Sql.rows(c, "SELECT * FROM assets WHERE epoch=?", RunRepository.epoch(c)));
  }

  public CompletableFuture<List<Map<String, Object>>> portfolio(UUID player) {
    return db.read(
        c ->
            Sql.rows(
                c,
                "SELECT h.asset,h.quantity,a.price,a.supply FROM holdings h JOIN assets a ON"
                    + " a.epoch=h.epoch AND a.id=h.asset WHERE h.player=? AND h.epoch=? AND"
                    + " h.quantity>0",
                player.toString(),
                RunRepository.epoch(c)));
  }

  public CompletableFuture<List<Map<String, Object>>> history(String asset) {
    return db.read(
        c ->
            Sql.rows(
                c,
                "SELECT quantity,price,created FROM market_history WHERE asset=? AND epoch=? ORDER"
                    + " BY created DESC LIMIT 30",
                asset,
                RunRepository.epoch(c)));
  }
}
