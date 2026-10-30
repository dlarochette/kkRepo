package com.github.klboke.kkrepo.server.routingrules;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RoutingRuleDao.Rule;
import com.github.klboke.kkrepo.server.routingrules.RoutingRuleService.Configuration;
import com.github.klboke.kkrepo.server.routingrules.RoutingRuleService.Preview;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DuplicateKeyException;

class RoutingRuleServiceTest {
  private final RoutingRuleDao dao = mock(RoutingRuleDao.class);
  private final RoutingRuleService service = new RoutingRuleService(dao);
  @Test void rulesCanBeCreatedRenamedListedAndDeletedWithoutChangingIdentity() {
    var before = new Rule(7, "private", "Block private", "BLOCK", List.of("/private/.*"));
    var input = new Configuration(before.name(), before.description(), before.mode(), before.matchers());
    service.create(input);
    verify(dao).insert("private", "Block private", "BLOCK", List.of("/private/.*"));
    when(dao.list()).thenReturn(List.of(before));
    when(dao.findByName("private")).thenReturn(Optional.of(before));
    when(dao.findById(7)).thenReturn(Optional.of(before));
    when(dao.repositoriesUsing(7)).thenReturn(List.of("proxy"));
    assertEquals(List.of(input), service.list());
    assertEquals(input, service.get("private"));
    assertEquals(List.of("proxy"), service.usedBy("private"));
    assertEquals("private", service.name(7L));
    assertNull(service.name(null));
    assertNull(service.name(99L));
    when(dao.lockByName("private")).thenReturn(Optional.of(before));
    service.update("private", new Configuration("renamed", "Allow public", "ALLOW", List.of("/public/.*")));
    verify(dao).update(7, "renamed", "Allow public", "ALLOW", List.of("/public/.*"));
    when(dao.lockByName("renamed")).thenReturn(Optional.of(new Rule(7, "renamed", "Allow public", "ALLOW", List.of("/public/.*"))));
    when(dao.repositoriesUsing(7)).thenReturn(List.of());
    service.delete("renamed");
    verify(dao).delete(7);
  }
  @Test void duplicateNamesAndMissingBindingsProduceClientErrors() {
    var input = new Configuration("taken", "", "BLOCK", List.of("/.*"));
    when(dao.insert("taken", "", "BLOCK", List.of("/.*"))).thenThrow(new DuplicateKeyException("duplicate"));
    assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.create(input)).getStatusCode().value());
    when(dao.lockByName("original")).thenReturn(Optional.of(new Rule(1, "original", "", "BLOCK", List.of("/.*"))));
    doThrow(new DuplicateKeyException("duplicate")).when(dao).update(1, "taken", "", "BLOCK", List.of("/.*"));
    assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.update("original", input)).getStatusCode().value());
    assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get("missing")).getStatusCode().value());
    assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.delete("missing")).getStatusCode().value());
    assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.binding(RepositoryType.PROXY, "missing", null)).getStatusCode().value());
    assertEquals(400, assertThrows(ResponseStatusException.class,
        () -> service.create(new Configuration("empty", "", "BLOCK", List.of("")))).getStatusCode().value());
  }
  @Test void invalidInputNeverReachesPersistence() {
    for (var input : List.of(new Configuration("bad name", "", "BLOCK", List.of("/.*")),
        new Configuration("name", null, "BLOCK", List.of("/.*")),
        new Configuration("name", "", "block", List.of("/.*")),
        new Configuration("name", "", "BLOCK", List.of()),
        new Configuration("name", "", "BLOCK", List.of("[")))) {
      assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.create(input)).getStatusCode().value());
    }
    verifyNoInteractions(dao);
  }
  @Test void previewUsesServerRegexAndWholePathIncludingQueries() {
    assertFalse(service.preview(new Preview("BLOCK", List.of("/a.*", "/b"), "/abc")).allowed());
    assertTrue(service.preview(new Preview("BLOCK", List.of("abc"), "/abc")).allowed());
    assertFalse(service.preview(new Preview("ALLOW", List.of("/abc"), "/abc?x=1")).allowed());
    assertThrows(ResponseStatusException.class, () -> service.preview(new Preview("BLOCK", List.of("/.*"), "abc")));
  }
  @Test void assignedRuleCannotBeDeletedAndBindingsLockTheSameRow() {
    when(dao.lockByName("rule")).thenReturn(Optional.of(new Rule(1, "rule", "", "BLOCK", List.of("/.*"))));
    when(dao.repositoriesUsing(1)).thenReturn(List.of("proxy"));
    assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.delete("rule")).getStatusCode().value());
    verify(dao, never()).delete(1);
    assertEquals(1L, service.binding(RepositoryType.PROXY, "rule", null));
    assertThrows(ResponseStatusException.class, () -> service.binding(RepositoryType.GROUP, "rule", null));
    assertNull(service.binding(RepositoryType.PROXY, "", 1L));
    assertEquals(1L, service.binding(RepositoryType.PROXY, null, 1L));
  }
}
