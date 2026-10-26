package dev.roguelike.run;

import dev.roguelike.world.WorldService;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;

public final class SessionListener implements Listener {
  private final WorldService worlds;

  public SessionListener(WorldService worlds) {
    this.worlds = worlds;
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    worlds.login(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    worlds.quit(e.getPlayer());
  }

  @EventHandler
  public void move(PlayerMoveEvent e) {
    if (worlds.locked(e.getPlayer()) && e.hasChangedPosition()) e.setCancelled(true);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (e.getWhoClicked() instanceof Player p && worlds.locked(p)) e.setCancelled(true);
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getWhoClicked() instanceof Player p && worlds.locked(p)) e.setCancelled(true);
  }

  @EventHandler
  public void drop(PlayerDropItemEvent e) {
    if (worlds.locked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler
  public void swap(PlayerSwapHandItemsEvent e) {
    if (worlds.locked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler
  public void consume(PlayerItemConsumeEvent e) {
    if (worlds.locked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler
  public void shoot(EntityShootBowEvent e) {
    if (e.getEntity() instanceof Player p && worlds.locked(p)) e.setCancelled(true);
  }

  @EventHandler
  public void interactEntity(PlayerInteractEntityEvent e) {
    if (worlds.locked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler
  public void attack(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player p && worlds.locked(p)) e.setCancelled(true);
  }

  @EventHandler
  public void open(InventoryOpenEvent e) {
    if (e.getPlayer() instanceof Player p && worlds.locked(p)) e.setCancelled(true);
  }

  @EventHandler
  public void pickup(EntityPickupItemEvent e) {
    if (e.getEntity() instanceof Player p && worlds.locked(p)) e.setCancelled(true);
  }

  @EventHandler
  public void damage(EntityDamageEvent e) {
    if (e.getEntity() instanceof Player p && (worlds.locked(p) || p.getWorld() == worlds.hub()))
      e.setCancelled(true);
  }

  @EventHandler
  public void interact(PlayerInteractEvent e) {
    if (worlds.locked(e.getPlayer())
        || e.getClickedBlock() != null
            && e.getClickedBlock().getType() == org.bukkit.Material.ENDER_CHEST)
      e.setCancelled(true);
  }

  @EventHandler
  public void place(BlockPlaceEvent e) {
    if (worlds.locked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler
  public void breakBlock(BlockBreakEvent e) {
    if (worlds.locked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler
  public void portal(PlayerPortalEvent e) {
    e.setCancelled(true);
    e.getPlayer().sendMessage("Portale sind in Roguelike-Runs deaktiviert.");
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void teleport(PlayerTeleportEvent e) {
    if (e.getTo() == null) return;
    var key = e.getTo().getWorld().getKey();
    if (key.getNamespace().equals("roguelike") && key.getKey().startsWith("run/")) {
      Run r = worlds.session(e.getPlayer());
      if (r == null || !WorldService.key(r).equals(key)) e.setCancelled(true);
    } else if (worlds.session(e.getPlayer()) != null
        && e.getFrom().getWorld() != e.getTo().getWorld()) e.setCancelled(true);
  }
}
