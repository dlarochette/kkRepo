package com.github.klboke.kkrepo.server.routingrules;

import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RoutingRuleErrorAdvice {
  @ExceptionHandler(RepositoryRoutingPolicy.Blocked.class)
  public ResponseEntity<?> blocked(RepositoryRoutingPolicy.Blocked failure, HttpServletRequest request) {
    var response = ResponseEntity.status(403).header("Cache-Control", "no-store");
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (path.startsWith("/v2/")) {
      return response.header(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION)
          .body(Map.of("errors", List.of(Map.of("code", "DENIED", "message", failure.getReason()))));
    }
    return response.body(Map.of("message", failure.getReason()));
  }
}
