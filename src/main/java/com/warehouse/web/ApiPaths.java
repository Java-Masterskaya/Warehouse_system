package com.warehouse.web;

import java.util.regex.Pattern;

/**
 * Canonical and transitional HTTP API roots.
 */
public final class ApiPaths {

    public static final String LEGACY_API_ROOT = "/api";
    public static final String V1_API_ROOT = LEGACY_API_ROOT + "/v1";
    public static final String LEGACY_BACKFILL_ROOT = "/admin/backfill";
    public static final String V1_BACKFILL_ROOT = V1_API_ROOT + "/admin/backfill";

    private static final Pattern VERSIONED_API_PATH = Pattern.compile("^/api/v\\d+(?:/.*)?$");

    private ApiPaths() {
    }

    /**
     * Returns whether a servlet path belongs to the temporary unversioned API.
     *
     * @param path servlet request path without a context path
     * @return true for a supported legacy alias
     */
    public static boolean isLegacyPath(String path) {
        boolean legacyApi = path.equals(LEGACY_API_ROOT)
                || path.startsWith(LEGACY_API_ROOT + "/");
        boolean versionedApi = VERSIONED_API_PATH.matcher(path).matches();
        boolean legacyBackfill = path.equals(LEGACY_BACKFILL_ROOT)
                || path.startsWith(LEGACY_BACKFILL_ROOT + "/");
        return (legacyApi && !versionedApi) || legacyBackfill;
    }
}
