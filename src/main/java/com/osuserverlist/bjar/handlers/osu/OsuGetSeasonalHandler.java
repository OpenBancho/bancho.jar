package com.osuserverlist.bjar.handlers.osu;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.google.gson.Gson;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

@Host("osu.")
@Path("/web/osu-getseasonal.php")
@HttpMethod("GET")
public class OsuGetSeasonalHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        ctx.contentType("application/json");

        List<String> seasonalBgs = App.server.config.getSeasonalBackgrounds();

        if (seasonalBgs == null || seasonalBgs.isEmpty()) {
            ctx.result("[]"); // Return empty JSON array if no seasonal_bgs
        } else {
            Gson gson = new Gson();
            String json = gson.toJson(seasonalBgs);
            ctx.result(json);
        }
    }

}
