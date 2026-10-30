package com.github.klboke.kkrepo.server.routingrules;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.*;
import com.github.klboke.kkrepo.server.routingrules.RoutingRuleService.Configuration;
import java.util.List;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao.Rule;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoutingRuleControllerTest {
  @Test void collectionAndPreviewExposePersistedRulesAndMatchingExpressions() throws Exception {
    var dao = mock(RoutingRuleDao.class);
    when(dao.list()).thenReturn(List.of(new Rule(1, "private", "", "BLOCK", List.of("/private/.*"))));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new RoutingRuleController(new RoutingRuleService(dao))).build();
    var listed = mvc.perform(get("/service/rest/v1/routing-rules")).andReturn().getResponse();
    assertEquals(200, listed.getStatus());
    assertTrue(listed.getContentAsString().contains("\"matchers\":[\"/private/.*\"]"));
    var created = mvc.perform(post("/service/rest/v1/routing-rules").contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"private\",\"description\":\"\",\"mode\":\"BLOCK\",\"matchers\":[\"/private/.*\"]}"))
        .andReturn().getResponse();
    assertEquals(204, created.getStatus());
    assertEquals("", created.getContentAsString());
    verify(dao).insert("private", "", "BLOCK", List.of("/private/.*"));
    var preview = mvc.perform(post("/internal/routing-rules/preview").contentType(MediaType.APPLICATION_JSON)
        .content("{\"mode\":\"BLOCK\",\"matchers\":[\"/private/.*\",\"/internal/.*\"],\"path\":\"/private/file.jar\"}"))
        .andReturn().getResponse();
    assertEquals(200, preview.getStatus());
    assertEquals("{\"allowed\":false,\"matchingExpressions\":[\"/private/.*\"]}", preview.getContentAsString());
  }
  @Test void namedRoutesBindParametersAndReturnNexusStatuses() throws Exception {
    var service = mock(RoutingRuleService.class);
    var config = new Configuration("private", "", "BLOCK", List.of("/private/.*"));
    when(service.get("private")).thenReturn(config);
    when(service.usedBy("private")).thenReturn(List.of("raw-proxy"));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new RoutingRuleController(service)).build();
    var read = mvc.perform(get("/service/rest/v1/routing-rules/private")).andReturn().getResponse();
    assertEquals(200, read.getStatus());
    assertTrue(read.getContentAsString().contains("private"));
    var usedBy = mvc.perform(get("/internal/routing-rules/private/repositories")).andReturn().getResponse();
    assertEquals(200, usedBy.getStatus());
    assertEquals("[\"raw-proxy\"]", usedBy.getContentAsString());
    var updated = mvc.perform(put("/service/rest/v1/routing-rules/private").contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"private\",\"description\":\"\",\"mode\":\"BLOCK\",\"matchers\":[\"/private/.*\"]}"))
        .andReturn().getResponse();
    assertEquals(204, updated.getStatus());
    verify(service).update("private", config);
    assertEquals(204, mvc.perform(delete("/service/rest/v1/routing-rules/private")).andReturn().getResponse().getStatus());
    verify(service).delete("private");
  }
}
