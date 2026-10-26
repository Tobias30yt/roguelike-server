package dev.roguelike.run;

import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import java.util.concurrent.CompletableFuture;

public final class SettlementService {
  private final Database db;
  private final EconomyService economy;
  private final long rebirthBonus;

  public SettlementService(Database db, EconomyService economy, long rebirthBonus) {
    this.db = db;
    this.economy = economy;
    this.rebirthBonus = rebirthBonus;
  }

  public CompletableFuture<Long> settle(
      String runId, String player, long inventoryValue, boolean death) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          var rows = Sql.rows(c, "SELECT * FROM runs WHERE id=? AND player=?", runId, player);
          if (rows.isEmpty()) throw new IllegalArgumentException("Run not owned by player");
          var run = rows.getFirst();
          if (!run.get("state").equals("ACTIVE") && !run.get("state").equals("PREPARING"))
            return ((Number) run.get("reward")).longValue();
          long epoch = ((Number) run.get("epoch")).longValue();
          if (epoch != RunRepository.epoch(c)) throw new IllegalStateException("Expired run");
          long bonus =
              Sql.number(
                  c,
                  "SELECT COALESCE(SUM(delta),0) FROM ledger WHERE player=? AND reason=?",
                  player,
                  "pending:" + runId);
          long rebirth =
              Sql.number(
                  c, "SELECT rebirth FROM accounts WHERE player=? AND epoch=?", player, epoch);
          long reward =
              Money.multiply(
                  Math.addExact(inventoryValue, bonus),
                  Math.addExact(10000, Math.multiplyExact(rebirth, rebirthBonus)));
          reward = Math.min(reward, economy.cap() - EconomyService.balance(c, player, epoch));
          economy.post(c, "settle:" + runId, player, epoch, reward, "run-settlement");
          Sql.update(
              c,
              "UPDATE runs SET state='COMPLETED',ended=?,reward=?,snapshot=NULL WHERE id=?",
              System.currentTimeMillis(),
              reward,
              runId);
          Sql.update(c, "UPDATE contexts SET active=NULL WHERE player=?", player);
          economy.statistic(c, player, epoch, "coins", reward);
          economy.statistic(c, player, epoch, "runs", 1);
          if (death) economy.statistic(c, player, epoch, "deaths", 1);
          economy.statistic(
              c,
              player,
              epoch,
              "survival",
              Math.max(
                  0,
                  (System.currentTimeMillis() - ((Number) run.get("created")).longValue()) / 1000));
          if (Sql.number(
                  c,
                  "SELECT COALESCE(SUM(value),0) FROM statistics WHERE player=? AND"
                      + " metric='deaths'",
                  player)
              >= 100)
            Sql.update(
                c,
                "INSERT OR IGNORE INTO trophies VALUES(?,?,?)",
                player,
                "hundred_deaths",
                System.currentTimeMillis());
          return reward;
        });
  }
}
