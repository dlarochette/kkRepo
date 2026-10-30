package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CsrfProtectionFilterTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "/service/rest/v1/%73ecurity/users",
      "/service/rest/v1/routing-rules",
      "/service/rest/v1/%72outing-rules/rule",
      "/internal/routing-rules/preview",
      "/service/rest/v1/security;x/users",
      "/%69nternal/security/users",
      "/internal/security;x/users",
      "/service/rest/internal/ui/%73ecurity/users"
  })
  void alternativeManagementPathsStillRequireCsrf(String path) throws Exception {
    CsrfProtectionFilter filter = filter(true);
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();
    filter.doFilter(new MockHttpServletRequest("POST", path), response, chain);
    assertEquals(403, response.getStatus());
    assertEquals(0, chain.calls);
  }

  @Test
  void encodedContextBootstrapIssuesUsableCsrfToken() throws Exception {
    CsrfProtectionFilter filter = filter(false);
    MockHttpServletRequest bootstrap = new MockHttpServletRequest("GET", "/internal/%73ecurity/context;x");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(bootstrap, response, new CountingChain());
    assertNotNull(response.getHeader(CsrfProtectionFilter.CSRF_HEADER));
    MockHttpServletRequest mutation = new MockHttpServletRequest("POST", "/internal/security;x/users");
    mutation.setSession(bootstrap.getSession(false));
    mutation.addHeader(CsrfProtectionFilter.CSRF_HEADER, response.getHeader(CsrfProtectionFilter.CSRF_HEADER));
    CountingChain chain = new CountingChain();
    filter.doFilter(mutation, new MockHttpServletResponse(), chain);
    assertEquals(1, chain.calls);
  }

  @Test
  void safeInternalRequestIssuesToken() throws Exception {
    CsrfProtectionFilter filter = filter(false);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/security/session");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertEquals(1, chain.calls);
    assertNotNull(response.getHeader(CsrfProtectionFilter.CSRF_HEADER));
    assertNotNull(response.getHeader("Set-Cookie"));
  }

  @Test
  void uiContextIssuesToken() throws Exception {
    CsrfProtectionFilter filter = filter(false);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/security/context");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertEquals(1, chain.calls);
    assertNotNull(response.getHeader(CsrfProtectionFilter.CSRF_HEADER));
    assertNotNull(response.getHeader("Set-Cookie"));
  }

  @Test
  void safeRepositoryListDoesNotCreateSessionOrToken() throws Exception {
    CsrfProtectionFilter filter = filter(false);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/repositories");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertEquals(1, chain.calls);
    assertNull(request.getSession(false));
    assertNull(response.getHeader(CsrfProtectionFilter.CSRF_HEADER));
    assertNull(response.getHeader("Set-Cookie"));
  }

  @Test
  void unsafeInternalRequestWithoutTokenIsRejected() throws Exception {
    CsrfProtectionFilter filter = filter(false);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/security/users");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertEquals(0, chain.calls);
    assertEquals(403, response.getStatus());
  }

  @Test
  void unsafeInternalRequestWithTokenPasses() throws Exception {
    CsrfProtectionFilter filter = filter(false);
    MockHttpServletRequest bootstrap = new MockHttpServletRequest("GET", "/internal/security/session");
    MockHttpServletResponse bootstrapResponse = new MockHttpServletResponse();
    filter.doFilter(bootstrap, bootstrapResponse, new CountingChain());
    String token = bootstrapResponse.getHeader(CsrfProtectionFilter.CSRF_HEADER);

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/security/users");
    request.setSession(bootstrap.getSession(false));
    request.addHeader(CsrfProtectionFilter.CSRF_HEADER, token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertEquals(1, chain.calls);
    assertEquals(200, response.getStatus());
  }

  @Test
  void authorizationHeaderBypassesCsrfForApiClients() throws Exception {
    CsrfProtectionFilter filter = filter(true);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/service/extdirect");
    request.addHeader("Authorization", "Basic abc");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertEquals(1, chain.calls);
    assertEquals(200, response.getStatus());
  }

  @Test
  void legacyInternalUiRequestFallsThroughWhenLegacyUiDisabled() throws Exception {
    CsrfProtectionFilter filter = filter(false);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/service/rest/internal/ui/upload/maven-releases");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertEquals(1, chain.calls);
    assertEquals(200, response.getStatus());
  }

  @Test
  void legacyInternalUiRequestIsProtectedWhenLegacyUiEnabled() throws Exception {
    CsrfProtectionFilter filter = filter(true);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/service/rest/internal/ui/upload/maven-releases");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertEquals(0, chain.calls);
    assertEquals(403, response.getStatus());
  }

  @Test
  void repositoryWritesAreNotCsrfProtected() throws Exception {
    CsrfProtectionFilter filter = filter(false);
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/repository/maven-releases/a.jar");
    MockHttpServletResponse response = new MockHttpServletResponse();
    CountingChain chain = new CountingChain();

    filter.doFilter(request, response, chain);

    assertEquals(1, chain.calls);
    assertEquals(200, response.getStatus());
  }

  private static CsrfProtectionFilter filter(boolean legacyUiEnabled) {
    return new CsrfProtectionFilter(
        "X-Nexus-Plus-Token",
        false,
        new NexusLegacyUiCompatibility(legacyUiEnabled));
  }

  private static class CountingChain implements FilterChain {
    private int calls;

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
      calls++;
    }
  }
}
