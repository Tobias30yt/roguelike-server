package dev.roguelike.unlock;

import dev.roguelike.core.Dispatch;
import dev.roguelike.world.WorldService;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

public final class UnlockAdapter implements Listener {
  private final UnlockService service;
  private final Dispatch dispatch;
  private final WorldService worlds;
  private final Set<String> starter;
  private final Map<UUID, Set<String>> owned = new HashMap<>();

  public UnlockAdapter(
      UnlockService service, Dispatch dispatch, WorldService worlds, Set<String> starter) {
    this.service = service;
    this.dispatch = dispatch;
    this.worlds = worlds;
    this.starter = Set.copyOf(starter);
  }

  public static List<UnlockDefinition> load(YamlConfiguration config) {
    var out = new ArrayList<UnlockDefinition>();
    var root = Objects.requireNonNull(config.getConfigurationSection("unlocks"));
    for (String id : root.getKeys(false)) {
      var c = root.getConfigurationSection(id);
      out.add(
          new UnlockDefinition(
              id,
              c.getString("category", "tools"),
              c.getString("description", id),
              c.getLong("price"),
              Set.copyOf(c.getStringList("requires")),
              Set.copyOf(c.getStringList("materials")),
              c.getString("generation-feature", ""),
              c.getLong("runs-required", 0)));
    }
    return out;
  }

  public void refresh(Player p) {
    dispatch.result(p, service.owned(p.getUniqueId()), set -> owned.put(p.getUniqueId(), set));
  }

  public Set<String> owned(Player p) {
    return owned.getOrDefault(p.getUniqueId(), Set.of());
  }

  private boolean matches(Set<String> patterns, String material) {
    return patterns.stream()
        .anyMatch(
            s ->
                s.endsWith("*")
                    ? material.startsWith(s.substring(0, s.length() - 1))
                    : material.equals(s));
  }

  public boolean allowed(Player p, ItemStack item) {
    if (item == null || item.getType().isAir()) return true;
    if (!item.getEnchantments().isEmpty() && !owned(p).contains("enchanting")) return false;
    String name = item.getType().name();
    return matches(starter, name)
        || service.definitions().stream()
            .anyMatch(d -> owned(p).contains(d.id()) && matches(d.materials(), name));
  }

  private boolean locked(Player p, ItemStack item) {
    return worlds.session(p) != null && !allowed(p, item);
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    refresh(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    owned.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void pickup(EntityPickupItemEvent e) {
    if (e.getEntity() instanceof Player p && locked(p, e.getItem().getItemStack()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (e.getWhoClicked() instanceof Player p
        && (locked(p, e.getCurrentItem())
            || locked(p, e.getCursor())
            || e.getHotbarButton() >= 0
                && locked(p, p.getInventory().getItem(e.getHotbarButton())))) e.setCancelled(true);
  }

  @EventHandler
  public void craft(PrepareItemCraftEvent e) {
    for (var human : e.getViewers())
      if (human instanceof Player p && locked(p, e.getInventory().getResult()))
        e.getInventory().setResult(null);
  }

  @EventHandler
  public void smith(PrepareSmithingEvent e) {
    for (var human : e.getViewers())
      if (human instanceof Player p && locked(p, e.getResult())) e.setResult(null);
  }

  @EventHandler
  public void anvil(PrepareAnvilEvent e) {
    for (var human : e.getViewers())
      if (human instanceof Player p && locked(p, e.getResult())) e.setResult(null);
  }

  @EventHandler
  public void interact(PlayerInteractEvent e) {
    if (locked(e.getPlayer(), e.getItem())) e.setCancelled(true);
  }

  @EventHandler
  public void place(BlockPlaceEvent e) {
    if (locked(e.getPlayer(), e.getItemInHand())) e.setCancelled(true);
  }

  @EventHandler
  public void breakBlock(BlockBreakEvent e) {
    if (locked(e.getPlayer(), e.getPlayer().getInventory().getItemInMainHand()))
      e.setCancelled(true);
  }

  @EventHandler
  public void attack(EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player p && locked(p, p.getInventory().getItemInMainHand()))
      e.setCancelled(true);
  }

  @EventHandler
  public void consume(PlayerItemConsumeEvent e) {
    if (locked(e.getPlayer(), e.getItem())) e.setCancelled(true);
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getWhoClicked() instanceof Player p && locked(p, e.getOldCursor())) e.setCancelled(true);
  }

  @EventHandler
  public void enchant(EnchantItemEvent e) {
    if (worlds.session(e.getEnchanter()) != null && !owned(e.getEnchanter()).contains("enchanting"))
      e.setCancelled(true);
  }

  @EventHandler
  public void shoot(EntityShootBowEvent e) {
    if (e.getEntity() instanceof Player p && locked(p, e.getBow())) e.setCancelled(true);
  }

  @EventHandler
  public void equip(BlockDispenseArmorEvent e) {
    if (e.getTargetEntity() instanceof Player p && locked(p, e.getItem())) e.setCancelled(true);
  }

  @EventHandler
  public void interactEntity(PlayerInteractEntityEvent e) {
    if (locked(e.getPlayer(), e.getPlayer().getInventory().getItem(e.getHand())))
      e.setCancelled(true);
  }
}
