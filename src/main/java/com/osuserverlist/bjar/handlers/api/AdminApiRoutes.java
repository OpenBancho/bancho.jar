package com.osuserverlist.bjar.handlers.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.admin.AdminActions;
import com.osuserverlist.bjar.modules.admin.AdminPrivileges;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * Authenticated moderation endpoints for the v1 API.
 *
 * <p>Every endpoint resolves the caller from the session cookie, re-checks their privileges
 * against the database and only then touches anything. The acting administrator's id is
 * passed down to {@link AdminActions} so each change is attributable in the log.</p>
 *
 * <p>Each endpoint asks for the one capability it actually needs: moderation for anything
 * aimed at players, nomination for anything aimed at beatmaps, administration for handing out
 * rights or destroying data. None of them implies another, and nobody may target their own
 * account with the destructive ones.</p>
 */
public final class AdminApiRoutes {

    private AdminApiRoutes() {
    }

    // ------------------------------------------------------------------
    // restrict / unrestrict
    // ------------------------------------------------------------------

    /** POST /api/v1/admin/restrict */
    @Host("api.")
    @Path("/api/v1/admin/restrict")
    @HttpMethod("POST")
    public static class RestrictHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Restrict a player",
            description = "Restricts an account and kicks it offline. Requires the moderation scope and the MODERATOR privilege.",
            tags = { "Administration" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.RestrictRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Done"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege"),
                @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "No such user")
            },
            path = "/api/v1/admin/restrict",
            methods = io.javalin.openapi.HttpMethod.POST
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
                ApiAuth.badRequest(ctx, "You cannot restrict your own account.");
                return;
            }

            String reason = ApiAuth.stringField(body, "reason");

            if (!AdminActions.restrict(session.getUserId(), userId, reason)) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            ctx.json(ApiAuth.success());
        }
    }

    /** POST /api/v1/admin/unrestrict */
    @Host("api.")
    @Path("/api/v1/admin/unrestrict")
    @HttpMethod("POST")
    public static class UnrestrictHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Unrestrict a player",
            description = "Lifts a restriction. Requires the moderation scope and the MODERATOR privilege.",
            tags = { "Administration" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.RestrictRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Done"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege"),
                @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "No such user")
            },
            path = "/api/v1/admin/unrestrict",
            methods = io.javalin.openapi.HttpMethod.POST
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

            String reason = ApiAuth.stringField(body, "reason");

            if (!AdminActions.unrestrict(session.getUserId(), userId, reason)) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            ctx.json(ApiAuth.success());
        }
    }

    // ------------------------------------------------------------------
    // wipe
    // ------------------------------------------------------------------

    /** POST /api/v1/admin/wipe */
    @Host("api.")
    @Path("/api/v1/admin/wipe")
    @HttpMethod("POST")
    public static class WipeHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Wipe a player",
            description = "Deletes every score and resets the stats of one game mode. Requires the admin scope and the ADMINISTRATOR privilege.",
            tags = { "Administration" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.WipeRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Done"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege"),
                @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "No such user")
            },
            path = "/api/v1/admin/wipe",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken session = ApiAuth.require(ctx);
            if (session == null || !ApiAuth.requireAdmin(ctx, session)) {
                return;
            }

            JsonNode body = ApiAuth.body(ctx);
            if (body == null) {
                return;
            }

            int userId = ApiAuth.intField(body, "user_id");
            int mode = ApiAuth.intField(body, "mode");

            if (userId == Integer.MIN_VALUE) {
                ApiAuth.badRequest(ctx, "A numeric user_id is required.");
                return;
            }

            if (mode == Integer.MIN_VALUE || mode < 0 || mode >= AdminActions.MODE_COUNT) {
                ApiAuth.badRequest(ctx, "A mode between 0 and " + (AdminActions.MODE_COUNT - 1) + " is required.");
                return;
            }

            if (userId == session.getUserId()) {
                ApiAuth.badRequest(ctx, "You cannot wipe your own account.");
                return;
            }

            if (!AdminActions.wipe(session.getUserId(), userId, mode)) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            ctx.json(ApiAuth.success());
        }
    }

    // ------------------------------------------------------------------
    // broadcast
    // ------------------------------------------------------------------

    /** POST /api/v1/admin/alert */
    @Host("api.")
    @Path("/api/v1/admin/alert")
    @HttpMethod("POST")
    public static class AlertHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Alert everyone online",
            description = "Sends a notification to every online player and answers with the number of recipients. Requires the moderation scope and the MODERATOR privilege.",
            tags = { "Administration" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.AlertRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.AlertResponse.class) }, description = "Delivered"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege")
            },
            path = "/api/v1/admin/alert",
            methods = io.javalin.openapi.HttpMethod.POST
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

            String message = ApiAuth.stringField(body, "message");
            if (message == null) {
                ApiAuth.badRequest(ctx, "A message is required.");
                return;
            }

            int delivered = AdminActions.alertAll(session.getUserId(), message);

            Map<String, Object> response = ApiAuth.success();
            response.put("delivered", delivered);

            ctx.json(response);
        }
    }

    // ------------------------------------------------------------------
    // donator
    // ------------------------------------------------------------------

    /** POST /api/v1/admin/donator */
    @Host("api.")
    @Path("/api/v1/admin/donator")
    @HttpMethod("POST")
    public static class DonatorHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Grant or remove supporter",
            description = "Sets the supporter period of an account and answers with the resulting donor_end timestamp. Requires the admin scope and the ADMINISTRATOR privilege.",
            tags = { "Administration" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.DonatorRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.DonatorResponse.class) }, description = "Updated"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege"),
                @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "No such user")
            },
            path = "/api/v1/admin/donator",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken session = ApiAuth.require(ctx);
            if (session == null || !ApiAuth.requireAdmin(ctx, session)) {
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

            long seconds = AdminActions.parseDuration(ApiAuth.stringField(body, "duration"));
            if (seconds <= 0) {
                ApiAuth.badRequest(ctx, "A duration such as 30d, 2w or 12h is required.");
                return;
            }

            long donorEnd = AdminActions.giveDonator(session.getUserId(), userId, seconds);
            if (donorEnd < 0) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            Map<String, Object> response = ApiAuth.success();
            response.put("donor_end", donorEnd);

            ctx.json(response);
        }
    }

    // ------------------------------------------------------------------
    // privileges
    // ------------------------------------------------------------------

    /** POST /api/v1/admin/privileges/add */
    @Host("api.")
    @Path("/api/v1/admin/privileges/add")
    @HttpMethod("POST")
    public static class AddPrivilegesHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Add privileges",
            description = "Adds privileges by name and answers with the resulting bitmask. Requires the admin scope and the ADMINISTRATOR privilege.",
            tags = { "Administration" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.PrivilegesRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.PrivilegesResponse.class) }, description = "Updated"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege"),
                @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "No such user")
            },
            path = "/api/v1/admin/privileges/add",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            changePrivileges(ctx, true);
        }
    }

    /** POST /api/v1/admin/privileges/remove */
    @Host("api.")
    @Path("/api/v1/admin/privileges/remove")
    @HttpMethod("POST")
    public static class RemovePrivilegesHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Remove privileges",
            description = "Removes privileges by name and answers with the resulting bitmask. Requires the admin scope and the ADMINISTRATOR privilege.",
            tags = { "Administration" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.PrivilegesRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.PrivilegesResponse.class) }, description = "Updated"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege"),
                @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "No such user")
            },
            path = "/api/v1/admin/privileges/remove",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            changePrivileges(ctx, false);
        }
    }

    private static void changePrivileges(Context ctx, boolean add) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireAdmin(ctx, session)) {
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
            ApiAuth.badRequest(ctx, "You cannot change your own privileges.");
            return;
        }

        JsonNode privsNode = body.get("privs");
        if (privsNode == null || !privsNode.isArray() || privsNode.isEmpty()) {
            ApiAuth.badRequest(ctx, "A non-empty privs array is required.");
            return;
        }

        List<Privileges> privileges = new ArrayList<>();

        for (JsonNode entry : privsNode) {
            Privileges resolved = AdminPrivileges.resolve(entry.asText());

            if (resolved == null) {
                ApiAuth.badRequest(ctx, "Unknown privilege: " + entry.asText());
                return;
            }

            privileges.add(resolved);
        }

        int result = AdminActions.changePrivileges(session.getUserId(), userId, privileges, add);
        if (result < 0) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        Map<String, Object> response = ApiAuth.success();
        response.put("priv", result);

        ctx.json(response);
    }

    // ------------------------------------------------------------------
    // beatmap status
    // ------------------------------------------------------------------

    /** POST /api/v1/admin/beatmap/status */
    @Host("api.")
    @Path("/api/v1/admin/beatmap/status")
    @HttpMethod("POST")
    public static class BeatmapStatusHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Set a beatmap status",
            description = "Ranks, unranks or loves a beatmap. Requires the beatmaps scope and the NOMINATOR privilege; moderators do not get this by default.",
            tags = { "Beatmaps" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.BeatmapStatusRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Done"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege"),
                @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "No such beatmap")
            },
            path = "/api/v1/admin/beatmap/status",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken session = ApiAuth.require(ctx);
            if (session == null || !ApiAuth.requireNominator(ctx, session)) {
                return;
            }

            JsonNode body = ApiAuth.body(ctx);
            if (body == null) {
                return;
            }

            int beatmapId = ApiAuth.intField(body, "beatmap_id");
            int status = ApiAuth.intField(body, "status");

            if (beatmapId == Integer.MIN_VALUE) {
                ApiAuth.badRequest(ctx, "A numeric beatmap_id is required.");
                return;
            }

            if (status == Integer.MIN_VALUE) {
                ApiAuth.badRequest(ctx, "A numeric status is required.");
                return;
            }

            boolean frozen = ApiAuth.booleanField(body, "frozen", true);

            if (!AdminActions.rankBeatmap(session.getUserId(), beatmapId, status, frozen)) {
                ApiAuth.notFound(ctx, "No such beatmap.");
                return;
            }

            ctx.json(ApiAuth.success());
        }
    }

    // ------------------------------------------------------------------
    // profile
    // ------------------------------------------------------------------

    /** POST /api/v1/admin/user/country */
    @Host("api.")
    @Path("/api/v1/admin/user/country")
    @HttpMethod("POST")
    public static class CountryHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Change a country",
            description = "Overrides the country of an account. Requires the moderation scope and the MODERATOR privilege.",
            tags = { "Administration" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.CountryRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Done"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege"),
                @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "No such user")
            },
            path = "/api/v1/admin/user/country",
            methods = io.javalin.openapi.HttpMethod.POST
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
            String country = ApiAuth.stringField(body, "country");

            if (userId == Integer.MIN_VALUE) {
                ApiAuth.badRequest(ctx, "A numeric user_id is required.");
                return;
            }

            if (country == null || country.trim().length() != 2) {
                ApiAuth.badRequest(ctx, "A two letter country code is required.");
                return;
            }

            if (!AdminActions.changeCountry(session.getUserId(), userId, country)) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            ctx.json(ApiAuth.success());
        }
    }

    /** POST /api/v1/admin/user/name */
    @Host("api.")
    @Path("/api/v1/admin/user/name")
    @HttpMethod("POST")
    public static class NameHandler implements Handler {

        /** Matches the in-game registration limits. */
        private static final int MIN_NAME_LENGTH = 2;
        private static final int MAX_NAME_LENGTH = 15;

        @Override
        @OpenApi(
            summary = "Rename a player",
            description = "Renames an account. Requires the admin scope and the ADMINISTRATOR privilege.",
            tags = { "Administration" },
            headers = { @OpenApiParam(name = "Authorization", description = "Bearer access token. May be omitted when the bjar_access cookie is sent.") },
            requestBody = @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.NameRequest.class) }),
            responses = {
                @OpenApiResponse(status = "200", content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) }, description = "Done"),
                @OpenApiResponse(status = "400", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing or invalid field"),
                @OpenApiResponse(status = "401", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "Missing, expired or revoked access token"),
                @OpenApiResponse(status = "403", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "The token lacks the required scope, or the account lacks the required privilege"),
                @OpenApiResponse(status = "404", content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) }, description = "No such user")
            },
            path = "/api/v1/admin/user/name",
            methods = io.javalin.openapi.HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            OAuthToken session = ApiAuth.require(ctx);
            if (session == null || !ApiAuth.requireAdmin(ctx, session)) {
                return;
            }

            JsonNode body = ApiAuth.body(ctx);
            if (body == null) {
                return;
            }

            int userId = ApiAuth.intField(body, "user_id");
            String name = ApiAuth.stringField(body, "name");

            if (userId == Integer.MIN_VALUE) {
                ApiAuth.badRequest(ctx, "A numeric user_id is required.");
                return;
            }

            if (name == null) {
                ApiAuth.badRequest(ctx, "A name is required.");
                return;
            }

            String trimmed = name.trim();

            if (trimmed.length() < MIN_NAME_LENGTH || trimmed.length() > MAX_NAME_LENGTH) {
                ApiAuth.badRequest(ctx, "The name must be "
                        + MIN_NAME_LENGTH + "-" + MAX_NAME_LENGTH + " characters in length.");
                return;
            }

            if (!AdminActions.changeName(session.getUserId(), userId, trimmed)) {
                ApiAuth.notFound(ctx, "No such user.");
                return;
            }

            ctx.json(ApiAuth.success());
        }
    }
}
