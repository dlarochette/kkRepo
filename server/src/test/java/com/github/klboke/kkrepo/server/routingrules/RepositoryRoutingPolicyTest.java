package com.github.klboke.kkrepo.server.routingrules;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao.Rule;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao.Binding;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RepositoryRoutingPolicyTest {
  private final RoutingRuleDao rules = mock(RoutingRuleDao.class);
  private final RepositoryRoutingPolicy policy = new RepositoryRoutingPolicy(rules);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(repositories, 300);

  @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

  @Test void blocksCachedRuntimeAndObservesChangesOnNextRequestWithoutCatalogInvalidation() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, "private/a", request()));
    when(rules.bindings()).thenReturn(List.of(binding("ALLOW", "/private/.*")));
    assertSame(proxy, policy.prepare(proxy, "private/a", request()));
    verify(rules, times(2)).bindings();
  }

  @Test void projectsNestedGroupsWithoutChangingCachedDefinitionsAndScopesGroupCaches() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    RepositoryRuntime hosted = runtime(2, RepositoryType.HOSTED);
    RepositoryRuntime nested = runtime(3, RepositoryType.GROUP).withMembers(List.of(proxy, hosted));
    RepositoryRuntime group = runtime(4, RepositoryType.GROUP).withMembers(List.of(nested));
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    RepositoryRuntime filtered = policy.prepare(group, "private/a", request());
    assertEquals(List.of(hosted), filtered.members().getFirst().members());
    assertEquals(List.of(proxy, hosted), group.members().getFirst().members());
    String blockedToken = RepositoryRoutingPolicy.cacheToken(group.id(), "1");
    assertNotEquals("1", blockedToken);
    policy.prepare(group, "public/a", request());
    assertNotEquals(blockedToken, RepositoryRoutingPolicy.cacheToken(group.id(), "1"));
    assertSame(group, policy.project(group));
    RequestContextHolder.resetRequestAttributes();
    assertEquals("1", RepositoryRoutingPolicy.cacheToken(group.id(), "1"));
  }

  @Test void durableGroupReloadsCannotRestoreDeniedMembers() {
    RepositoryRecord proxy = record(1, RepositoryType.PROXY);
    RepositoryRecord hosted = record(2, RepositoryType.HOSTED);
    RepositoryRecord group = record(3, RepositoryType.GROUP);
    when(repositories.findById(3L)).thenReturn(Optional.of(group));
    when(repositories.listMembers(3L)).thenReturn(List.of(proxy, hosted));
    RepositoryRuntime unfiltered = registry.resolveFreshById(3).orElseThrow();
    ReflectionTestUtils.setField(registry, "routingPolicy", policy);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/.*")));
    policy.prepare(unfiltered, "item", request());
    assertEquals(List.of(2L), registry.resolveFreshById(3).orElseThrow().members().stream()
        .map(RepositoryRuntime::id).toList());
  }

  @Test void includesQueriesUsesWholePathAnyMatchAndDoesNotApplyToHostedOrWrites() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("ALLOW", "/a", "/b\\?x=1")));
    var request = request(); request.addParameter("x", "1");
    assertSame(proxy, policy.prepare(proxy, "b", request));
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, "aa", request()));
    assertSame(runtime(2, RepositoryType.HOSTED).type(),
        policy.prepare(runtime(2, RepositoryType.HOSTED), "aa", request()).type());
    var put = request(); put.setMethod("PUT");
    assertSame(proxy, policy.prepare(proxy, "aa", put));
  }

  @Test void decodesRawPathsOnceAndSeparatesDecodedQuestionMarksFromQueries() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*", "/a\\?b\\?x=1")));
    var encoded = request(); encoded.setRequestURI("/repository/%74est/%70rivate/file");
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepareRepository(proxy, encoded));
    assertSame(proxy, policy.prepare(proxy, "/%2570rivate/file", request()));
    var query = request(); query.addParameter("x", "1");
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, "/a%3Fb", query));
    assertSame(proxy, policy.prepare(proxy, "/public/a+b", request()));
  }

  @Test void alternatePathsCannotRestoreBlockedProxyMembersOrHitTheirCachedAssets() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    RepositoryRuntime hosted = runtime(2, RepositoryType.HOSTED);
    RepositoryRuntime group = runtime(3, RepositoryType.GROUP).withMembers(List.of(proxy, hosted));
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    for (String path : List.of("//private/file", "/public/../private/file", "/public/%2e%2e/private/file", "/./private/file")) {
      assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepare(proxy, path, request()), path);
      assertEquals(List.of(hosted), policy.prepare(group, path, request()).members(), path);
    }
    var invalid = assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> policy.prepare(proxy, "/../private/file", request()));
    assertEquals(400, invalid.getStatusCode().value());
  }

  @Test void malformedEncodingAndCorruptPersistedModesNeverAllowDownloads() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    var failure = assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> policy.prepare(proxy, "/%GG", request()));
    assertEquals(400, failure.getStatusCode().value());
    when(rules.bindings()).thenReturn(List.of(binding("unknown", "/private/.*")));
    assertThrows(IllegalStateException.class, () -> policy.prepare(proxy, "/public/file", request()));
  }

  @Test void matrixParametersFollowMvcWithoutReinterpretingEncodedSemicolons() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    RepositoryRuntime hosted = runtime(2, RepositoryType.HOSTED);
    RepositoryRuntime group = runtime(3, RepositoryType.GROUP).withMembers(List.of(proxy, hosted));
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/simple/private/.*")));
    assertThrows(RepositoryRoutingPolicy.Blocked.class,
        () -> policy.prepare(proxy, "/simple;x=1/private;x=2/", request()));
    assertEquals(List.of(hosted), policy.prepare(group, "/simple/private;x=2/", request()).members());
    assertSame(proxy, policy.prepare(proxy, "/simple/private%3Bx=2/", request()));
    assertSame(proxy, policy.prepare(proxy, "/simple/private%253Bx=2/", request()));
  }

  @Test void repositoryBoundaryUsesDecodedMvcSegmentsIncludingCustomPrefixAndContext() {
    RepositoryRuntime proxy = runtime(1, RepositoryType.PROXY);
    when(rules.bindings()).thenReturn(List.of(binding("BLOCK", "/private/.*")));
    var request = request();
    request.setContextPath("/ctx");
    new com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix("/custom/repos")
        .onStartup(request.getServletContext());
    request.setRequestURI("/ctx/custom;x=1/%72epos/%74est;y=2/%70rivate/file");
    assertThrows(RepositoryRoutingPolicy.Blocked.class, () -> policy.prepareRepository(proxy, request));
    var opaque = request();
    opaque.setRequestURI("/repository/test/%2570rivate/file");
    assertSame(proxy, policy.prepareRepository(proxy, opaque));
  }

  private Binding binding(String mode, String... matchers) {
    return new Binding(1, new Rule(10, "rule", "", mode, List.of(matchers)));
  }
  private MockHttpServletRequest request() {
    var request = new MockHttpServletRequest("GET", "/repository/test/file");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request)); return request;
  }
  private RepositoryRuntime runtime(long id, RepositoryType type) {
    when(repositories.findById(id)).thenReturn(Optional.of(record(id, type)));
    return registry.resolveFreshById(id).orElseThrow();
  }
  private RepositoryRecord record(long id, RepositoryType type) {
    return new RepositoryRecord(id, "repo" + id, RepositoryFormat.RAW, type,
        "raw-" + type.name().toLowerCase(), true, 1L, null, "https://example.com/",
        null, null, null, false, Map.of());
  }
}
