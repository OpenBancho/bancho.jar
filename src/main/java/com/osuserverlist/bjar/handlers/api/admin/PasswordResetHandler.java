package com.osuserverlist.bjar.handlers.api.admin;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.modules.account.PasswordResetService;
import com.osuserverlist.bjar.modules.admin.AdminActions;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * POST /api/v1/admin/user/password-reset - a link a player can use to choose a new password.
 *
 * <p>There is no self service "forgot my password" on this server, and there cannot honestly be
 * one: the email address on an account is never verified, so a mail sent to it proves nothing.
 * What is left is the thing that actually happens in practice - the player asks staff, and staff
 * satisfy themselves it is really them - and this endpoint is only the last step of it: turning
 * that decision into a single use ticket.
 *
 * <p>Deliberately behind {@code DEVELOPER} rather than {@code ADMINISTRATOR}. Every other admin
 * action changes what an account may do; this one produces a string that lets its holder become
 * the account, which is a different kind of power and belongs to the smallest group.
 *
 * <p>The answer contains the token and the path to redeem it, not a full URL: the API is reached
 * under several hostnames and has no business deciding which frontend the link should point at.
 * The panel, which knows its own origin, builds the address it hands over.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/admin/user/password-reset")
@WebEngine.HttpMethod("POST")
public final class PasswordResetHandler implements Handler {

    /** Where the ticket is redeemed, so the panel does not hard code the frontend's route. */
    private static final String REDEEM_PATH = "/reset-password";

    @Override
    @OpenApi(
        summary = "Issue a password reset link",
        description = "Creates a single use password reset ticket for an account and answers with it. "
                + "Nothing about the account changes: the new password is chosen by whoever redeems "
                + "the ticket at /api/v1/users/password/reset, so staff never handle it. Issuing a "
                + "ticket invalidates any earlier one of the same account. Requires the admin scope "
                + "and the DEVELOPER privilege.",
        tags = { "Administration" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        requestBody =
            @OpenApiRequestBody(
                required = true,
                content = { @OpenApiContent(from = ApiDto.PasswordResetLinkRequest.class) }
            ),
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.PasswordResetLinkResponse.class) },
                description = "The issued ticket"
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
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "No such user"
            ),
            @OpenApiResponse(
                status = "503",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The ticket store is unavailable"
            )
        },
        path = "/api/v1/admin/user/password-reset",
        methods = HttpMethod.POST
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);

        if (session == null || !ApiAuth.requireDeveloper(ctx, session)) {
            return;
        }

        JsonNode body = ApiAuth.body(ctx);

        if (body == null) {
            return;
        }

        int userId = ApiAuth.intField(body, "user_id");

        if (userId == Integer.MIN_VALUE) {
            ApiAuth.badRequest(ctx, "A numeric user_id is required.");
            return;
        }

        // Optional, and clamped rather than rejected: a link that lives a day longer than asked
        // for is not worth failing an action over.
        int hours = ApiAuth.intField(body, "expires_in_hours");
        long ttl = hours == Integer.MIN_VALUE
                ? PasswordResetService.DEFAULT_TTL_SECONDS
                : PasswordResetService.clampTtl(hours * 3600L);

        PasswordResetService.Issued issued =
                AdminActions.issuePasswordReset(session.getUserId(), userId, ttl);

        if (issued == null) {
            // Either the account does not exist, or Redis does not. The first is the caller's
            // mistake and the second is ours, so they are told apart.
            if (!UserRepository.exists(userId)) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            ctx.status(503).json(ApiPagination
                    .error("The password reset store is unavailable; please try again."));
            return;
        }

        Map<String, Object> response = ApiAuth.success();

        response.put("user_id", userId);
        response.put("token", issued.getToken());
        response.put("path", REDEEM_PATH + "?token=" + issued.getToken());
        response.put("expires_at", issued.getExpiresAt());
        response.put("expires_in", issued.getTtlSeconds());

        // A reset link is a credential for as long as it lives; no cache may keep a copy.
        ctx.header("Cache-Control", "private, no-store");
        ctx.json(response);
    }
}
