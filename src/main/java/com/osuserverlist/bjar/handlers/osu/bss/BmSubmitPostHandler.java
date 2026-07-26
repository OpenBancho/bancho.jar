package com.osuserverlist.bjar.handlers.osu.bss;

import java.time.LocalDateTime;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Final step of a beatmap submission: stores the description the creator typed
 * into the editor and reports back the id of the associated discussion thread.
 *
 * <p>bancho.jar has no forum, so the set id doubles as the thread id. That is
 * enough for the client, which only uses the value to round-trip the
 * description through {@code osu-get-beatmap-topic.php}.</p>
 */
@Host("osu.")
@Path("/web/osu-osz2-bmsubmit-post.php")
@HttpMethod("POST")
public class BmSubmitPostHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(BmSubmitPostHandler.class);

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

        int setId = BssAuth.intParam(ctx, "b", BssAuth.intParam(ctx, "s", -1));

        BssMapsetEntity mapset = setId > 0 ? BssMapsetRepository.findBySetId(setId) : null;

        if (mapset == null) {
            ctx.result(BssStatusCode.ERROR.toResponse());
            return;
        }

        if (!mapset.getCreatorId().equals(user.getId())) {
            ctx.result(BssStatusCode.NOT_OWNER.toResponse());
            return;
        }

        String subject = BssAuth.param(ctx, "subject");
        String message = BssAuth.param(ctx, "message");

        if (subject != null) {
            mapset.setSubject(subject.length() > 128 ? subject.substring(0, 128) : subject);
        }

        if (message != null) {
            mapset.setMessage(message);
        }

        if (mapset.getTopicId() == null || mapset.getTopicId() <= 0) {
            mapset.setTopicId(mapset.getSetId());
        }

        mapset.setLastUpdate(LocalDateTime.now());

        BssMapsetRepository.save(mapset);

        logger.info("BSS: user {} updated the description of set {}", user.getName(), setId);

        ctx.result(String.valueOf(mapset.getTopicId()));
    }
}
