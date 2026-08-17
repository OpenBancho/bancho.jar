package com.osuserverlist.bjar.handlers.api.admin;

import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.GroupEntity;
import com.osuserverlist.bjar.modules.admin.AdminActions;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.GroupRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * POST /api/v1/admin/groups — creating, editing and deleting groups. Which of
 * the three a call means is in the {@code action} field, the same arrangement
 * the other multi-action endpoints use.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/admin/groups")
@WebEngine.HttpMethod("POST")
public final class GroupsActionHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Create, edit or delete a group",
        description = "Chosen by the action field. Requires the admin scope and the ADMINISTRATOR privilege.",
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
                content = { @OpenApiContent(from = ApiDto.GroupSaveRequest.class) }
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
                description = "Missing or invalid field, or a duplicate name"
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
                description = "No such group"
            )
        },
        path = "/api/v1/admin/groups",
        methods = HttpMethod.POST
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

        String action = ApiAuth.stringField(body, "action");
        if (action == null) {
            ApiAuth.badRequest(ctx, "An action is required: create, update or delete.");
            return;
        }

        switch (action) {
            case "create" -> save(ctx, session, body, true);
            case "update" -> save(ctx, session, body, false);
            case "delete" -> {
                int id = ApiAuth.intField(body, "id");
                if (id == Integer.MIN_VALUE) {
                    ApiAuth.badRequest(ctx, "A numeric id is required.");
                    return;
                }

                if (!AdminActions.deleteGroup(session.getUserId(), id)) {
                    ApiAuth.notFound(ctx, "No such group.");
                    return;
                }

                ctx.json(ApiAuth.success());
            }
            default -> ApiAuth.badRequest(ctx, "Unknown action: " + action + ".");
        }
    }

    /** The shared part of create and update: validation, then the write. */
    private static void save(Context ctx, OAuthToken session, JsonNode body, boolean create) {
        String name = ApiAuth.stringField(body, "name");
        if (name == null || name.trim().length() > 32) {
            ApiAuth.badRequest(ctx, "A group name of 1-32 characters is required.");
            return;
        }
        name = name.trim();

        String icon = ApiAuth.stringField(body, "icon");
        icon = icon == null ? "" : icon.trim();
        if (icon.length() > 16) {
            ApiAuth.badRequest(ctx, "The icon may be at most 16 characters long.");
            return;
        }

        String colour = normalizeColour(ApiAuth.stringField(body, "colour"));
        if (colour == null) {
            ApiAuth.badRequest(ctx, "The colour must be six hex digits, for example ff4d8d.");
            return;
        }

        String description = ApiAuth.stringField(body, "description");
        description = description == null ? "" : description.trim();
        if (description.length() > 128) {
            ApiAuth.badRequest(ctx, "The description may be at most 128 characters long.");
            return;
        }

        if (create) {
            GroupEntity group = AdminActions.createGroup(session.getUserId(), name, icon, colour,
                    description);

            if (group == null) {
                ApiAuth.badRequest(ctx, "A group with that name already exists.");
                return;
            }

            Map<String, Object> answer = ApiAuth.success();
            answer.put("group", GroupRepository.publicGroup(group));
            ctx.json(answer);
            return;
        }

        int id = ApiAuth.intField(body, "id");
        if (id == Integer.MIN_VALUE) {
            ApiAuth.badRequest(ctx, "A numeric id is required.");
            return;
        }

        if (GroupRepository.findById(id) == null) {
            ApiAuth.notFound(ctx, "No such group.");
            return;
        }

        if (!AdminActions.updateGroup(session.getUserId(), id, name, icon, colour, description)) {
            ApiAuth.badRequest(ctx, "A group with that name already exists.");
            return;
        }

        ctx.json(ApiAuth.success());
    }

    /** Six lowercase hex digits, with or without the leading hash; null when malformed. */
    private static String normalizeColour(String raw) {
        if (raw == null) {
            return null;
        }

        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("#")) {
            value = value.substring(1);
        }

        return value.matches("[0-9a-f]{6}") ? value : null;
    }
}
