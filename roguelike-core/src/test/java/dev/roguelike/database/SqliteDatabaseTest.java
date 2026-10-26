package dev.roguelike.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDatabaseTest {
  @TempDir Path directory;

  @Test
  void migrationsAreRepeatableAndFailedTransactionsRollback() {
    Path path = directory.resolve("test.db");
    try (var db = new SqliteDatabase()) {
      db.open(path).join();
      assertThrows(
          Exception.class,
          () ->
              db.transaction(
                      c -> {
                        Sql.update(c, "INSERT INTO settings VALUES('test','x')");
                        throw new IllegalStateException();
                      })
                  .join());
      assertEquals(
          0L, db.read(c -> Sql.number(c, "SELECT COUNT(*) FROM settings WHERE key='test'")).join());
      db.backup(directory.resolve("backup.db")).join();
    }
    try (var db = new SqliteDatabase()) {
      db.open(path).join();
      assertEquals(
          3L, db.read(c -> Sql.number(c, "SELECT COUNT(*) FROM schema_migrations")).join());
    }
  }
}
