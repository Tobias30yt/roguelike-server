package dev.roguelike.database;

import java.sql.Connection;
import java.util.concurrent.CompletableFuture;

/** Repository unit-of-work boundary. Implementations execute work off the game thread. */
public interface Database extends AutoCloseable {
  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws Exception;
  }

  <T> CompletableFuture<T> read(Work<T> work);

  <T> CompletableFuture<T> transaction(Work<T> work);

  @Override
  void close();
}
