package com.osuserverlist.bjar.handlers.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.BssMapsetEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.BeatmapRepository;

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
 * GET /api/v1/get_player_beatmapsets - the beatmap sets a player has uploaded
 * through the in-game Beatmap Submission System.
 *
 * <p>The set rows in {@code bss_mapsets} are keyed by creator id, so a rename
 * never breaks the listing. Every set carries its difficulties, taken from the
 * {@code maps} table, which is what a profile page needs in one request.
 * Inactive (deleted) sets are never listed.
 */
@Host("api.")
@Path("/api/v1/get_player_beatmapsets")
@HttpMethod("GET")
public class PlayerBeatmapsetsAPIHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Player beatmap sets",
        description = "The beatmap sets a player uploaded through the in-game submission system, "
                + "newest update first. Each set includes its difficulties.",
        tags = { "Users", "Beatmaps" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, description = "Player id (id or name required)."),
            @OpenApiParam(name = "name", type = String.class, description = "Player name (id or name required)."),
            @OpenApiParam(name = "status", type = Integer.class, description = "Optional ranked status filter (-2 graveyard, -1 WIP, 0 pending, 1 ranked, 2 approved, 3 qualified, 4 loved)."),
            @OpenApiParam(name = "offset", type = Integer.class, description = "Zero-based offset into the result set (default 0)."),
            @OpenApiParam(name = "limit", type = Integer.class, description = "Maximum results to return, 1-100 (default 50).")
        },
        responses = {
            @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.PaginatedBeatmapsets.class) }, description = "Paginated list of beatmap sets"),
            @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Player not found")
        },
        path = "/api/v1/get_player_beatmapsets",
        methods = io.javalin.openapi.HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        int offset = ApiPagination.offset(ctx);
        int limit = ApiPagination.limit(ctx);

        UserEntity user = ApiMappers.resolveUser(ctx);
        if (user == null) {
            ctx.status(404).json(ApiPagination.error("Player not found."));
            return;
        }

        ExpressionList<BssMapsetEntity> where = DB.find(BssMapsetEntity.class)
                .where()
                .eq("creatorId", user.getId())
                .eq("active", true);

        int status = ApiPagination.intParam(ctx, "status", Integer.MIN_VALUE);
        if (status != Integer.MIN_VALUE) {
            where.eq("status", status);
        }

        PagedList<BssMapsetEntity> paged = where
                .orderBy("lastUpdate desc")
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findPagedList();

        List<Map<String, Object>> results = new ArrayList<>();
        for (BssMapsetEntity mapset : paged.getList()) {
            results.add(serialize(mapset));
        }

        ctx.json(ApiPagination.envelope(offset, limit, paged.getTotalCount(), results));
    }

    /** Set metadata plus the difficulties that currently belong to it. */
    private static Map<String, Object> serialize(BssMapsetEntity mapset) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("set_id", mapset.getSetId());
        map.put("creator_id", mapset.getCreatorId());
        map.put("creator_name", mapset.getCreatorName());
        map.put("artist", mapset.getArtist());
        map.put("title", mapset.getTitle());
        map.put("subject", mapset.getSubject());
        map.put("message", mapset.getMessage());
        map.put("status", mapset.getStatus());
        map.put("revision", mapset.getRevision());
        map.put("topic_id", mapset.getTopicId());
        map.put("has_video", mapset.getHasVideo());
        map.put("filesize", mapset.getFilesize());
        map.put("filesize_novideo", mapset.getFilesizeNoVideo());
        map.put("submission_date", mapset.getSubmissionDate() == null
                ? null
                : mapset.getSubmissionDate().toString());
        map.put("last_update", mapset.getLastUpdate() == null
                ? null
                : mapset.getLastUpdate().toString());

        List<Map<String, Object>> difficulties = new ArrayList<>();
        for (BeatmapEntity beatmap : BeatmapRepository.findBySetId(mapset.getSetId())) {
            difficulties.add(ApiMappers.beatmap(beatmap));
        }
        map.put("difficulties", difficulties);

        return map;
    }
}
