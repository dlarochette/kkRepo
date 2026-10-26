package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoutingRulesBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(15)).build();

  @ParameterizedTest
  @ValueSource(strings = {"raw", "maven2"})
  void managementAndCachedProxyGroupBehaviorMatchNexus(String format) throws Exception {
    assumeTrue(Boolean.parseBoolean(CompatDefaults.setting("compat.routingRules", "ROUTING_RULES_COMPAT_ENABLED").orElse("false")));
    exercise(new Endpoint(CompatDefaults.nexusBaseUrl().orElseThrow(),
        CompatDefaults.nexusUsername().orElseThrow(), CompatDefaults.nexusPassword().orElseThrow(), true, format));
    if (!Boolean.getBoolean("compat.routingRules.referenceOnly")) {
      exercise(new Endpoint(CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
          CompatDefaults.nexusPlusUsername().orElseThrow(), CompatDefaults.nexusPlusPassword().orElseThrow(), false, format));
    }
  }

  private void exercise(Endpoint e) throws Exception {
    String prefix = "rr452-" + UUID.randomUUID().toString().substring(0, 8);
    String rule = prefix + "-rule", upstream = prefix + "-upstream", fallback = prefix + "-fallback";
    String proxy = prefix + "-proxy", group = prefix + "-group";
    String rules = "/service/rest/v1/routing-rules";
    try {
      check(e, "POST", rules, rule(rule, "BLOCK", List.of()), 400);
      check(e, "POST", rules, rule(rule, "BLOCK", List.of("[")), 400);
      check(e, "POST", rules, rule(rule, "BLOCK", List.of("/never")), 204);
      check(e, "POST", rules, rule(rule, "BLOCK", List.of("/never")), 400);
      JsonNode saved = JSON.readTree(check(e, "GET", rules + "/" + rule, null, 200).body());
      assertEquals("BLOCK", saved.path("mode").asText());
      assertEquals(List.of("/never"), JSON.convertValue(saved.path("matchers"), List.class));
      assertTrue(JSON.readTree(check(e, "GET", rules, null, 200).body()).toString().contains(rule));
      e.create(upstream, "hosted", null, null, List.of());
      e.create(fallback, "hosted", null, null, List.of());
      check(e, "PUT", "/repository/" + upstream + "/blocked/component/1.0/component-1.0.jar", "upstream", 201);
      check(e, "PUT", "/repository/" + fallback + "/blocked/component/1.0/component-1.0.jar", "fallback", 201);
      check(e, "PUT", "/repository/" + upstream + "/allowed/component/1.0/component-1.0.jar", "allowed", 201);
      String remote = e.nexus ? "http://localhost:8081/repository/" + upstream + "/"
          : e.base + "/repository/" + upstream + "/";
      e.create(proxy, "proxy", remote, rule, List.of());
      e.create(group, "group", null, null, List.of(proxy, fallback));
      assertEquals("upstream", check(e, "GET", "/repository/" + proxy + "/blocked/component/1.0/component-1.0.jar", null, 200).body());
      assertEquals("upstream", check(e, "GET", "/repository/" + group + "/blocked/component/1.0/component-1.0.jar", null, 200).body());
      check(e, "DELETE", rules + "/" + rule, null, 400);
      check(e, "PUT", rules + "/" + rule, rule(rule, "BLOCK", List.of("/blocked/.*", "/unused")), 204);
      check(e, "GET", "/repository/" + proxy + "/blocked/component/1.0/component-1.0.jar", null, 403);
      check(e, "HEAD", "/repository/" + proxy + "/blocked/component/1.0/component-1.0.jar", null, 403);
      check(e, "GET", "/repository/" + proxy + "/%62locked/component/1.0/component-1.0.jar", null, 403);
      assertEquals("fallback", check(e, "GET", "/repository/" + group + "/blocked/component/1.0/component-1.0.jar", null, 200).body());
      assertEquals("allowed", check(e, "GET", "/repository/" + proxy + "/allowed/component/1.0/component-1.0.jar", null, 200).body());
      check(e, "PUT", rules + "/" + rule, rule(rule, "ALLOW", List.of("/allowed/.*\\.jar")), 204);
      check(e, "GET", "/repository/" + proxy + "/blocked/component/1.0/component-1.0.jar", null, 403);
      check(e, "GET", "/repository/" + proxy + "/allowed/component/1.0/component-1.0.jar?x=1", null, 403);
      check(e, "GET", "/repository/" + proxy + "/allowed/component/1.0/component-1.0.jar", null, 200);
      check(e, "PUT", rules + "/" + rule, rule(rule, "BLOCK", List.of("blocked")), 204);
      // Java matches(), not substring/find(). Previously warmed contents remain available after unblocking.
      assertEquals("upstream", check(e, "GET", "/repository/" + proxy + "/blocked/component/1.0/component-1.0.jar", null, 200).body());
      assertEquals("upstream", check(e, "GET", "/repository/" + group + "/blocked/component/1.0/component-1.0.jar", null, 200).body());
    } finally {
      for (String repository : List.of(group, proxy, fallback, upstream)) {
        e.removeRepository(repository);
      }
      e.send("DELETE", rules + "/" + rule, null);
    }
    check(e, "GET", rules + "/" + rule, null, 404);
  }

  private static Map<String, Object> rule(String name, String mode, List<String> matchers) {
    return Map.of("name", name, "description", "Routing compatibility fixture", "mode", mode, "matchers", matchers);
  }

  private static HttpResponse<String> check(Endpoint e, String method, String path, Object body, int status)
      throws Exception {
    HttpResponse<String> response = e.send(method, path, body);
    assertEquals(status, response.statusCode(), e.base + " " + method + " " + path + ": " + response.body());
    return response;
  }

  private record Endpoint(String base, String username, String password, boolean nexus, String format) {
    HttpResponse<String> send(String method, String path, Object body) throws Exception {
      String serialized = body == null ? "" : body instanceof String s ? s : JSON.writeValueAsString(body);
      return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
          .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
              (username + ":" + password).getBytes(StandardCharsets.UTF_8)))
          .header("Content-Type", body instanceof String ? "text/plain" : "application/json")
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(serialized))
          .build(), HttpResponse.BodyHandlers.ofString());
    }

    void removeRepository(String name) throws Exception {
      if (!nexus) {
        // Explicit source also removes Maven group-owned cache assets, not member contents.
        for (String path : List.of("blocked", "allowed")) {
          HttpResponse<String> removed = send("DELETE", "/internal/browse/" + name
              + "?path=" + path + "&source=" + name, null);
          assertTrue(removed.statusCode() == 200 || removed.statusCode() == 404, removed.body());
        }
      }
      HttpResponse<String> removed = send("DELETE",
          (nexus ? "/service/rest/v1/repositories/" : "/internal/repositories/") + name, null);
      assertTrue(removed.statusCode() == 204 || removed.statusCode() == 404, removed.body());
    }

    void create(String name, String type, String remote, String rule, List<String> members) throws Exception {
      Map<String, Object> body = new java.util.LinkedHashMap<>();
      body.put("name", name); body.put("online", true);
      if (nexus) {
        body.put("storage", Map.of("blobStoreName", "default", "strictContentTypeValidation", false,
            "writePolicy", "ALLOW"));
        if (type.equals("proxy")) {
          body.put("proxy", Map.of("remoteUrl", remote, "contentMaxAge", 1440, "metadataMaxAge", 1440));
          body.put("negativeCache", Map.of("enabled", true, "timeToLive", 1440));
          body.put("httpClient", Map.of("blocked", false, "autoBlock", false, "authentication",
              Map.of("type", "username", "username", username, "password", password)));
          body.put("routingRuleName", rule);
          if (nexus) body.put("routingRule", rule);
        }
      } else {
        body.put("recipe", format + "-" + type); body.put("blobStoreName", "default");
        body.put("strictContentTypeValidation", false);
        if (type.equals("hosted")) body.put("hosted", Map.of("writePolicy", "ALLOW", "versionPolicy", "MIXED", "layoutPolicy", "PERMISSIVE"));
        if (type.equals("proxy")) {
          body.put("proxy", Map.of("remoteUrl", remote, "contentMaxAgeMinutes", 1440,
              "metadataMaxAgeMinutes", 1440, "remoteUsername", username, "remotePassword", password));
          body.put("routingRuleName", rule);
        }
      }
      if (nexus && format.equals("maven2")) body.put("maven", Map.of("versionPolicy", "MIXED", "layoutPolicy", "PERMISSIVE"));
      if (type.equals("group")) body.put("group", Map.of("memberNames", members));
      check(this, "POST", nexus ? "/service/rest/v1/repositories/" + (format.equals("maven2") ? "maven" : format) + "/" + type : "/internal/repositories", body, 201);
    }
  }
}
