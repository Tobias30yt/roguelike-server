package dev.roguelike.run;

import java.io.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

public final class PlayerSnapshot {
  private PlayerSnapshot() {}

  public static byte[] capture(Player p) {
    try (var buffer = new ByteArrayOutputStream();
        var out = new DataOutputStream(buffer)) {
      out.writeInt(3);
      byte[] items = ItemStack.serializeItemsAsBytes(p.getInventory().getContents());
      out.writeInt(items.length);
      out.write(items);
      byte[] ender = ItemStack.serializeItemsAsBytes(p.getEnderChest().getContents());
      out.writeInt(ender.length);
      out.write(ender);
      out.writeDouble(p.getHealth());
      out.writeInt(p.getFoodLevel());
      out.writeFloat(p.getSaturation());
      out.writeInt(p.getLevel());
      out.writeFloat(p.getExp());
      Location l = p.getLocation();
      out.writeDouble(l.getX());
      out.writeDouble(l.getY());
      out.writeDouble(l.getZ());
      out.writeFloat(l.getYaw());
      out.writeFloat(l.getPitch());
      out.writeFloat(p.getExhaustion());
      out.writeInt(p.getFireTicks());
      out.writeInt(p.getRemainingAir());
      out.writeInt(p.getActivePotionEffects().size());
      for (var effect : p.getActivePotionEffects()) {
        out.writeUTF(effect.getType().getKey().toString());
        out.writeInt(effect.getDuration());
        out.writeInt(effect.getAmplifier());
        out.writeBoolean(effect.isAmbient());
        out.writeBoolean(effect.hasParticles());
        out.writeBoolean(effect.hasIcon());
      }
      return buffer.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Location restore(Player p, World world, byte[] data) {
    if (data == null) {
      clear(p);
      return world.getSpawnLocation();
    }
    try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
      int version = in.readInt();
      if (version < 1 || version > 3) throw new IOException("Unsupported snapshot version");
      int length = in.readInt();
      if (length < 0 || length > 8_000_000) throw new IOException("Invalid inventory payload");
      ItemStack[] inventory = ItemStack.deserializeItemsFromBytes(in.readNBytes(length));
      if (inventory.length != p.getInventory().getSize())
        throw new IOException("Invalid inventory size");
      ItemStack[] ender = null;
      if (version >= 2) {
        int n = in.readInt();
        if (n < 0 || n > 8_000_000) throw new IOException("Invalid ender inventory");
        ender = ItemStack.deserializeItemsFromBytes(in.readNBytes(n));
        if (ender.length != p.getEnderChest().getSize())
          throw new IOException("Invalid ender size");
      }
      double health = in.readDouble();
      int food = in.readInt();
      float saturation = in.readFloat();
      int level = in.readInt();
      float xp = in.readFloat();
      Location result =
          new Location(
              world,
              in.readDouble(),
              in.readDouble(),
              in.readDouble(),
              in.readFloat(),
              in.readFloat());
      if (!Double.isFinite(health)
          || food < 0
          || food > 20
          || !Float.isFinite(saturation)
          || saturation < 0
          || level < 0
          || !Float.isFinite(xp)
          || xp < 0
          || xp > 1
          || !Double.isFinite(result.getX())
          || !Double.isFinite(result.getY())
          || !Double.isFinite(result.getZ())
          || !Float.isFinite(result.getYaw())
          || !Float.isFinite(result.getPitch())) throw new IOException("Invalid player state");
      float exhaustion = 0;
      int fire = 0, air = p.getMaximumAir();
      var effects = new java.util.ArrayList<PotionEffect>();
      if (version >= 3) {
        exhaustion = in.readFloat();
        fire = in.readInt();
        air = in.readInt();
        if (!Float.isFinite(exhaustion) || exhaustion < 0)
          throw new IOException("Invalid exhaustion");
        int count = in.readInt();
        if (count < 0 || count > 256) throw new IOException("Invalid effects");
        for (int i = 0; i < count; i++) {
          var type = Registry.MOB_EFFECT.get(NamespacedKey.fromString(in.readUTF()));
          int duration = in.readInt(), amplifier = in.readInt();
          boolean ambient = in.readBoolean(), particles = in.readBoolean(), icon = in.readBoolean();
          if (type != null)
            effects.add(new PotionEffect(type, duration, amplifier, ambient, particles, icon));
        }
      }
      if (in.available() != 0) throw new IOException("Trailing snapshot data");
      // Decode and validate the complete snapshot before changing any live player data.
      clear(p);
      p.getInventory().setContents(inventory);
      if (ender != null) p.getEnderChest().setContents(ender);
      p.setHealth(Math.max(1, Math.min(20, health)));
      p.setFoodLevel(food);
      p.setSaturation(saturation);
      p.setLevel(level);
      p.setExp(xp);
      p.setExhaustion(exhaustion);
      p.setFireTicks(fire);
      p.setRemainingAir(air);
      for (var effect : effects) p.addPotionEffect(effect);
      return result;
    } catch (IOException e) {
      throw new IllegalStateException("Corrupt snapshot", e);
    }
  }

  public static void clear(Player p) {
    p.getInventory().clear();
    p.getEnderChest().clear();
    p.setHealth(20);
    p.setFoodLevel(20);
    p.setSaturation(5);
    p.setExhaustion(0);
    p.setRemainingAir(p.getMaximumAir());
    p.setLevel(0);
    p.setExp(0);
    p.setFireTicks(0);
    p.setFallDistance(0);
    for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
    p.setGameMode(GameMode.SURVIVAL);
  }
}
