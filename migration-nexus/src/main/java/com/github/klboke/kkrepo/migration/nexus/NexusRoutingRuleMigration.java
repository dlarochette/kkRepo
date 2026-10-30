package com.github.klboke.kkrepo.migration.nexus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusInventory;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryDocument;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao.Rule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Configuration-only migration. Rules and bindings commit in the enclosing migration transaction. */
public final class NexusRoutingRuleMigration {
  public record Definition(String name, String description, String mode, List<String> matchers) {
    public Definition { matchers = List.copyOf(matchers); }
  }
  public record Binding(String repository, String rule) {}
  public record Plan(List<Definition> rules, List<Binding> bindings, List<String> errors, String checksum) {
    public Plan { rules = List.copyOf(rules); bindings = List.copyOf(bindings); errors = List.copyOf(errors); }
    public void requireValid() {
      if (!errors.isEmpty()) throw new IllegalArgumentException("Routing rule migration blocked: " + String.join("; ", errors));
    }
  }

  private final RoutingRuleDao rules;
  private final RepositoryDao repositories;
  public NexusRoutingRuleMigration(RoutingRuleDao rules, RepositoryDao repositories) {
    this.rules = rules;
    this.repositories = repositories;
  }

  public Plan plan(NexusInventory inventory, List<RepositoryDocument> selected) {
    List<String> errors = new ArrayList<>();
    if (inventory.routingRulesError() != null) errors.add(inventory.routingRulesError());
    Map<String, Definition> definitions = new LinkedHashMap<>();
    for (Map<String, Object> raw : inventory.routingRules()) {
      try {
        Definition definition = definition(raw);
        if (definitions.putIfAbsent(definition.name(), definition) != null) {
          errors.add("Duplicate source routing rule: " + definition.name());
        }
      } catch (IllegalArgumentException invalid) {
        errors.add("Invalid source routing rule " + raw.get("name") + ": " + invalid.getMessage());
      }
    }
    List<Binding> bindings = new ArrayList<>();
    for (RepositoryDocument repository : selected) {
      String name = value(repository, "name");
      String modern = value(repository, "routingRuleName");
      String legacy = value(repository, "routingRule");
      if (modern != null && legacy != null && !modern.equals(legacy)) {
        errors.add("Conflicting routing rule fields on repository: " + name);
      }
      String assigned = modern != null ? modern : legacy;
      if (assigned != null) {
        bindings.add(new Binding(name, assigned));
        if (!"proxy".equalsIgnoreCase(value(repository, "type"))) {
          errors.add("Routing rule assigned to non-proxy repository: " + name);
        }
        if (!definitions.containsKey(assigned)) errors.add("Missing routing rule " + assigned + " for repository " + name);
      }
      // Do not silently replace an already configured target policy during a rerun.
      if (repositories != null) repositories.findByName(name).ifPresent(existing -> {
        if (existing.routingRuleId() != null) {
          Rule current = rules == null ? null : rules.findById(existing.routingRuleId()).orElse(null);
          if (current == null || !Objects.equals(current.name(), assigned)) {
            errors.add("Target repository already has a different routing rule: " + name);
          }
        }
      });
    }
    List<Definition> ordered = definitions.values().stream().sorted(Comparator.comparing(Definition::name)).toList();
    bindings.sort(Comparator.comparing(Binding::repository));
    if (!ordered.isEmpty() && rules == null) errors.add("Target routing rule persistence is unavailable");
    if (rules != null) for (Definition definition : ordered) {
      rules.findByName(definition.name()).ifPresent(existing -> {
        if (!same(existing, definition)) errors.add("Target routing rule has different content: " + definition.name());
      });
    }
    return new Plan(ordered, bindings, errors, checksum(ordered, bindings));
  }

  /** Recheck under row locks: another replica may edit/delete a rule after preflight. */
  public Map<String, Long> apply(Plan plan) {
    plan.requireValid();
    Map<String, Long> ids = new LinkedHashMap<>();
    for (Definition definition : plan.rules()) {
      Rule existing = rules.lockByName(definition.name()).orElse(null);
      if (existing == null) {
        rules.insert(definition.name(), definition.description(), definition.mode(), definition.matchers());
        existing = rules.lockByName(definition.name()).orElseThrow();
      }
      if (!same(existing, definition)) {
        throw new IllegalArgumentException("Target routing rule changed during migration: " + definition.name());
      }
      ids.put(definition.name(), existing.id());
    }
    return ids;
  }

  public List<String> validate(Plan plan) {
    List<String> failures = new ArrayList<>();
    for (Definition definition : plan.rules()) {
      Rule stored = rules.findByName(definition.name()).orElse(null);
      if (stored == null || !same(stored, definition)) failures.add("Rule content mismatch: " + definition.name());
    }
    for (Binding binding : plan.bindings()) {
      var repository = repositories.findByName(binding.repository()).orElse(null);
      Rule stored = rules.findByName(binding.rule()).orElse(null);
      if (repository == null || stored == null || !Objects.equals(repository.routingRuleId(), stored.id())) {
        failures.add("Repository binding mismatch: " + binding.repository());
      }
    }
    return failures;
  }

  static boolean same(Rule rule, Definition definition) {
    return Objects.equals(rule.description(), definition.description()) && rule.mode().equals(definition.mode())
        && rule.matchers().equals(definition.matchers());
  }

  private static Definition definition(Map<String, Object> raw) {
    String name = required(raw, "name"), mode = required(raw, "mode");
    if (name.length() > 200 || !name.matches("[a-zA-Z0-9-][a-zA-Z0-9_.-]*")) throw new IllegalArgumentException("Invalid name");
    if (!"ALLOW".equals(mode) && !"BLOCK".equals(mode)) throw new IllegalArgumentException("Invalid mode");
    if (!(raw.get("description") instanceof String description) || description.length() > 1024) {
      throw new IllegalArgumentException("Invalid description");
    }
    if (!(raw.get("matchers") instanceof List<?> values) || values.isEmpty()) throw new IllegalArgumentException("Missing matchers");
    List<String> matchers = new ArrayList<>();
    for (Object value : values) {
      if (!(value instanceof String matcher) || matcher.isEmpty()) throw new IllegalArgumentException("Invalid matcher");
      Pattern.compile(matcher);
      matchers.add(matcher);
    }
    return new Definition(name, description, mode, matchers);
  }

  private static String required(Map<String, Object> raw, String key) {
    if (raw.get(key) instanceof String value && !value.isBlank()) return value;
    throw new IllegalArgumentException("Missing " + key);
  }

  static String value(RepositoryDocument document, String key) {
    Object raw = document.detail().containsKey(key) ? document.detail().get(key) : document.summary().get(key);
    if (raw == null || raw instanceof String text && text.isBlank()) return null;
    if (!(raw instanceof String text)) throw new IllegalArgumentException("Invalid repository field: " + key);
    return text;
  }

  private static String checksum(List<Definition> rules, List<Binding> bindings) {
    try {
      byte[] bytes = new ObjectMapper().writeValueAsString(List.of(rules, bindings)).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) { throw new IllegalStateException("Cannot hash routing rule migration", failure); }
  }
}
