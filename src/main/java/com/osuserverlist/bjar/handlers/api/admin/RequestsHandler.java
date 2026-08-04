package com.osuserverlist.bjar.handlers.api.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.MapRequestEntity;
import com.osuserverlist.bjar.modules.admin.AdminActions;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.LogRepository;
import com.osuserverlist.bjar.repos.MapRequestRepository;

import io.ebean.DB;
import io.ebean.PagedList;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * The nominator queue: reading pending rank requests, and answering them.
 *
 * <p>The two endpoints live together because they are two halves of one screen. Listing without
 * resolving is a read-only curiosity, and resolving without listing means a nominator has to
 * already know the map id they are ranking, which is not how anyone works through a queue.</p>
 */
public final class RequestsHandler {

    /** Statuses a nominator may set. Anything else is not a ranking decision. */
    private static final Set<Integer> ACCEPTABLE_STATUSES = Set.of(1, 2, 4);

    private RequestsHandler() {
    }

    /** GET /api/v1/admin/requests - the pending queue, oldest request first. */
    @Host({"api.", "server", ""})
    @Path("/api/v1/admin/requests")
    @WebEngine.HttpMethod("GET")
    public static final class ListRequestsHandler implements Handler {

        @Override
        @OpenApi(
            summary = "List pending rank requests",
            description = "Open beatmap rank requests joined with the beatmap they refer to and the player "
                    + "who asked, oldest first. Requires the beatmaps scope and the NOMINATOR privilege.",
            tags = { "Administration" },
            headers = {
                @OpenApiParam(
                    name = "Authorization",
                    description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
                )
            },
            queryParams = {
                @OpenApiParam(name = "offset", type = Integer.class, description = "Zero-based offset (default 0)."),
                @OpenApiParam(name = "limit", type = Integer.class, description = "Maximum rows, 1-100 (default 50).")
            },
            responses = {
                @OpenApiResponse(
                    status = "200",
                    content = { @OpenApiContent(from = ApiDto.PaginatedAdminRequests.class) },
                    description = "Paginated queue"
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
            path = "/api/v1/admin/requests"
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken session = ApiAuth.require(ctx);
            if (session == null || !ApiAuth.requireNominator(ctx, session)) {
                return;
            }

            int offset = ApiPagination.offset(ctx);
            int limit = ApiPagination.limit(ctx);

            PagedList<MapRequestEntity> paged = DB.find(MapRequestEntity.class)
                    .where()
                    .eq("active", true)
                    .orderBy("datetime asc, id asc")
                    .setFirstRow(offset)
                    .setMaxRows(limit)
                    .findPagedList();

            List<MapRequestEntity> requests = paged.getList();

            // Two batch lookups rather than two per row: a queue of fifty maps should not be
            // a hundred and one queries.
            Map<Long, BeatmapEntity> beatmaps = beatmapsFor(requests);
            Map<Integer, String> requesters = requesterNames(requests);

            List<Map<String, Object>> results = new ArrayList<>();

            for (MapRequestEntity request : requests) {
                results.add(row(request, beatmaps.get(request.getMapId().longValue()), requesters));
            }

            ctx.json(ApiPagination.envelope(offset, limit, paged.getTotalCount(), results));
        }

        private static Map<Long, BeatmapEntity> beatmapsFor(List<MapRequestEntity> requests) {
            Set<Long> ids = new HashSet<>();

            for (MapRequestEntity request : requests) {
                ids.add(request.getMapId().longValue());
            }

            Map<Long, BeatmapEntity> beatmaps = new HashMap<>();

            if (ids.isEmpty()) {
                return beatmaps;
            }

            for (BeatmapEntity beatmap : DB.find(BeatmapEntity.class).where().idIn(ids).findList()) {
                beatmaps.put(beatmap.getId(), beatmap);
            }

            return beatmaps;
        }

        private static Map<Integer, String> requesterNames(List<MapRequestEntity> requests) {
            Set<Integer> ids = new HashSet<>();

            for (MapRequestEntity request : requests) {
                ids.add(request.getPlayerId());
            }

            ids.remove(0);

            return AdminPresenter.names(ids);
        }

        /**
         * One queue row. A request whose beatmap is missing from the cache is still returned,
         * because a nominator needs to be able to clear it rather than wonder why the count
         * says four and the list shows three.
         */
        private static Map<String, Object> row(MapRequestEntity request, BeatmapEntity beatmap,
                Map<Integer, String> requesters) {
            Map<String, Object> row = new LinkedHashMap<>();

            row.put("request_id", request.getId());
            row.put("map_id", request.getMapId());
            row.put("active", request.getActive());
            row.put("requested_by_id", request.getPlayerId());
            row.put("requested_by", requesters.getOrDefault(request.getPlayerId(), "?"));
            row.put("requested_at", request.getDatetime() == null ? null : request.getDatetime().toString());

            if (beatmap == null) {
                row.put("set_id", 0);
                row.put("artist", null);
                row.put("title", null);
                row.put("version", null);
                row.put("creator", null);
                row.put("status", null);
                row.put("mode", 0);
                row.put("stars", 0);
                row.put("missing", true);

                return row;
            }

            row.put("set_id", beatmap.getSetId());
            row.put("artist", beatmap.getArtist());
            row.put("title", beatmap.getTitle());
            row.put("version", beatmap.getVersion());
            row.put("creator", beatmap.getCreator());
            row.put("status", beatmap.getStatus());
            row.put("mode", beatmap.getMode());
            row.put("stars", beatmap.getDiff());
            row.put("bpm", beatmap.getBpm());
            row.put("total_length", beatmap.getTotalLength());
            row.put("max_combo", beatmap.getMaxCombo());
            row.put("plays", beatmap.getPlays());
            row.put("missing", false);

            return row;
        }
    }

    /** POST /api/v1/admin/requests/resolve - accept or reject one request. */
    @Host({"api.", "server", ""})
    @Path("/api/v1/admin/requests/resolve")
    @WebEngine.HttpMethod("POST")
    public static final class ResolveRequestHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Accept or reject a rank request",
            description = "Accepting sets the beatmap status and closes the request; rejecting only closes it. "
                    + "Requires the beatmaps scope and the NOMINATOR privilege.",
            tags = { "Administration" },
            headers = {
                @OpenApiParam(
                    name = "Authorization",
                    description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
                )
            },
            requestBody =
                @OpenApiRequestBody(
                    required = true,
                    content = { @OpenApiContent(from = ApiDto.ResolveRequestRequest.class) }
                ),
            responses = {
                @OpenApiResponse(
                    status = "200",
                    content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) },
                    description = "Resolved"
                ),
                @OpenApiResponse(
                    status = "400",
                    content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                    description = "Missing or invalid field"
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
                ),
                @OpenApiResponse(
                    status = "404",
                    content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                    description = "No such beatmap"
                )
            },
            path = "/api/v1/admin/requests/resolve",
            methods = HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken session = ApiAuth.require(ctx);
            if (session == null || !ApiAuth.requireNominator(ctx, session)) {
                return;
            }

            JsonNode body = ApiAuth.body(ctx);
            if (body == null) {
                return;
            }

            int mapId = ApiAuth.intField(body, "map_id");
            if (mapId == Integer.MIN_VALUE) {
                ApiAuth.badRequest(ctx, "A numeric map_id is required.");
                return;
            }

            String action = ApiAuth.stringField(body, "action");
            if (action == null) {
                ApiAuth.badRequest(ctx, "An action of accept or reject is required.");
                return;
            }

            action = action.toLowerCase(Locale.ROOT);

            boolean accept = action.equals("accept");
            if (!accept && !action.equals("reject")) {
                ApiAuth.badRequest(ctx, "The action must be accept or reject.");
                return;
            }

            BeatmapEntity beatmap = BeatmapRepository.findById(mapId);
            if (beatmap == null) {
                ApiAuth.notFound(ctx, "No such beatmap.");
                return;
            }

            boolean wholeSet = ApiAuth.booleanField(body, "whole_set", false);
            int actorId = session.getUserId();

            if (accept) {
                int status = ApiAuth.intField(body, "status");

                // Ranked is the decision a nominator makes almost every time.
                if (status == Integer.MIN_VALUE) {
                    status = 1;
                }

                if (!ACCEPTABLE_STATUSES.contains(status)) {
                    ApiAuth.badRequest(ctx, "The status must be 1 (ranked), 2 (approved) or 4 (loved).");
                    return;
                }

                // Frozen, so the next beatmap sync does not quietly undo the decision.
                boolean applied = wholeSet
                        ? AdminActions.rankBeatmapSet(actorId, beatmap.getSetId(), status, true)
                        : AdminActions.rankBeatmap(actorId, mapId, status, true);

                if (!applied) {
                    ApiAuth.notFound(ctx, "No such beatmap.");
                    return;
                }
            }

            List<Integer> closed = close(beatmap, wholeSet, actorId);

            recordDecision(actorId, beatmap, accept, wholeSet, ApiAuth.stringField(body, "reason"));

            Map<String, Object> response = ApiAuth.success();

            response.put("map_id", mapId);
            response.put("resolved", closed);

            ctx.json(response);
        }

        /**
         * Closes the request for this difficulty, or for every difficulty in the set.
         *
         * <p>Whole-set resolution matters because players request the difficulty they happen to
         * be looking at. Ranking a set while leaving four sibling requests open leaves the queue
         * full of decisions that have already been made.</p>
         */
        private static List<Integer> close(BeatmapEntity beatmap, boolean wholeSet, int actorId) {
            List<Integer> closed = new ArrayList<>();

            if (!wholeSet) {
                MapRequestRepository.closeRequest(beatmap.getId(), actorId);
                closed.add(beatmap.getId().intValue());

                return closed;
            }

            for (BeatmapEntity sibling : BeatmapRepository.findBySetId(beatmap.getSetId())) {
                MapRequestRepository.closeRequest(sibling.getId(), actorId);
                closed.add(sibling.getId().intValue());
            }

            return closed;
        }

        /** Notes the decision against the requester, so the queue leaves a trail like everything else. */
        private static void recordDecision(int actorId, BeatmapEntity beatmap, boolean accept,
                boolean wholeSet, String reason) {
            String subject = beatmap.getArtist() + " - " + beatmap.getTitle()
                    + (wholeSet ? " (whole set)" : " [" + beatmap.getVersion() + "]");

            String message = (accept ? "Accepted " : "Rejected ") + subject
                    + (reason == null || reason.isBlank() ? "." : ": " + reason);

            try {
                // Targeted at the beatmap's own id so the trail hangs off the map, not a player.
                LogRepository.write(actorId, beatmap.getId().intValue(), "rank", message);
            } catch (Exception e) {
                // A missing audit line must not undo a ranking that already happened.
            }
        }
    }
}
