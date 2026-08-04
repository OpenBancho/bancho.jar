package com.osuserverlist.bjar.handlers.api;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.api.ApiVisibility;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.api.ApiProfile;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.server.scheudler.RankSnapshotTask;

import io.ebean.DB;
import io.ebean.SqlRow;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_player_rank_history - where the player stood in the world
 * over the past days, for the graph on a profile page.
 *
 * <p>The positions come from the daily snapshots {@code RankSnapshotTask}
 * leaves in {@code rank_history}. Days without a snapshot - the server was
 * down, or the account had no pp yet - carry the last known position forward,
 * so a gap does not tear the line in half, and days before the very first
 * snapshot are reported as null and simply not drawn.
 *
 * <p>The last point is today's live position rather than a stored one, so the
 * graph agrees with the rank printed next to it even on a day whose snapshot
 * has not been taken yet.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/get_player_rank_history")
@WebEngine.HttpMethod("GET")
public class PlayerRankHistoryAPIHandler implements Handler {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final int DEFAULT_DAYS = 90;
    private static final int MAX_DAYS = 365;

    @Override
    @OpenApi(
        summary = "Player rank history",
        description = "Global rank per day, oldest day first. Days without a position are null.",
        tags = { "Users" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, description = "Player id (id or name required)."),
            @OpenApiParam(name = "name", type = String.class, description = "Player name (id or name required)."),
            @OpenApiParam(name = "mode", type = Integer.class, description = "Game mode (default 0)."),
            @OpenApiParam(
                name = "days",
                type = Integer.class,
                description = "How many days back to report, 1-365 (default 90)."
            )
        },
        responses = {
            @OpenApiResponse(status = "200", description = "Rank per day"),
            @OpenApiResponse(status = "404", description = "Player not found")
        },
        path = "/api/v1/get_player_rank_history",
        methods = HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        int mode = ApiPagination.intParam(ctx, "mode", 0);
        int days = ApiPagination.intParam(ctx, "days", DEFAULT_DAYS);

        if (days < 1) {
            days = DEFAULT_DAYS;
        }

        days = Math.min(days, MAX_DAYS);

        UserEntity user = ApiVisibility.resolveVisibleUser(ctx);

        if (user == null) {
            ctx.status(404).json(ApiPagination.error("Player not found."));
            return;
        }

        LocalDate first = LocalDate.now().minusDays(days - 1L);
        Map<String, Integer> snapshots = snapshots(user.getId(), mode, first);

        List<Map<String, Object>> series = new ArrayList<>();
        Integer carried = null;

        for (int i = 0; i < days; i++) {
            String day = first.plusDays(i).format(DAY);
            Integer position = snapshots.get(day);

            if (position != null) {
                carried = position;
            }

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day);
            point.put("rank", carried);

            series.add(point);
        }

        // Today is taken live: the snapshot for it may still be hours away.
        Integer today = liveRank(user, mode);

        if (today != null && !series.isEmpty()) {
            series.get(series.size() - 1).put("rank", today);

            // A server that has only just gained the graph holds no snapshots
            // at all, and an empty plot tells the player nothing. Until the
            // history builds up, the whole span is drawn flat at the position
            // held right now, so the graph at least shows where the account
            // stands.
            if (carried == null) {
                for (Map<String, Object> point : series) {
                    point.put("rank", today);
                }
            }
        }

        // Reading a profile also pins down today's position. The scheduled task
        // does this for everyone every few hours, but doing it here as well
        // means a fresh install starts collecting real points from the first
        // visit rather than from the first scheduled run.
        RankSnapshotTask.remember(user.getId(), mode, today);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("mode", mode);
        body.put("rank", today);
        body.put("days", series);

        ctx.json(body);
    }

    /**
     * The stored positions of this player, keyed by day.
     *
     * <p>A profile can be opened seconds after a restart, before the snapshot
     * task has had its first turn, so the table is created here too if it is
     * missing. Should reading still fail, the history comes back empty and the
     * page simply shows a graph with nothing in it rather than an error.
     */
    private static Map<String, Integer> snapshots(int userId, int mode, LocalDate from) {
        Map<String, Integer> found = new HashMap<>();
        List<SqlRow> rows;

        try {
            RankSnapshotTask.ensureTable();

            rows = DB.sqlQuery(
                    "SELECT DATE_FORMAT(`date`, '%Y-%m-%d') AS day, `rank` AS position"
                    + " FROM `rank_history`"
                    + " WHERE `userid` = :user AND `mode` = :mode AND `date` >= :from"
                    + " ORDER BY `date`")
                    .setParameter("user", userId)
                    .setParameter("mode", mode)
                    .setParameter("from", from)
                    .findList();
        } catch (Exception e) {
            return found;
        }

        for (SqlRow row : rows) {
            String day = row.getString("day");
            Integer position = row.getInteger("position");

            if (day != null && position != null) {
                found.put(day, position);
            }
        }

        return found;
    }

    /** Today's position, counted the same way a profile page counts it. */
    private static Integer liveRank(UserEntity user, int mode) {
        SqlRow row = DB.sqlQuery("SELECT `pp` FROM `stats` WHERE `id` = :user AND `mode` = :mode")
                .setParameter("user", user.getId())
                .setParameter("mode", mode)
                .findOne();

        if (row == null) {
            return null;
        }

        Integer pp = row.getInteger("pp");

        if (pp == null || pp <= 0) {
            return null;
        }

        return ApiProfile.globalRank(mode, pp);
    }
}
