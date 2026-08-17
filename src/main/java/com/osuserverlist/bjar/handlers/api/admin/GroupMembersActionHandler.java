package com.osuserverlist.bjar.handlers.api.admin;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.GroupEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.admin.AdminActions;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.GroupRepository;
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
 * POST /api/v1/admin/groups/members — deciding who carries a group.
 *
 * <p>{@code add} and {@code remove} move one account in or out of one group.
 * {@code set} takes the whole membership of one account at once — the groups
 * dialog on the player page submits the full ticked set rather than one call
 * per checkbox, so a save is a single request either way. Every change is
 * recorded in the account's staff history.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/admin/groups/members")
@WebEngine.HttpMethod("POST")
public final class GroupMembersActionHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Change group membership",
        description = "Adds, removes or sets the groups of an account, chosen by the action field. Requires the admin scope and the ADMINISTRATOR privilege.",
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
                content = { @OpenApiContent(from = ApiDto.GroupMemberRequest.class) }
            ),
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.SuccessResponse.class) },
                description = "Done; the account's fresh group list is in the answer"
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
                description = "No such user or group"
            )
        },
        path = "/api/v1/admin/groups/members",
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
            ApiAuth.badRequest(ctx, "An action is required: add, remove or set.");
            return;
        }

        int userId = ApiAuth.intField(body, "user_id");
        if (userId == Integer.MIN_VALUE) {
            ApiAuth.badRequest(ctx, "A numeric user_id is required.");
            return;
        }

        UserEntity user = UserRepository.findById(userId);
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        switch (action) {
            case "add", "remove" -> {
                int groupId = ApiAuth.intField(body, "group_id");
                if (groupId == Integer.MIN_VALUE) {
                    ApiAuth.badRequest(ctx, "A numeric group_id is required.");
                    return;
                }

                GroupEntity group = GroupRepository.findById(groupId);
                if (group == null) {
                    ApiAuth.notFound(ctx, "No such group.");
                    return;
                }

                if (action.equals("add")) {
                    AdminActions.addToGroup(session.getUserId(), userId, group);
                } else {
                    AdminActions.removeFromGroup(session.getUserId(), userId, group);
                }
            }
            case "set" -> {
                JsonNode ids = body.get("group_ids");
                if (ids == null || !ids.isArray()) {
                    ApiAuth.badRequest(ctx, "group_ids must be an array of group ids.");
                    return;
                }

                // Unknown ids are refused rather than dropped: a silently
                // ignored one would make the set not what the caller asked for.
                Set<Integer> wanted = new LinkedHashSet<>();
                for (JsonNode node : ids) {
                    if (!node.canConvertToInt()) {
                        ApiAuth.badRequest(ctx, "group_ids must be an array of group ids.");
                        return;
                    }

                    int id = node.asInt();
                    if (GroupRepository.findById(id) == null) {
                        ApiAuth.badRequest(ctx, "No group with id " + id + ".");
                        return;
                    }

                    wanted.add(id);
                }

                // Everything not asked for comes off, everything new goes on;
                // each of the two helpers records its own staff log line.
                for (GroupEntity group : GroupRepository.groupsOf(userId)) {
                    if (!wanted.contains(group.getId())) {
                        AdminActions.removeFromGroup(session.getUserId(), userId, group);
                    }
                }

                for (int id : wanted) {
                    GroupEntity group = GroupRepository.findById(id);
                    if (group != null) {
                        AdminActions.addToGroup(session.getUserId(), userId, group);
                    }
                }
            }
            default -> {
                ApiAuth.badRequest(ctx, "Unknown action: " + action + ".");
                return;
            }
        }

        // The fresh membership travels with the answer, so the page does not
        // have to re-read it.
        Map<String, Object> answer = ApiAuth.success();
        answer.put("groups", GroupRepository.publicGroupsOf(userId));
        ctx.json(answer);
    }
}
