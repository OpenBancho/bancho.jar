package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.BssMapsetEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.osu.BeatmapSubmissionService;
import com.osuserverlist.bjar.modules.osu.BssStorage;
import com.osuserverlist.bjar.repos.BssMapsetRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Beatmap download route.
 *
 * <p>Sets that were uploaded through the Beatmap Submission System only exist
 * on this server, so they are streamed straight from disk. Everything else is
 * redirected to the configured mirror exactly like before, which keeps the
 * submission system from interfering with the regular download links.</p>
 */
@Host("osu.")
@Path("/d/{id}")
@HttpMethod("GET")
public class OsuDownloadHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(OsuDownloadHandler.class);

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String mapSetId = ctx.pathParam("id");

        boolean noVideo = mapSetId.endsWith("n");
        if (noVideo) {
            mapSetId = mapSetId.substring(0, mapSetId.length() - 1);
        }

        // Locally hosted sets take precedence over the mirror.
        if (serveLocalSet(ctx, mapSetId, noVideo)) {
            return;
        }

        String dlEndpoint = App.server.enviromentConfig.getDlEndpoint();

        if (dlEndpoint == null || dlEndpoint.isBlank()) {
            ctx.status(503).result("Download endpoint not configured.");
            return;
        }

        String queryStr = mapSetId + "?n=" + (noVideo ? 0 : 1);

        ctx.redirect(dlEndpoint + "/" + queryStr);
    }

    /**
     * Streams a submitted set from disk.
     *
     * @return {@code true} when the request was fully handled here, {@code false}
     *         when the caller should fall back to the mirror redirect.
     */
    private boolean serveLocalSet(Context ctx, String rawSetId, boolean noVideo) {
        int setId;

        try {
            setId = Integer.parseInt(rawSetId.trim());
        } catch (NumberFormatException e) {
            return false;
        }

        if (!BeatmapSubmissionService.isLocalSet(setId)) {
            return false;
        }

        // Fall back to the variant that does exist if the requested one was
        // never generated.
        boolean variant = noVideo;

        if (!BssStorage.hasOsz(setId, variant)) {
            variant = !variant;
        }

        if (!BssStorage.hasOsz(setId, variant)) {
            logger.warn("BSS: set {} is registered but no package is stored on disk", setId);

            ctx.status(404).result("This beatmap set is no longer available.");
            return true;
        }

        BssMapsetEntity mapset = BssMapsetRepository.findBySetId(setId);

        String filename = BeatmapSubmissionService.downloadFilename(mapset)
                .orElse(setId + ".osz");

        try {
            byte[] data = BssStorage.readOsz(setId, variant);

            ctx.contentType("application/x-osu-beatmap-archive")
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .result(data);
        } catch (Exception e) {
            logger.error("BSS: failed to serve the package of set {}", setId, e);

            ctx.status(500).result("Failed to read the beatmap package.");
        }

        return true;
    }
}
