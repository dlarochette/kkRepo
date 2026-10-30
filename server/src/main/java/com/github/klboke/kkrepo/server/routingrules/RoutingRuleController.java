package com.github.klboke.kkrepo.server.routingrules;

import com.github.klboke.kkrepo.server.routingrules.RoutingRuleService.Configuration;
import com.github.klboke.kkrepo.server.routingrules.RoutingRuleService.Preview;
import com.github.klboke.kkrepo.server.routingrules.RoutingRuleService.PreviewResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class RoutingRuleController {
  private final RoutingRuleService rules;
  public RoutingRuleController(RoutingRuleService rules) { this.rules = rules; }

  @GetMapping("/service/rest/v1/routing-rules")
  public List<Configuration> list() { return rules.list(); }
  @GetMapping("/service/rest/v1/routing-rules/{name}")
  public Configuration get(@PathVariable("name") String name) { return rules.get(name); }
  @PostMapping("/service/rest/v1/routing-rules")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void create(@RequestBody Configuration input) { rules.create(input); }
  @PutMapping("/service/rest/v1/routing-rules/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void update(@PathVariable("name") String name, @RequestBody Configuration input) { rules.update(name, input); }
  @DeleteMapping("/service/rest/v1/routing-rules/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable("name") String name) { rules.delete(name); }
  @GetMapping("/internal/routing-rules/{name}/repositories")
  public List<String> usedBy(@PathVariable("name") String name) { return rules.usedBy(name); }
  @PostMapping("/internal/routing-rules/preview")
  public PreviewResult preview(@RequestBody Preview input) { return rules.preview(input); }
}
