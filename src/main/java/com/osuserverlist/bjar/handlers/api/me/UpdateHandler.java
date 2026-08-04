package com.osuserverlist.bjar.handlers.api.me;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.api.ApiMappers;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.modules.account.DonorService;
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

        // A banner is changed by uploading one, so the only thing this route can do
        // with it is take it away again. Accepting a path here would let an account
        // point its cover at any file the server happens to serve.
        if (body.has("custom_banner")) {
            if (!DonorService.isDonor(user)) {
                ctx.status(403).json(ApiPagination.error(
                        "A profile banner is a supporter perk."));
                return;
            }

            if (!body.get("custom_banner").isNull()) {
                ApiAuth.badRequest(ctx, "A banner is set by uploading one, so "
                        + "custom_banner may only be null.");
                return;
            }

            user.setCustomBanner(null);
            changed = true;
        }

        boolean touchesBadge = body.has("custom_badge_name") || body.has("custom_badge_icon");

        // A custom badge is a supporter perk. Supporter is either the timed
        // donor_end handed out by the admin panel or the permanent privilege
        // bits, so the shared check decides instead of the bits alone.
        if (touchesBadge && !DonorService.isDonor(user)) {
            ctx.status(403).json(ApiPagination.error(
                    "A custom badge is a supporter perk."));
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

        // A badge without a picture would render as a bare word on the profile,
        // so the two fields are only accepted together. Clearing both is fine.
        if (touchesBadge) {
            String badgeName = trimToNull(user.getCustomBadgeName());
            String badgeIcon = trimToNull(user.getCustomBadgeIcon());

            if (badgeIcon != null && !isImageUrl(badgeIcon)) {
                ApiAuth.badRequest(ctx, "The badge icon must be an http(s) link to an image.");
                return;
            }

            if (badgeName != null && badgeIcon == null) {
                ApiAuth.badRequest(ctx, "A badge needs an icon image.");
                return;
            }

            if (badgeIcon != null && badgeName == null) {
                ApiAuth.badRequest(ctx, "A badge needs a name.");
                return;
            }

            user.setCustomBadgeName(badgeName);
            user.setCustomBadgeIcon(badgeIcon);
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

    /** Blank badge fields are stored as {@code null} so "no badge" has one shape. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The badge icon is rendered as an {@code <img>} on the profile, so only two
     * shapes are accepted: a path this server itself handed out for an uploaded
     * picture, and a plain http(s) link. Anything else (a {@code javascript:} or
     * {@code data:} value in particular) is refused rather than escaped later.
     */
    private static boolean isImageUrl(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);

        // What BadgeIconHandler stores after an upload. It is the normal case, and
        // rejecting it used to make saving an uploaded badge impossible.
        boolean uploaded = lower.startsWith("/api/v1/badge/");

        if (!uploaded && !lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }

        return !lower.contains("\"") && !lower.contains("<") && !lower.contains(" ");
    }
}
