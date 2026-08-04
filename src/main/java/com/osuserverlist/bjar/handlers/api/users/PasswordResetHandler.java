package com.osuserverlist.bjar.handlers.api.users;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.account.PasswordResetService;
import com.osuserverlist.bjar.modules.account.RegistrationService;
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
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * POST /api/v1/users/password/reset - redeeming a ticket that staff handed out.
 *
 * <p>Public by necessity: somebody who has forgotten their password has no token to authenticate
 * with, which is the whole reason this path exists. The ticket is the credential, and it is a
 * single use random string that only staff with the developer privilege can mint.
 *
 * <p>The password rules are {@link RegistrationService}'s, not a second copy: an account must not
 * be able to leave here with a password the login path would refuse or the registration form
 * would never have allowed.
 *
 * <p>The ticket is spent before anything else happens, so a link cannot be redeemed twice by two
 * requests arriving together, and every live game session of the account is disconnected: if the
 * reset was needed because somebody else had the old password, leaving their session running
 * would defeat the point.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/users/password/reset")
@WebEngine.HttpMethod("POST")
public final class PasswordResetHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger("PasswordReset");

    @Override
    @OpenApi(
        summary = "Redeem a password reset ticket",
        description = "Sets a new password using a single use ticket issued by staff. The ticket is "
                + "spent, and every game session of the account is disconnected. Public: the ticket "
                + "itself is the credential, so no access token is required.",
        tags = { "Users" },
        requestBody =
            @OpenApiRequestBody(
                required = true,
                content = { @OpenApiContent(from = ApiDto.PasswordResetRequest.class) }
            ),
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) },
                description = "The password was changed"
            ),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing field, or a password the rules refuse"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The ticket is unknown, expired or already used"
            )
        },
        path = "/api/v1/users/password/reset",
        methods = HttpMethod.POST
    )
    public void handle(@NotNull Context ctx) {
        JsonNode body = ApiAuth.body(ctx);

        if (body == null) {
            return;
        }

        String token = ApiAuth.stringField(body, "token");
        String password = ApiAuth.stringField(body, "new_password");

        if (token == null) {
            ApiAuth.badRequest(ctx, "A token is required.");
            return;
        }

        if (password == null) {
            ApiAuth.badRequest(ctx, "A new_password is required.");
            return;
        }

        PasswordResetService.Ticket ticket = PasswordResetService.resolve(token);

        if (ticket == null) {
            ApiAuth.notFound(ctx, "This password reset link is no longer valid. "
                    + "Ask a member of staff for a new one.");
            return;
        }

        UserEntity user = UserRepository.findById(ticket.getUserId());

        if (user == null) {
            PasswordResetService.consume(token, ticket.getUserId());
            ApiAuth.notFound(ctx, "This password reset link is no longer valid. "
                    + "Ask a member of staff for a new one.");
            return;
        }

        // The same rules the registration form applies, so a reset cannot produce an account
        // the rest of the server would consider badly set up.
        if (password.length() < RegistrationService.MIN_PASSWORD_LENGTH
                || password.length() > RegistrationService.MAX_PASSWORD_LENGTH) {

            ApiAuth.badRequest(ctx, "The password must be "
                    + RegistrationService.MIN_PASSWORD_LENGTH + "-"
                    + RegistrationService.MAX_PASSWORD_LENGTH + " characters in length.");
            return;
        }

        if (password.chars().distinct().count() <= RegistrationService.MIN_UNIQUE_PASSWORD_CHARS) {
            ApiAuth.badRequest(ctx, "The password must contain more than "
                    + RegistrationService.MIN_UNIQUE_PASSWORD_CHARS + " unique characters.");
            return;
        }

        // Spent first: two requests racing with the same link must not both go through, and a
        // link that has already changed a password has done its job either way.
        PasswordResetService.consume(token, ticket.getUserId());

        user.setPasswordHash(RegistrationService.hashPassword(password));
        UserRepository.save(user);

        // Whoever was playing on the old password is shown the door. Access tokens already
        // handed out are left to expire on their own, as everywhere else in this API.
        for (Player player : sessionsOf(user.getId())) {
            player.sendPacket(new NotificationPacket(
                    "Your password was reset; please log in again."));
            App.server.playerManager.disconnect(player);
        }

        logger.info("User <{}> reset their password from <{}> with a ticket issued by <{}>",
                user.getId(), ctx.ip(), ticket.getIssuedBy());

        Map<String, Object> response = ApiAuth.success();
        response.put("username", user.getName());

        ctx.header("Cache-Control", "private, no-store");
        ctx.json(response);
    }

    /** Every live bancho session of an account, snapshotted because callers disconnect them. */
    private static List<Player> sessionsOf(int userId) {
        List<Player> sessions = new ArrayList<>();

        for (Player player : App.server.playerManager.getAllSessions()) {
            if (player.getId() == userId && !player.isBot()) {
                sessions.add(player);
            }
        }

        return sessions;
    }
}
