package dev.roguelike.run;

import static org.junit.jupiter.api.Assertions.*;

import dev.roguelike.database.*;
import dev.roguelike.world.ManagedWorldFiles;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RunRepositoryTest {
  @TempDir Path dir;

  @Test
  void ownerHasExactlyOneActiveRunAndSurvivesRestart() {
    UUID player = UUID.randomUUID();
    String run;
    try (var db = new SqliteDatabase()) {
      db.open(dir.resolve("db")).join();
      var repo = new RunRepository(db);
      repo.register(player, "Player").join();
      run = repo.create(player, 3, "").join().id();
      assertThrows(Exception.class, () -> repo.create(player, 3, "").join());
      assertNull(repo.active(UUID.randomUUID()).join());
    }
    try (var db = new SqliteDatabase()) {
      db.open(dir.resolve("db")).join();
      assertEquals(run, new RunRepository(db).active(player).join().id());
    }
  }

  @Test
  void terminationClearsContextAndRejectsLateCallbacksFromOldRun() {
    try (var db = new SqliteDatabase()) {
      db.open(dir.resolve("db")).join();
      var repo = new RunRepository(db);
      UUID player = UUID.randomUUID();
      repo.register(player, "Player").join();
      Run old = repo.create(player, 3, "").join();
      repo.activated(old, dir.resolve(old.id()).toString()).join();
      repo.enter(old, new byte[] {1, 2}).join();
      repo.entered(old).join();
      repo.terminate(old).join();
      assertNull(repo.context(player).join());
      assertNull(repo.active(player).join());
      assertArrayEquals(new byte[] {1, 2}, repo.hub(player).join());
      Run next = repo.create(player, 3, "").join();
      repo.activated(next, dir.resolve(next.id()).toString()).join();
      repo.enter(next, new byte[] {3, 4}).join();
      assertThrows(Exception.class, () -> repo.enter(old, new byte[] {9}).join());
      assertThrows(Exception.class, () -> repo.entered(old).join());
      assertThrows(Exception.class, () -> repo.leave(old, new byte[] {9}).join());
      assertEquals("enter:" + next.id(), repo.context(player).join());
      assertArrayEquals(new byte[] {3, 4}, repo.hub(player).join());
      repo.entered(next).join();
      repo.leave(next, new byte[] {5}).join();
      assertThrows(Exception.class, () -> repo.entered(next).join());
      assertNull(repo.context(player).join());
    }
  }

  @Test
  void deletionCannotEscapeManagedDirectory() throws Exception {
    var files = new ManagedWorldFiles(dir.resolve("runs"));
    String id = UUID.randomUUID().toString();
    Path outside = dir.resolve("hub");
    Files.createDirectories(outside);
    assertThrows(Exception.class, () -> files.delete(id, outside));
    assertTrue(Files.exists(outside));
    Path run = dir.resolve("runs").resolve(id);
    Files.createDirectories(run);
    Files.writeString(run.resolve("test"), "data");
    files.delete(id, run);
    assertFalse(Files.exists(run));
  }
}
