package dev.roguelike.core;

import java.util.concurrent.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class Dispatch {
  private final JavaPlugin plugin;

  public Dispatch(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public void main(Runnable work) {
    if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, work);
  }

  public <T> void result(Player player, CompletableFuture<T> future, Consumer<T> success) {
    future.whenComplete(
        (value, error) ->
            main(
                () -> {
                  if (error != null) {
                    Throwable cause = error;
                    while (cause.getCause() != null) cause = cause.getCause();
                    plugin
                        .getLogger()
                        .log(java.util.logging.Level.WARNING, "Operation failed", error);
                    if (player.isOnline())
                      player.sendMessage(Component.text("Roguelike: " + cause.getMessage()));
                  } else if (player.isOnline()) success.accept(value);
                }));
  }
}
