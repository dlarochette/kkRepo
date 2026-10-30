package com.github.klboke.kkrepo.server.kkquality;

import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Analysis;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Project;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * kkQuality ingestion API.
 *
 * <p>Analyzers in CI submit one SARIF 2.1.0 document per analysis:
 * {@code {"project":"orders","branch":"main","revision":"sha","repository":"maven-releases",
 * "sarif":{...}}}. The SARIF value may also be a JSON string. Binding a project to a repository
 * ({@code repository}) scopes read access to that repository; without it only the application-wide
 * {@code nx-kkquality-read} privilege can read the project.
 *
 * <p>Read compatibility with the SonarQube Web API lives in
 * {@link KkQualitySonarController} so ingestion stays independent of the compatibility surface.
 */
@RestController
@RequestMapping("/api/kkquality")
public class KkQualityApiController {
  private final KkQualityIngestionService ingestion;
  private final KkQualityDao dao;
  private final KkQualityAuthorizer authorizer;
  private final KkQualityProperties properties;

  public KkQualityApiController(
      KkQualityIngestionService ingestion, KkQualityDao dao, KkQualityAuthorizer authorizer,
      KkQualityProperties properties) {
    this.ingestion = ingestion;
    this.dao = dao;
    this.authorizer = authorizer;
    this.properties = properties;
  }

  @PostMapping("/analyses")
  public ResponseEntity<KkQualityIngestionService.AnalysisStatusView> ingest(
      @RequestBody(required = false) KkQualityIngestionService.IngestRequest request,
      HttpServletRequest httpRequest) {
    ensureEnabled();
    AuthenticatedSubject subject = authorizer.requireSubject(httpRequest);
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An analysis payload is required");
    }
    Long repositoryId = authorizer.findRepository(request.repository())
        .map(repository -> repository.id())
        .orElse(null);
    if (repositoryId == null) {
      // A project is bound once: honor an existing binding even when the caller omits it.
      repositoryId = dao.findProjectByKey(request.project())
          .map(Project::repositoryId)
          .orElse(null);
    }
    authorizer.requireIngest(subject, repositoryId);
    return ResponseEntity.ok(ingestion.ingest(request));
  }

  @GetMapping("/analyses/{analysisId}")
  public KkQualityIngestionService.AnalysisStatusView status(
      @PathVariable("analysisId") String analysisId, HttpServletRequest request) {
    ensureEnabled();
    AuthenticatedSubject subject = authorizer.requireSubject(request);
    Analysis analysis = dao.findAnalysisByExternalId(analysisId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Analysis " + analysisId + " was not found"));
    Project project = dao.findProject(analysis.projectId())
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Analysis " + analysisId + " was not found"));
    authorizer.requireProjectRead(subject, project);
    return view(analysis, project);
  }

  @GetMapping("/analyses")
  public Map<String, Object> list(
      @RequestParam(name = "project") String projectKey,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "pageSize", required = false) Integer pageSize,
      HttpServletRequest request) {
    ensureEnabled();
    AuthenticatedSubject subject = authorizer.requireSubject(request);
    Project project = dao.findProjectByKey(projectKey)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Project " + projectKey + " was not found"));
    authorizer.requireProjectRead(subject, project);
    int size = pageSize == null || pageSize < 1 ? 50 : Math.min(pageSize, 500);
    int index = page == null || page < 1 ? 1 : page;
    List<KkQualityIngestionService.AnalysisStatusView> analyses = new ArrayList<>();
    for (Analysis analysis : dao.listAnalyses(project.id(), size, (index - 1) * size)) {
      analyses.add(view(analysis, project));
    }
    return Map.of(
        "total", dao.countAnalyses(project.id()),
        "page", index,
        "pageSize", size,
        "analyses", analyses);
  }

  private KkQualityIngestionService.AnalysisStatusView view(Analysis analysis, Project project) {
    String branch = analysis.branchId() == null
        ? "main"
        : dao.listBranches(project.id()).stream()
            .filter(candidate -> candidate.id() == analysis.branchId())
            .map(KkQualityDao.Branch::name)
            .findFirst()
            .orElse("main");
    return new KkQualityIngestionService.AnalysisStatusView(
        analysis.analysisId(), project.key(), branch, analysis.revision(), analysis.status(),
        analysis.issueCount(), analysis.newIssueCount(), analysis.createdAt(),
        analysis.completedAt(), analysis.error());
  }

  private void ensureEnabled() {
    if (!properties.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "kkQuality is disabled");
    }
  }
}
