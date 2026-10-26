package dev.roguelike.unlock;

import java.util.Set;

public record UnlockDefinition(
    String id,
    String category,
    String description,
    long price,
    Set<String> requires,
    Set<String> materials,
    String feature,
    long runsRequired) {
  public UnlockDefinition {
    requires = Set.copyOf(requires);
    materials = Set.copyOf(materials);
    if (!id.matches("[a-z0-9_]+") || price < 0 || runsRequired < 0)
      throw new IllegalArgumentException("Invalid unlock: " + id);
  }
}
