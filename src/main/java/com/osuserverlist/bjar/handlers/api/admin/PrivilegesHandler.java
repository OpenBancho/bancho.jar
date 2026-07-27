package com.osuserverlist.bjar.handlers.api.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.admin.AdminActions;
import com.osuserverlist.bjar.modules.admin.AdminPrivileges;
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

public final class PrivilegesHandler {

    @Host({"api.", "server"})
    @Path("/api/v1/admin/privileges/add")
    @WebEngine.HttpMethod("POST")
    public static class AddPrivilegesHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Add privileges",
            description = "Adds privileges by name and answers with the resulting bitmask. Requires the admin scope and the ADMINISTRATOR privilege.",
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
                    content = { @OpenApiContent(from = ApiDto.PrivilegesRequest.class) }
                ),
            responses = {
                @OpenApiResponse(
                    status = "200",
                    content = { @OpenApiContent(from = ApiDto.PrivilegesResponse.class) },
                    description = "Updated"
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
            path = "/api/v1/admin/privileges/add",
            methods = HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            changePrivileges(ctx, true);
        }

    }

    @Host({"api.", "server"})
    @Path("/api/v1/admin/privileges/remove")
    @WebEngine.HttpMethod("POST")
    public static class RemovePrivilegesHandler implements Handler {

        @Override
        @OpenApi(
            summary = "Remove privileges",
            description = "Removes privileges by name and answers with the resulting bitmask. Requires the admin scope and the ADMINISTRATOR privilege.",
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
                    content = { @OpenApiContent(from = ApiDto.PrivilegesRequest.class) }
                ),
            responses = {
                @OpenApiResponse(
                    status = "200",
                    content = { @OpenApiContent(from = ApiDto.PrivilegesResponse.class) },
                    description = "Updated"
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
            path = "/api/v1/admin/privileges/remove",
            methods = HttpMethod.POST
        )
        public void handle(@NotNull Context ctx) {
            changePrivileges(ctx, false);
        }
    }

    static void changePrivileges(Context ctx, boolean add) {
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

    
}
