package com.osuserverlist.bjar.modules.account;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.StatsId;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.repos.StatsRepository;
import com.osuserverlist.bjar.repos.UserRepository;

/**
 * Account creation, in one place.
 *
 * <p>There are two ways into the server - the in-game registration form of the
 * osu! client and the registration form of the website - and they have to agree
 * on every rule, or an account made one way could be one the other refuses to
 * let in. The rules, the password hashing and the bootstrapping of a new player
 * therefore live here, and both handlers only translate between their own wire
 * format and this class.
 *
 * <p>The stored hash is bcrypt over the md5 of the password, which is not a
 * choice: the osu! client only ever sends the md5, so that is what the login
 * path has to be able to check.
 */
public final class RegistrationService {

    private static final Logger logger = LoggerFactory.getLogger("Registration");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Letters, digits, spaces, underscores, brackets and dashes, like osu!. */
    public static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\w \\[\\]-]{2,15}$");

    public static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public static final int MIN_USERNAME_LENGTH = 2;
    public static final int MAX_USERNAME_LENGTH = 15;
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 32;
    public static final int MIN_UNIQUE_PASSWORD_CHARS = 3;

    /** RFC 5321 limit, which is also the width of the column. */
    public static final int MAX_EMAIL_LENGTH = 254;

    /** Ids 1 and 2 belong to the bot, so the first human is 3 - and the owner. */
    private static final int FIRST_REAL_USER_ID = 3;

    /** Every mode a player can have stats in, including the relax/autopilot ones. */
    private static final int[] GAME_MODES_TO_SEED = { 0, 1, 2, 3, 4, 5, 6, 8 };

    private static final int BCRYPT_COST = 11;

    private RegistrationService() {
    }

    /**
     * Field name used for username errors. The in-game form expects exactly
     * these three names, so they are constants rather than free text.
     */
    public static final String FIELD_USERNAME = "username";
    public static final String FIELD_EMAIL = "user_email";
    public static final String FIELD_PASSWORD = "password";

    /**
     * Checks the three fields and returns the problems found, keyed by field.
     * An empty map means the values are acceptable.
     */
    public static Map<String, List<String>> validate(String username, String email, String password) {
        Map<String, List<String>> errors = new HashMap<>();

        validateUsername(username, errors);
        validatePassword(password, errors);
        validateEmail(email, errors);

        return errors;
    }

    private static void validateUsername(String username, Map<String, List<String>> errors) {
        String value = username == null ? "" : username;

        if (!USERNAME_PATTERN.matcher(value).matches()) {
            addError(errors, FIELD_USERNAME, "Must be "
                    + MIN_USERNAME_LENGTH + "-" + MAX_USERNAME_LENGTH + " characters in length.");
        }

        // osu! stores a "safe name" with spaces turned into underscores, so a
        // name containing both would collide with another player's.
        if (value.contains("_") && value.contains(" ")) {
            addError(errors, FIELD_USERNAME, "May contain '_' or ' ', but not both.");
        }
    }

    private static void validatePassword(String password, Map<String, List<String>> errors) {
        String value = password == null ? "" : password;

        if (value.length() < MIN_PASSWORD_LENGTH || value.length() > MAX_PASSWORD_LENGTH) {
            addError(errors, FIELD_PASSWORD, "Must be "
                    + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH + " characters in length.");
        }

        if (value.chars().distinct().count() <= MIN_UNIQUE_PASSWORD_CHARS) {
            addError(errors, FIELD_PASSWORD, "Must have more than "
                    + MIN_UNIQUE_PASSWORD_CHARS + " unique characters.");
        }
    }

    private static void validateEmail(String email, Map<String, List<String>> errors) {
        String trimmed = email == null ? "" : email.trim();

        if (trimmed.isEmpty()) {
            addError(errors, FIELD_EMAIL, "Must not be empty.");
            return;
        }

        if (trimmed.length() > MAX_EMAIL_LENGTH) {
            addError(errors, FIELD_EMAIL, "Must be at most " + MAX_EMAIL_LENGTH + " characters in length.");
        }

        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            addError(errors, FIELD_EMAIL, "Must be a valid email address.");
        }
    }

    /**
     * Creates the account and seeds its stats rows.
     *
     * <p>The caller must have validated the fields and, on a public endpoint,
     * verified the captcha first: nothing is checked again here except that the
     * name and the email are still free.
     *
     * @param errors filled with the reason when the name or email is taken
     * @return the new user, or null when nothing was created
     */
    public static UserEntity create(String username, String email, String password,
            Map<String, List<String>> errors) {

        String name = username.trim();
        String address = email.trim();

        if (rejectIfConflicting(name, address, errors)) {
            return null;
        }

        UserEntity user = UserRepository.create(name, safeNameOf(name), address, hashPassword(password));

        if (user == null || user.getId() == null) {
            logger.error("Failed to retrieve last insert ID for user: {}", name);
            addError(errors, "database", "An error occurred while creating the account. Please try again.");
            return null;
        }

        bootstrap(user);

        logger.info("Registered new user: <{}>({})", name, user.getId());

        return user;
    }

    /** The lower case, underscore separated form osu! looks accounts up by. */
    public static String safeNameOf(String username) {
        return username.toLowerCase().replace(" ", "_");
    }

    /** True when the name or the email already belongs to somebody. */
    public static boolean rejectIfConflicting(String username, String email,
            Map<String, List<String>> errors) {

        UserEntity existing = UserRepository.findByNameOrEmail(username, email);

        if (existing == null) {
            return false;
        }

        if (existing.getName().equalsIgnoreCase(username)) {
            addError(errors, FIELD_USERNAME, "Username is already taken.");
        }

        if (existing.getEmail().equalsIgnoreCase(email)) {
            addError(errors, FIELD_EMAIL, "Email is already registered.");
        }

        return true;
    }

    /** bcrypt over the md5 of the password, the way the game client needs it. */
    public static String hashPassword(String password) {
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

    /**
     * Gives the very first account every privilege - somebody has to be able to
     * run the server - and creates the stats row of every mode, so a profile
     * exists before the first score is submitted.
     */
    private static void bootstrap(UserEntity user) {
        if (user.getId() == FIRST_REAL_USER_ID) {
            user.setPrivileges(Privileges.allPrivsToInt());
            UserRepository.save(user);
        }

        for (int mode : GAME_MODES_TO_SEED) {
            StatsEntity stats = new StatsEntity();
            stats.setId(new StatsId(user.getId(), mode));
            StatsRepository.save(stats);
        }
    }

    public static void addError(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, key -> new ArrayList<>()).add(message);
    }

    /**
     * Collapses the messages of every field into one string each, which is the
     * shape the in-game form reads.
     */
    public static Map<String, List<String>> formatErrors(Map<String, List<String>> errors) {
        Map<String, List<String>> formatted = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : errors.entrySet()) {
            formatted.put(entry.getKey(), List.of(String.join("\n", entry.getValue())));
        }

        return formatted;
    }

    /** The first message of the first field, for endpoints with one error line. */
    public static String firstMessage(Map<String, List<String>> errors) {
        for (String field : List.of(FIELD_USERNAME, FIELD_EMAIL, FIELD_PASSWORD)) {
            List<String> messages = errors.get(field);

            if (messages != null && !messages.isEmpty()) {
                return messages.get(0);
            }
        }

        for (List<String> messages : errors.values()) {
            if (messages != null && !messages.isEmpty()) {
                return messages.get(0);
            }
        }

        return "The account could not be created.";
    }
}
