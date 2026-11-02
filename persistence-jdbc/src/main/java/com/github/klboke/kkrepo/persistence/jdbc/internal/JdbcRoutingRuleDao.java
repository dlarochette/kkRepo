package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRoutingRuleDao implements RoutingRuleDao {
  private static final TypeReference<List<String>> MATCHERS = new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final JsonColumns json;
  private final RowMapper<Rule> mapper;

  public JdbcRoutingRuleDao(JdbcTemplate jdbc, JsonColumns json) {
    this.jdbc = jdbc;
    this.json = json;
    this.mapper = (rs, n) -> new Rule(rs.getLong("id"), rs.getString("name"),
        rs.getString("description"), rs.getString("mode"), json.readValue(rs.getString("matchers_json"), MATCHERS));
  }

  @Override public List<Rule> list() {
    return jdbc.query("SELECT * FROM routing_rule ORDER BY name", mapper);
  }
  @Override public Optional<Rule> findById(long id) {
    return jdbc.query("SELECT * FROM routing_rule WHERE id = ?", mapper, id).stream().findFirst();
  }
  @Override public Optional<Rule> findByName(String name) {
    return jdbc.query("SELECT * FROM routing_rule WHERE name = ?", mapper, name).stream().findFirst();
  }
  @Override public Optional<Rule> lockByName(String name) {
    return jdbc.query("SELECT * FROM routing_rule WHERE name = ? FOR UPDATE", mapper, name).stream().findFirst();
  }
  @Override public long insert(String name, String description, String mode, List<String> matchers) {
    return JdbcInserts.insert(jdbc,
        "INSERT INTO routing_rule (name, description, mode, matchers_json) VALUES (?, ?, ?, ?)", ps -> {
          ps.setString(1, name); ps.setString(2, description); ps.setString(3, mode);
          json.bindSerialized(ps, 4, json.writeValue(matchers));
        });
  }
  @Override public void update(long id, String name, String description, String mode, List<String> matchers) {
    jdbc.update("UPDATE routing_rule SET name = ?, description = ?, mode = ?, matchers_json = ? WHERE id = ?",
        name, description, mode, json.serializedParameter(json.writeValue(matchers)), id);
  }
  @Override public void delete(long id) { jdbc.update("DELETE FROM routing_rule WHERE id = ?", id); }
  @Override public List<String> repositoriesUsing(long id) {
    return jdbc.queryForList("SELECT name FROM repository WHERE routing_rule_id = ? ORDER BY name", String.class, id);
  }
  @Override public List<Binding> bindings() {
    return jdbc.query("SELECT r.id AS repository_id, rr.* FROM repository r "
        + "JOIN routing_rule rr ON rr.id = r.routing_rule_id WHERE r.type = 'proxy'",
        (rs, n) -> new Binding(rs.getLong("repository_id"), mapper.mapRow(rs, n)));
  }
}
