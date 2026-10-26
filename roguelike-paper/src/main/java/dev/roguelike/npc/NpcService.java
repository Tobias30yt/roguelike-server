package dev.roguelike.npc;

import dev.roguelike.core.Dispatch;
import dev.roguelike.database.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class NpcService implements Listener {
  public static final Set<String> ROLES = Set.of("create", "rejoin", "rebirth", "upgrades");
  private final Database db;
  private final Dispatch dispatch;
  private final NamespacedKey tag;
  private final BiConsumer<Player, String> action;

  public NpcService(
      JavaPlugin plugin, Database db, Dispatch dispatch, BiConsumer<Player, String> action) {
    this.db = db;
    this.dispatch = dispatch;
    this.action = action;
    tag = new NamespacedKey(plugin, "npc");
  }

  public void defaults() {
    db.transaction(
            c -> {
              if (Sql.number(c, "SELECT COUNT(*) FROM npcs") == 0) {
                int i = 0;
                for (String role : List.of("create", "rejoin", "rebirth", "upgrades"))
                  Sql.update(
                      c,
                      "INSERT INTO npcs VALUES(?,?,?, ?,65,4)",
                      UUID.randomUUID().toString(),
                      role,
                      "roguelike:hub",
                      -6 + i++ * 4);
              }
              return null;
            })
        .thenRun(this::load);
  }

  public void load() {
    db.read(c -> Sql.rows(c, "SELECT * FROM npcs"))
        .thenAccept(
            rows ->
                dispatch.main(
                    () -> {
                      for (var row : rows) {
                        World w =
                            Bukkit.getWorld(NamespacedKey.fromString((String) row.get("world")));
                        if (w == null) continue;
                        spawn(
                            (String) row.get("id"),
                            (String) row.get("role"),
                            new Location(
                                w,
                                ((Number) row.get("x")).doubleValue(),
                                ((Number) row.get("y")).doubleValue(),
                                ((Number) row.get("z")).doubleValue()));
                      }
                    }));
  }

  private void spawn(String id, String role, Location at) {
    at.getChunk().load();
    for (Entity e : at.getWorld().getEntities())
      if (id.equals(e.getPersistentDataContainer().get(tag, PersistentDataType.STRING))) e.remove();
    Villager npc = at.getWorld().spawn(at, Villager.class);
    npc.setAI(false);
    npc.setInvulnerable(true);
    npc.setSilent(true);
    npc.setCollidable(false);
    npc.setPersistent(true);
    npc.customName(Component.text("Roguelike | " + role));
    npc.setCustomNameVisible(true);
    npc.getPersistentDataContainer().set(tag, PersistentDataType.STRING, id);
    npc.getPersistentDataContainer()
        .set(new NamespacedKey("roguelike", "role"), PersistentDataType.STRING, role);
  }

  public void create(Player p, String role) {
    if (!ROLES.contains(role)) throw new IllegalArgumentException("Rollen: " + ROLES);
    String id = UUID.randomUUID().toString();
    Location at = p.getLocation();
    String worldKey = at.getWorld().getKey().toString();
    dispatch.result(
        p,
        db.transaction(
            c -> {
              Sql.update(
                  c,
                  "INSERT INTO npcs VALUES(?,?,?,?,?,?)",
                  id,
                  role,
                  worldKey,
                  at.getX(),
                  at.getY(),
                  at.getZ());
              return id;
            }),
        v -> {
          spawn(id, role, at);
          p.sendMessage("NPC erstellt: " + id);
        });
  }

  public void remove(Player p, String id) {
    UUID.fromString(id);
    dispatch.result(
        p,
        db.transaction(c -> Sql.update(c, "DELETE FROM npcs WHERE id=?", id)),
        n -> {
          for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntities())
              if (id.equals(e.getPersistentDataContainer().get(tag, PersistentDataType.STRING)))
                e.remove();
          p.sendMessage("NPC entfernt: " + n);
        });
  }

  public void move(Player p, String id) {
    UUID.fromString(id);
    Location at = p.getLocation();
    String worldKey = at.getWorld().getKey().toString();
    dispatch.result(
        p,
        db.transaction(
            c -> {
              Sql.update(
                  c,
                  "UPDATE npcs SET world=?,x=?,y=?,z=? WHERE id=?",
                  worldKey,
                  at.getX(),
                  at.getY(),
                  at.getZ(),
                  id);
              return Sql.string(c, "SELECT role FROM npcs WHERE id=?", id);
            }),
        role -> {
          if (role != null) {
            for (World w : Bukkit.getWorlds())
              for (Entity e : w.getEntities())
                if (id.equals(e.getPersistentDataContainer().get(tag, PersistentDataType.STRING)))
                  e.remove();
            spawn(id, role, at);
          }
        });
  }

  @EventHandler
  public void interact(PlayerInteractEntityEvent e) {
    String role =
        e.getRightClicked()
            .getPersistentDataContainer()
            .get(new NamespacedKey("roguelike", "role"), PersistentDataType.STRING);
    if (role != null) {
      e.setCancelled(true);
      if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND)
        action.accept(e.getPlayer(), role);
    }
  }

  @EventHandler
  public void damage(EntityDamageEvent e) {
    if (e.getEntity().getPersistentDataContainer().has(tag)) e.setCancelled(true);
  }
}
