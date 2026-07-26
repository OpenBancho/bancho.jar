package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.FavouriteRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Starring a set from inside the game.
 *
 * <p>The two possible answers are plain sentences rather than status codes: the
 * client shows whatever text comes back as a notification.</p>
 */
@Host("osu.")
@Path("/web/osu-addfavourite.php")
@HttpMethod("GET")
public class OsuAddFavouriteHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        Player player = OsuWebAuth.authenticate(ctx.queryParam("u"), ctx.queryParam("h"));

        if (player == null) {
            ctx.status(401).result("Invalid credentials.");
            return;
        }

        Integer setId = ctx.queryParamAsClass("a", Integer.class).getOrNull();

        if (setId == null || setId <= 0) {
            ctx.status(400).result("Invalid beatmap set.");
            return;
        }

        boolean added = FavouriteRepository.add(player.getId(), setId);

        if (!added) {
            ctx.status(200).result("You've already favourited this beatmap!");
            return;
        }

        ctx.status(200).result("Added favourite!");
    }
}
