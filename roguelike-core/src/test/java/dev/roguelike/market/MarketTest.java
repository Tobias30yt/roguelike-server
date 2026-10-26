package dev.roguelike.market;

import static org.junit.jupiter.api.Assertions.*;

import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import dev.roguelike.run.*;
import dev.roguelike.trade.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MarketTest {
  @TempDir Path dir;

  @Test
  void limitOrdersExecuteOnceAndInsufficientFundsRejectAtomically() {
    try (var db = new SqliteDatabase()) {
      db.open(dir.resolve("orders")).join();
      UUID p = UUID.randomUUID();
      new RunRepository(db).register(p, "p").join();
      var money = new EconomyService(db, 1000000, ZoneId.of("UTC"));
      money.adjust(p, 1000, "seed").join();
      var market =
          new MarketService(
              db, money, List.of(new AssetDefinition("test", 100, 1, 10000, 1000, 1, 100)));
      market.initialize().join();
      market.order(p, "test", 2, 150).join();
      market.tick(0).join();
      long balance = money.balance(p).join();
      assertEquals("FILLED", market.orders(p).join().getFirst().get("state"));
      market.tick(0).join();
      assertEquals(balance, money.balance(p).join());
      market.order(p, "test", 100, 10000).join();
      market.tick(0).join();
      assertEquals("REJECTED", market.orders(p).join().getFirst().get("state"));
      assertEquals(balance, money.balance(p).join());
    }
  }

  @Test
  void immediateRoundTripsCannotCreateMoney() {
    var a = new AssetDefinition("test", 100, 1, 10000, 1000, 2, 100);
    for (int qty = 1; qty <= 1000; qty++)
      assertTrue(
          MarketService.quote(a, 100, 1000, qty) > MarketService.quote(a, 100, 1000 - qty, -qty));
    assertThrows(IllegalArgumentException.class, () -> MarketService.quote(a, 100, 0, 1));
  }

  @Test
  void marketAndTwoPartyTransfersAreAtomic() {
    try (var db = new SqliteDatabase()) {
      db.open(dir.resolve("db")).join();
      var runs = new RunRepository(db);
      UUID a = UUID.randomUUID(), b = UUID.randomUUID();
      runs.register(a, "a").join();
      runs.register(b, "b").join();
      var money = new EconomyService(db, 1000000, ZoneId.of("UTC"));
      money.adjust(a, 10000, "start").join();
      var market =
          new MarketService(
              db, money, List.of(new AssetDefinition("test", 100, 1, 10000, 1000, 2, 100)));
      market.initialize().join();
      long paid = market.trade(a, "test", 10, "buy").join();
      assertEquals(paid, market.trade(a, "test", 10, "buy").join());
      assertEquals(1, market.portfolio(a).join().size());
      assertThrows(Exception.class, () -> market.trade(b, "test", -1, "bad").join());
      market.trade(a, "test", -10, "sell").join();
      assertTrue(money.balance(a).join() < 10000);
      var trade = new TradeService(db, money);
      trade.invite(a, b).join();
      trade.offer(a, 100).join();
      assertFalse(trade.accept(a).join());
      trade.offer(a, 200).join();
      assertFalse(trade.accept(b).join());
      assertTrue(trade.accept(a).join());
      assertEquals(200, money.balance(b).join());
      assertFalse(trade.accept(a).join());
    }
  }
}
