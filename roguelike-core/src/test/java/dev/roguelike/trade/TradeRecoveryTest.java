package dev.roguelike.trade;

import static org.junit.jupiter.api.Assertions.*;

import dev.roguelike.database.*;
import dev.roguelike.economy.*;
import dev.roguelike.run.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TradeRecoveryTest {
  @TempDir Path dir;

  @Test
  void escrowSurvivesRestartAndCanOnlyBeClaimedOnce() {
    Path path = dir.resolve("db");
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    try (var db = new SqliteDatabase()) {
      db.open(path).join();
      var repo = new RunRepository(db);
      repo.register(a, "a").join();
      repo.register(b, "b").join();
      var trades = new TradeService(db, new EconomyService(db, 10000, ZoneId.of("UTC")));
      trades.invite(a, b).join();
      trades.escrow(a, new byte[] {1, 2}, new byte[] {3}).join();
      assertArrayEquals(new byte[] {3}, repo.hub(a).join());
    }
    try (var db = new SqliteDatabase()) {
      db.open(path).join();
      var trades = new TradeService(db, new EconomyService(db, 10000, ZoneId.of("UTC")));
      trades.recover().join();
      var items = trades.deliveries(a).join();
      assertEquals(1, items.size());
      var ids = List.of((String) items.getFirst().get("id"));
      trades.claim(a, ids, new byte[] {4}).join();
      assertThrows(Exception.class, () -> trades.claim(a, ids, new byte[] {5}).join());
      assertArrayEquals(new byte[] {4}, new RunRepository(db).hub(a).join());
      assertTrue(trades.deliveries(a).join().isEmpty());
    }
  }

  @Test
  void staleConfirmationCannotAcceptChangedOffer() {
    try (var db = new SqliteDatabase()) {
      db.open(dir.resolve("db")).join();
      var repo = new RunRepository(db);
      UUID a = UUID.randomUUID(), b = UUID.randomUUID();
      repo.register(a, "a").join();
      repo.register(b, "b").join();
      var trades = new TradeService(db, new EconomyService(db, 10000, ZoneId.of("UTC")));
      trades.invite(a, b).join();
      trades.offer(a, 1).join();
      assertThrows(Exception.class, () -> trades.accept(b, 0).join());
    }
  }
}
