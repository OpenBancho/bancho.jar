package com.osuserverlist.bjar.handlers.osu.bss;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.database.BssMapsetEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.osu.BssStatusCode;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.BssMapsetRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Returns the stored description of a submitted set so the editor can pre-fill
 * the submission dialog.
 *
 * <p>Response layout, using {@code \u0003} as the separator the client expects:</p>
 * <pre>
 * {topicId}\n\u0003{subject}\u0003{message}
 * </pre>
 */
@Host("osu.")
@Path("/web/osu-get-beatmap-topic.php")
@HttpMethod("GET")
public class GetBeatmapTopicHandler implements Handler {

    private static final char SEPARATOR = '\u0003';

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        ctx.contentType("text/plain");

        String username = BssAuth.param(ctx, "u");
        String passwordHash = BssAuth.param(ctx, "h");

        UserEntity user = BssAuth.authenticate(username, passwordHash);

        if (user == null) {
            ctx.result(BssStatusCode.INVALID_CREDENTIALS.toResponse());
            return;
        }

        int setId = BssAuth.intParam(ctx, "s", BssAuth.intParam(ctx, "b", -1));

        BssMapsetEntity mapset = setId > 0 ? BssMapsetRepository.findBySetId(setId) : null;

        if (mapset == null) {
            ctx.result(BssStatusCode.ERROR.toResponse());
            return;
        }

        if (!mapset.getCreatorId().equals(user.getId())) {
            ctx.result(BssStatusCode.NOT_OWNER.toResponse());
            return;
        }

        int topicId = mapset.getTopicId() == null ? 0 : mapset.getTopicId();

        String subject = mapset.getSubject() == null ? "" : mapset.getSubject();
        String message = mapset.getMessage() == null ? "" : mapset.getMessage();

        ctx.result(topicId + "\n" + SEPARATOR + subject + SEPARATOR + message);
    }
}
