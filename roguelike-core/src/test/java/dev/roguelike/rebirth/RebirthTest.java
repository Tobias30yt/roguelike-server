package dev.roguelike.rebirth;

import static org.junit.jupiter.api.Assertions.*;

import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import dev.roguelike.run.*;
import dev.roguelike.unlock.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RebirthTest {
  @TempDir Path dir;

  @Test
  void rebirthResetsConfiguredProgressButNotPlotsOrTrophies() {
    try (var db = new SqliteDatabase()) {
      db.open(dir.resolve("db")).join();
      UUID p = UUID.randomUUID();
      var runs = new RunRepository(db);
      runs.register(p, "p").join();
      var money = new EconomyService(db, 100000, ZoneId.of("UTC"));
      money.adjust(p, 5000, "grant").join();
      db.transaction(
              c -> {
                Sql.update(c, "INSERT INTO unlocks VALUES(?,1,'stone')", p.toString());
                Sql.update(c, "INSERT INTO trophies VALUES(?,'test',0)", p.toString());
                return null;
              })
          .join();
      var rebirth = new RebirthService(db, money, 1000, 500, 0, true, true);
      assertEquals(1, rebirth.rebirth(p, 0).join());
      assertEquals(0, money.balance(p).join());
      assertEquals(0, db.read(c -> Sql.number(c, "SELECT COUNT(*) FROM unlocks")).join());
      assertEquals(1, db.read(c -> Sql.number(c, "SELECT COUNT(*) FROM trophies")).join());
      assertEquals(1, db.read(c -> Sql.number(c, "SELECT COUNT(*) FROM plots")).join());
      assertThrows(Exception.class, () -> rebirth.rebirth(p, 0).join());
      money.adjust(p, 5000, "grant2").join();
      runs.create(p, 2, "").join();
      assertThrows(Exception.class, () -> rebirth.rebirth(p, 1).join());
    }
  }

  @Test
  void unlockPurchasesEnforcePrerequisitesAndChargeOnce() {
    try (var db = new SqliteDatabase()) {
      db.open(dir.resolve("db")).join();
      UUID p = UUID.randomUUID();
      new RunRepository(db).register(p, "p").join();
      var money = new EconomyService(db, 10000, ZoneId.of("UTC"));
      money.adjust(p, 500, "grant").join();
      var unlocks =
          new UnlockService(
              db,
              money,
              List.of(
                  new UnlockDefinition("stone", "tools", "Stone", 100, Set.of(), Set.of(), "", 0),
                  new UnlockDefinition(
                      "iron", "tools", "Iron", 200, Set.of("stone"), Set.of(), "iron", 0)));
      assertThrows(Exception.class, () -> unlocks.buy(p, "iron").join());
      assertEquals(500, money.balance(p).join());
      unlocks.buy(p, "stone").join();
      unlocks.buy(p, "iron").join();
      assertThrows(Exception.class, () -> unlocks.buy(p, "iron").join());
      assertEquals(200, money.balance(p).join());
    }
  }
}
