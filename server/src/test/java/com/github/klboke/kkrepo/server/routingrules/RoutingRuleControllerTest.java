package com.github.klboke.kkrepo.server.routingrules;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.*;
import com.github.klboke.kkrepo.server.routingrules.RoutingRuleService.Configuration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoutingRuleControllerTest {
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
