package dev.roguelike.core;

import dev.roguelike.generation.IslandGenerator;
import dev.roguelike.world.ManagedWorldFiles;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Explicit operator diagnostic; creates and removes only its own UUID world. */
public final class PaperSmokeTest {
  private PaperSmokeTest() {}

  public static void run(JavaPlugin plugin) {
    String id = UUID.randomUUID().toString();
    World world =
        Objects.requireNonNull(
            WorldCreator.ofKey(new NamespacedKey("roguelike", "run/" + id))
                .seed(12345)
                .generator(new IslandGenerator("iron,diamond,ruins,desert", false))
                .generateStructures(false)
                .createWorld());
    var path = world.getWorldFolder().toPath();
    plugin.getLogger().info("SMOKE world folder: " + path);
    try {
      world.getChunkAt(0, 0);
      int h = new IslandGenerator("", false).height(12345, 0, 0);
      if (world.getBlockAt(0, h, 0).getType() != Material.GRASS_BLOCK)
        throw new IllegalStateException("Terrain generation mismatch");
      var items = new ItemStack[] {ItemStack.of(Material.DIAMOND, 7), ItemStack.empty()};
      if (ItemStack.deserializeItemsFromBytes(ItemStack.serializeItemsAsBytes(items))[0].getAmount()
          != 7) throw new IllegalStateException("Inventory codec mismatch");
      var valuation =
          new dev.roguelike.economy.InventoryValuation(
              Map.of(Material.DIAMOND, 80L, Material.IRON_HELMET, 20L));
      var completeInventory = new ItemStack[41];
      completeInventory[0] = ItemStack.of(Material.DIAMOND, 7);
      completeInventory[39] = ItemStack.of(Material.IRON_HELMET);
      completeInventory[40] = ItemStack.of(Material.DIAMOND, 1);
      if (valuation.value(completeInventory) != 660)
        throw new IllegalStateException("Armor/offhand valuation mismatch");
      completeInventory[0].editMeta(
          m ->
              m.getPersistentDataContainer()
                  .set(
                      new NamespacedKey("roguelike", "trophy"),
                      org.bukkit.persistence.PersistentDataType.STRING,
                      "test"));
      if (valuation.value(completeInventory) != 100)
        throw new IllegalStateException("Trophy valuation exclusion failed");
      Villager npc = world.spawn(new Location(world, .5, h + 1, .5), Villager.class);
      npc.setAI(false);
      npc.remove();
      plugin.getLogger().info("SMOKE terrain, biome provider, inventory serialization, NPC: PASS");
    } finally {
      if (!Bukkit.unloadWorld(world, false))
        throw new IllegalStateException("Smoke world unload failed");
    }
    World hub = Objects.requireNonNull(Bukkit.getWorld(new NamespacedKey("roguelike", "hub")));
    var files = new ManagedWorldFiles(hub.getWorldFolder().toPath().getParent().resolve("run"));
    plugin
        .getServer()
        .getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              try {
                files.delete(id, path);
                plugin.getLogger().info("SMOKE managed world deletion: PASS");
              } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "SMOKE deletion failed", e);
              }
            });
  }
}
