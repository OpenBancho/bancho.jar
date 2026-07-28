package com.osuserverlist.bjar.handlers.api.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.ebean.DB;
import io.ebean.ExpressionList;
import io.ebean.PagedList;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/admin/players - every account on the server, for the moderation list.
 *
 * <p>Deliberately not the same query as the public player search. That one hides anything that
 * is not a clean public account, which is exactly backwards here: the accounts a moderator
 * most needs to find are the restricted and silenced ones the public list is built to omit.</p>
 *
 * <p>Sorted by last activity rather than by id, because the useful question is almost always
 * "who has been here recently" rather than "who registered first".</p>
 */
@Host({"api.", "server", ""})
@Path("/api/v1/admin/players")
@HttpMethod("GET")
public final class PlayersHandler implements Handler {

    /** Everything that counts as staff, for the staff filter. */
    private static final int STAFF_MASK = Privileges.MODERATOR.value
            | Privileges.ADMINISTRATOR.value
            | Privileges.DEVELOPER.value
            | Privileges.NOMINATOR.value;

    @Override
    @OpenApi(
        summary = "List players for moderation",
        description = "Every account, including restricted and silenced ones, newest activity first. "
                + "Requires the moderation scope and the MODERATOR privilege.",
        tags = { "Administration" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        queryParams = {
            @OpenApiParam(name = "q", type = String.class, description = "Name fragment or exact user id."),
            @OpenApiParam(
                name = "filter",
                type = String.class,
                description = "One of all, online, restricted, silenced, supporters, staff. Defaults to all."
            ),
            @OpenApiParam(name = "offset", type = Integer.class, description = "Zero-based offset (default 0)."),
            @OpenApiParam(name = "limit", type = Integer.class, description = "Maximum rows, 1-100 (default 50).")
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.PaginatedAdminPlayers.class) },
                description = "Paginated list of accounts"
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
        path = "/api/v1/admin/players"
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireModeration(ctx, session)) {
            return;
        }

        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);

        String query = ctx.queryParam("q");
        String filter = ctx.queryParam("filter");

        long now = System.currentTimeMillis() / 1000L;
        Set<Integer> online = AdminPresenter.onlineIds();

        ExpressionList<UserEntity> where = DB.find(UserEntity.class).where();

        if (query != null && !query.isBlank()) {
            String trimmed = query.trim();

            // A moderator pasting an id should not have to switch to a different box.
            Integer asId = asInt(trimmed);

            if (asId != null) {
                where.or()
                        .eq("id", asId)
                        .ilike("name", "%" + trimmed + "%")
                        .endOr();
            } else {
                where.ilike("name", "%" + trimmed + "%");
            }
        }

        if (filter != null && !filter.isBlank() && !filter.equalsIgnoreCase("all")) {
            switch (filter.toLowerCase()) {
                case "online" -> {
                    if (online.isEmpty()) {
                        // idIn of nothing is ambiguous across drivers; say "no rows" plainly.
                        where.raw("1 = 0");
                    } else {
                        where.idIn(online);
                    }
                }
                case "restricted" -> where.raw("priv & " + Privileges.UNRESTRICTED.value + " = 0");
                case "silenced" -> where.gt("silenceEnd", now);
                case "supporters" -> where.gt("donorEnd", now);
                case "staff" -> where.raw("priv & " + STAFF_MASK + " != 0");
                default -> {
                    ctx.status(400).json(ApiPagination.error("Unknown filter '" + filter + "'."));
                    return;
                }
            }
        }

        PagedList<UserEntity> paged = where
                .orderBy("latestActivity desc, id asc")
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findPagedList();

        List<Map<String, Object>> results = new ArrayList<>();

        for (UserEntity user : paged.getList()) {
            results.add(AdminPresenter.player(user, online));
        }

        ctx.json(ApiPagination.envelope(offset, limit, paged.getTotalCount(), results));
    }

    /** Parses an id, or answers {@code null} when the text is not one. */
    private static Integer asInt(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
