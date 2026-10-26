package dev.roguelike.plot;

import dev.roguelike.core.Dispatch;
import dev.roguelike.database.*;
import dev.roguelike.gui.Menus;
import dev.roguelike.world.WorldService;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class PlotService implements Listener {
  private final Database db;
  private final Dispatch dispatch;
  private final WorldService worlds;
  private final Menus menus;
  private final Map<UUID, Integer> plots = new HashMap<>();
  private final Map<Long, UUID> owners = new HashMap<>();
  private final Map<UUID, Set<UUID>> trusted = new HashMap<>();
  private final Set<UUID> privatePlots = new HashSet<>();
  private final List<Material> palette;

  public PlotService(
      Database db, Dispatch dispatch, WorldService worlds, Menus menus, List<Material> palette) {
    this.db = db;
    this.dispatch = dispatch;
    this.worlds = worlds;
    this.menus = menus;
    this.palette = List.copyOf(palette);
  }

  public void load() {
    db.read(c -> Sql.rows(c, "SELECT id,player FROM plots"))
        .thenAccept(
            rows ->
                dispatch.main(
                    () -> {
                      plots.clear();
                      owners.clear();
                      for (var row : rows) {
                        int id = ((Number) row.get("id")).intValue();
                        UUID p = UUID.fromString((String) row.get("player"));
                        plots.put(p, id);
                        owners.put(key(cx(id), cz(id)), p);
                      }
                    }));
  }

  public static int cx(int id) {
    return 10 + (id - 1) % 100 * 2;
  }

  public static int cz(int id) {
    return 10 + (id - 1) / 100 * 2;
  }

  private static long key(int x, int z) {
    return ((long) x << 32) ^ (z & 0xffffffffL);
  }

  public boolean owns(Player p, Location at) {
    UUID owner = owners.get(key(at.getBlockX() >> 4, at.getBlockZ() >> 4));
    return p.getUniqueId().equals(owner)
        || owner != null && trusted.getOrDefault(owner, Set.of()).contains(p.getUniqueId());
  }

  public void permissions() {
    db.read(c -> Sql.rows(c, "SELECT * FROM plot_trust"))
        .thenAccept(
            rows ->
                dispatch.main(
                    () -> {
                      trusted.clear();
                      for (var row : rows)
                        trusted
                            .computeIfAbsent(
                                UUID.fromString((String) row.get("owner")), k -> new HashSet<>())
                            .add(UUID.fromString((String) row.get("visitor")));
                    }));
    db.read(c -> Sql.rows(c, "SELECT player FROM plots WHERE visitors=0"))
        .thenAccept(
            rows ->
                dispatch.main(
                    () -> {
                      privatePlots.clear();
                      for (var row : rows)
                        privatePlots.add(UUID.fromString((String) row.get("player")));
                    }));
  }

  public void trust(Player p, UUID visitor, boolean allow) {
    dispatch.result(
        p,
        db.transaction(
            c -> {
              if (allow)
                Sql.update(
                    c,
                    "INSERT OR IGNORE INTO plot_trust VALUES(?,?)",
                    p.getUniqueId().toString(),
                    visitor.toString());
              else
                Sql.update(
                    c,
                    "DELETE FROM plot_trust WHERE owner=? AND visitor=?",
                    p.getUniqueId().toString(),
                    visitor.toString());
              return null;
            }),
        v -> {
          permissions();
          p.sendMessage("Bauberechtigung aktualisiert.");
        });
  }

  public void visitors(Player p, boolean allow) {
    dispatch.result(
        p,
        db.transaction(
            c ->
                Sql.update(
                    c,
                    "UPDATE plots SET visitors=? WHERE player=?",
                    allow ? 1 : 0,
                    p.getUniqueId().toString())),
        v -> {
          permissions();
          p.sendMessage("Besucher: " + allow);
        });
  }

  @EventHandler
  public void visit(PlayerMoveEvent e) {
    if (e.getTo() == null || !hub(e.getTo()) || !e.hasChangedBlock()) return;
    UUID owner = owners.get(key(e.getTo().getBlockX() >> 4, e.getTo().getBlockZ() >> 4));
    if (owner != null
        && privatePlots.contains(owner)
        && !owns(e.getPlayer(), e.getTo())
        && !e.getPlayer().hasPermission("roguelike.admin")) e.setCancelled(true);
  }

  public void home(Player p) {
    if (worlds.session(p) != null) {
      p.sendMessage("Zuerst /hub verwenden.");
      return;
    }
    if (worlds.locked(p)) return;
    dispatch.result(
        p,
        db.read(
            c -> Sql.number(c, "SELECT id FROM plots WHERE player=?", p.getUniqueId().toString())),
        n -> {
          if (n <= 0) return;
          int id = Math.toIntExact(n);
          plots.put(p.getUniqueId(), id);
          owners.put(key(cx(id), cz(id)), p.getUniqueId());
          p.teleportAsync(new Location(worlds.hub(), cx(id) * 16 + 8.5, 65, cz(id) * 16 + 8.5));
        });
  }

  public void menu(Player p) {
    var entries = new ArrayList<Menus.Entry>();
    entries.add(new Menus.Entry(Material.GRASS_BLOCK, "Zum Plot", () -> home(p)));
    for (Material material : palette)
      entries.add(
          new Menus.Entry(
              material,
              material.name(),
              () -> {
                if (p.getWorld() != worlds.hub() || worlds.session(p) != null || worlds.locked(p))
                  return;
                ItemStack item = ItemStack.of(material, 64);
                item.editMeta(
                    m ->
                        m.getPersistentDataContainer()
                            .set(
                                new NamespacedKey("roguelike", "plot_material"),
                                PersistentDataType.BYTE,
                                (byte) 1));
                p.getInventory().addItem(item);
              }));
    menus.open(p, "Plot & Baumaterial", entries, 0);
  }

  private boolean hub(Location l) {
    return l.getWorld() == worlds.hub();
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void place(BlockPlaceEvent e) {
    if (!hub(e.getBlock().getLocation())) return;
    if (!owns(e.getPlayer(), e.getBlock().getLocation())
        || !palette.contains(e.getBlock().getType())
        || e.getBlock().getY() < 65
        || e.getBlock().getY() > 128) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void breakBlock(BlockBreakEvent e) {
    if (!hub(e.getBlock().getLocation())) return;
    if (!owns(e.getPlayer(), e.getBlock().getLocation()) || e.getBlock().getY() < 65)
      e.setCancelled(true);
    else {
      e.setDropItems(false);
      e.setExpToDrop(0);
    }
  }

  @EventHandler
  public void interact(PlayerInteractEvent e) {
    if (e.getClickedBlock() != null
        && hub(e.getClickedBlock().getLocation())
        && !owns(e.getPlayer(), e.getClickedBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void bucket(PlayerBucketEmptyEvent e) {
    if (hub(e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void bucket(PlayerBucketFillEvent e) {
    if (hub(e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void entityChange(EntityChangeBlockEvent e) {
    if (hub(e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void explode(EntityExplodeEvent e) {
    if (hub(e.getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void explode(BlockExplodeEvent e) {
    if (hub(e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void flow(BlockFromToEvent e) {
    if (hub(e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void piston(BlockPistonExtendEvent e) {
    if (hub(e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void piston(BlockPistonRetractEvent e) {
    if (hub(e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void fire(BlockIgniteEvent e) {
    if (hub(e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void burn(BlockBurnEvent e) {
    if (hub(e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void spread(BlockSpreadEvent e) {
    if (hub(e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void drop(PlayerDropItemEvent e) {
    if (hub(e.getPlayer().getLocation())) e.setCancelled(true);
  }

  @EventHandler
  public void entity(PlayerInteractEntityEvent e) {
    if (hub(e.getRightClicked().getLocation()) && !(e.getRightClicked() instanceof Villager))
      e.setCancelled(true);
  }

  public void displayTrophy(Player p, String id) {
    if (!owns(p, p.getLocation()) || !hub(p.getLocation())) {
      p.sendMessage("Stelle dich auf deinen Plot.");
      return;
    }
    Location at = p.getLocation();
    dispatch.result(
        p,
        db.read(
            c ->
                Sql.number(
                    c,
                    "SELECT COUNT(*) FROM trophies WHERE player=? AND id=?",
                    p.getUniqueId().toString(),
                    id)),
        n -> {
          if (n == 0) return;
          NamespacedKey tag = new NamespacedKey("roguelike", "trophy");
          String identity = p.getUniqueId() + ":" + id;
          for (Entity e : worlds.hub().getEntities())
            if (identity.equals(e.getPersistentDataContainer().get(tag, PersistentDataType.STRING)))
              e.remove();
          worlds
              .hub()
              .spawn(
                  at,
                  TextDisplay.class,
                  e -> {
                    e.text(net.kyori.adventure.text.Component.text("★ " + id));
                    e.setPersistent(true);
                    e.getPersistentDataContainer().set(tag, PersistentDataType.STRING, identity);
                  });
        });
  }
}
