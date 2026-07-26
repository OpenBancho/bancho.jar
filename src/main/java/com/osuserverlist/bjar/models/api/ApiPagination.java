package com.osuserverlist.bjar.models.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.javalin.http.Context;

/**
 * Shared helpers for the public v1 developer API.
 *
 * <p>Every list endpoint returns the standard paginated envelope:
 *
 * <pre>
 * {
 *   "status":  "success",
 *   "offset":  &lt;int&gt;,   // echo of the requested offset
 *   "limit":   &lt;int&gt;,   // echo of the (clamped) requested limit
 *   "count":   &lt;long&gt;,  // TOTAL matching records (not the page size)
 *   "results": [ ... ]  // the current page of results
 * }
 * </pre>
 *
 * <p>Because {@code count} is the total number of matching records, clients can
 * page through the whole result set with {@code offset}/{@code limit}.
 */
public final class ApiPagination {

    /** Default page size when the caller does not supply {@code limit}. */
    public static final int DEFAULT_LIMIT = 50;

    /** Hard upper bound on the page size a caller may request. */
    public static final int MAX_LIMIT = 100;

    /** Parse an integer query parameter, falling back to {@code fallback}. */
    public static int intParam(Context ctx, String name, int fallback) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The requested offset, clamped to be non-negative. */
    public static int offset(Context ctx) {
        return Math.max(0, intParam(ctx, "offset", 0));
    }

    /** The requested limit, clamped to [1, {@link #MAX_LIMIT}]. */
    public static int limit(Context ctx) {
        int limit = intParam(ctx, "limit", DEFAULT_LIMIT);
        if (limit < 1) {
            limit = DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** Build the standard paginated response envelope. */
    public static Map<String, Object> envelope(int offset, int limit, long count, List<?> results) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("offset", offset);
        body.put("limit", limit);
        body.put("count", count);
        body.put("results", results);
        return body;
    }

    /** Build a simple {@code { "status": <message> }} error body. */
    public static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", message);
        return body;
    }
}
