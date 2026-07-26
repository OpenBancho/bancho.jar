package com.osuserverlist.bjar.handlers.api.me;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.api.TokenStore;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.NotificationPacket;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

import static com.osuserverlist.bjar.handlers.api.me.MeSupport.MAX_PASSWORD_LENGTH;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.MIN_PASSWORD_LENGTH;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.MIN_UNIQUE_PASSWORD_CHARS;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.clearCookies;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.confirmPassword;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.hash;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.logger;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.sessionsOf;

@Host("api.")
@Path("/api/v1/me/password")
@WebEngine.HttpMethod("POST")
public final class PasswordHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Change own password",
        description = "Changes the password and revokes every other session of the account. The current password has to be supplied again. Requires the profile scope.",
        tags = { "Me" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        requestBody =
            @OpenApiRequestBody(
                required = true,
                content = { @OpenApiContent(from = ApiDto.SelfPasswordRequest.class) }
            ),
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) },
                description = "Done"
            ),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing or invalid field"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing, expired or revoked access token"
            ),
            @OpenApiResponse(
                status = "403",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The token lacks the required scope, or the account lacks the required privilege"
            )
        },
        path = "/api/v1/me/password",
        methods = HttpMethod.POST
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken token = ApiAuth.require(ctx);
        if (token == null || !ApiAuth.requireProfile(ctx, token)) {
            return;
        }

        JsonNode body = ApiAuth.body(ctx);
        if (body == null) {
            return;
        }

        UserEntity user = confirmPassword(ctx, token, body);
        if (user == null) {
            return;
        }

        String password = ApiAuth.stringField(body, "new_password");
        if (password == null) {
            ApiAuth.badRequest(ctx, "A new_password is required.");
            return;
        }

        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            ApiAuth.badRequest(ctx, "The password must be "
                    + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH + " characters in length.");
            return;
        }

        if (password.chars().distinct().count() <= MIN_UNIQUE_PASSWORD_CHARS) {
            ApiAuth.badRequest(ctx, "The password must contain more than "
                    + MIN_UNIQUE_PASSWORD_CHARS + " unique characters.");
            return;
        }

        user.setPasswordHash(hash(password));
        UserRepository.save(user);

        // A password change ends the sessions that were opened with the old one.
        TokenStore.revokeAccess(token.getToken());
        TokenStore.revokeFamily(token.getFamilyId());
        clearCookies(ctx);

        for (Player player : sessionsOf(user.getId())) {
            player.sendPacket(new NotificationPacket("Your password was changed; please log in again."));
            App.server.playerManager.disconnect(player);
        }

        logger.info("User <{}> changed their password from <{}>", user.getId(), ctx.ip());

        Map<String, Object> response = ApiAuth.success();
        response.put("reauthenticate", true);

        ctx.json(response);
    }
}
