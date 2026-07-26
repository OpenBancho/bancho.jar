package com.osuserverlist.bjar.handlers.osu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.StatsId;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.StatsRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import lombok.AllArgsConstructor;

@Host("osu.")
@Path("/users")
@HttpMethod("POST")
public class IngameRegistrationHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(IngameRegistrationHandler.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\w \\[\\]-]{2,15}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final int MIN_USERNAME_LENGTH = 2;
    private static final int MAX_USERNAME_LENGTH = 15;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 32;
    private static final int MIN_UNIQUE_PASSWORD_CHARS = 3;
    private static final int MAX_EMAIL_LENGTH = 254; // RFC 5321 limit

    private static final int FIRST_REAL_USER_ID = 3;
    private static final int[] GAME_MODES_TO_SEED = { 0, 1, 2, 3, 4, 5, 6, 8 };
    private static final int BCRYPT_COST = 11;

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

        Map<String, List<String>> errors = new HashMap<>();
        validateUsername(username, errors);
        validatePassword(password, errors);
        validateEmail(email, errors);

        if (!App.server.enviromentConfig.isIngameRegistrationEnabled()) {
            errors.put("password", List.of("In-game registration is currently disabled."));
        }

        if (!errors.isEmpty()) {
            ctx.status(400).json(
                    Map.of(
                            "form_error",
                            Map.of(
                                    "user",
                                    formatErrors(errors))));
            return;
        }

        if (isFinalSubmission) {
            String bcryptHash = hashPassword(password);

            registerUser(username, email, bcryptHash, errors);

            if (!errors.isEmpty()) {
                ctx.status(400).json(Map.of("form_error", Map.of("user", formatErrors(errors))));
                return;
            }
        }

        ctx.status(200).result("ok");
    }

    private void validateUsername(String username, Map<String, List<String>> errors) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            addError(errors, "username", "Must be "
                    + MIN_USERNAME_LENGTH + "-" + MAX_USERNAME_LENGTH + " characters in length.");
        }

        if (username.contains("_") && username.contains(" ")) {
            addError(errors, "username", "May contain '_' or ' ', but not both.");
        }
    }

    private void validatePassword(String password, Map<String, List<String>> errors) {
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            addError(errors, "password", "Must be "
                    + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH + " characters in length.");
        }

        if (password.chars().distinct().count() <= MIN_UNIQUE_PASSWORD_CHARS) {
            addError(errors, "password", "Must have more than "
                    + MIN_UNIQUE_PASSWORD_CHARS + " unique characters.");
        }
    }

    private void validateEmail(String email, Map<String, List<String>> errors) {
        String trimmed = email.trim();

        if (trimmed.isEmpty()) {
            addError(errors, "user_email", "Must not be empty.");
            return;
        }

        if (trimmed.length() > MAX_EMAIL_LENGTH) {
            addError(errors, "user_email", "Must be at most " + MAX_EMAIL_LENGTH + " characters in length.");
        }

        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            addError(errors, "user_email", "Must be a valid email address.");
        }
    }

    private void addError(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, k -> new ArrayList<>()).add(message);
    }

    private String hashPassword(String password) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            // MD5 is a guaranteed JDK algorithm; this should never happen.
            throw new IllegalStateException("MD5 algorithm not found", e);
        }

        byte[] md5Bytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
        String md5Hex = HexFormat.of().formatHex(md5Bytes);

        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);

        return OpenBSDBCrypt.generate(md5Hex.toCharArray(), salt, BCRYPT_COST);
    }

    private void registerUser(String username, String email, String bcryptHash,
            Map<String, List<String>> errors) {

            if (rejectIfConflicting(username, email, errors)) {
                return;
            }

            UserEntity userEntity = new UserEntity();
            userEntity.setName(username);
            userEntity.setSafeName(username.toLowerCase().replaceAll(" ", "_"));
            userEntity.setEmail(email);
            userEntity.setPasswordHash(bcryptHash);
            userEntity.setCreationTime((int)(System.currentTimeMillis() / 1000));
            UserRepository.save(userEntity);

            if (userEntity.getId() == null) {
                logger.error("Failed to retrieve last insert ID for user: {}", username);
                addError(errors, "database", "An error occurred while creating the account. Please try again.");
                return;
            }

            bootstrapNewUser(userEntity);

            logger.info("Registered new user: <{}>({})", username, userEntity.getId());

    }

    /**
     * Returns true (and populates errors) if the username or email is already
     * taken.
     */
    private boolean rejectIfConflicting(String username, String email, Map<String, List<String>> errors) {
        UserEntity existingUser = UserRepository.findByNameOrEmail(username, email);
        if (existingUser == null) {
            return false;
        }

        if (existingUser.getName().equalsIgnoreCase(username)) {
            addError(errors, "username", "Username is already taken.");
        }
        if (existingUser.getEmail().equalsIgnoreCase(email)) {
            addError(errors, "user_email", "Email is already registered.");
        }
        return true;
    }

    private void bootstrapNewUser(UserEntity userEntity) {
        if (userEntity.getId() == FIRST_REAL_USER_ID) {
            userEntity.setPrivileges(Privileges.allPrivsToInt());
            UserRepository.save(userEntity);
        }

        for (int mode : GAME_MODES_TO_SEED) {
            StatsEntity stats = new StatsEntity();
            stats.setId(new StatsId(userEntity.getId(), mode));
            StatsRepository.save(stats);
        }
    }

    private Map<String, List<String>> formatErrors(Map<String, List<String>> errors) {
        Map<String, List<String>> formatted = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : errors.entrySet()) {
            formatted.put(entry.getKey(), List.of(String.join("\n", entry.getValue())));
        }

        return formatted;
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
