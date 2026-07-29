package com.osuserverlist.bjar.modules.main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.osuserverlist.bjar.App;

import lombok.Value;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class Captcha {

    private static final Logger logger = LoggerFactory.getLogger("Captcha");

    /** A token is valid for a few minutes, so a short timeout is plenty. */
    private static final long TIMEOUT_SECONDS = 10;

    private static final Gson GSON = new Gson();

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /**
     * Score below which a reCAPTCHA v3 verdict counts as a bot. Ignored by
     * Turnstile and by reCAPTCHA v2, which do not answer with a score.
     */
    private static final double MIN_SCORE = 0.5d;

    private Captcha() {
    }

    /**
     * The supported widgets, each with the form field it writes its token into
     * and the endpoint that turns that token into a verdict.
     *
     * <p>If your {@code CaptchaProvider} already lives in the config package,
     * delete this enum and keep the two accessors on that one instead.
     */
    public enum Provider {

        NONE("", ""),
        TURNSTILE("cf-turnstile-response",
                "https://challenges.cloudflare.com/turnstile/v0/siteverify"),
        RECAPTCHA("g-recaptcha-response",
                "https://www.google.com/recaptcha/api/siteverify");

        private final String field;
        private final String verifyUrl;

        Provider(String field, String verifyUrl) {
            this.field = field;
            this.verifyUrl = verifyUrl;
        }

        /** The form field the widget writes its token into. */
        public String field() {
            return field;
        }

        /** The provider's siteverify endpoint. */
        public String verifyUrl() {
            return verifyUrl;
        }
    }

    /** The outcome of one verification: solved, or not, with a reason. */
    @Value
    public static class Result {

        boolean success;

        /** Human readable reason, empty when the challenge was solved. */
        String message;

        /** The {@code error-codes} the provider answered with, for the log. */
        List<String> errorCodes;

        public static Result ok() {
            return new Result(true, "", List.of());
        }

        public static Result failed(String message, List<String> errorCodes) {
            return new Result(false, message, errorCodes == null ? List.of() : errorCodes);
        }
    }

    /** The configured widget, never null; {@code NONE} when unconfigured. */
    public static Provider provider() {
        Provider provider = Provider.valueOf(App.server.enviromentConfig.getCaptchaProvider());
        return provider == null ? Provider.NONE : provider;
    }

    /** The secret key, or an empty string when the captcha is not configured. */
    public static String secretKey() {
        String secret = App.server.enviromentConfig.getCaptchaSecretKey();
        return secret == null ? "" : secret.trim();
    }

    /**
     * True while a provider and a secret key are configured; without both no
     * check is made. A provider without a secret cannot be verified, so it is
     * treated as no captcha at all rather than as a wall nobody can pass.
     */
    public static boolean enabled() {
        return provider() != Provider.NONE && !secretKey().isEmpty();
    }

    /**
     * The form field the current widget writes its token into, or an empty
     * string while the captcha is disabled. Templates use this to name the
     * field they read back.
     */
    public static String field() {
        return enabled() ? provider().field() : "";
    }

    /**
     * Verifies a widget token with the configured provider.
     *
     * <p>A token may only be verified once, so this must be called exactly once
     * per submitted form.
     *
     * @param token    the value of the widget's response field, see {@link #field()}
     * @param remoteIp the visitor's address, may be null; it is only sent when
     *                 present, because a wrong address fails the check
     * @return the verdict, always success when the captcha is not configured
     */
    public static Result verify(String token, String remoteIp) {
        if (!enabled()) {
            return Result.ok();
        }

        Provider provider = provider();

        if (token == null || token.isBlank()) {
            return Result.failed("Please complete the captcha.", List.of("missing-input-response"));
        }

        FormBody.Builder form = new FormBody.Builder()
                .add("secret", secretKey())
                .add("response", token.trim());
        if (remoteIp != null && !remoteIp.isBlank()) {
            form.add("remoteip", remoteIp.trim());
        }

        Request request = new Request.Builder()
                .url(provider.verifyUrl())
                .header("Accept", "application/json")
                .post(form.build())
                .build();

        String payload;
        int statusCode;
        try (Response response = CLIENT.newCall(request).execute()) {
            statusCode = response.code();
            ResponseBody body = response.body();
            payload = body == null ? "" : body.string();
        } catch (IOException e) {
            logger.error("Could not reach the {} verification endpoint", provider, e);
            // The provider being unreachable is our problem, not the visitor's,
            // but letting the form through would turn an outage into an open
            // registration endpoint. It stays closed.
            return Result.failed("The captcha service is unreachable. Please try again in a moment.",
                    List.of("internal-error"));
        }

        JsonObject body;
        try {
            JsonElement parsed = GSON.fromJson(payload.isBlank() ? "{}" : payload, JsonElement.class);
            body = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (JsonSyntaxException e) {
            logger.warn("{} answered with an unreadable body ({})", provider, statusCode);
            return Result.failed("The captcha could not be verified. Please try again.",
                    List.of("bad-response"));
        }

        List<String> codes = errorCodes(body);

        if (!bool(body, "success")) {
            logger.warn("A {} token was rejected from <{}>: {}", provider, remoteIp, codes);
            return Result.failed(describe(codes), codes);
        }

        // reCAPTCHA v3 always succeeds and grades the visitor instead; a low
        // score is a bot even though the challenge itself came back solved.
        Double score = score(body);
        if (score != null && score < MIN_SCORE) {
            logger.warn("A {} token from <{}> scored {}, below the {} threshold",
                    provider, remoteIp, score, MIN_SCORE);
            return Result.failed("The captcha could not be verified. Please try again.",
                    List.of("low-score"));
        }

        return Result.ok();
    }

    private static boolean bool(JsonObject body, String member) {
        JsonElement value = body.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    /** The v3 score, or null when the provider did not send one. */
    private static Double score(JsonObject body) {
        JsonElement value = body.get("score");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return value.getAsDouble();
    }

    private static List<String> errorCodes(JsonObject body) {
        JsonElement value = body.get("error-codes");
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (JsonElement code : value.getAsJsonArray()) {
            if (code != null && code.isJsonPrimitive()) {
                codes.add(code.getAsString());
            }
        }
        return codes;
    }

    /**
     * Turns the error codes into something worth showing to a person. Only the
     * ones the visitor can act on get their own wording; a misconfigured secret
     * is not their business, so it reads like any other failure. Both providers
     * use the same vocabulary here, so one mapping serves both.
     */
    private static String describe(List<String> codes) {
        if (codes.contains("timeout-or-duplicate")) {
            return "The captcha expired. Please solve it again.";
        }
        if (codes.contains("missing-input-response") || codes.contains("invalid-input-response")) {
            return "Please complete the captcha.";
        }
        return "The captcha could not be verified. Please try again.";
    }
}