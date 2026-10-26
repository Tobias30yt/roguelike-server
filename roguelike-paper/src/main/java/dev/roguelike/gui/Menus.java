package dev.roguelike.gui;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

public final class Menus implements Listener {
  public record Entry(Material icon, String title, List<String> lore, Runnable action) {
    public Entry(Material icon, String title, Runnable action) {
      this(icon, title, List.of(), action);
    }
  }

  private static final class Menu implements InventoryHolder {
    private Inventory inventory;
    private final UUID owner;
    private final Map<Integer, Runnable> actions = new HashMap<>();

    Menu(UUID owner) {
      this.owner = owner;
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  public void open(Player player, String title, List<Entry> entries, int page) {
    int pages = Math.max(1, (entries.size() + 44) / 45);
    int current = Math.max(0, Math.min(page, pages - 1));
    Menu menu = new Menu(player.getUniqueId());
    menu.inventory =
        Bukkit.createInventory(menu, 54, Component.text(title + " " + (current + 1) + "/" + pages));
    for (int i = 0; i < 45 && current * 45 + i < entries.size(); i++)
      put(menu, i, entries.get(current * 45 + i));
    if (current > 0)
      put(
          menu,
          45,
          new Entry(Material.ARROW, "Zurueck", () -> open(player, title, entries, current - 1)));
    put(menu, 49, new Entry(Material.BARRIER, "Schliessen", player::closeInventory));
    if (current + 1 < pages)
      put(
          menu,
          53,
          new Entry(Material.ARROW, "Weiter", () -> open(player, title, entries, current + 1)));
    player.openInventory(menu.inventory);
  }

  private void put(Menu menu, int slot, Entry entry) {
    ItemStack item = ItemStack.of(entry.icon());
    var meta = item.getItemMeta();
    meta.displayName(Component.text(entry.title()));
    meta.lore(entry.lore().stream().map(Component::text).toList());
    item.setItemMeta(meta);
    menu.inventory.setItem(slot, item);
    menu.actions.put(slot, entry.action());
  }

  public void confirm(Player p, String title, Runnable action) {
    open(
        p,
        title,
        List.of(
            new Entry(
                Material.LIME_CONCRETE,
                "Bestaetigen",
                () -> {
                  p.closeInventory();
                  action.run();
                }),
            new Entry(Material.RED_CONCRETE, "Abbrechen", p::closeInventory)),
        0);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)
        || !menu.owner.equals(p.getUniqueId())
        || e.getRawSlot() < 0
        || e.getRawSlot() >= 54
        || !e.isLeftClick()
        || e.isShiftClick()) return;
    Runnable action = menu.actions.get(e.getRawSlot());
    if (action != null) {
      p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, .4f, 1);
      action.run();
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }
}
