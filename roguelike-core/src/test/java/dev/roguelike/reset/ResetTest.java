package dev.roguelike.reset;

import static org.junit.jupiter.api.Assertions.*;

import dev.roguelike.achievement.*;
import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import dev.roguelike.run.*;
import dev.roguelike.season.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ResetTest {
  @TempDir Path dir;

  @Test
  void resetsArchiveProgressAndPreserveLifetimeData() {
    try (var db = new SqliteDatabase()) {
      db.open(dir.resolve("db")).join();
      UUID p = UUID.randomUUID();
      var repo = new RunRepository(db);
      repo.register(p, "p").join();
      var economy = new EconomyService(db, 100000, ZoneId.of("UTC"));
      economy.adjust(p, 500, "grant").join();
      new AchievementService(db, economy)
          .award(p.toString(), "lifetime", "first", 0, "first")
          .join();
      Run run = repo.create(p, 1, "").join();
      new SettlementService(db, economy, 1000).settle(run.id(), p.toString(), 50, true).join();
      var reset = new ResetService(db, dir.resolve("backups"));
      reset.begin("test").join();
      reset.begin("test").join();
      assertEquals(0, economy.balance(p).join());
      assertEquals(2, db.read(RunRepository::epoch).join());
      assertEquals(1, db.read(c -> Sql.number(c, "SELECT COUNT(*) FROM plots")).join());
      assertEquals(
          1, db.read(c -> Sql.number(c, "SELECT COUNT(*) FROM trophies WHERE id='first'")).join());
      assertTrue(
          db.read(c -> Sql.number(c, "SELECT COUNT(*) FROM leaderboard_history")).join() > 0);
      assertNull(reset.recover().join());
    }
  }

  @Test
  void preparedResetResumesAfterDatabaseReopen() {
    Path path = dir.resolve("db");
    try (var db = new SqliteDatabase()) {
      db.open(path).join();
      db.transaction(
              c -> {
                Sql.update(c, "UPDATE settings SET value='1' WHERE key='frozen'");
                Sql.update(
                    c,
                    "INSERT INTO resets(id,old_epoch,new_epoch,stage,created)"
                        + " VALUES('crash',1,2,'PREPARED',0)");
                return null;
              })
          .join();
    }
    try (var db = new SqliteDatabase()) {
      db.open(path).join();
      new ResetService(db, dir.resolve("backups")).recover().join();
      assertEquals(2, db.read(RunRepository::epoch).join());
      assertEquals(
          0, db.read(c -> Sql.number(c, "SELECT value FROM settings WHERE key='frozen'")).join());
    }
  }

  @Test
  void seasonsAndResetBoundariesUseLocalCalendar() {
    var date = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneId.of("Europe/Berlin"));
    assertEquals("WINTER:2026", Seasons.scope(date));
    assertEquals(2027, Seasons.cycle(date, 1, 1));
    assertEquals(2026, Seasons.cycle(date.minusSeconds(1), 1, 1));
  }
}
