package com.github.klboke.kkrepo.server.openvsx;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Query parameters accepted by the Open VSX registry and VS Code gallery search routes. */
public record OpenVsxQuery(
    String query,
    String category,
    String targetPlatform,
    int size,
    int offset,
    String sortBy,
    String sortOrder,
    boolean includeAllVersions,
    String namespaceName,
    String extensionName,
    String extensionVersion,
    String extensionId) {

  public static OpenVsxQuery from(HttpServletRequest request) {
    return new OpenVsxQuery(
        request.getParameter("query"),
        request.getParameter("category"),
        blankToNull(request.getParameter("targetPlatform")),
        intParam(request, "size", 18, 0, 1000),
        intParam(request, "offset", 0, 0, Integer.MAX_VALUE),
        request.getParameter("sortBy"),
        request.getParameter("sortOrder"),
        booleanParam(request, "includeAllVersions"),
        request.getParameter("namespaceName"),
        request.getParameter("extensionName"),
        request.getParameter("extensionVersion"),
        request.getParameter("extensionId"));
  }

  boolean matches(String namespace, String name, String version) {
    if (extensionId != null && !extensionId.isBlank()
        && !extensionId.equalsIgnoreCase(namespace + "." + name)) {
      return false;
    }
    if (namespaceName != null && !namespaceName.isBlank()
        && !namespaceName.equalsIgnoreCase(namespace)) {
      return false;
    }
    if (extensionName != null && !extensionName.isBlank()
        && !extensionName.equalsIgnoreCase(name)) {
      return false;
    }
    return extensionVersion == null || extensionVersion.isBlank()
        || extensionVersion.equals(version);
  }

  boolean hasTextQuery() {
    return query != null && !query.isBlank();
  }

  private static int intParam(
      HttpServletRequest request, String name, int fallback, int min, int max) {
    String value = request.getParameter(name);
    if (value == null || value.isBlank()) return fallback;
    try {
      int parsed = Integer.parseInt(value);
      return Math.max(min, Math.min(max, parsed));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static boolean booleanParam(HttpServletRequest request, String name) {
    String value = request.getParameter(name);
    return "true".equalsIgnoreCase(value) || "1".equals(value);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
