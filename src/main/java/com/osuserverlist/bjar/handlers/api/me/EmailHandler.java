package com.osuserverlist.bjar.handlers.api.me;

import java.util.Locale;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.UserEntity;
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

import static com.osuserverlist.bjar.handlers.api.me.MeSupport.EMAIL_PATTERN;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.MAX_EMAIL_LENGTH;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.confirmPassword;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.logger;

@Host({"api.", "server", ""})
@Path("/api/v1/me/email")
@WebEngine.HttpMethod("POST")
public final class EmailHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Change own email",
        description = "Changes the email address. The current password has to be supplied again, so a stolen access token is not enough. Requires the profile scope.",
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
        path = "/api/v1/me/email",
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

        String email = ApiAuth.stringField(body, "email");
        if (email == null) {
            ApiAuth.badRequest(ctx, "An email is required.");
            return;
        }

        String trimmed = email.trim().toLowerCase(Locale.ROOT);

        if (trimmed.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(trimmed).matches()) {
            ApiAuth.badRequest(ctx, "That email is not valid.");
            return;
        }

        UserEntity existing = UserRepository.findByEmail(trimmed);

        if (existing != null && !existing.getId().equals(user.getId())) {
            ApiAuth.badRequest(ctx, "That email is already in use.");
            return;
        }

        user.setEmail(trimmed);
        UserRepository.save(user);

        logger.info("User <{}> changed their email from <{}>", user.getId(), ctx.ip());

        ctx.json(ApiAuth.success());
    }
}
