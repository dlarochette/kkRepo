package com.github.klboke.kkrepo.server.routingrules;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RoutingRuleErrorAdviceTest {
  @Test void blockedDownloadsRemainUncacheableAndKeepDockerErrorEnvelope() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new BlockedEndpoint())
        .setControllerAdvice(new RoutingRuleErrorAdvice()).build();
    for (String path : new String[]{"/repository/raw/private/file", "/v2/image/manifests/latest"}) {
      var response = mvc.perform(get("/context" + path).contextPath("/context")).andReturn().getResponse();
      assertEquals(403, response.getStatus());
      assertEquals("no-store", response.getHeader("Cache-Control"));
      assertTrue(response.getContentAsString().contains("Routing rules block"));
      if (path.startsWith("/v2/")) {
        assertEquals("registry/2.0", response.getHeader("Docker-Distribution-Api-Version"));
        assertTrue(response.getContentAsString().contains("\"errors\":[{"));
        assertTrue(response.getContentAsString().contains("\"code\":\"DENIED\""));
      } else {
        assertNull(response.getHeader("Docker-Distribution-Api-Version"));
        assertFalse(response.getContentAsString().contains("\"errors\""));
      }
      assertEquals(403, mvc.perform(head("/context" + path).contextPath("/context"))
          .andReturn().getResponse().getStatus());
    }
  }

  @RestController
  static class BlockedEndpoint {
    @GetMapping({"/repository/raw/private/file", "/v2/image/manifests/latest"})
    public void download() { throw new RepositoryRoutingPolicy.Blocked(); }
  }
}
