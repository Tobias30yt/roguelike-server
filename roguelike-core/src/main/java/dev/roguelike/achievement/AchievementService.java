package dev.roguelike.achievement;

import dev.roguelike.database.*;
import dev.roguelike.economy.EconomyService;
import dev.roguelike.run.RunRepository;
import java.util.concurrent.CompletableFuture;

public final class AchievementService {
  private final Database db;
  private final EconomyService economy;

  public AchievementService(Database db, EconomyService economy) {
    this.db = db;
    this.economy = economy;
  }

  public CompletableFuture<Boolean> award(
      String player, String scope, String id, long coins, String trophy) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          long epoch = RunRepository.epoch(c);
          if (scope.startsWith("year:") && !scope.equals("year:" + epoch))
            throw new IllegalStateException("Expired achievement scope");
          if (scope.startsWith("run:")
              && Sql.number(
                      c,
                      "SELECT COUNT(*) FROM runs WHERE id=? AND player=? AND epoch=?",
                      scope.substring(4),
                      player,
                      epoch)
                  == 0) throw new IllegalStateException("Expired achievement run");
          if (Sql.update(
                  c,
                  "INSERT OR IGNORE INTO achievements VALUES(?,?,?,?)",
                  player,
                  scope,
                  id,
                  System.currentTimeMillis())
              == 0) return false;
          if (coins > 0)
            economy.post(
                c,
                "achievement:" + player + ":" + scope + ":" + id,
                player,
                epoch,
                coins,
                "achievement");
          if (trophy != null && !trophy.isBlank())
            Sql.update(
                c,
                "INSERT OR IGNORE INTO trophies VALUES(?,?,?)",
                player,
                trophy,
                System.currentTimeMillis());
          economy.statistic(c, player, epoch, "achievements", 1);
          return true;
        });
  }
}
