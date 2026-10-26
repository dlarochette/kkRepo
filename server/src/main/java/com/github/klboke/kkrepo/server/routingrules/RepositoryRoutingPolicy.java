package com.github.klboke.kkrepo.server.routingrules;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao.Rule;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Request-local projection of a repository tree. Durable bindings are read once per request,
 * after authentication, before any protocol cache or remote fetch. Cached runtime definitions
 * are never modified. Groups omit denied proxies and retain ordered fallback to other members.
 *
 * <p>The database is the sole rule authority: sibling replicas see committed changes on their
 * next request, independently of repository-runtime and catalog cache TTLs. Request-local maps
 * are discarded with the servlet request and are not node-local policy caches.
 */
@Service
public class RepositoryRoutingPolicy {
  private static final String CONTEXT = RepositoryRoutingPolicy.class.getName();
  private final RoutingRuleDao rules;
  public RepositoryRoutingPolicy(RoutingRuleDao rules) { this.rules = rules; }

  /** Extract the raw relative path before protocol-specific decoding and normalization. */
  public RepositoryRuntime prepareRepository(RepositoryRuntime runtime, HttpServletRequest request) {
    String uri = request.getRequestURI();
    String prefix = request.getContextPath() + RepositoryUrlPrefix.path(request);
    int separator = uri.indexOf('/', prefix.length());
    return prepare(runtime, separator < 0 ? "/" : uri.substring(separator), request);
  }

  public RepositoryRuntime prepare(RepositoryRuntime runtime, String path, HttpServletRequest request) {
    if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) return runtime;
    if (!(request.getAttribute(CONTEXT) instanceof Context)) {
      Map<Long, Rule> bindings = new HashMap<>();
      rules.bindings().forEach(binding -> bindings.put(binding.repositoryId(), binding.rule()));
      String decoded;
      try { decoded = UriPathDecoder.decodeComponent(path); }
      catch (IllegalArgumentException invalid) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
      }
      StringBuilder matchPath = new StringBuilder(canonicalPath(decoded));
      StringBuilder query = new StringBuilder();
      // Nexus RoutingRuleHandler includes decoded query parameters in matcher input.
      request.getParameterMap().forEach((name, values) -> {
        for (String value : values) {
          if (!query.isEmpty()) query.append('&');
          query.append(name).append('=').append(value);
        }
      });
      if (!query.isEmpty()) matchPath.append('?').append(query);
      request.setAttribute(CONTEXT, new Context(matchPath.toString(), bindings));
    }
    return project(runtime, (Context) request.getAttribute(CONTEXT));
  }

  /** Match the resource identity after the slash and dot normalization used by routing/upstreams. */
  private static String canonicalPath(String decoded) {
    var segments = new ArrayDeque<String>();
    for (String segment : decoded.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".")) continue;
      if (segment.equals("..")) {
        if (segments.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path escapes repository");
        segments.removeLast();
      } else segments.addLast(segment);
    }
    String path = "/" + String.join("/", segments);
    return decoded.endsWith("/") && !segments.isEmpty() ? path + "/" : path;
  }

  /** Reapply the active request policy when a protocol reloads a durable group snapshot. */
  public RepositoryRuntime project(RepositoryRuntime runtime) {
    Context context = context();
    return context == null ? runtime : project(runtime, context);
  }

  private RepositoryRuntime project(RepositoryRuntime runtime, Context context) {
    if (runtime.isProxy()) {
      Rule rule = context.bindings.get(runtime.id());
      if (rule != null && !allows(rule, context.path)) {
        throw new Blocked();
      }
      return runtime;
    }
    if (!runtime.isGroup()) return runtime;
    List<RepositoryRuntime> members = new ArrayList<>();
    StringBuilder fingerprint = new StringBuilder();
    boolean affected = appendRules(runtime, context, fingerprint);
    for (RepositoryRuntime member : runtime.members()) {
      try { members.add(project(member, context)); }
      catch (Blocked ignored) { /* Nexus groups continue to the next member. */ }
    }
    if (affected) {
      // Include the decision as well as the rule configuration. Different paths may select
      // different members; their merged metadata/winner caches must not be interchangeable.
      appendMembership(members, fingerprint);
      context.groupFingerprints.put(runtime.id(), digest(fingerprint.toString()));
    }
    return members.equals(runtime.members()) ? runtime : runtime.withMembers(members);
  }

  private static boolean appendRules(RepositoryRuntime runtime, Context context, StringBuilder out) {
    Rule rule = context.bindings.get(runtime.id());
    if (rule != null) {
      out.append(runtime.id()).append(':').append(rule.mode()).append(':');
      for (String matcher : rule.matchers()) out.append(matcher.length()).append(':').append(matcher);
      return true;
    }
    boolean affected = false;
    for (RepositoryRuntime member : runtime.members()) affected |= appendRules(member, context, out);
    return affected;
  }

  private static void appendMembership(List<RepositoryRuntime> members, StringBuilder out) {
    out.append('[');
    for (RepositoryRuntime member : members) {
      out.append(member.id()).append(',');
      appendMembership(member.members(), out);
    }
    out.append(']');
  }

  static boolean allows(Rule rule, String path) {
    boolean matched = rule.matchers().stream().anyMatch(path::matches);
    return switch (rule.mode()) {
      case "ALLOW" -> matched;
      case "BLOCK" -> !matched;
      default -> throw new IllegalStateException("Invalid persisted routing rule mode");
    };
  }

  /** Namespace group cache entries by the policy snapshot that produced their content. */
  public static String cacheToken(long repositoryId, String token) {
    Context context = context();
    String fingerprint = context == null ? null : context.groupFingerprints.get(repositoryId);
    return fingerprint == null ? token : token + ":routing:" + fingerprint;
  }

  public static boolean filtersGroup(long repositoryId) {
    Context context = context();
    return context != null && context.groupFingerprints.containsKey(repositoryId);
  }

  private static Context context() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
        && attrs.getRequest().getAttribute(CONTEXT) instanceof Context context) return context;
    return null;
  }
  private static String digest(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  private static final class Context {
    final String path;
    final Map<Long, Rule> bindings;
    final Map<Long, String> groupFingerprints = new HashMap<>();
    Context(String path, Map<Long, Rule> bindings) { this.path = path; this.bindings = Map.copyOf(bindings); }
  }
  public static final class Blocked extends ResponseStatusException {
    public Blocked() { super(HttpStatus.FORBIDDEN, "Routing rules block the requested item from this repository"); }
  }
}
