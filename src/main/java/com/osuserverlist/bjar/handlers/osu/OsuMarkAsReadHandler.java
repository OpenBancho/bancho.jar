package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.MailRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Clears the unread flag on a private conversation.
 *
 * <p>The client sends the name of whoever it was talking to as {@code channel}.
 * Public channels arrive here too, prefixed with a hash; those have no stored
 * messages, so they are simply acknowledged.</p>
 */
@Host("osu.")
@Path("/web/osu-markasread.php")
@HttpMethod("GET")
public class OsuMarkAsReadHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        Player player = OsuWebAuth.authenticate(ctx.queryParam("u"), ctx.queryParam("h"));

        if (player == null) {
            ctx.status(401).result("");
            return;
        }

        String channel = OsuWebAuth.decode(ctx.queryParam("channel"));

        if (channel == null || channel.isBlank() || channel.startsWith("#")) {
            ctx.status(200).result("");
            return;
        }

        UserEntity sender = UserRepository.findByName(channel.trim());

        if (sender == null) {
            ctx.status(200).result("");
            return;
        }

        MailRepository.markConversationAsRead(player.getId(), sender.getId());

        ctx.status(200).result("");
    }
}
