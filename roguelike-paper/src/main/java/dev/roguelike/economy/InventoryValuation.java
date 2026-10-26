package dev.roguelike.economy;

import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class InventoryValuation {
  private final Map<Material, Long> values;

  public InventoryValuation(Map<Material, Long> values) {
    this.values = Map.copyOf(values);
    if (values.values().stream().anyMatch(n -> n < 0))
      throw new IllegalArgumentException("Negative item value");
  }

  public long value(ItemStack[] items) {
    long result = 0;
    for (ItemStack item : items) {
      if (item == null
          || item.getType().isAir()
          || !item.getItemMeta().getPersistentDataContainer().isEmpty()) continue;
      result =
          Math.addExact(
              result,
              Math.multiplyExact(values.getOrDefault(item.getType(), 0L), item.getAmount()));
    }
    return result;
  }
}
