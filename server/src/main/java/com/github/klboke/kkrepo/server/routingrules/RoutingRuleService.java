package com.github.klboke.kkrepo.server.routingrules;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao.Rule;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoutingRuleService {
  private final RoutingRuleDao rules;
  public RoutingRuleService(RoutingRuleDao rules) { this.rules = rules; }

  public record Configuration(String name, String description, String mode, List<String> matchers) {}
  public record Preview(String mode, List<String> matchers, String path) {}
  public record PreviewResult(boolean allowed, List<String> matchingExpressions) {}

  public List<Configuration> list() { return rules.list().stream().map(RoutingRuleService::view).toList(); }
  public Configuration get(String name) { return view(require(name)); }
  public String name(Long id) { return id == null ? null : rules.findById(id).map(Rule::name).orElse(null); }
  public List<String> usedBy(String name) { return rules.repositoriesUsing(require(name).id()); }

  @Transactional
  public void create(Configuration input) {
    validate(input);
    try { rules.insert(input.name(), input.description(), input.mode(), input.matchers()); }
    catch (DuplicateKeyException e) { throw badRequest("A routing rule with this name already exists"); }
  }

  @Transactional
  public void update(String name, Configuration input) {
    validate(input);
    Rule current = lock(name);
    try { rules.update(current.id(), input.name(), input.description(), input.mode(), input.matchers()); }
    catch (DuplicateKeyException e) { throw badRequest("A routing rule with this name already exists"); }
  }

  @Transactional
  public void delete(String name) {
    Rule current = lock(name);
    if (!rules.repositoriesUsing(current.id()).isEmpty()) {
      throw badRequest("Routing rule is in use by a repository");
    }
    rules.delete(current.id());
  }

  /** Locks the rule until the enclosing repository write commits, serializing binding with deletion. */
  public Long binding(RepositoryType type, String name, Long existing) {
    if (name == null) return existing; // Older kkRepo repository clients perform partial updates.
    if (name.isEmpty()) return null;
    if (type != RepositoryType.PROXY) throw badRequest("Routing rules can only be assigned to proxy repositories");
    return rules.lockByName(name).orElseThrow(() -> badRequest("Routing rule not found: " + name)).id();
  }

  public PreviewResult preview(Preview input) {
    if (input == null || input.path() == null || !input.path().startsWith("/")) {
      throw badRequest("A repository-relative path starting with / is required");
    }
    validateMatchers(input.mode(), input.matchers());
    List<String> matches = input.matchers().stream().filter(p -> input.path().matches(p)).toList();
    return new PreviewResult("ALLOW".equals(input.mode()) == !matches.isEmpty(), matches);
  }

  private Rule require(String name) {
    return rules.findByName(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Routing rule not found"));
  }
  private Rule lock(String name) {
    return rules.lockByName(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Routing rule not found"));
  }
  private static Configuration view(Rule rule) {
    return new Configuration(rule.name(), rule.description(), rule.mode(), rule.matchers());
  }
  private static void validate(Configuration input) {
    if (input == null || input.name() == null || input.name().length() > 200
        || !input.name().matches("[a-zA-Z0-9-][a-zA-Z0-9_.-]*")) throw badRequest("Invalid routing rule name");
    if (input.description() == null || input.description().length() > 1024) throw badRequest("Description is required (maximum 1024 characters)");
    validateMatchers(input.mode(), input.matchers());
  }
  private static void validateMatchers(String mode, List<String> matchers) {
    if (!"ALLOW".equals(mode) && !"BLOCK".equals(mode)) throw badRequest("Mode must be ALLOW or BLOCK");
    if (matchers == null || matchers.isEmpty()) throw badRequest("At least one matcher is required");
    for (String matcher : matchers) {
      if (matcher == null || matcher.isEmpty()) throw badRequest("Matchers must not be empty");
      try { Pattern.compile(matcher); }
      catch (PatternSyntaxException e) { throw badRequest("Invalid regular expression: " + e.getDescription()); }
    }
  }
  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
