package dev.roguelike.economy;

import static org.junit.jupiter.api.Assertions.*;

import dev.roguelike.achievement.*;
import dev.roguelike.database.*;
import dev.roguelike.run.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EconomyTest {
  @TempDir Path dir;

  @Test
  void integerArithmeticAndOverflow() {
    assertEquals(110, Money.multiply(100, 11000));
    assertEquals(1, Money.multiply(1, 11000));
    assertThrows(ArithmeticException.class, () -> Money.multiply(Long.MAX_VALUE, 20000));
    assertThrows(IllegalArgumentException.class, () -> Money.positive("-1"));
  }

  @Test
  void ledgerTransfersAndSettlementAreAtomicAndIdempotent() {
    try (var db = new SqliteDatabase()) {
      db.open(dir.resolve("db")).join();
      var repo = new RunRepository(db);
      UUID a = UUID.randomUUID(), b = UUID.randomUUID();
      repo.register(a, "a").join();
      repo.register(b, "b").join();
      var money = new EconomyService(db, 1000000, ZoneId.of("UTC"));
      assertTrue(money.adjust(a, 1000, "grant").join());
      assertFalse(money.adjust(a, 1000, "grant").join());
      money.transfer(a, b, 300, "pay").join();
      money.transfer(a, b, 300, "pay").join();
      assertEquals(700, money.balance(a).join());
      assertEquals(300, money.balance(b).join());
      assertThrows(Exception.class, () -> money.transfer(a, UUID.randomUUID(), 100, "bad").join());
      assertEquals(700, money.balance(a).join());
      Run run = repo.create(a, 3, "").join();
      var settlement = new SettlementService(db, money, 1000);
      assertEquals(80, settlement.settle(run.id(), a.toString(), 80, true).join());
      assertEquals(80, settlement.settle(run.id(), a.toString(), 800, true).join());
      assertEquals(780, money.balance(a).join());
      assertNull(repo.active(a).join());
      var achievements = new AchievementService(db, money);
      assertTrue(achievements.award(a.toString(), "lifetime", "first", 20, "first").join());
      assertFalse(achievements.award(a.toString(), "lifetime", "first", 20, "first").join());
      assertEquals(800, money.balance(a).join());
    }
  }
}
