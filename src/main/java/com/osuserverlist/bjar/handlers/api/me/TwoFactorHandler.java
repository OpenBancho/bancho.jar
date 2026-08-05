package com.osuserverlist.bjar.handlers.api.me;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.account.QrCode;
import com.osuserverlist.bjar.modules.account.TotpService;
import com.osuserverlist.bjar.modules.account.TwoFactorService;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import static com.osuserverlist.bjar.handlers.api.me.MeSupport.confirmPassword;
import static com.osuserverlist.bjar.handlers.api.me.MeSupport.logger;

/**
 * Two factor authentication of the account behind the token: reading whether it is on, and
 * turning it on or off.
 *
 * <p>{@code GET} answers the state. {@code POST} takes an {@code action}:
 *
 * <ul>
 *   <li>{@code setup} - hands out a fresh secret and the {@code otpauth://} address for the QR
 *       code. Nothing changes on the account yet: the secret waits in Redis until a code proves
 *       the authenticator actually has it.</li>
 *   <li>{@code enable} - takes a {@code code} from that authenticator and, if it fits the
 *       waiting secret, stores it on the account.</li>
 *   <li>{@code disable} - takes the {@code current_password} and removes it.</li>
 *   <li>{@code verify} - takes a {@code code} and says whether it fits the secret already on
 *       the account. Changes nothing; it exists so a caller that wants a second factor for
 *       something else - the staff panel asking a session to prove itself - can ask for one
 *       without knowing how any of this works.</li>
 * </ul>
 *
 * <p>Enabling asks for a code rather than the password, because the code is the thing that has
 * to be proven to work - an account that stores a secret its owner cannot produce codes for has
 * locked itself out of the game. Disabling asks for the password instead, for the same reason
 * every other dangerous change here does: a stolen session should not be able to take the second
 * factor off.
 *
 * <p>Turning it either way forgets every machine that was trusted before, so a secret that was
 * removed and set up again does not inherit the old exemptions.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/me/2fa")
@WebEngine.HttpMethod({ "GET", "POST" })
public final class TwoFactorHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) {
        if ("GET".equalsIgnoreCase(ctx.method().name())) {
            status(ctx);
            return;
        }

        change(ctx);
    }

    private void status(Context ctx) {
        OAuthToken token = ApiAuth.require(ctx);
        if (token == null || !ApiAuth.requireScope(ctx, token, ApiAuth.SCOPE_IDENTIFY)) {
            return;
        }

        UserEntity user = UserRepository.findById(token.getUserId());
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        Map<String, Object> body = ApiAuth.success();
        body.put("enabled", TotpService.enabled(user.getTotpSecret()));
        // Whether a setup is half finished, so the page can offer to carry on with it rather
        // than silently hand out a second secret.
        body.put("pending", TwoFactorService.pending(user.getId()) != null);

        ctx.json(body);
    }

    private void change(Context ctx) {
        OAuthToken token = ApiAuth.require(ctx);
        if (token == null || !ApiAuth.requireProfile(ctx, token)) {
            return;
        }

        JsonNode body = ApiAuth.body(ctx);
        if (body == null) {
            return;
        }

        String action = ApiAuth.stringField(body, "action");
        if (action == null) {
            ApiAuth.badRequest(ctx, "An action is required: setup, enable, disable or verify.");
            return;
        }

        switch (action) {
            case "setup" -> setup(ctx, token);
            case "enable" -> enable(ctx, token, body);
            case "disable" -> disable(ctx, token, body);
            case "verify" -> verify(ctx, token, body);
            default -> ApiAuth.badRequest(ctx, "Unknown action: " + action + ".");
        }
    }

    /**
     * Hands out a secret to scan. Repeatable on purpose: somebody who closed the page before
     * confirming gets a new one rather than being stuck with a secret they no longer have.
     */
    private void setup(Context ctx, OAuthToken token) {
        UserEntity user = UserRepository.findById(token.getUserId());
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        if (TotpService.enabled(user.getTotpSecret())) {
            ApiAuth.badRequest(ctx, "Two factor authentication is already on for this account.");
            return;
        }

        String secret = TotpService.newSecret();
        TwoFactorService.storePending(user.getId(), secret);

        String issuer = App.server.enviromentConfig.getDomain();
        String uri = TotpService.otpauthUri(issuer, user.getName(), secret);

        Map<String, Object> response = ApiAuth.success();
        response.put("secret", secret);
        // The same secret in groups of four, for an app that is being set up by hand.
        response.put("secret_formatted", TotpService.formatSecret(secret));
        response.put("uri", uri);
        // Drawn here rather than in the browser: a page that cannot reach a CDN would show no
        // QR code at all, and this costs a fraction of a millisecond.
        response.put("qr_svg", QrCode.toSvg(uri, 4));
        response.put("issuer", issuer);
        response.put("account", user.getName());
        response.put("digits", TotpService.DIGITS);
        response.put("period", TotpService.STEP_SECONDS);
        response.put("expires_in", TwoFactorService.PENDING_TTL_SECONDS);

        ctx.json(response);
    }

    private void enable(Context ctx, OAuthToken token, JsonNode body) {
        UserEntity user = UserRepository.findById(token.getUserId());
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        if (TotpService.enabled(user.getTotpSecret())) {
            ApiAuth.badRequest(ctx, "Two factor authentication is already on for this account.");
            return;
        }

        String pending = TwoFactorService.pending(user.getId());
        if (pending == null) {
            ApiAuth.badRequest(ctx, "This setup has expired. Start again to get a new QR code.");
            return;
        }

        String code = ApiAuth.stringField(body, "code");
        if (code == null) {
            ApiAuth.badRequest(ctx, "A code from your authenticator is required.");
            return;
        }

        if (!TotpService.verify(pending, code)) {
            logger.warn("Rejected a 2FA setup for user <{}> from <{}>: wrong code",
                    user.getId(), ctx.ip());
            ApiAuth.badRequest(ctx, "That code does not match. Check your device's clock and try the next one.");
            return;
        }

        user.setTotpSecret(pending);
        UserRepository.save(user);

        TwoFactorService.clearPending(user.getId());
        // A machine trusted under an older secret has proven nothing about this one.
        TwoFactorService.forgetDevices(user.getId());

        logger.info("User <{}> turned two factor authentication on from <{}>", user.getId(), ctx.ip());

        Map<String, Object> response = ApiAuth.success();
        response.put("enabled", true);

        ctx.json(response);
    }

    /**
     * Checks a code against the secret the account already has, and changes nothing.
     *
     * <p>The counterpart of the login check, for a caller that is a logged in session rather
     * than a game client: the session has a password behind it and wants the second factor as
     * well before it is allowed somewhere sensitive.
     *
     * <p>Wrong codes are counted per account here rather than per machine, because there is no
     * client hash to file them under. Ten of them and the account has to wait out the hour,
     * which is the same ceiling the login flow uses - six digits are guessable given enough
     * tries, and this endpoint would otherwise be the cheapest place to try.
     */
    private void verify(Context ctx, OAuthToken token, JsonNode body) {
        UserEntity user = UserRepository.findById(token.getUserId());
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        if (!TotpService.enabled(user.getTotpSecret())) {
            ApiAuth.badRequest(ctx, "This account has no authenticator set up.");
            return;
        }

        String bucket = "user:" + user.getId();

        if (TwoFactorService.tooManyFailures(bucket)) {
            ApiAuth.badRequest(ctx, "Too many wrong codes. Wait an hour before trying again.");
            return;
        }

        String code = ApiAuth.stringField(body, "code");
        if (code == null) {
            ApiAuth.badRequest(ctx, "A code from your authenticator is required.");
            return;
        }

        if (!TotpService.verify(user.getTotpSecret(), code)) {
            TwoFactorService.countFailure(bucket);

            logger.warn("Rejected a 2FA code for user <{}> from <{}>", user.getId(), ctx.ip());
            ApiAuth.badRequest(ctx, "That code does not match. Check your device's clock and try the next one.");
            return;
        }

        TwoFactorService.clearFailures(bucket);

        Map<String, Object> response = ApiAuth.success();
        response.put("verified", true);

        ctx.json(response);
    }

    private void disable(Context ctx, OAuthToken token, JsonNode body) {
        UserEntity user = confirmPassword(ctx, token, body);
        if (user == null) {
            return;
        }

        if (!TotpService.enabled(user.getTotpSecret())) {
            Map<String, Object> already = new LinkedHashMap<>(ApiAuth.success());
            already.put("enabled", false);

            ctx.json(already);
            return;
        }

        user.setTotpSecret(null);
        UserRepository.save(user);

        TwoFactorService.clearPending(user.getId());
        TwoFactorService.forgetDevices(user.getId());

        logger.info("User <{}> turned two factor authentication off from <{}>", user.getId(), ctx.ip());

        Map<String, Object> response = ApiAuth.success();
        response.put("enabled", false);

        ctx.json(response);
    }
}
