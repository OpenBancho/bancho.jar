package com.osuserverlist.bjar.handlers.osu.bss;

import java.io.InputStream;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.BssMapsetEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.osu.BssStatusCode;
import com.osuserverlist.bjar.models.osu.RankedStatus;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.osu.BeatmapSubmissionService;
import com.osuserverlist.bjar.modules.osu.BeatmapSubmissionService.BssException;
import com.osuserverlist.bjar.repos.BssMapsetRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.UploadedFile;

/**
 * Second step of a beatmap submission: receives the osz2 package itself.
 *
 * <p>When {@code t=1} the body holds the complete package. Otherwise it holds
 * a bsdiff patch that has to be applied on top of the revision the server
 * already stores, which is what keeps updates of large sets cheap.</p>
 */
@Host("osu.")
@Path("/web/osu-osz2-bmsubmit-upload.php")
@HttpMethod("POST")
public class BmSubmitUploadHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(BmSubmitUploadHandler.class);

    private static final List<String> FILE_FIELDS = List.of("osz2", "file", "beatmap");

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

        if (!BeatmapSubmissionService.isEnabled()) {
            ctx.result(BssStatusCode.DISABLED.toResponse());
            return;
        }

        int setId = BssAuth.intParam(ctx, "s", -1);

        BssMapsetEntity mapset = setId > 0 ? BssMapsetRepository.findBySetId(setId) : null;

        if (mapset == null) {
            // The client never called getid, or the reservation disappeared.
            ctx.result(BssStatusCode.ERROR.toResponse());
            return;
        }

        if (!mapset.getCreatorId().equals(user.getId())) {
            ctx.result(BssStatusCode.NOT_OWNER.toResponse());
            return;
        }

        if (mapset.getStatus() >= RankedStatus.Ranked.getId()) {
            ctx.result(BssStatusCode.ALREADY_RANKED.toResponse());
            return;
        }

        byte[] payload = readPayload(ctx);

        if (payload == null || payload.length == 0) {
            ctx.result(BssStatusCode.INVALID_PACKAGE.toResponse());
            return;
        }

        long maxBytes = Math.max(1L, App.server.enviromentConfig.getBssMaxUploadSizeMb()) * 1024L * 1024L;

        if (payload.length > maxBytes) {
            logger.warn("BSS: user {} tried to upload {} bytes for set {} (limit {})",
                    user.getName(), payload.length, setId, maxBytes);

            ctx.result(BssStatusCode.INVALID_PACKAGE.toResponse());
            return;
        }

        boolean fullSubmit = BssAuth.intParam(ctx, "t", 1) != 0;

        try {
            byte[] osz2 = payload;

            if (!fullSubmit) {
                osz2 = BeatmapSubmissionService.applyPatch(setId, payload);
            }

            BeatmapSubmissionService.ingest(mapset, user, osz2);

            ctx.result(BssStatusCode.SUCCESS.toResponse());
        } catch (BssException e) {
            ctx.result(e.getCode().toResponse());
        } catch (Exception e) {
            logger.error("BSS: upload failed for set {}", setId, e);
            ctx.result(BssStatusCode.ERROR.toResponse());
        }
    }

    /**
     * The editor posts the package as a multipart file, but some builds send
     * it as a raw body instead.
     */
    private byte[] readPayload(Context ctx) {
        for (String field : FILE_FIELDS) {
            UploadedFile file = ctx.uploadedFile(field);

            if (file != null) {
                return readFully(file);
            }
        }

        List<UploadedFile> files = ctx.uploadedFiles();

        if (files != null && !files.isEmpty()) {
            return readFully(files.get(0));
        }

        return ctx.bodyAsBytes();
    }

    private byte[] readFully(UploadedFile file) {
        try (InputStream stream = file.content()) {
            return stream.readAllBytes();
        } catch (Exception e) {
            logger.error("BSS: failed to read the uploaded package", e);
            return null;
        }
    }
}
