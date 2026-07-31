package com.osuserverlist.bjar.handlers.web;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;

/**
 * The game client sends players to /home/account/edit on the osu. subdomain when
 * they ask to change their avatar or profile. Nothing is served there, so the
 * request is bounced to the settings page of the website on the apex domain.
 *
 * The #avatar fragment never reaches the server - the browser keeps it and
 * re-applies it to whatever the redirect points at - so the settings page sees
 * it and can scroll straight to the picture picker.
 */
@Host({ "osu.", "c.", "c4.", "ce." })
@Path("/home/account/edit")
@HttpMethod("GET")
public final class AccountEditRedirectHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) {
        String target = "https://" + App.server.enviromentConfig.getDomain() + "/settings";

        // Found, not Moved Permanently: a permanent redirect would be cached by
        // the browser forever and could not be changed without clearing it.
        ctx.redirect(target, HttpStatus.FOUND);
    }
}
