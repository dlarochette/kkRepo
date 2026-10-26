package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Staged PyPI uploads.
 *
 * <p>PyPI publishes through multipart POST at the repository root. The request-aware hook buffers
 * the package, derives its canonical package path, and promotion replays {@code uploadAsset}, which
 * rebuilds the simple/root indexes in the same transaction.
 */
@Component
final class PypiStagingContentAdapter implements StagingContentAdapter {
  private final RawHostedService hosted;
  private final PypiHostedService pypiHosted;

  PypiStagingContentAdapter(RawHostedService hosted, PypiHostedService pypiHosted) {
    this.hosted = hosted;
    this.pypiHosted = pypiHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.PYPI;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    return path.isEmpty() ? Optional.empty() : Optional.of(path);
  }

  @Override
  public boolean supportsPublication(String rawPath) {
    return rawPath == null || rawPath.isBlank() || "/".equals(rawPath.trim());
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    throw new PypiExceptions.BadRequestException(
        "PyPI staging requires a multipart file_upload POST at the repository root");
  }

  @Override
  public Optional<StagedUpload> stageRequest(
      RepositoryRuntime runtime,
      String requestedPath,
      HttpServletRequest request,
      String actor,
      String ip) {
    if (!multipart(request) || !supportsPublication(requestedPath)) {
      return Optional.empty();
    }
    Part signature;
    Part content;
    try {
      signature = request.getPart("gpg_signature");
      content = request.getPart("content");
    } catch (Exception error) {
      throw new PypiExceptions.BadRequestException("Unable to read PyPI multipart upload: " + error.getMessage());
    }
    if (signature != null && signature.getSize() > 0) {
      throw new PypiExceptions.BadRequestException(
          "Detached PyPI signatures are not supported in staged uploads");
    }
    if (content == null || content.getSize() == 0) {
      throw new PypiExceptions.BadRequestException(
          "PyPI upload requires multipart file field 'content'");
    }
    String filename = content.getSubmittedFileName();
    if (filename == null || filename.isBlank()) {
      throw new PypiExceptions.BadRequestException("PyPI upload is missing the content filename");
    }
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "pypi-staging-", ".upload");
      try (InputStream input = content.getInputStream()) {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      PypiHostedService.UploadPlan plan = pypiHosted.planUpload(
          temp, filename, content.getContentType());
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, plan.packagePath(), temp, plan.contentType(), Map.of(), actor, ip);
      return Optional.of(new StagedUpload(
          blob, plan.packagePath(), plan.contentType(),
          Map.of("pypiOriginalFilename", plan.filename())));
    } catch (IOException error) {
      throw new PypiExceptions.BadRequestException("Unable to read PyPI upload: " + error.getMessage());
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    throw new MavenExceptions.MethodNotAllowed("PyPI staging does not support deletions");
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if (item != null && "DELETE".equals(item.action())) {
      return "PyPI staging does not support deletions";
    }
    if (live != null) {
      return "PyPI asset already exists: " + item.logicalPath();
    }
    return null;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    String filename = item.logicalPath();
    int slash = filename.lastIndexOf('/');
    if (slash >= 0) {
      filename = filename.substring(slash + 1);
    }
    Path file = source.materialize(".upload");
    try {
      pypiHosted.uploadAsset(
          runtime,
          new StagedMultipartFile(filename, item.contentType(), file),
          actor,
          ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new PypiExceptions.BadRequestException("Unable to replay staged PyPI upload: " + error.getMessage());
    } finally {
      TempBlobFiles.deleteQuietly(file);
    }
  }

  private static boolean multipart(HttpServletRequest request) {
    String contentType = request.getContentType();
    return contentType != null
        && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
  }

  private record StagedMultipartFile(String filename, String contentType, Path file)
      implements org.springframework.web.multipart.MultipartFile {
    @Override
    public String getName() {
      return "asset";
    }

    @Override
    public String getOriginalFilename() {
      return filename;
    }

    @Override
    public String getContentType() {
      return contentType;
    }

    @Override
    public boolean isEmpty() {
      try {
        return Files.size(file) == 0;
      } catch (IOException error) {
        return true;
      }
    }

    @Override
    public long getSize() {
      try {
        return Files.size(file);
      } catch (IOException error) {
        return 0;
      }
    }

    @Override
    public byte[] getBytes() throws IOException {
      return Files.readAllBytes(file);
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return Files.newInputStream(file);
    }

    @Override
    public void transferTo(File dest) throws IOException {
      Files.copy(file, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
  }

}
