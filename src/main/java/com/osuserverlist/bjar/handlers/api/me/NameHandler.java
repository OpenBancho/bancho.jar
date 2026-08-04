package com.osuserverlist.bjar.handlers.api.me;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.account.DonorService;
import com.osuserverlist.bjar.modules.account.RegistrationService;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.packets.server.UtilServerPackets.NotificationPacket;
import com.osuserverlist.bjar.repos.LogRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.ebean.DB;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

import static com.osuserverlist.bjar.handlers.api.me.MeSupport.confirmPassword;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.logger;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.sessionsOf;

/**
 * The supporter username change.
 *
 * <p>It lives next to the other dangerous account changes rather than in the
 * chat: the current password is asked for again, and the same rules as
 * registration decide what a name may look like, so an account can never end up
 * with a name it could not have signed up with.</p>
 */
@Host({"api.", "server", ""})
@Path("/api/v1/me/name")
@WebEngine.HttpMethod("POST")
public final class NameHandler implements Handler {

    /** Written to the moderation log, so a name history exists after the fact. */
    private static final String LOG_ACTION = "name_change";

    @Override
    @OpenApi(
        summary = "Change own username",
        description = "Changes the username. Supporter only, and the current password has to be "
                + "supplied again. Live game sessions of the account are disconnected so the "
                + "client picks the new name up on its next login.",
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
                content = { @OpenApiContent(from = ApiDto.SelfEmailRequest.class) }
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
                description = "Missing or invalid name"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing token, or the current password is wrong"
            ),
            @OpenApiResponse(
                status = "403",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Not a supporter, or the token lacks the profile scope"
            )
        },
        path = "/api/v1/me/name",
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

        if (!DonorService.isDonor(user)) {
            ctx.status(403).json(ApiPagination.error("A username change is a supporter perk."));
            return;
        }

        String requested = ApiAuth.stringField(body, "name");
        if (requested == null) {
            ApiAuth.badRequest(ctx, "A name is required.");
            return;
        }

        String name = requested.trim();

        if (name.equals(user.getName())) {
            ApiAuth.badRequest(ctx, "That is already your name.");
            return;
        }

        if (!RegistrationService.USERNAME_PATTERN.matcher(name).matches()) {
            ApiAuth.badRequest(ctx, "A name is "
                    + RegistrationService.MIN_USERNAME_LENGTH + "-"
                    + RegistrationService.MAX_USERNAME_LENGTH
                    + " characters and may only contain letters, digits, '_', '-', '[', ']' "
                    + "and spaces.");
            return;
        }

        // The same oddity registration enforces: mixing both separators makes two
        // names that only differ in one of them collide as the same safe name.
        if (name.contains("_") && name.contains(" ")) {
            ApiAuth.badRequest(ctx, "A name may contain '_' or ' ', but not both.");
            return;
        }

        String safeName = RegistrationService.safeNameOf(name);

        // The safe name is what makes two names the same account-wise, so the
        // uniqueness check runs against that column rather than the display name.
        UserEntity existing = DB.find(UserEntity.class)
                .where()
                .eq("safeName", safeName)
                .findOne();

        if (existing != null && !existing.getId().equals(user.getId())) {
            ApiAuth.badRequest(ctx, "That name is already taken.");
            return;
        }

        String previous = user.getName();

        user.setName(name);
        user.setSafeName(safeName);
        UserRepository.save(user);

        LogRepository.write(user.getId(), user.getId(), LOG_ACTION,
                "Changed name from " + previous + " to " + name);

        // The client caches its own name for the whole session, so the session is
        // ended instead of left showing the old one until the player restarts.
        for (Player player : sessionsOf(user.getId())) {
            player.sendPacket(new NotificationPacket("Your name is now " + name
                    + ". Log back in to continue."));
            App.server.playerManager.disconnect(player);
        }

        logger.info("User <{}> changed their name from <{}> to <{}> from <{}>",
                user.getId(), previous, name, ctx.ip());

        ctx.json(ApiAuth.success());
    }
}
