package com.osuserverlist.bjar.handlers.osu;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.models.osu.RankedStatus;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.RatingRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * The 1-10 star vote shown after a map ends.
 *
 * <p>The client calls this twice: once without a vote to ask whether it may show
 * the dialog, then again carrying the chosen value. Both calls land here, and the
 * difference is only whether {@code v} is present.</p>
 *
 * <p>Only ranked and better maps can be rated, matching the client, which hides
 * the dialog for everything else.</p>
 */
@Host("osu.")
@Path("/web/osu-rate.php")
@HttpMethod("GET")
public class OsuRateHandler implements Handler {

    /**
     * Anything below this is pending, WIP or graveyarded and cannot be voted on.
     *
     * <p>Taken from {@link RankedStatus} rather than written as a literal: the
     * numbering here is the osu!api one, where ranked is 1, and hardcoding a value
     * from another server's scale silently rejects every ranked map.</p>
     */
    private static final int MIN_RATEABLE_STATUS = RankedStatus.Ranked.getId();

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        Player player = OsuWebAuth.authenticate(ctx.queryParam("u"), ctx.queryParam("p"));

        if (player == null) {
            ctx.status(401).result("auth fail");
            return;
        }

        String mapMd5 = ctx.queryParam("c");

        if (mapMd5 == null || mapMd5.length() != 32) {
            ctx.status(400).result("no exist");
            return;
        }

        BeatmapEntity beatmap = BeatmapRepository.findByMd5(mapMd5);

        if (beatmap == null) {
            ctx.status(200).result("no exist");
            return;
        }

        if (beatmap.getStatus() == null || beatmap.getStatus() < MIN_RATEABLE_STATUS) {
            ctx.status(200).result("not ranked");
            return;
        }

        Integer rating = ctx.queryParamAsClass("v", Integer.class).getOrNull();
        boolean alreadyRated = RatingRepository.hasRated(player.getId(), mapMd5);

        if (rating == null) {
            // First call: the client is only asking whether the dialog may open.
            if (!alreadyRated) {
                ctx.status(200).result("ok");
                return;
            }

            ctx.status(200).result(formatAverage(mapMd5));
            return;
        }

        if (rating < 1 || rating > 10) {
            ctx.status(400).result("no exist");
            return;
        }

        // A second vote never overwrites the first one.
        if (!alreadyRated) {
            RatingRepository.save(player.getId(), mapMd5, rating);
        }

        ctx.status(200).result(formatAverage(mapMd5));
    }

    private String formatAverage(String mapMd5) {
        return String.format(java.util.Locale.ROOT, "alreadyvoted%n%.2f", RatingRepository.averageForMap(mapMd5))
                .replace(System.lineSeparator(), "\n");
    }
}
