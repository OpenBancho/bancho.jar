package com.osuserverlist.bjar.handlers.osu;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.BeatmapRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

@Host("osu.")
@Path("/web/osu-search-set.php")
@HttpMethod("GET")
public class OsuSearchSetHandler implements Handler {

    private static final DateTimeFormatter LAST_UPDATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void handle(@NotNull Context ctx) {
        Integer setId = ctx.queryParamAsClass("s", Integer.class).getOrNull();
        Integer bmId = ctx.queryParamAsClass("b", Integer.class).getOrNull();
        String checksum = ctx.queryParamAsClass("c", String.class).getOrNull();

        String username = ctx.queryParamAsClass("u", String.class).required().get();
        String passwordHash = ctx.queryParamAsClass("h", String.class).required().get();

        Server server = App.server;
        Player player = server.playerManager.getByApiIdent(username + "|" + passwordHash);

        if (player == null) {
            ctx.status(401).result("Invalid credentials.");
            return;
        }

        BeatmapEntity beatmap = null;

        if (setId != null) {
            beatmap = BeatmapRepository.findFirstBySetId(setId);
        } else if (bmId != null) {
            beatmap = BeatmapRepository.findById(bmId);
        } else if (checksum != null) {
            beatmap = BeatmapRepository.findByMd5(checksum);
        } else {
            ctx.result("");
            return;
        }

        if (beatmap == null) {
            ctx.result("");
            return;
        }

        String response = String.format(
                Locale.US,
                "%d.osz|%s|%s|%s|%d|%.1f|%s|%d|0|0|0|0|0",
                beatmap.getSetId(),
                fix(beatmap.getArtist()),
                fix(beatmap.getTitle()),
                fix(beatmap.getCreator()),
                beatmap.getStatus(),
                beatmap.getDiff(),
                fix(beatmap.getLastUpdate().format(LAST_UPDATE_FORMAT)),
                beatmap.getSetId());

        ctx.contentType("text/plain");
        ctx.result(response);
    }

    private static String fix(String s) {
        return s == null ? "" : s.replace("|", "I");
    }
}
