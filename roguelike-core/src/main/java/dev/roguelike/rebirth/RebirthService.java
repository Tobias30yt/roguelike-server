package dev.roguelike.rebirth;

import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import dev.roguelike.run.RunRepository;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class RebirthService {
  private final Database db;
  private final EconomyService economy;
  private final long baseCost, costStep, runsRequired;
  private final boolean clearUnlocks, clearBalance;

  public RebirthService(
      Database db,
      EconomyService economy,
      long baseCost,
      long costStep,
      long runsRequired,
      boolean clearUnlocks,
      boolean clearBalance) {
    if (baseCost < 1 || costStep < 0 || runsRequired < 0)
      throw new IllegalArgumentException("Invalid rebirth config");
    this.db = db;
    this.economy = economy;
    this.baseCost = baseCost;
    this.costStep = costStep;
    this.runsRequired = runsRequired;
    this.clearUnlocks = clearUnlocks;
    this.clearBalance = clearBalance;
  }

  public long cost(long level) {
    return Math.addExact(baseCost, Math.multiplyExact(level, costStep));
  }

  public CompletableFuture<Long> level(UUID p) {
    return db.read(
        c ->
            Sql.number(
                c,
                "SELECT rebirth FROM accounts WHERE player=? AND epoch=?",
                p.toString(),
                RunRepository.epoch(c)));
  }

  public CompletableFuture<Long> rebirth(UUID player, long expectedLevel) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          String p = player.toString();
          long epoch = RunRepository.epoch(c);
          if (RunRepository.active(c, p) != null)
            throw new IllegalArgumentException("End your active run before rebirth");
          long level =
              Sql.number(c, "SELECT rebirth FROM accounts WHERE player=? AND epoch=?", p, epoch);
          if (level != expectedLevel)
            throw new IllegalArgumentException("Rebirth confirmation expired");
          if (Sql.number(
                  c,
                  "SELECT COALESCE(SUM(value),0) FROM statistics WHERE player=? AND epoch=? AND"
                      + " metric='runs'",
                  p,
                  epoch)
              < Math.multiplyExact(runsRequired, level + 1))
            throw new IllegalArgumentException("More completed runs required");
          long cost = cost(level);
          long balance = EconomyService.balance(c, p, epoch);
          if (balance < cost) throw new IllegalArgumentException("Not enough Coins");
          economy.post(
              c,
              "rebirth:" + epoch + ":" + p + ":" + level,
              p,
              epoch,
              -(clearBalance ? balance : cost),
              "rebirth");
          Sql.update(
              c, "UPDATE accounts SET rebirth=rebirth+1 WHERE player=? AND epoch=?", p, epoch);
          if (clearUnlocks)
            Sql.update(c, "DELETE FROM unlocks WHERE player=? AND epoch=?", p, epoch);
          economy.statistic(c, p, epoch, "rebirths", 1);
          if (level + 1 == 10 || level + 1 == 50)
            Sql.update(
                c,
                "INSERT OR IGNORE INTO trophies VALUES(?,?,?)",
                p,
                "rebirth_" + (level + 1),
                System.currentTimeMillis());
          return level + 1;
        });
  }
}
