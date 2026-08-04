package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.SendMessagePacket;
import com.osuserverlist.bjar.repos.LogRepository;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Player reports sent from inside the game client.
 *
 * <p>The client posts the account being reported, a reason picked from its
 * dropdown and an optional comment. The report is written to the staff action
 * log so it survives a restart, and every member of staff who is online is told
 * about it straight away by the bot.</p>
 *
 * <p>The client expects an empty 200; anything else shows an error dialog.</p>
 */
@Host("osu.")
@Path("/web/osu-report.php")
@HttpMethod("POST")
public class OsuReportHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(OsuReportHandler.class);

    /** Action name used in the staff log. */
    public static final String LOG_ACTION = "report";

    private static final int MAX_REASON_LENGTH = 512;

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        process(ctx);
    }

    /** Shared by every path the client may use for reports. */
    public static void process(Context ctx) {
        Player reporter = OsuWebAuth.authenticate(
                first(ctx, "u", "username"),
                first(ctx, "h", "p", "password"));

        if (reporter == null) {
            ctx.status(401).result("");
            return;
        }

        UserEntity target = resolveTarget(ctx);

        if (target == null) {
            logger.warn("{} reported an account that could not be resolved", reporter.getUsername());
            ctx.status(200).result("");
            return;
        }

        if (target.getId() == reporter.getId()) {
            ctx.status(200).result("");
            return;
        }

        String reason = buildReason(ctx);

        try {
            LogRepository.write(reporter.getId(), target.getId(), LOG_ACTION, reason);
        } catch (Exception e) {
            // A failed log must never turn into a client-side error dialog.
            logger.error("Failed to store report from {} against {}",
                    reporter.getUsername(), target.getName(), e);
        }

        logger.info("{} reported {}: {}", reporter.getUsername(), target.getName(), reason);

        notifyStaff(reporter, target, reason);

        ctx.status(200).result("");
    }

    /** The client identifies the target by id on modern builds and by name on old ones. */
    private static UserEntity resolveTarget(Context ctx) {
        String rawId = first(ctx, "id", "target_id", "userid");

        if (rawId != null) {
            try {
                UserEntity byId = UserRepository.findById(Integer.parseInt(rawId.trim()));

                if (byId != null) {
                    return byId;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the name lookup below.
            }
        }

        String name = first(ctx, "target", "u2", "name");

        if (name == null) {
            return null;
        }

        return UserRepository.findByName(OsuWebAuth.decode(name).trim());
    }

    private static String buildReason(Context ctx) {
        String reason = first(ctx, "r", "reason");
        String comment = first(ctx, "comment", "c", "info");
        String mode = first(ctx, "m", "mode");

        StringBuilder text = new StringBuilder();

        text.append(reason == null ? "no reason given" : OsuWebAuth.decode(reason).trim());

        if (comment != null && !comment.isBlank()) {
            text.append(" — ").append(OsuWebAuth.decode(comment).trim());
        }

        if (mode != null && !mode.isBlank()) {
            text.append(" (mode ").append(mode.trim()).append(')');
        }

        String result = text.toString();

        return result.length() > MAX_REASON_LENGTH
                ? result.substring(0, MAX_REASON_LENGTH)
                : result;
    }

    /** Tells every member of staff who is currently online. */
    private static void notifyStaff(Player reporter, UserEntity target, String reason) {
        String message = String.format("[REPORT] %s reported %s: %s",
                reporter.getUsername(), target.getName(), reason);

        Player bot = App.server.botPlayer;

        if (bot == null) {
            return;
        }

        for (Player staff : App.server.playerManager.getAllSessions()) {
            if (staff.isBot()) {
                continue;
            }

            if (!Privileges.hasAny(staff.getServerPrivileges(),
                    Privileges.MODERATOR, Privileges.ADMINISTRATOR, Privileges.DEVELOPER)) {
                continue;
            }

            staff.sendPacket(new SendMessagePacket(
                    bot.getUsername(),
                    message,
                    bot.getUsername(),
                    bot.getId()));
        }
    }

    /** First non-blank value among the given form or query parameter names. */
    private static String first(Context ctx, String... names) {
        for (String name : names) {
            String value = ctx.formParam(name);

            if (value == null || value.isBlank()) {
                value = ctx.queryParam(name);
            }

            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }
}
