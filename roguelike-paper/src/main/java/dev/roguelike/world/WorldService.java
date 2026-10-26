package dev.roguelike.world;

import dev.roguelike.config.Settings;
import dev.roguelike.core.Dispatch;
import dev.roguelike.database.Database;
import dev.roguelike.generation.IslandGenerator;
import dev.roguelike.run.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldService {
  private final JavaPlugin plugin;
  private final Settings settings;
  private final RunRepository runs;
  private final Database db;
  private final Dispatch dispatch;
  private final Map<UUID, Run> sessions = new HashMap<>();
  private final Map<UUID, Player> connections = new HashMap<>();
  private final Set<UUID> busy = new HashSet<>();
  private final Set<UUID> ready = new HashSet<>();
  private java.util.function.BiConsumer<Player, Run> deathRecovery;
  private boolean frozen;

  public void frozen(boolean value) {
    frozen = value;
  }

  private String generationSuffix = "";

  public void generationSuffix(String suffix) {
    new IslandGenerator(suffix, false);
    generationSuffix = suffix;
  }

  private final ExecutorService files =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "roguelike-world-files"));
  private ManagedWorldFiles managed;
  private World hub;

  public WorldService(
      JavaPlugin plugin, Settings settings, RunRepository runs, Database db, Dispatch dispatch) {
    this.plugin = plugin;
    this.settings = settings;
    this.runs = runs;
    this.db = db;
    this.dispatch = dispatch;
  }

  public void start() {
    hub =
        Objects.requireNonNull(
            WorldCreator.ofKey(new NamespacedKey("roguelike", "hub"))
                .generator(new IslandGenerator("", true))
                .createWorld());
    managed = new ManagedWorldFiles(hub.getWorldFolder().toPath().getParent().resolve("run"));
    hub.setSpawnLocation(0, 65, 0);
    hub.setDifficulty(Difficulty.PEACEFUL);
    hub.getWorldBorder().setSize(100000);
    cleanup();
  }

  public World hub() {
    return hub;
  }

  public Run session(Player p) {
    return current(p) ? sessions.get(p.getUniqueId()) : null;
  }

  public boolean current(Player p) {
    return connections.get(p.getUniqueId()) == p;
  }

  public boolean locked(Player p) {
    return frozen
        || !current(p)
        || !ready.contains(p.getUniqueId())
        || busy.contains(p.getUniqueId());
  }

  public void deathRecovery(java.util.function.BiConsumer<Player, Run> recovery) {
    deathRecovery = recovery;
  }

  public boolean lock(Player p) {
    boolean acquired =
        !frozen && current(p) && ready.contains(p.getUniqueId()) && busy.add(p.getUniqueId());
    if (acquired) p.closeInventory();
    return acquired;
  }

  public void unlock(Player p) {
    if (current(p)) busy.remove(p.getUniqueId());
  }

  public void login(Player p) {
    if (frozen) {
      p.kick(net.kyori.adventure.text.Component.text("Jahresreset laeuft."));
      return;
    }
    UUID id = p.getUniqueId();
    connections.put(id, p);
    ready.remove(id);
    busy.add(id);
    runs.register(id, p.getName())
        .thenCompose(v -> runs.active(id))
        .thenCombine(runs.hub(id), (run, bytes) -> new Object[] {run, bytes})
        .thenCombine(
            runs.context(id), (values, context) -> new Object[] {values[0], values[1], context})
        .whenComplete(
            (value, error) ->
                dispatch.main(
                    () -> {
                      if (!current(p) || !p.isOnline()) return;
                      if (frozen) {
                        p.kick(net.kyori.adventure.text.Component.text("Jahresreset laeuft."));
                        return;
                      }
                      if (error != null) {
                        p.kick(
                            net.kyori.adventure.text.Component.text(
                                "Roguelike database unavailable. Please reconnect."));
                        return;
                      }
                      Run run = (Run) value[0];
                      if (run != null
                          && p.getPersistentDataContainer()
                              .has(new NamespacedKey("roguelike", "pending_death"))) {
                        sessions.put(id, run);
                        ready.add(id);
                        deathRecovery.accept(p, run);
                        return;
                      }
                      if (run == null)
                        p.getPersistentDataContainer()
                            .remove(new NamespacedKey("roguelike", "pending_death"));
                      String context = (String) value[2];
                      if (run != null && context != null) {
                        ready.add(id);
                        boolean pending = context.startsWith("enter:");
                        byte[] state = pending ? run.snapshot() : PlayerSnapshot.capture(p);
                        enterLocked(
                            p,
                            run,
                            true,
                            state,
                            !pending && !p.getWorld().getKey().equals(key(run)));
                        return;
                      } else {
                        PlayerSnapshot.restore(p, hub, (byte[]) value[1]);
                        p.teleport(hub.getSpawnLocation());
                      }
                      ready.add(id);
                      busy.remove(id);
                    }));
  }

  public void create(Player p, String profile) {
    if (!lock(p)) return;
    String modifier =
        java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? ",rugged" : "";
    dispatch.result(
        p,
        runs.create(p.getUniqueId(), settings.maxActive(), profile + modifier + generationSuffix)
            .whenComplete(
                (v, e) -> {
                  if (e != null) dispatch.main(() -> unlock(p));
                }),
        run -> enterLocked(p, run));
  }

  public void rejoin(Player p) {
    if (session(p) != null || !lock(p)) return;
    dispatch.result(
        p,
        runs.active(p.getUniqueId())
            .whenComplete(
                (v, e) -> {
                  if (e != null) dispatch.main(() -> unlock(p));
                }),
        run -> {
          if (run == null) {
            unlock(p);
            p.sendMessage("Kein aktiver Run vorhanden.");
            return;
          }
          enterLocked(p, run);
        });
  }

  private void enterLocked(Player p, Run run) {
    enterLocked(p, run, false, run.snapshot(), false);
  }

  private void enterLocked(Player p, Run run, boolean recovery, byte[] state, boolean useSpawn) {
    try {
      long loaded =
          Bukkit.getWorlds().stream()
              .filter(
                  w ->
                      w.getKey().getNamespace().equals("roguelike")
                          && w.getKey().getKey().startsWith("run/"))
              .count();
      World w = Bukkit.getWorld(key(run));
      if (w == null && loaded >= settings.maxLoaded())
        throw new IllegalStateException(
            "Zu viele geladene Welten. Bitte spaeter erneut versuchen.");
      if (w == null)
        w =
            Objects.requireNonNull(
                WorldCreator.ofKey(key(run))
                    .seed(run.seed())
                    .generator(new IslandGenerator(run.profile(), false))
                    .generateStructures(false)
                    .createWorld());
      World target = w;
      target.getWorldBorder().setCenter(0, 0);
      target.getWorldBorder().setSize(settings.border());
      String folder = target.getWorldFolder().getAbsolutePath();
      byte[] hubState = PlayerSnapshot.capture(p);
      runs.activated(run, folder)
          .thenCompose(
              v -> recovery ? CompletableFuture.completedFuture(null) : runs.enter(run, hubState))
          .whenComplete(
              (v, error) ->
                  dispatch.main(
                      () -> {
                        if (error != null) {
                          unlock(p);
                          p.sendMessage("Run konnte nicht gespeichert werden.");
                          return;
                        }
                        if (!current(p) || !p.isOnline() || frozen) {
                          unlock(p);
                          unload(target);
                          return;
                        }
                        sessions.put(p.getUniqueId(), run);
                        if (!recovery && run.snapshot() == null) resetAdvancements(p);
                        Location restored = PlayerSnapshot.restore(p, target, state);
                        Location spawn = useSpawn ? target.getSpawnLocation() : restored;
                        if (state == null) {
                          if (run.profile().contains("perk_food"))
                            p.getInventory()
                                .addItem(org.bukkit.inventory.ItemStack.of(Material.BREAD, 3));
                          if (run.profile().contains("perk_torches"))
                            p.getInventory()
                                .addItem(org.bukkit.inventory.ItemStack.of(Material.TORCH, 8));
                        }
                        p.teleportAsync(spawn)
                            .whenComplete(
                                (ok, e) ->
                                    dispatch.main(
                                        () -> {
                                          if (!current(p) || !p.isOnline() || frozen) return;
                                          if (e != null || !Boolean.TRUE.equals(ok)) {
                                            unlock(p);
                                            leave(p);
                                            return;
                                          }
                                          p.saveData();
                                          runs.entered(run)
                                              .whenComplete(
                                                  (ignored, failure) ->
                                                      dispatch.main(
                                                          () -> {
                                                            if (failure == null) unlock(p);
                                                            else
                                                              p.kick(
                                                                  net.kyori.adventure.text.Component
                                                                      .text(
                                                                          "Run entry could not be"
                                                                              + " saved."
                                                                              + " Reconnect."));
                                                          }));
                                        }));
                      }));
    } catch (Exception e) {
      unlock(p);
      p.sendMessage("Weltstart fehlgeschlagen: " + e.getMessage());
      plugin.getLogger().log(java.util.logging.Level.WARNING, "World entry", e);
    }
  }

  public void leave(Player p) {
    Run run = session(p);
    if (run == null) {
      if (!locked(p)) p.teleport(hub.getSpawnLocation());
      return;
    }
    if (!lock(p)) return;
    byte[] snapshot = PlayerSnapshot.capture(p);
    World old = p.getWorld();
    runs.leave(run, snapshot)
        .whenComplete(
            (bytes, error) ->
                dispatch.main(
                    () -> {
                      if (error != null) {
                        unlock(p);
                        p.sendMessage("Speichern fehlgeschlagen; Run bleibt aktiv.");
                        return;
                      }
                      removeSession(p);
                      if (current(p) && p.isOnline() && !frozen) {
                        PlayerSnapshot.restore(p, hub, bytes);
                        p.teleport(hub.getSpawnLocation());
                        p.saveData();
                      }
                      unlock(p);
                      unload(old);
                    }));
  }

  public void quit(Player p) {
    if (!current(p)) return;
    Run run = session(p);
    if (ready.contains(p.getUniqueId()) && !busy.contains(p.getUniqueId())) {
      if (run != null) runs.checkpoint(run, PlayerSnapshot.capture(p));
      else runs.saveHub(p.getUniqueId(), PlayerSnapshot.capture(p));
    }
    sessions.remove(p.getUniqueId());
    ready.remove(p.getUniqueId());
    busy.remove(p.getUniqueId());
    connections.remove(p.getUniqueId());
    World world = p.getWorld();
    plugin.getServer().getScheduler().runTaskLater(plugin, () -> unload(world), 20);
  }

  public void removeSession(Player p) {
    if (current(p)) sessions.remove(p.getUniqueId());
  }

  public void terminate(Player p, Run run) {
    boolean inRun = session(p) != null;
    if (!lock(p)) throw new IllegalStateException("Spielerzustand wird bereits gespeichert.");
    World old = p.getWorld();
    runs.terminate(run)
        .thenCompose(v -> runs.hub(p.getUniqueId()))
        .whenComplete(
            (bytes, error) ->
                dispatch.main(
                    () -> {
                      if (error != null) {
                        unlock(p);
                        plugin
                            .getLogger()
                            .log(java.util.logging.Level.WARNING, "Run termination failed", error);
                        if (current(p)) p.sendMessage("Run konnte nicht beendet werden.");
                        return;
                      }
                      removeSession(p);
                      if (current(p) && p.isOnline() && !frozen) {
                        if (inRun) {
                          PlayerSnapshot.restore(p, hub, bytes);
                          p.teleport(hub.getSpawnLocation());
                          p.saveData();
                        }
                        p.sendMessage("Dein Run wurde administrativ ohne Auszahlung beendet.");
                      }
                      unlock(p);
                      unload(old);
                      cleanup();
                    }));
  }

  public void unload(World world) {
    if (world != null
        && world != hub
        && world.getKey().getNamespace().equals("roguelike")
        && world.getKey().getKey().startsWith("run/")
        && world.getPlayers().isEmpty()) Bukkit.unloadWorld(world, true);
  }

  public void cleanup() {
    runs.pendingCleanup()
        .thenAccept(
            list ->
                dispatch.main(
                    () -> {
                      for (Run run : list) {
                        World w = Bukkit.getWorld(key(run));
                        if (w != null) {
                          if (!w.getPlayers().isEmpty()) continue;
                          if (!Bukkit.unloadWorld(w, false)) continue;
                        }
                        if (run.folder() == null) {
                          runs.cleaned(run.id());
                          continue;
                        }
                        CompletableFuture.runAsync(
                                () -> {
                                  try {
                                    managed.delete(run.id(), Path.of(run.folder()));
                                  } catch (Exception e) {
                                    throw new CompletionException(e);
                                  }
                                },
                                files)
                            .thenCompose(v -> runs.cleaned(run.id()))
                            .exceptionally(
                                e -> {
                                  plugin
                                      .getLogger()
                                      .warning("World cleanup deferred: " + e.getMessage());
                                  return null;
                                });
                      }
                    }));
  }

  public static NamespacedKey key(Run run) {
    return new NamespacedKey("roguelike", "run/" + run.id());
  }

  public static void resetAdvancements(Player p) {
    Bukkit.advancementIterator()
        .forEachRemaining(
            a -> {
              var progress = p.getAdvancementProgress(a);
              for (String criterion : progress.getAwardedCriteria())
                progress.revokeCriteria(criterion);
            });
  }

  public void close() {
    for (Player p : Bukkit.getOnlinePlayers()) {
      Run r = session(p);
      if (!locked(p)) {
        if (r != null) runs.checkpoint(r, PlayerSnapshot.capture(p));
        else runs.saveHub(p.getUniqueId(), PlayerSnapshot.capture(p));
      }
    }
    files.shutdown();
  }
}
