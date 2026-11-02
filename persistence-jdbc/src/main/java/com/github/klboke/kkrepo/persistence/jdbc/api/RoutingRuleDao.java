package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.util.List;
import java.util.Optional;

/** Durable routing rules and repository bindings, shared by all replicas. */
public interface RoutingRuleDao {
  record Rule(long id, String name, String description, String mode, List<String> matchers) {
    public Rule { matchers = List.copyOf(matchers); }
  }
  record Binding(long repositoryId, Rule rule) {}
  List<Rule> list();
  Optional<Rule> findById(long id);
  Optional<Rule> findByName(String name);
  /** Must be called in the transaction that binds, updates or deletes the rule. */
  Optional<Rule> lockByName(String name);
  long insert(String name, String description, String mode, List<String> matchers);
  void update(long id, String name, String description, String mode, List<String> matchers);
  void delete(long id);
  List<String> repositoriesUsing(long id);
  /** One consistent SQL snapshot; never read authorization-sensitive bindings from a TTL cache. */
  List<Binding> bindings();
}
