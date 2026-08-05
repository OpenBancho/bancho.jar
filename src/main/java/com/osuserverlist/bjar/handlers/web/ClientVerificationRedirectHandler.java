package com.osuserverlist.bjar.handlers.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;

/**
 * Where the game client goes when a login is answered with the verification reply.
 *
 * <p>The client opens {@code osu.<domain>/client-verifications/create?ch=<client hash>} in the
 * player's browser by itself - that address is built into it, and {@code -devserver} is what
 * makes it point here instead of at ppy. Nothing is served on this subdomain, so the request is
 * bounced to the page on the website that can actually ask for a code.
 *
 * <p>The {@code ch} parameter is carried over untouched: it identifies the login that is
 * waiting, and the page has no other way of knowing which one that is. It is not a credential -
 * a code from the account's authenticator is still required - so having it in an address is
 * fine.
 */
@Host({ "osu.", "c.", "c4.", "ce." })
@Path("/client-verifications/create")
@HttpMethod({ "GET", "POST" })
public final class ClientVerificationRedirectHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) {
        String clientHash = ctx.queryParam("ch");

        String target = "https://" + App.server.enviromentConfig.getDomain() + "/verify-client";

        if (clientHash != null && !clientHash.isBlank()) {
            target += "?ch=" + URLEncoder.encode(clientHash, StandardCharsets.UTF_8);
        }

        // Found, not Moved Permanently: a permanent redirect would be cached by the browser
        // forever and could not be changed without clearing it.
        ctx.redirect(target, HttpStatus.FOUND);
    }
}
