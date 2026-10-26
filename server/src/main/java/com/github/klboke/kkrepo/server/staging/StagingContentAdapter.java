package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/**
 * Format-specific staged-content boundary.
 *
 * <p>The generic staging service owns sessions, isolation, preflight, promotion transactions and
 * blob ownership. A format module contributes an adapter that understands how to validate an
 * upload, where its logical asset path lives, and how its live content is removed. This keeps the
 * core staging branch independent from Arch, Open VSX or any future format implementation.
 */
public interface StagingContentAdapter {
  boolean supports(RepositoryFormat format);

  /** Returns the concrete asset path, or empty when this request is not a staged asset path. */
  Optional<String> normalizePath(String rawPath);

  /** True when the format accepts a staged publication body at this request path (for example POST). */
  default boolean supportsPublication(String rawPath) {
    return false;
  }

  StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip);

  /** Removes live content during promotion; returns false when the path was already absent. */
  boolean delete(RepositoryRuntime runtime, String path);

  /**
   * Format-specific preflight rule. Return a human-readable conflict, or {@code null} to allow the
   * generic checks to decide.
   */
  default String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    return null;
  }

  /**
   * Uploaded staged blob plus the final logical path. {@code logicalPath} may differ from the
   * request path for publication endpoints that derive their storage path from the payload.
   */
  record StagedUpload(
      AssetBlobRecord blob,
      String logicalPath,
      String contentType,
      Map<String, Object> attributes) {
  }
}
