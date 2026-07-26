package com.osuserverlist.bjar.handlers.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
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
 * The countries that actually appear in a ranking, with the number of players
 * behind each one.
 *
 * <p>Meant for building a country filter: listing every country of the world
 * would leave most of the entries empty, and the front end has no way of
 * knowing which ones are populated.</p>
 */
@Host("api.")
@Path("/api/v1/get_countries")
@HttpMethod("GET")
public class CountriesAPIHandler implements Handler {

    private static final String QUERY = """
            SELECT u.`country` AS country, COUNT(*) AS players
            FROM `stats` s
            JOIN `users` u ON u.`id` = s.`id`
            WHERE s.`mode` = :mode
              AND s.`plays` > 0
              AND u.`country` IS NOT NULL
              AND u.`country` <> ''
              AND u.`country` <> 'xx'
            GROUP BY u.`country`
            ORDER BY players DESC, country ASC
            """;

    @Override
    @OpenApi(
        summary = "Countries present in a ranking",
        description = "Country codes that have at least one ranked player in the given mode, most populated first.",
        tags = { "Server" },
        queryParams = {
            @OpenApiParam(name = "mode", type = Integer.class, description = "Game mode (default 0).")
        },
        responses = {
            @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.CountriesResponse.class) }, description = "Country list")
        },
        path = "/api/v1/get_countries"
    )
    public void handle(@NotNull Context ctx) {
        int mode = ApiPagination.intParam(ctx, "mode", 0);

        List<SqlRow> rows = DB.sqlQuery(QUERY)
                .setParameter("mode", mode)
                .findList();

        List<Map<String, Object>> countries = new ArrayList<>();

        for (SqlRow row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("country", row.getString("country"));
            entry.put("players", row.getInteger("players"));
            countries.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("mode", mode);
        body.put("countries", countries);

        ctx.json(body);
    }
}
