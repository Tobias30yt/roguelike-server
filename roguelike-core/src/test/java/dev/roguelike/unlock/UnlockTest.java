package dev.roguelike.unlock;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class UnlockTest {
  UnlockDefinition d(String id, String... parents) {
    return new UnlockDefinition(id, "tools", id, 1, Set.of(parents), Set.of(), id, 0);
  }

  @Test
  void cyclesAndMissingDependenciesAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new UnlockService(null, null, List.of(d("a", "b"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UnlockService(null, null, List.of(d("a", "b"), d("b", "a"))));
    var service = new UnlockService(null, null, List.of(d("a"), d("b", "a")));
    assertEquals("a,b", service.profile(Set.of("a", "b")));
  }
}
