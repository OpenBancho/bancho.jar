package com.osuserverlist.bjar.handlers.api.admin;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.modules.admin.AdminActions;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * Silencing, unsilencing, and leaving a note on an account.
 *
 * <p>These three sit together because they are the moderator's everyday tools, and the
 * everyday tool was the one the API did not have: until now the only thing a moderator could
 * do over HTTP was take an account away entirely. A silence is the smaller answer, and a note
 * is the answer when the right action is to write down what happened and do nothing.</p>
 */
public final class SilenceHandler {

    private SilenceHandler() {
    }

    @Host({"api.", "server", ""})
    @Path("/api/v1/admin/silence")
    @WebEngine.HttpMethod("POST")
    public static final class SilenceApplyHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Silence a player",
            description = "Silences an account for a duration such as 30m, 2h or 1d and answers with the "
                    + "resulting silence_end timestamp. Requires the moderation scope and the MODERATOR "
                    + "privilege.",
            tags = { "Administration" },
            headers = {
                @OpenApiParam(
                    name = "Authorization",
                    description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
                )
            },
            requestBody =
                @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.SilenceRequest.class) }),
            responses = {
                @OpenApiResponse(
                    status = "200",
                    content = { @OpenApiContent(from = ApiDto.SilenceResponse.class) },
                    description = "Silenced"
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
                )
            },
            path = "/api/v1/admin/silence",
            methods = HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken session = ApiAuth.require(ctx);
            if (session == null || !ApiAuth.requireModeration(ctx, session)) {
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

            if (userId == session.getUserId()) {
                ApiAuth.badRequest(ctx, "You cannot silence your own account.");
                return;
            }

            long seconds = AdminActions.parseDuration(ApiAuth.stringField(body, "duration"));
            if (seconds <= 0) {
                ApiAuth.badRequest(ctx, "A duration such as 30m, 2h or 1d is required.");
                return;
            }

            long silenceEnd = AdminActions.silence(session.getUserId(), userId, seconds,
                    ApiAuth.stringField(body, "reason"));

            if (silenceEnd < 0) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            Map<String, Object> response = ApiAuth.success();
            response.put("silence_end", silenceEnd);

            ctx.json(response);
        }
    }

    @Host({"api.", "server", ""})
    @Path("/api/v1/admin/unsilence")
    @WebEngine.HttpMethod("POST")
    public static final class UnsilenceHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Unsilence a player",
            description = "Lifts a silence early. Requires the moderation scope and the MODERATOR privilege.",
            tags = { "Administration" },
            headers = {
                @OpenApiParam(
                    name = "Authorization",
                    description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
                )
            },
            requestBody =
                @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.RestrictRequest.class) }),
            responses = {
                @OpenApiResponse(
                    status = "200",
                    content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) },
                    description = "Done"
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
                )
            },
            path = "/api/v1/admin/unsilence",
            methods = HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken session = ApiAuth.require(ctx);
            if (session == null || !ApiAuth.requireModeration(ctx, session)) {
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

            if (!AdminActions.unsilence(session.getUserId(), userId, ApiAuth.stringField(body, "reason"))) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            ctx.json(ApiAuth.success());
        }
    }

    @Host({"api.", "server", ""})
    @Path("/api/v1/admin/note")
    @WebEngine.HttpMethod("POST")
    public static final class NoteHandler implements Handler {

        /** Long enough for an explanation, short enough to stay in the column. */
        private static final int MAX_NOTE_LENGTH = 2000;

        @Override
        @OpenApi(
            summary = "Leave a note on a player",
            description = "Appends a free text note to an account's staff history without changing the "
                    + "account. Requires the moderation scope and the MODERATOR privilege.",
            tags = { "Administration" },
            headers = {
                @OpenApiParam(
                    name = "Authorization",
                    description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
                )
            },
            requestBody =
                @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.NoteRequest.class) }),
            responses = {
                @OpenApiResponse(
                    status = "200",
                    content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) },
                    description = "Recorded"
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
                )
            },
            path = "/api/v1/admin/note",
            methods = HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken session = ApiAuth.require(ctx);
            if (session == null || !ApiAuth.requireModeration(ctx, session)) {
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

            String message = ApiAuth.stringField(body, "message");
            if (message == null) {
                ApiAuth.badRequest(ctx, "A message is required.");
                return;
            }

            if (message.length() > MAX_NOTE_LENGTH) {
                ApiAuth.badRequest(ctx, "The note must be at most " + MAX_NOTE_LENGTH + " characters.");
                return;
            }

            if (!AdminActions.note(session.getUserId(), userId, message.trim())) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            ctx.json(ApiAuth.success());
        }
    }
}
