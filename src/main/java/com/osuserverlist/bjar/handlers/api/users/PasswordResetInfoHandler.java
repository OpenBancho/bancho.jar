package com.osuserverlist.bjar.handlers.api.users;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.account.PasswordResetService;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/users/password/reset - whether a reset link is still good, and whose it is.
 *
 * <p>Asked by the reset page before it draws its form, so an expired link says so immediately
 * instead of after somebody has thought of a password and typed it twice. The ticket is only
 * read, never spent: reloading the page does not burn the link.
 *
 * <p>The username is included because the page has to be able to show whose account is about to
 * be changed - a link pasted into the wrong conversation should be obvious to whoever opens it.
 * Nothing else about the account is returned, and a bad token is answered with the same flat
 * "no" whether it never existed or has expired.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/users/password/reset")
@WebEngine.HttpMethod("GET")
public final class PasswordResetInfoHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Check a password reset ticket",
        description = "Answers whether a password reset ticket is still valid and which account it "
                + "belongs to. The ticket is not spent. Public: the ticket itself is the credential.",
        tags = { "Users" },
        queryParams = {
            @OpenApiParam(name = "token", description = "The ticket handed to the player.", required = true)
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.PasswordResetCheckResponse.class) },
                description = "The ticket is valid"
            ),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "No token was given"
            ),
            @OpenApiResponse(
                status = "404",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The ticket is unknown, expired or already used"
            )
        },
        path = "/api/v1/users/password/reset"
    )
    public void handle(@NotNull Context ctx) {
        String token = ctx.queryParam("token");

        if (token == null || token.isBlank()) {
            ApiAuth.badRequest(ctx, "A token is required.");
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
            // The account was deleted after the link was handed out; the ticket is worthless.
            PasswordResetService.consume(token, ticket.getUserId());
            ApiAuth.notFound(ctx, "This password reset link is no longer valid. "
                    + "Ask a member of staff for a new one.");
            return;
        }

        Map<String, Object> response = ApiAuth.success();

        response.put("user_id", user.getId());
        response.put("username", user.getName());
        response.put("expires_at", ticket.getExpiresAt());

        ctx.header("Cache-Control", "private, no-store");
        ctx.json(response);
    }
}
