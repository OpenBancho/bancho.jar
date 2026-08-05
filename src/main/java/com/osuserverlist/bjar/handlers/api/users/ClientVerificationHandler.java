package com.osuserverlist.bjar.handlers.api.users;

import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.account.TotpService;
import com.osuserverlist.bjar.modules.account.TwoFactorService;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Answering a login that is waiting on a two factor code.
 *
 * <p>{@code GET ?ch=...} says whose login is waiting, so the page can address the player by
 * name instead of asking for a code out of nowhere. {@code POST {ch, code}} checks the code and,
 * if it fits, remembers the machine - the next login from the game goes straight through.
 *
 * <p>Open to anyone signing in, and it has to be: whoever is looking at this page cannot log in
 * to the website either if their session has expired, and the whole point is that they are
 * stuck at the login screen. What authorises the call is the pair of a client hash the server
 * itself filed a moment ago and a code from the account's authenticator; neither is guessable,
 * and wrong codes are counted so the six digits cannot be walked through.
 *
 * <p>Nothing about the account leaks to a caller with a random hash: an unknown hash is a plain
 * 404, the same answer as an expired one.
 */
@Host({"api.", "server", ""})
@Path("/api/v1/client-verification")
@HttpMethod({ "GET", "POST" })
public final class ClientVerificationHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger("ClientVerification");

    private static final String EXPIRED =
            "This verification link has expired. Start the game and try to log in again.";

    @Override
    public void handle(@NotNull Context ctx) {
        if ("GET".equalsIgnoreCase(ctx.method().name())) {
            describe(ctx);
            return;
        }

        verify(ctx);
    }

    private void describe(Context ctx) {
        String clientHash = ctx.queryParam("ch");

        if (clientHash == null || clientHash.isBlank()) {
            ApiAuth.badRequest(ctx, "This address is missing its client hash.");
            return;
        }

        UserEntity user = waiting(clientHash);

        if (user == null) {
            ApiAuth.notFound(ctx, EXPIRED);
            return;
        }

        Map<String, Object> body = ApiAuth.success();
        body.put("username", user.getName());
        body.put("digits", TotpService.DIGITS);

        ctx.json(body);
    }

    private void verify(Context ctx) {
        JsonNode body = ApiAuth.body(ctx);
        if (body == null) {
            return;
        }

        String clientHash = ApiAuth.stringField(body, "ch");
        String code = ApiAuth.stringField(body, "code");

        if (clientHash == null) {
            ApiAuth.badRequest(ctx, "A client hash is required.");
            return;
        }

        if (code == null) {
            ApiAuth.badRequest(ctx, "A code from your authenticator is required.");
            return;
        }

        String fingerprint = TwoFactorService.fingerprint(clientHash);

        if (TwoFactorService.tooManyFailures(fingerprint)) {
            logger.warn("Too many wrong 2FA codes from <{}>", ctx.ip());
            ApiAuth.badRequest(ctx, "Too many wrong codes. Try to log in from the game again.");
            return;
        }

        UserEntity user = waiting(clientHash);

        if (user == null) {
            ApiAuth.notFound(ctx, EXPIRED);
            return;
        }

        if (!TotpService.verify(user.getTotpSecret(), code)) {
            TwoFactorService.countFailure(fingerprint);

            logger.warn("Wrong 2FA code for user <{}> from <{}>", user.getId(), ctx.ip());
            ApiAuth.badRequest(ctx, "That code does not match. Wait for the next one and try again.");
            return;
        }

        TwoFactorService.trust(user.getId(), fingerprint);
        TwoFactorService.closeChallenge(fingerprint);

        logger.info("User <{}> verified a client from <{}>", user.getId(), ctx.ip());

        Map<String, Object> response = ApiAuth.success();
        response.put("username", user.getName());
        response.put("verified", true);

        ctx.json(response);
    }

    /**
     * The account whose login is waiting on this machine, or {@code null} if none is - which
     * covers an unknown hash, an expired challenge, a deleted account, and an account that has
     * since turned 2FA off.
     */
    private UserEntity waiting(String clientHash) {
        Integer userId = TwoFactorService.challengeUser(TwoFactorService.fingerprint(clientHash));

        if (userId == null) {
            return null;
        }

        UserEntity user = UserRepository.findById(userId);

        if (user == null || !TotpService.enabled(user.getTotpSecret())) {
            return null;
        }

        return user;
    }
}
