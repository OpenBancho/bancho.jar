package com.osuserverlist.bjar.handlers.osu;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.modules.account.RegistrationService;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import lombok.AllArgsConstructor;

/**
 * Registration from inside the osu! client.
 *
 * <p>The client posts the form twice: once per edited field with {@code check}
 * set, to colour the field red while it is being typed, and once with
 * {@code check=0} to actually create the account. Only the second one writes
 * anything.
 *
 * <p>The rules and the account creation itself live in
 * {@link RegistrationService}, shared with the website's registration endpoint,
 * so both doors into the server agree on what a valid account looks like. There
 * is no captcha here: the client cannot render one.
 */
@Host("osu.")
@Path("/users")
@HttpMethod("POST")
public class IngameRegistrationHandler implements Handler {

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String username = ctx.formParam("user[username]");
        String email = ctx.formParam("user[user_email]");
        String password = ctx.formParam("user[password]");
        Integer check = ctx.formParamAsClass("check", Integer.class).getOrNull();

        // "check" is used by the in-game client to validate fields before
        // the final submission. check == 0 means "actually register now".
        boolean isFinalSubmission = (check == null || check == 0);

        if (username == null || email == null || password == null) {
            ctx.status(400).result(AccountRegistrationResultCode.MISSING_REQUIRED_PARAMS.code);
            return;
        }

        Map<String, List<String>> errors = RegistrationService.validate(username, email, password);

        if (!App.server.enviromentConfig.isIngameRegistrationEnabled()) {
            errors.put(RegistrationService.FIELD_PASSWORD,
                    List.of("In-game registration is currently disabled."));
        }

        if (!errors.isEmpty()) {
            ctx.status(400).json(
                    Map.of(
                            "form_error",
                            Map.of(
                                    "user",
                                    RegistrationService.formatErrors(errors))));
            return;
        }

        if (isFinalSubmission) {
            RegistrationService.create(username, email, password, errors);

            if (!errors.isEmpty()) {
                ctx.status(400).json(Map.of("form_error",
                        Map.of("user", RegistrationService.formatErrors(errors))));
                return;
            }
        }

        ctx.status(200).result("ok");
    }

    @AllArgsConstructor
    public enum AccountRegistrationResultCode {
        OK("ok"),
        MISSING_REQUIRED_PARAMS("missing_required_params"),
        INGAME_REGISTRATION_DISABLED("ingame_registration_disabled"),
        VALIDATION_FAILED("validation_failed");

        public final String code;
    }
}
