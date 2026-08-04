package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * The handshake older clients perform before they open the bancho connection.
 *
 * <p>The client uses the answer to decide which country flag and which language
 * pack to preselect, and it refuses to continue if the route is missing. A bare
 * country code is all it wants; anything else it ignores.</p>
 */
@Host("osu.")
@Path("/web/bancho_connect.php")
@HttpMethod({ "GET", "POST" })
public class BanchoConnectHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(BanchoConnectHandler.class);

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String username = param(ctx, "u");
        String passwordHash = param(ctx, "h");
        String version = param(ctx, "v");

        // Some builds only ping the route to see whether the server is alive.
        if (username == null || passwordHash == null) {
            ctx.status(200).result("");
            return;
        }

        Player player = OsuWebAuth.authenticate(username, passwordHash);

        String country = null;

        if (player != null && player.getEntity() != null) {
            country = player.getEntity().getCountry();
        }

        if (country == null) {
            UserEntity entity = UserRepository.findByName(OsuWebAuth.decode(username));

            if (entity != null) {
                country = entity.getCountry();
            }
        }

        logger.debug("bancho_connect from {} (osu! {}) answered with country {}",
                username, version == null ? "unknown" : version, country);

        ctx.contentType("text/plain");
        ctx.status(200).result(country == null ? "" : country.toLowerCase());
    }

    private static String param(Context ctx, String name) {
        String value = ctx.queryParam(name);

        if (value == null || value.isBlank()) {
            value = ctx.formParam(name);
        }

        return value == null || value.isBlank() ? null : value;
    }
}
