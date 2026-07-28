package com.osuserverlist.bjar.handlers.api.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/admin/access - what the caller may see and do.
 *
 * <p>The panel has to draw a sidebar before it knows anything, and the alternative to asking is
 * for the browser to guess from a privilege bitmask it happens to have been handed. That guess
 * is where role logic quietly forks: the server decides one thing, the client decides another,
 * and the difference is a link that 403s. So the server names the sections instead.</p>
 *
 * <p>This is not a security boundary. Every endpoint behind it checks its own privilege, and
 * hiding a button has never stopped anyone from issuing the request by hand. It exists so the
 * interface can be honest about what is behind each door.</p>
 */
@Host({"api.", "server", ""})
@Path("/api/v1/admin/access")
@HttpMethod("GET")
public final class AccessHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Describe the caller's panel access",
        description = "Answers with the sections and actions the authenticated staff member may use. "
                + "Requires any one of the NOMINATOR, MODERATOR, ADMINISTRATOR or DEVELOPER privileges.",
        tags = { "Administration" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.AdminAccessResponse.class) },
                description = "The caller's access"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing, expired or revoked access token"
            ),
            @OpenApiResponse(
                status = "403",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The account holds no staff privilege"
            )
        },
        path = "/api/v1/admin/access"
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireStaff(ctx, session)) {
            return;
        }

        int priv = session.getPrivileges();

        boolean nominator = Privileges.has(priv, Privileges.NOMINATOR);
        boolean moderator = Privileges.has(priv, Privileges.MODERATOR);
        boolean admin = Privileges.has(priv, Privileges.ADMINISTRATOR);
        boolean developer = Privileges.has(priv, Privileges.DEVELOPER);

        List<String> sections = new ArrayList<>();
        List<String> actions = new ArrayList<>();

        if (nominator) {
            sections.add("requests");
            actions.add("rank");
        }

        if (moderator || admin) {
            sections.add("moderation");
            sections.add("logs");
        }

        if (moderator) {
            actions.add("restrict");
            actions.add("silence");
            actions.add("note");
            actions.add("alert");
            actions.add("country");
        }

        if (admin) {
            actions.add("wipe");
            actions.add("supporter");
            actions.add("privileges");
            actions.add("rename");
        }

        if (developer) {
            sections.add("server");
        }

        Map<String, Object> response = ApiAuth.success();

        response.put("id", session.getUserId());
        response.put("priv", priv);
        response.put("roles", AdminPresenter.roles(priv));
        response.put("sections", sections);
        response.put("actions", actions);

        ctx.json(response);
    }
}
