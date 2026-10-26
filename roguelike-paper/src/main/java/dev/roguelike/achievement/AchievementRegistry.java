package dev.roguelike.achievement;

import dev.roguelike.database.*;
import dev.roguelike.run.RunRepository;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.configuration.file.YamlConfiguration;

public final class AchievementRegistry {
  private record Rule(
      String id, String scope, String metric, long threshold, long coins, String trophy) {}

  private record Award(Rule rule, String scope) {}

  private final Database db;
  private final AchievementService service;
  private final List<Rule> rules;

  public AchievementRegistry(Database db, AchievementService service, YamlConfiguration config) {
    this.db = db;
    this.service = service;
    var list = new ArrayList<Rule>();
    var root = Objects.requireNonNull(config.getConfigurationSection("achievements"));
    for (String id : root.getKeys(false)) {
      var c = root.getConfigurationSection(id);
      var rule =
          new Rule(
              id,
              c.getString("scope"),
              c.getString("metric"),
              c.getLong("threshold"),
              c.getLong("coins"),
              c.getString("trophy", ""));
      if (!Set.of("run", "annual", "lifetime").contains(rule.scope())
          || !Set.of("runs", "deaths", "coins", "survival", "rebirths").contains(rule.metric())
          || rule.threshold() < 1
          || rule.coins() < 0) throw new IllegalArgumentException("Invalid achievement " + id);
      list.add(rule);
    }
    rules = List.copyOf(list);
  }

  public CompletableFuture<Void> evaluate(UUID player) {
    String p = player.toString();
    return db.read(
            c -> {
              long epoch = RunRepository.epoch(c);
              var eligible = new ArrayList<Award>();
              for (var rule : rules) {
                String scope = rule.scope().equals("annual") ? "year:" + epoch : rule.scope();
                long value;
                if (rule.scope().equals("run")) {
                  var rows =
                      Sql.rows(
                          c,
                          "SELECT id,created,ended,reward FROM runs WHERE player=? AND ended IS NOT"
                              + " NULL AND epoch=? ORDER BY ended DESC LIMIT 1",
                          p,
                          epoch);
                  if (rows.isEmpty()) continue;
                  var run = rows.getFirst();
                  scope = "run:" + run.get("id");
                  value =
                      switch (rule.metric()) {
                        case "runs", "deaths" -> 1;
                        case "coins" -> ((Number) run.get("reward")).longValue();
                        case "survival" ->
                            Math.max(
                                0,
                                (((Number) run.get("ended")).longValue()
                                        - ((Number) run.get("created")).longValue())
                                    / 1000);
                        default -> 0;
                      };
                } else
                  value =
                      Sql.number(
                          c,
                          "SELECT COALESCE(SUM(value),0) FROM statistics WHERE player=? AND"
                              + " metric=? AND (?='lifetime' OR epoch=?)",
                          p,
                          rule.metric(),
                          rule.scope(),
                          epoch);
                if (value >= rule.threshold()) eligible.add(new Award(rule, scope));
              }
              return eligible;
            })
        .thenCompose(
            eligible ->
                CompletableFuture.allOf(
                    eligible.stream()
                        .map(
                            a ->
                                service.award(
                                    p,
                                    a.scope(),
                                    a.rule().id(),
                                    a.rule().coins(),
                                    a.rule().trophy()))
                        .toArray(CompletableFuture[]::new)));
  }
}
