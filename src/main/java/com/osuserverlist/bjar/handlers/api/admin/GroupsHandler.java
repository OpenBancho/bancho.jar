package com.osuserverlist.bjar.handlers.api.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.GroupEntity;
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
 * GET /api/v1/admin/groups — every group, with its member count. This is the
 * Groups page of the staff panel; the public display of a group travels with
 * the profile and leaderboard answers instead.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/admin/groups")
@WebEngine.HttpMethod("GET")
public final class GroupsHandler implements Handler {

    @Override
    @OpenApi(
        summary = "List groups",
        description = "Every group, with its member count. Requires the admin scope and the ADMINISTRATOR privilege.",
        tags = { "Administration" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        responses = {
            @OpenApiResponse(status = "200", description = "The groups"),
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
        path = "/api/v1/admin/groups",
        methods = HttpMethod.GET
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireAdmin(ctx, session)) {
            return;
        }

        List<Map<String, Object>> groups = new ArrayList<>();

        for (GroupEntity group : GroupRepository.findAll()) {
            Map<String, Object> map = GroupRepository.publicGroup(group);
            map.put("description", group.getDescription());
            map.put("members", GroupRepository.countMembers(group.getId()));
            groups.add(map);
        }

        Map<String, Object> body = ApiAuth.success();
        body.put("groups", groups);
        ctx.json(body);
    }
}
