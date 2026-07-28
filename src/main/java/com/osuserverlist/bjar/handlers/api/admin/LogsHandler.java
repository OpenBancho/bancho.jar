package com.osuserverlist.bjar.handlers.api.admin;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.LogEntity;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.LogRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/admin/logs - the whole staff history, newest first.
 *
 * <p>The per-player history answers "what was done to this account". This answers the question
 * that actually catches problems: "what has been done lately, and by whom". Moderation that
 * nobody can review is indistinguishable from moderation nobody is doing, and a queue of
 * restrictions with names attached is the cheapest audit a small server will ever get.</p>
 */
@Host({"api.", "server", ""})
@Path("/api/v1/admin/logs")
@HttpMethod("GET")
public final class LogsHandler implements Handler {

    @Override
    @OpenApi(
        summary = "List staff actions",
        description = "The staff action history, newest first, optionally narrowed to one account or one "
                + "kind of action. Requires the moderation scope and the MODERATOR privilege.",
        tags = { "Administration" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        queryParams = {
            @OpenApiParam(
                name = "user_id",
                type = Integer.class,
                description = "Only actions taken against this account."
            ),
            @OpenApiParam(
                name = "action",
                type = String.class,
                description = "One of restrict, unrestrict, silence, unsilence, wipe, supporter, "
                        + "privileges, name, country, note, rank."
            ),
            @OpenApiParam(name = "offset", type = Integer.class, description = "Zero-based offset (default 0)."),
            @OpenApiParam(name = "limit", type = Integer.class, description = "Maximum rows, 1-100 (default 50).")
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.PaginatedStaffLogs.class) },
                description = "Paginated history"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing, expired or revoked access token"
            ),
            @OpenApiResponse(
                status = "403",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The token lacks the required scope, or the account lacks the required privilege"
            )
        },
        path = "/api/v1/admin/logs"
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireModeration(ctx, session)) {
            return;
        }

        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);

        String action = ctx.queryParam("action");
        String rawUserId = ctx.queryParam("user_id");

        List<LogEntity> entries;
        long count;

        if (rawUserId != null && !rawUserId.isBlank()) {
            Integer userId;

            try {
                userId = Integer.valueOf(rawUserId.trim());
            } catch (NumberFormatException e) {
                ctx.status(400).json(ApiPagination.error("user_id must be a number."));
                return;
            }

            entries = LogRepository.findByTarget(userId, offset, limit);
            count = LogRepository.countByTarget(userId);
        } else {
            entries = LogRepository.findRecent(action, offset, limit);
            count = LogRepository.countRecent(action);
        }

        List<Map<String, Object>> results = AdminPresenter.logs(entries);

        ctx.json(ApiPagination.envelope(offset, limit, count, results));
    }
}
