package dev.roguelike.unlock;

import dev.roguelike.database.*;
import dev.roguelike.economy.EconomyService;
import dev.roguelike.run.RunRepository;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class UnlockService {
  private final Database db;
  private final EconomyService economy;
  private final Map<String, UnlockDefinition> definitions;

  public UnlockService(Database db, EconomyService economy, List<UnlockDefinition> entries) {
    this.db = db;
    this.economy = economy;
    var map = new LinkedHashMap<String, UnlockDefinition>();
    for (var d : entries)
      if (map.put(d.id(), d) != null)
        throw new IllegalArgumentException("Duplicate unlock " + d.id());
    definitions = Collections.unmodifiableMap(map);
    for (String id : map.keySet()) visit(id, new HashSet<>(), new HashSet<>());
  }

  private void visit(String id, Set<String> visiting, Set<String> done) {
    if (done.contains(id)) return;
    if (!visiting.add(id)) throw new IllegalArgumentException("Cyclic unlock: " + id);
    var d = definitions.get(id);
    if (d == null) throw new IllegalArgumentException("Missing prerequisite: " + id);
    for (String parent : d.requires()) visit(parent, visiting, done);
    visiting.remove(id);
    done.add(id);
  }

  public Collection<UnlockDefinition> definitions() {
    return definitions.values();
  }

  public CompletableFuture<Set<String>> owned(UUID player) {
    return db.read(
        c -> {
          var set = new HashSet<String>();
          for (var row :
              Sql.rows(
                  c,
                  "SELECT id FROM unlocks WHERE player=? AND epoch=?",
                  player.toString(),
                  RunRepository.epoch(c))) set.add((String) row.get("id"));
          return Set.copyOf(set);
        });
  }

  public CompletableFuture<Void> buy(UUID player, String id) {
    return db.transaction(
        c -> {
          RunRepository.writable(c);
          var definition = Objects.requireNonNull(definitions.get(id), "Unknown unlock");
          long epoch = RunRepository.epoch(c);
          String p = player.toString();
          if (Sql.number(
                  c,
                  "SELECT COUNT(*) FROM unlocks WHERE player=? AND epoch=? AND id=?",
                  p,
                  epoch,
                  id)
              > 0) throw new IllegalArgumentException("Already unlocked");
          for (String parent : definition.requires())
            if (Sql.number(
                    c,
                    "SELECT COUNT(*) FROM unlocks WHERE player=? AND epoch=? AND id=?",
                    p,
                    epoch,
                    parent)
                == 0) throw new IllegalArgumentException("Requires " + parent);
          if (Sql.number(
                  c,
                  "SELECT COALESCE(SUM(value),0) FROM statistics WHERE player=? AND epoch=? AND"
                      + " metric='runs'",
                  p,
                  epoch)
              < definition.runsRequired())
            throw new IllegalArgumentException("Not enough completed runs");
          economy.post(
              c, "unlock:" + epoch + ":" + p + ":" + id, p, epoch, -definition.price(), "unlock");
          Sql.update(c, "INSERT INTO unlocks VALUES(?,?,?)", p, epoch, id);
          return null;
        });
  }

  public String profile(Set<String> owned) {
    return definitions.values().stream()
        .filter(d -> owned.contains(d.id()))
        .map(UnlockDefinition::feature)
        .filter(s -> s != null && !s.isBlank())
        .sorted()
        .collect(java.util.stream.Collectors.joining(","));
  }
}
