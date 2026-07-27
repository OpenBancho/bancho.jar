package com.osuserverlist.bjar.handlers.api.me;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.admin.AdminActions;
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

import static com.osuserverlist.bjar.handlers.api.me.MeSupport.MAX_BADGE_ICON_LENGTH;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.MAX_BADGE_NAME_LENGTH;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.MAX_PLAY_STYLE;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.MAX_USERPAGE_LENGTH;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.logger;

@Host({"api.", "server", ""})
@Path("/api/v1/me/update")
@WebEngine.HttpMethod("POST")
public final class UpdateHandler implements Handler {

    @Override
    @OpenApi(
        summary = "Update own profile",
        description = "Changes the profile fields of the account behind the access token. Only the supplied fields are touched. Requires the profile scope and an unrestricted account.",
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
                content = { @OpenApiContent(from = ApiDto.SelfUpdateRequest.class) }
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
        path = "/api/v1/me/update",
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

        UserEntity user = UserRepository.findById(token.getUserId());
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        boolean changed = false;

        if (body.has("userpage_content")) {
            JsonNode node = body.get("userpage_content");

            if (node.isNull()) {
                user.setUserpageContent(null);
            } else if (node.isTextual()) {
                String value = node.asText();

                if (value.length() > MAX_USERPAGE_LENGTH) {
                    ApiAuth.badRequest(ctx, "The userpage may be at most "
                            + MAX_USERPAGE_LENGTH + " characters long.");
                    return;
                }

                user.setUserpageContent(value);
            } else {
                ApiAuth.badRequest(ctx, "userpage_content must be a string or null.");
                return;
            }

            changed = true;
        }

        if (body.has("preferred_mode")) {
            int mode = ApiAuth.intField(body, "preferred_mode");

            if (mode < 0 || mode >= AdminActions.MODE_COUNT) {
                ApiAuth.badRequest(ctx, "preferred_mode must be between 0 and "
                        + (AdminActions.MODE_COUNT - 1) + ".");
                return;
            }

            user.setPreferredMode(mode);
            changed = true;
        }

        if (body.has("play_style")) {
            int style = ApiAuth.intField(body, "play_style");

            if (style < 0 || style > MAX_PLAY_STYLE) {
                ApiAuth.badRequest(ctx, "play_style must be between 0 and "
                        + MAX_PLAY_STYLE + ".");
                return;
            }

            user.setPlayStyle(style);
            changed = true;
        }

        boolean touchesBadge = body.has("custom_badge_name") || body.has("custom_badge_icon");

        // A custom badge is a supporter perk in game; the API keeps the same rule.
        if (touchesBadge && !ApiAuth.requireAny(ctx, token, Privileges.SUPPORTER, Privileges.PREMIUM)) {
            return;
        }

        if (body.has("custom_badge_name")) {
            JsonNode node = body.get("custom_badge_name");

            if (node.isNull()) {
                user.setCustomBadgeName(null);
            } else if (node.isTextual() && node.asText().length() <= MAX_BADGE_NAME_LENGTH) {
                user.setCustomBadgeName(node.asText());
            } else {
                ApiAuth.badRequest(ctx, "custom_badge_name must be null or at most "
                        + MAX_BADGE_NAME_LENGTH + " characters.");
                return;
            }

            changed = true;
        }

        if (body.has("custom_badge_icon")) {
            JsonNode node = body.get("custom_badge_icon");

            if (node.isNull()) {
                user.setCustomBadgeIcon(null);
            } else if (node.isTextual() && node.asText().length() <= MAX_BADGE_ICON_LENGTH) {
                user.setCustomBadgeIcon(node.asText());
            } else {
                ApiAuth.badRequest(ctx, "custom_badge_icon must be null or at most "
                        + MAX_BADGE_ICON_LENGTH + " characters.");
                return;
            }

            changed = true;
        }

        if (!changed) {
            ApiAuth.badRequest(ctx, "Nothing to update.");
            return;
        }

        UserRepository.save(user);

        logger.info("User <{}> updated their profile from <{}>", user.getId(), ctx.ip());

        Map<String, Object> response = ApiAuth.success();
        response.put("info", ApiMappers.userInfo(user));

        ctx.json(response);
    }
}
