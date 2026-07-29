package com.osuserverlist.bjar.handlers.api.users;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.account.RegistrationService;
import com.osuserverlist.bjar.modules.api.TokenStore;
import com.osuserverlist.bjar.modules.main.Turnstile;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * Registration for everything that is not the game client: the website, and any
 * other application built on this API.
 *
 * <p>The account is created and an OAuth2 token pair is issued in the same
 * answer, so the caller does not have to post the password a second time to the
 * token endpoint. The pair is the same one {@code /api/v1/oauth/token} hands
 * out, cookies included, which means a browser is logged in the moment its
 * registration succeeds.
 *
 * <p>Because this endpoint is public and creates rows, it is the one place that
 * insists on a solved Cloudflare Turnstile challenge whenever
 * {@code TURNSTILE_SECRET_KEY} is configured. The token is verified here, on
 * the server, so a frontend cannot be talked out of the check.
 */
@Host({ "api.", "server", "" })
@Path("/api/v1/users/register")
@WebEngine.HttpMethod("POST")
public final class RegisterHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger("Registration");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    @OpenApi(
        summary = "Register an account",
        description = "Creates an account and returns an OAuth2 token pair for it, so the caller is "
                + "logged in straight away. Accepts a form encoded or a JSON body. When "
                + "TURNSTILE_SECRET_KEY is set, a solved Cloudflare Turnstile token has to be sent as "
                + "cf-turnstile-response. The password is stored as bcrypt over its md5, the same way "
                + "the in-game registration stores it.",
        tags = { "Users" },
        requestBody =
            @OpenApiRequestBody(required = true, content = { @OpenApiContent(from = ApiDto.RegisterRequest.class) }),
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.RegisterResponse.class) },
                description = "The account was created and a token pair was issued"
            ),
            @OpenApiResponse(
                status = "400",
                content = { @OpenApiContent(from = ApiDto.RegisterErrorResponse.class) },
                description = "A field is missing or invalid, the name or email is taken, or the captcha failed"
            ),
            @OpenApiResponse(
                status = "403",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Registration through the API is disabled on this server"
            ),
            @OpenApiResponse(
                status = "503",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The account exists, but no token could be issued because the session store is unreachable"
            )
        },
        path = "/api/v1/users/register",
        methods = HttpMethod.POST
    )
    public void handle(@NotNull Context ctx) {
        if (!App.server.enviromentConfig.isWebRegistrationEnabled()) {
            ctx.status(403).json(Map.of("status", "Registration is currently disabled on this server."));
            return;
        }

        Params params = Params.of(ctx);

        String username = params.get("username");
        String email = params.get("email");
        String password = params.get("password");

        if (username == null || email == null || password == null) {
            ctx.status(400).json(Map.of("status", "A username, an email address and a password are required."));
            return;
        }

        // One token, one verification: this happens before anything is written,
        // and before the database is asked whether the name is free, so a bot
        // cannot use the endpoint to probe for taken usernames either.
        Turnstile.Result captcha = Turnstile.verify(params.get(Turnstile.FIELD), ctx.ip());

        if (!captcha.isSuccess()) {
            ctx.status(400).json(Map.of(
                    "status", captcha.getMessage(),
                    "errors", Map.of("captcha", List.of(captcha.getMessage()))));
            return;
        }

        Map<String, List<String>> errors = RegistrationService.validate(username, email, password);

        if (!errors.isEmpty()) {
            reject(ctx, errors);
            return;
        }

        UserEntity user = RegistrationService.create(username, email, password, errors);

        if (user == null) {
            reject(ctx, errors);
            return;
        }

        TokenStore.TokenPair pair = TokenStore.issue(user.getId(), user.getName(),
                user.getPrivileges(), params.get("scope"), params.get("client_id"), ctx.ip());

        if (pair == null) {
            // The account is real, only the login could not be handed over. The
            // caller can simply use the token endpoint with the credentials it
            // already has, so this is not a failed registration.
            logger.warn("Registered <{}>({}) but could not issue a token pair",
                    user.getName(), user.getId());

            ctx.status(503).json(Map.of(
                    "status", "The account was created, but you could not be logged in. Please log in manually.",
                    "user", userBody(user)));
            return;
        }

        logger.info("Registered <{}>({}) from <{}> through the API (scope: {})",
                user.getName(), user.getId(), ctx.ip(), pair.getScope());

        ctx.header("Cache-Control", "no-store");
        ctx.header("Pragma", "no-cache");

        // The same two cookies the token endpoint sets, so a browser that
        // registered is authenticated against the API from here on.
        ctx.res().addHeader("Set-Cookie", TokenStore.buildAccessCookie(pair.getAccessToken()));
        ctx.res().addHeader("Set-Cookie", TokenStore.buildRefreshCookie(pair.getRefreshToken()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("user", userBody(user));
        body.put("access_token", pair.getAccessToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", pair.getAccessExpiresIn());
        body.put("refresh_token", pair.getRefreshToken());
        body.put("refresh_expires_in", pair.getRefreshExpiresIn());
        body.put("scope", pair.getScope());

        // The account exists and the token is real, but neither opens anything yet: a fresh
        // account has no VERIFIED bit, and every authenticated endpoint refuses it until the
        // player logs into the game once. Said here so the caller can show that instead of
        // discovering it on the next 401.
        body.put("verified", false);
        body.put("message", ApiAuth.UNVERIFIED_MSG);

        ctx.json(body);
    }

    private Map<String, Object> userBody(UserEntity user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("name", user.getName());
        body.put("priv", user.getPrivileges());
        body.put("verified", ApiAuth.isVerified(user.getPrivileges()));

        return body;
    }

    /**
     * Answers with one readable line in {@code status} and the per field
     * messages in {@code errors}, so a form can print them next to the fields
     * while a script only has to look at one key.
     */
    private void reject(Context ctx, Map<String, List<String>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", RegistrationService.firstMessage(errors));
        body.put("errors", RegistrationService.formatErrors(errors));

        ctx.status(400).json(body);
    }

    /**
     * Reads the fields from a form body, as an HTML form posts them, or from a
     * JSON body, as the rest of this API does. Query parameters are never read:
     * a password has no business being in a URL.
     */
    private static final class Params {

        private final Context ctx;
        private final JsonNode json;

        private Params(Context ctx, JsonNode json) {
            this.ctx = ctx;
            this.json = json;
        }

        static Params of(Context ctx) {
            JsonNode parsed = null;
            String type = ctx.header("Content-Type");

            if (type != null && type.toLowerCase().contains("json")) {
                try {
                    JsonNode node = MAPPER.readTree(ctx.body());

                    if (node != null && node.isObject()) {
                        parsed = node;
                    }
                } catch (Exception ignored) {
                    // Falls through; the missing fields are reported instead.
                }
            }

            return new Params(ctx, parsed);
        }

        String get(String name) {
            if (json != null) {
                JsonNode node = json.get(name);

                if (node == null || !node.isValueNode()) {
                    return null;
                }

                String value = node.asText();

                return value.isBlank() ? null : value;
            }

            String value = ctx.formParam(name);

            return value == null || value.isBlank() ? null : value;
        }
    }
}
