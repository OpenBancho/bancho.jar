package com.osuserverlist.bjar.handlers.api;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.ebean.DB;
import io.ebean.SqlRow;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/get_player_playcounts - plays per month, for the graph on a
 * profile page.
 *
 * <p>Counted from the scores themselves, so no history table is needed. Months
 * without a single play are returned as zero, which keeps the graph evenly
 * spaced on the client.
 */
@Host("api.")
@Path("/api/v1/get_player_playcounts")
@HttpMethod("GET")
public class PlayerPlaycountsAPIHandler implements Handler {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final int DEFAULT_MONTHS = 12;
    private static final int MAX_MONTHS = 36;

    @Override
    @OpenApi(
        summary = "Player playcounts",
        description = "How many scores the player submitted per month, oldest month first.",
        tags = { "Users" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, description = "Player id (id or name required)."),
            @OpenApiParam(name = "name", type = String.class, description = "Player name (id or name required)."),
            @OpenApiParam(name = "mode", type = Integer.class, description = "Game mode (default 0)."),
            @OpenApiParam(name = "months", type = Integer.class, description = "How many months back to report, 1-36 (default 12).")
        },
        responses = {
            @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.PlaycountsResponse.class) }, description = "Plays per month"),
            @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Player not found")
        },
        path = "/api/v1/get_player_playcounts",
        methods = io.javalin.openapi.HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        int mode = ApiPagination.intParam(ctx, "mode", 0);
        int months = ApiPagination.intParam(ctx, "months", DEFAULT_MONTHS);

        if (months < 1) {
            months = DEFAULT_MONTHS;
        }

        months = Math.min(months, MAX_MONTHS);

        UserEntity user = ApiMappers.resolveUser(ctx);

        if (user == null) {
            ctx.status(404).json(ApiPagination.error("Player not found."));
            return;
        }

        LocalDate first = LocalDate.now().withDayOfMonth(1).minusMonths(months - 1L);

        List<SqlRow> rows = DB.sqlQuery(
                "SELECT DATE_FORMAT(`play_time`, '%Y-%m') AS month, COUNT(*) AS plays"
                + " FROM `scores`"
                + " WHERE `userid` = :user AND `mode` = :mode AND `play_time` >= :from"
                + " GROUP BY month")
                .setParameter("user", user.getId())
                .setParameter("mode", mode)
                .setParameter("from", first.atStartOfDay())
                .findList();

        Map<String, Integer> counted = new HashMap<>();

        for (SqlRow row : rows) {
            String month = row.getString("month");
            Integer plays = row.getInteger("plays");

            if (month != null) {
                counted.put(month, plays == null ? 0 : plays);
            }
        }

        List<Map<String, Object>> series = new ArrayList<>();

        for (int i = 0; i < months; i++) {
            String month = first.plusMonths(i).format(MONTH);

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("month", month);
            point.put("plays", counted.getOrDefault(month, 0));

            series.add(point);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("mode", mode);
        body.put("months", series);

        ctx.json(body);
    }
}
