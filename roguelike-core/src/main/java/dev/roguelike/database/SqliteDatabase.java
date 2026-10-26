package dev.roguelike.database;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;

public final class SqliteDatabase implements Database {
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(2048),
          r -> new Thread(r, "roguelike-database"),
          new ThreadPoolExecutor.AbortPolicy());
  private Connection connection;

  public CompletableFuture<Void> open(Path path) {
    return submit(
        () -> {
          Files.createDirectories(path.toAbsolutePath().getParent());
          Class.forName("org.sqlite.JDBC");
          connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
          try (var s = connection.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA busy_timeout=5000");
          }
          migrate();
          if (!"ok".equals(Sql.string(connection, "PRAGMA integrity_check")))
            throw new SQLException("SQLite integrity check failed");
          return null;
        });
  }

  private void migrate() throws Exception {
    Sql.update(
        connection, "CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY)");
    for (int version = 1; version <= 3; version++) {
      if (Sql.number(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version=?", version)
          > 0) continue;
      String script;
      try (var stream = getClass().getResourceAsStream("/db/V" + version + ".sql")) {
        if (stream == null) throw new IllegalStateException("Missing migration");
        script = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
      connection.setAutoCommit(false);
      try {
        for (String statement : script.split(";")) {
          if (!statement.isBlank()) Sql.update(connection, statement);
        }
        Sql.update(connection, "INSERT INTO schema_migrations VALUES(?)", version);
        connection.commit();
      } catch (Exception e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  private <T> CompletableFuture<T> submit(Callable<T> work) {
    var future = new CompletableFuture<T>();
    try {
      executor.execute(
          () -> {
            try {
              future.complete(work.call());
            } catch (Exception e) {
              future.completeExceptionally(e);
            }
          });
    } catch (RejectedExecutionException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  public <T> CompletableFuture<T> read(Work<T> work) {
    return submit(() -> work.run(connection));
  }

  public <T> CompletableFuture<T> transaction(Work<T> work) {
    return submit(
        () -> {
          connection.setAutoCommit(false);
          try {
            T result = work.run(connection);
            connection.commit();
            return result;
          } catch (Exception e) {
            connection.rollback();
            throw e;
          } finally {
            connection.setAutoCommit(true);
          }
        });
  }

  public CompletableFuture<Path> backup(Path destination) {
    return read(
        c -> {
          Files.createDirectories(destination.toAbsolutePath().getParent());
          try (var s = Sql.prepare(c, "VACUUM INTO ?", destination.toAbsolutePath().toString())) {
            s.execute();
          }
          return destination;
        });
  }

  public void close() {
    var closing =
        submit(
            () -> {
              if (connection != null) connection.close();
              return null;
            });
    executor.shutdown();
    // Lifecycle shutdown only: no game-time database work is performed on the server thread.
    try {
      closing.get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("Database shutdown did not complete", e);
    }
  }
}
