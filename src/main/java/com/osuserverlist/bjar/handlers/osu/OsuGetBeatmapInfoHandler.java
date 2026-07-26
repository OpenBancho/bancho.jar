package com.osuserverlist.bjar.handlers.osu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.RankedStatus;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.BeatmapRepository;

import io.ebean.DB;
import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * Tells the client what the server knows about the maps sitting in the player's
 * Songs folder.
 *
 * <p>This is what paints the ranked status next to each map in the song select
 * screen, and the little grade letters on the right. The client sends the file
 * names it has locally and expects one line back per map the server recognises;
 * maps it does not know are simply left out of the answer.</p>
 *
 * <p>Line format: {@code index|map_id|set_id|md5|status|grade0|grade1|grade2|grade3},
 * with one grade per vanilla mode and {@code N} where the player has no pass.</p>
 */
@Host("osu.")
@Path("/web/osu-getbeatmapinfo.php")
@HttpMethod("POST")
public class OsuGetBeatmapInfoHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(OsuGetBeatmapInfoHandler.class);

    /**
     * Lowest status the client can display.
     *
     * <p>The {@code maps} table already stores the osu!api numbering, so the value
     * is sent as it is. Only the internal placeholder below graveyard has no
     * client-side meaning.</p>
     */
    private static final int MIN_KNOWN_STATUS = RankedStatus.Graveyard.getId();

    /** A score that currently counts as the player's best on that map. */
    private static final int STATUS_BEST = 2;

    private static final int VANILLA_MODES = 4;

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        Player player = OsuWebAuth.authenticate(ctx.queryParam("u"), ctx.queryParam("h"));

        if (player == null) {
            ctx.status(401).result("");
            return;
        }

        Request request = parseBody(ctx.body());

        if (request == null) {
            ctx.status(400).result("");
            return;
        }

        List<String> lines = new ArrayList<>();

        for (int index = 0; index < request.filenames.size(); index++) {
            BeatmapEntity beatmap = BeatmapRepository.findByFilename(request.filenames.get(index));

            String line = formatBeatmap(index, beatmap, player.getId());

            if (line != null) {
                lines.add(line);
            }
        }

        // Rarely used by the client, but cheap to answer correctly.
        for (int i = 0; i < request.ids.size(); i++) {
            BeatmapEntity beatmap = BeatmapRepository.findById(request.ids.get(i));

            String line = formatBeatmap(request.filenames.size() + i, beatmap, player.getId());

            if (line != null) {
                lines.add(line);
            }
        }

        logger.debug("{} requested info for {} maps", player.getUsername(),
                request.filenames.size() + request.ids.size());

        ctx.status(200).result(String.join("\n", lines));
    }

    private String formatBeatmap(int index, BeatmapEntity beatmap, int userId) {
        if (beatmap == null || beatmap.getStatus() == null) {
            return null;
        }

        int osuApiStatus = beatmap.getStatus();

        // Unsubmitted maps have no client-side equivalent.
        if (osuApiStatus < MIN_KNOWN_STATUS) {
            return null;
        }

        return String.join("|",
                String.valueOf(index),
                String.valueOf(beatmap.getId()),
                String.valueOf(beatmap.getSetId()),
                beatmap.getMd5(),
                String.valueOf(osuApiStatus),
                String.join("|", gradesFor(beatmap.getMd5(), userId)));
    }

    /** The player's best grade in each vanilla mode, or N where there is none. */
    private String[] gradesFor(String mapMd5, int userId) {
        String[] grades = { "N", "N", "N", "N" };

        List<ScoreEntity> scores = DB.find(ScoreEntity.class)
                .where()
                .eq("mapMd5", mapMd5)
                .eq("user.id", userId)
                .eq("status", STATUS_BEST)
                .lt("mode", VANILLA_MODES)
                .findList();

        for (ScoreEntity score : scores) {
            Integer mode = score.getMode();

            if (mode == null || mode < 0 || mode >= VANILLA_MODES) {
                continue;
            }

            if (score.getGrade() != null) {
                grades[mode] = score.getGrade();
            }
        }

        return grades;
    }

    /**
     * The client posts a small JSON document. Older builds post the same fields as
     * a form instead, so both shapes are accepted.
     */
    private Request parseBody(String body) {
        Request request = new Request();

        if (body == null || body.isBlank()) {
            return request;
        }

        try {
            JsonElement parsed = JsonParser.parseString(body);

            if (!parsed.isJsonObject()) {
                return null;
            }

            JsonObject object = parsed.getAsJsonObject();

            collectStrings(object, "Filenames", request.filenames);
            collectLongs(object, "Ids", request.ids);

            return request;
        } catch (Exception e) {
            return parseFormBody(body, request);
        }
    }

    private Request parseFormBody(String body, Request request) {
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');

            if (separator <= 0) {
                continue;
            }

            String key = pair.substring(0, separator);
            String value = OsuWebAuth.decode(pair.substring(separator + 1));

            if (value == null || value.isBlank()) {
                continue;
            }

            if (key.startsWith("filenames")) {
                request.filenames.add(value);
            } else if (key.startsWith("ids")) {
                try {
                    request.ids.add(Long.parseLong(value.trim()));
                } catch (NumberFormatException ignored) {
                    // A malformed id is not worth failing the whole request over.
                }
            }
        }

        return request;
    }

    private void collectStrings(JsonObject object, String field, List<String> target) {
        if (!object.has(field) || !object.get(field).isJsonArray()) {
            return;
        }

        JsonArray array = object.getAsJsonArray(field);

        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive()) {
                target.add(element.getAsString());
            }
        }
    }

    private void collectLongs(JsonObject object, String field, List<Long> target) {
        if (!object.has(field) || !object.get(field).isJsonArray()) {
            return;
        }

        JsonArray array = object.getAsJsonArray(field);

        for (JsonElement element : array) {
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }

            try {
                target.add(element.getAsLong());
            } catch (NumberFormatException ignored) {
                // Same reasoning as above.
            }
        }
    }

    private static final class Request {
        private final List<String> filenames = new ArrayList<>();
        private final List<Long> ids = new ArrayList<>();
    }
}
