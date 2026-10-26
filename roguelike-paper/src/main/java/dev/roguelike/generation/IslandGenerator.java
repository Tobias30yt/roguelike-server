package dev.roguelike.generation;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.generator.*;

/** Deterministic terraced islands. No vanilla ore or structure generation is invoked. */
public final class IslandGenerator extends ChunkGenerator {
  private final Set<String> features;
  private final boolean hub;
  private final int baseHeight, amplitude, coalRarity, ironRarity, diamondRarity;
  private final double gap;

  public IslandGenerator(String profile, boolean hub) {
    String[] parts = profile.split(";");
    this.features = Set.copyOf(Arrays.asList(parts[0].split(",")));
    this.hub = hub;
    var settings = new HashMap<String, String>();
    for (int i = 1; i < parts.length; i++) {
      String[] entry = parts[i].split("=", 2);
      if (entry.length == 2) settings.put(entry[0], entry[1]);
    }
    baseHeight = Integer.parseInt(settings.getOrDefault("height", "68"));
    amplitude = Integer.parseInt(settings.getOrDefault("amplitude", "9"));
    coalRarity = Integer.parseInt(settings.getOrDefault("coal", "61"));
    ironRarity = Integer.parseInt(settings.getOrDefault("iron", "83"));
    diamondRarity = Integer.parseInt(settings.getOrDefault("diamond", "311"));
    gap = Double.parseDouble(settings.getOrDefault("gap", "0.72"));
    if (baseHeight < 50
        || baseHeight > 160
        || amplitude < 0
        || amplitude > 20
        || coalRarity < 1
        || ironRarity < 1
        || diamondRarity < 1
        || gap < .1
        || gap > .95) throw new IllegalArgumentException("Invalid generation profile");
  }

  public int height(long seed, int x, int z) {
    if (hub) return 64;
    int variation = features.contains("rugged") ? Math.min(20, amplitude + 5) : amplitude;
    return baseHeight
        + (int)
            (variation * Math.sin((x + (seed & 1023)) * 0.041)
                + 7 * Math.cos((z + ((seed >>> 10) & 1023)) * 0.037));
  }

  @Override
  public void generateNoise(WorldInfo world, Random random, int cx, int cz, ChunkData data) {
    long seed = world.getSeed();
    for (int x = 0; x < 16; x++)
      for (int z = 0; z < 16; z++) {
        int gx = cx * 16 + x, gz = cz * 16 + z;
        int h = height(seed, gx, gz);
        boolean voidGap =
            !hub
                && (Math.abs(gx) > 12 || Math.abs(gz) > 12)
                && Math.sin(gx * .026) * Math.cos(gz * .026) > gap;
        if (voidGap) continue;
        data.setBlock(x, hub ? 60 : 32, z, Material.BEDROCK);
        for (int y = hub ? 61 : 33; y < h; y++) {
          Material m = y >= h - 3 ? Material.DIRT : Material.STONE;
          long hash = mix(seed ^ ((long) gx * 341873128712L) ^ ((long) gz * 132897987541L) ^ y);
          if (!hub && y < h - 4) {
            if (Math.floorMod(hash, coalRarity) == 0) m = Material.COAL_ORE;
            if (features.contains("iron") && Math.floorMod(hash, ironRarity) == 0)
              m = Material.IRON_ORE;
            if (features.contains("diamond") && y < 45 && Math.floorMod(hash, diamondRarity) == 0)
              m = Material.DIAMOND_ORE;
          }
          data.setBlock(x, y, z, m);
        }
        data.setBlock(x, h, z, Material.GRASS_BLOCK);
      }
    if (!hub) {
      int x = 4 + random.nextInt(8),
          z = 4 + random.nextInt(8),
          h = height(seed, cx * 16 + x, cz * 16 + z);
      if (data.getType(x, h, z) == Material.GRASS_BLOCK) {
        for (int y = h + 1; y <= h + 4; y++) data.setBlock(x, y, z, Material.OAK_LOG);
        for (int dx = -2; dx <= 2; dx++)
          for (int dz = -2; dz <= 2; dz++)
            for (int dy = 3; dy <= 5; dy++)
              if (Math.abs(dx) + Math.abs(dz) < 4 && (dx != 0 || dz != 0 || dy == 5))
                data.setBlock(x + dx, h + dy, z + dz, Material.OAK_LEAVES);
        if (features.contains("ruins") && Math.floorMod(mix(seed ^ cx ^ ((long) cz << 32)), 7) == 0)
          for (int dx = 1; dx <= 5; dx++)
            for (int dz = 1; dz <= 5; dz++)
              data.setBlock(
                  dx, height(seed, cx * 16 + dx, cz * 16 + dz) + 1, dz, Material.MOSSY_COBBLESTONE);
      }
    }
  }

  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }

  @Override
  public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
    List<Biome> biomes =
        features.contains("desert") ? List.of(Biome.PLAINS, Biome.DESERT) : List.of(Biome.PLAINS);
    return new BiomeProvider() {
      public Biome getBiome(WorldInfo w, int x, int y, int z) {
        return biomes.get(
            Math.floorMod(
                (int) mix(w.getSeed() ^ (x >> 6) ^ ((long) (z >> 6) << 32)), biomes.size()));
      }

      public List<Biome> getBiomes(WorldInfo w) {
        return biomes;
      }
    };
  }

  @Override
  public Location getFixedSpawnLocation(World world, Random random) {
    return new Location(world, .5, height(world.getSeed(), 0, 0) + 1, .5);
  }

  @Override
  public boolean shouldGenerateMobs() {
    return !hub;
  }
}
