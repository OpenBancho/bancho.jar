package com.osuserverlist.bjar.handlers.osu.bss;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.database.BssMapsetEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.osu.BssStatusCode;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.osu.BssStorage;
import com.osuserverlist.bjar.repos.BssMapsetRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Hands the currently stored osz2 package back to its creator so the editor
 * can build an incremental patch against it.
 */
@Host("osu.")
@Path("/web/osu-osz2-bmsubmit-getfile.php")
@HttpMethod("GET")
public class BmSubmitGetFileHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(BmSubmitGetFileHandler.class);

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String username = BssAuth.param(ctx, "u");
        String passwordHash = BssAuth.param(ctx, "h");

        UserEntity user = BssAuth.authenticate(username, passwordHash);

        if (user == null) {
            ctx.status(401).contentType("text/plain")
                    .result(BssStatusCode.INVALID_CREDENTIALS.toResponse());
            return;
        }

        int setId = BssAuth.intParam(ctx, "s", -1);

        BssMapsetEntity mapset = setId > 0 ? BssMapsetRepository.findBySetId(setId) : null;

        if (mapset == null || !BssStorage.hasOsz2(setId)) {
            ctx.status(404).contentType("text/plain")
                    .result(BssStatusCode.ERROR.toResponse());
            return;
        }

        if (!mapset.getCreatorId().equals(user.getId())) {
            ctx.status(403).contentType("text/plain")
                    .result(BssStatusCode.NOT_OWNER.toResponse());
            return;
        }

        try {
            byte[] data = BssStorage.readOsz2(setId);

            ctx.contentType("application/octet-stream")
                    .header("Content-Disposition", "attachment; filename=\"" + setId + ".osz2\"")
                    .result(data);
        } catch (Exception e) {
            logger.error("BSS: failed to serve the stored package of set {}", setId, e);

            ctx.status(500).contentType("text/plain")
                    .result(BssStatusCode.ERROR.toResponse());
        }
    }
}
