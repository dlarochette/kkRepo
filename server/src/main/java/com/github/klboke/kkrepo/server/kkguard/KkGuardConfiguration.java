package com.github.klboke.kkrepo.server.kkguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.kkguard.dependencytrack.DependencyTrackClient;
import com.github.klboke.kkrepo.server.kkguard.dependencytrack.HttpDependencyTrackClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the kkGuard Dependency-Track client from operator configuration. */
@Configuration(proxyBeanMethods = false)
public class KkGuardConfiguration {
  @Bean
  DependencyTrackClient dependencyTrackClient(
      KkGuardProperties properties, ObjectMapper objectMapper) {
    return HttpDependencyTrackClient.create(properties.getDependencyTrack(), objectMapper);
  }
}
