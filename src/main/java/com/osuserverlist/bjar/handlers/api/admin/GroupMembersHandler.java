package com.osuserverlist.bjar.handlers.api.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.database.GroupEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
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
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/admin/groups/members — the accounts in one group, for the
 * member list the Groups page expands.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/admin/groups/members")
@WebEngine.HttpMethod("GET")
public final class GroupMembersHandler implements Handler {

    @Override
    @OpenApi(
        summary = "List group members",
        description = "The accounts in one group. Requires the admin scope and the ADMINISTRATOR privilege.",
        tags = { "Administration" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, required = true, description = "The group's id.")
        },
        responses = {
            @OpenApiResponse(status = "200", description = "The group and its members"),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing or malformed id"
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
        path = "/api/v1/admin/groups/members",
        methods = HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireAdmin(ctx, session)) {
            return;
        }

        int groupId;
        try {
            groupId = Integer.parseInt(String.valueOf(ctx.queryParam("id")).trim());
        } catch (NumberFormatException e) {
            ApiAuth.badRequest(ctx, "A numeric id is required.");
            return;
        }

        GroupEntity group = GroupRepository.findById(groupId);
        if (group == null) {
            ApiAuth.notFound(ctx, "No such group.");
            return;
        }

        List<Map<String, Object>> members = new ArrayList<>();
        for (UserEntity member : GroupRepository.membersOf(groupId)) {
            members.add(ApiMappers.userRef(member));
        }

        Map<String, Object> body = ApiAuth.success();
        body.put("group", GroupRepository.publicGroup(group));
        body.put("members", members);
        ctx.json(body);
    }
}
