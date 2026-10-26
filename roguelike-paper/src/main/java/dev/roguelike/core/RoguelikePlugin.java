package dev.roguelike.core;

import dev.roguelike.config.Settings;
import dev.roguelike.database.SqliteDatabase;
import dev.roguelike.reset.ResetService;
import org.bukkit.plugin.java.JavaPlugin;

public final class RoguelikePlugin extends JavaPlugin {
  private SqliteDatabase database;
  private GameApplication application;
  private volatile boolean ready;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    getServer()
        .getPluginManager()
        .registerEvents(
            new org.bukkit.event.Listener() {
              @org.bukkit.event.EventHandler
              public void login(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
                if (!ready)
                  event.disallow(
                      org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                      net.kyori.adventure.text.Component.text(
                          "Roguelike startet oder stellt Daten wieder her. Bitte kurz warten."));
              }
            },
            this);
    try {
      Settings settings = Settings.load(getConfig());
      database = new SqliteDatabase();
      database
          .open(getDataFolder().toPath().resolve(settings.database()))
          .thenCompose(
              v ->
                  new ResetService(database, getDataFolder().toPath().resolve("backups")).recover())
          .whenComplete(
              (v, error) ->
                  new Dispatch(this)
                      .main(
                          () -> {
                            if (error != null) {
                              fail(error);
                              return;
                            }
                            try {
                              application = new GameApplication(this, database, settings);
                              application.start();
                              ready = true;
                              getLogger().info("Roguelike ready on Paper 26.3");
                            } catch (Exception e) {
                              fail(e);
                            }
                          }));
    } catch (Exception e) {
      fail(e);
    }
  }

  private void fail(Throwable e) {
    getLogger().log(java.util.logging.Level.SEVERE, "Roguelike startup failed", e);
    getServer().getPluginManager().disablePlugin(this);
    getServer().shutdown();
  }

  @Override
  public void onDisable() {
    ready = false;
    if (application != null) application.close();
    if (database != null) database.close();
  }
}
