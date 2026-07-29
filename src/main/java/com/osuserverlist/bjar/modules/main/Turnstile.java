package com.osuserverlist.bjar.modules.main;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.bjar.App;

import lombok.Value;

/**
 * Server side verification of a Cloudflare Turnstile widget.
 *
 * <p>The browser solves the challenge against the site key and posts the
 * resulting token along with the form. That token proves nothing on its own:
 * it is single use and only becomes a verdict once it has been handed to
 * Cloudflare together with the secret key, which is exactly what happens here.
 *
 * <p>Turnstile is optional. With no secret configured
 * ({@code TURNSTILE_SECRET_KEY} empty) {@link #enabled()} is false and every
 * caller skips the check, so a server that does not want a captcha keeps
 * working unchanged.
 */
public final class Turnstile {

    private static final Logger logger = LoggerFactory.getLogger("Turnstile");

    /** The form field the Turnstile widget writes its token into. */
    public static final String FIELD = "cf-turnstile-response";

    private static final String VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    /** A token is valid for 300 seconds, so a short timeout is plenty. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Turnstile() {
    }

    /** The outcome of one verification: solved, or not, with a reason. */
    @Value
    public static class Result {
        boolean success;

        /** Human readable reason, empty when the challenge was solved. */
        String message;

        /** The {@code error-codes} Cloudflare answered with, for the log. */
        List<String> errorCodes;

        public static Result ok() {
            return new Result(true, "", List.of());
        }

        public static Result failed(String message, List<String> errorCodes) {
            return new Result(false, message, errorCodes == null ? List.of() : errorCodes);
        }
    }

    /** The secret key, or an empty string when the captcha is not configured. */
    public static String secretKey() {
        String secret = App.server.enviromentConfig.getTurnstileSecretKey();

        return secret == null ? "" : secret.trim();
    }

    /** True while a secret key is configured; without one no check is made. */
    public static boolean enabled() {
        return !secretKey().isEmpty();
    }

    /**
     * Verifies a widget token with Cloudflare.
     *
     * <p>A token may only be verified once, so this must be called exactly once
     * per submitted form.
     *
     * @param token    the value of the {@code cf-turnstile-response} field
     * @param remoteIp the visitor's address, may be null; it is only sent when
     *                 present, because a wrong address fails the check
     * @return the verdict, always success when the captcha is not configured
     */
    public static Result verify(String token, String remoteIp) {
        if (!enabled()) {
            return Result.ok();
        }

        if (token == null || token.isBlank()) {
            return Result.failed("Please complete the captcha.", List.of("missing-input-response"));
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("secret", secretKey());
        form.put("response", token.trim());

        if (remoteIp != null && !remoteIp.isBlank()) {
            form.put("remoteip", remoteIp.trim());
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(VERIFY_URL))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(form), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;

        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("Could not reach the Turnstile verification endpoint", e);

            // Cloudflare being unreachable is our problem, not the visitor's,
            // but letting the form through would turn an outage into an open
            // registration endpoint. It stays closed.
            return Result.failed("The captcha service is unreachable. Please try again in a moment.",
                    List.of("internal-error"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return Result.failed("The captcha check was interrupted. Please try again.",
                    List.of("internal-error"));
        }

        JsonNode body;

        try {
            body = MAPPER.readTree(response.body() == null ? "{}" : response.body());
        } catch (Exception e) {
            logger.warn("Turnstile answered with an unreadable body ({})", response.statusCode());

            return Result.failed("The captcha could not be verified. Please try again.",
                    List.of("bad-response"));
        }

        if (body.path("success").asBoolean(false)) {
            return Result.ok();
        }

        List<String> codes = new ArrayList<>();

        for (JsonNode code : body.path("error-codes")) {
            codes.add(code.asText());
        }

        logger.warn("A Turnstile token was rejected from <{}>: {}", remoteIp, codes);

        return Result.failed(describe(codes), codes);
    }

    /**
     * Turns the error codes into something worth showing to a person. Only the
     * ones the visitor can act on get their own wording; a misconfigured secret
     * is not their business, so it reads like any other failure.
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

    private static String formBody(Map<String, String> values) {
        StringBuilder body = new StringBuilder();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }

            if (body.length() > 0) {
                body.append('&');
            }

            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            body.append('=');
            body.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        return body.toString();
    }
}
