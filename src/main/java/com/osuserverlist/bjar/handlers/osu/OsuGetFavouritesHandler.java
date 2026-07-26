package com.osuserverlist.bjar.handlers.osu;

import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.FavouriteRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * The favourites tab of the in-game beatmap browser.
 *
 * <p>Answers with nothing but set ids, newest first; the client already knows how
 * to turn those into rows.</p>
 */
@Host("osu.")
@Path("/web/osu-getfavourites.php")
@HttpMethod("GET")
public class OsuGetFavouritesHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        Player player = OsuWebAuth.authenticate(ctx.queryParam("u"), ctx.queryParam("h"));

        if (player == null) {
            ctx.status(401).result("Invalid credentials.");
            return;
        }

        String response = FavouriteRepository.findSetIdsByUser(player.getId())
                .stream()
                .map(String::valueOf)
                .collect(Collectors.joining("\n"));

        ctx.status(200).result(response);
    }
}
