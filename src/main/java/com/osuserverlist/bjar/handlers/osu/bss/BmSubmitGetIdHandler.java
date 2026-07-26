package com.osuserverlist.bjar.handlers.osu.bss;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.osu.BssStatusCode;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.osu.BeatmapSubmissionService;
import com.osuserverlist.bjar.modules.osu.BeatmapSubmissionService.BssException;
import com.osuserverlist.bjar.modules.osu.BeatmapSubmissionService.PreparedSubmission;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * First step of a beatmap submission: the editor asks the server which set id
 * and which difficulty ids it should use, and whether it has to upload the
 * whole package or only a patch.
 *
 * <p>Response layout on success:</p>
 * <pre>
 * 0                 result code
 * 1234              beatmap set id
 * 12,13,14          beatmap ids, in the order the client sent them
 * 1                 1 = full submit, 0 = incremental (patch) submit
 * 7                 remaining submission slots
 * </pre>
 */
@Host("osu.")
@Path("/web/osu-osz2-bmsubmit-getid.php")
@HttpMethod("GET")
public class BmSubmitGetIdHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(BmSubmitGetIdHandler.class);

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

        int requestedSetId = BssAuth.intParam(ctx, "s", -1);
        List<Long> clientBeatmapIds = BssAuth.idList(BssAuth.param(ctx, "b"));

        if (clientBeatmapIds.isEmpty()) {
            // Older clients omit "b" when the set has a single difficulty.
            clientBeatmapIds = List.of(0L);
        }

        try {
            PreparedSubmission submission = BeatmapSubmissionService.prepare(
                    user, requestedSetId, clientBeatmapIds);

            String response = String.join("\n",
                    String.valueOf(BssStatusCode.SUCCESS.getId()),
                    String.valueOf(submission.mapset().getSetId()),
                    BssAuth.join(submission.beatmapIds()),
                    submission.fullSubmit() ? "1" : "0",
                    String.valueOf(submission.remainingQuota()));

            logger.info("BSS: user {} reserved set {} ({} difficulties, full submit: {})",
                    user.getName(),
                    submission.mapset().getSetId(),
                    submission.beatmapIds().size(),
                    submission.fullSubmit());

            ctx.result(response);
        } catch (BssException e) {
            ctx.result(e.getCode().toResponse());
        } catch (Exception e) {
            logger.error("BSS: getid failed for user {}", user.getName(), e);
            ctx.result(BssStatusCode.ERROR.toResponse());
        }
    }
}
