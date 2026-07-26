package com.osuserverlist.bjar.handlers.osu;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.BssMapsetEntity;
import com.osuserverlist.bjar.models.direct.DirectBeatmapSet;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.osu.RankedStatus;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.osu.BeatmapSubmissionService;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.BssMapsetRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Host("osu.")
@Path("/web/osu-search.php")
@HttpMethod("GET")
public class OsuSearchHandler implements Handler {

    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final Gson GSON = new Gson();

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        int mode = ctx.queryParamAsClass("m", Integer.class).getOrDefault(-1);
        int page = ctx.queryParamAsClass("p", Integer.class).getOrDefault(0);
        int rankedStatus = ctx.queryParamAsClass("r", Integer.class).getOrDefault(4);
        String query = ctx.queryParam("q");

        Server server = App.server;

        String username = ctx.queryParam("u");
        String passwordHash = ctx.queryParam("h");

        Player player = server.playerManager.getByApiIdent(String.format("%s|%s", username, passwordHash));

        if (player == null) {
            ctx.status(401).result("Invalid credentials.");
            return;
        }

        HttpUrl.Builder url = HttpUrl.parse(server.enviromentConfig.getSearchEndpoint())
                .newBuilder()
                .addQueryParameter("amount", "100")
                .addQueryParameter("offset", String.valueOf(page * 100));

        if (query != null
                && !query.equals("Newest")
                && !query.equals("Top+Rated")
                && !query.equals("Most+Played")) {
            url.addQueryParameter("query", query);
        }

        if (mode != -1) {
            url.addQueryParameter("mode", String.valueOf(mode));
        }

        if (rankedStatus != 4) {
            url.addQueryParameter(
                    "status",
                    String.valueOf(RankedStatus.fromOsuDirect(rankedStatus).getId()));
        }

        // Sets submitted to this server are not known to the mirror, so they
        // are prepended to the first page of every search.
        List<String> localSets = page == 0 ? buildLocalSets(query, mode) : List.of();

        Request request = new Request.Builder()
                .url(url.build())
                .get()
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                if (localSets.isEmpty()) {
                    ctx.result("-1\nFailed to retrieve data from the beatmap mirror.");
                    return;
                }

                StringBuilder localOnly = new StringBuilder();
                localOnly.append(localSets.size());

                for (String line : localSets) {
                    localOnly.append('\n').append(line);
                }

                ctx.contentType("text/plain");
                ctx.result(localOnly.toString());
                return;
            }

            String body = response.body().string();

            Type type = new TypeToken<List<DirectBeatmapSet>>() {
            }.getType();

            List<DirectBeatmapSet> result = GSON.fromJson(body, type);

            StringBuilder ret = new StringBuilder();

            int total = result.size() + localSets.size();

            ret.append(total >= 100 ? "101" : total);

            for (String line : localSets) {
                ret.append('\n').append(line);
            }

            for (DirectBeatmapSet set : result) {
                if (set.getChildrenBeatmaps() == null) {
                    continue;
                }

                set.getChildrenBeatmaps().sort(
                        Comparator.comparingDouble(m -> m.getDifficultyRating()));

                String diffs = set.getChildrenBeatmaps().stream()
                        .map(map -> String.format(
                                Locale.US,
                                "[%.2f⭐] %s {cs: %s / od: %s / ar: %s / hp: %s}@%d",
                                map.getDifficultyRating(),
                                fix(map.getDiffName()),
                                map.getCS(),
                                map.getOD(),
                                map.getAR(),
                                map.getHP(),
                                map.getMode()))
                        .collect(Collectors.joining(","));

                int hasVideo;

                Object hasVideoObj = set.getHasVideo();

                if (hasVideoObj instanceof Boolean b) {
                    hasVideo = b ? 1 : 0;
                } else {
                    hasVideo = (int) Double.parseDouble(hasVideoObj.toString());
                }

                ret.append('\n');

                ret.append(String.format(
                        Locale.US,
                        "%d.osz|%s|%s|%s|%d|10.0|%s|%d|0|%d|0|0|0|%s",
                        set.getSetID(),
                        fix(set.getArtist()),
                        fix(set.getTitle()),
                        set.getCreator(),
                        set.getRankedStatus(),
                        set.getLastUpdate(),
                        set.getSetID(),
                        hasVideo,
                        diffs));
            }

            ctx.contentType("text/plain");
            ctx.result(ret.toString());
        }
    }

    /**
     * Renders the locally submitted sets in the osu!direct listing format.
     * They keep their own id range, so their download links resolve to this
     * server while every other result still goes to the mirror.
     */
    private static List<String> buildLocalSets(String query, int mode) {
        List<String> lines = new ArrayList<>();

        if (!BeatmapSubmissionService.isEnabled()) {
            return lines;
        }

        String term = query;

        if (term == null
                || term.equals("Newest")
                || term.equals("Top+Rated")
                || term.equals("Most+Played")) {
            term = "";
        }

        try {
            for (BssMapsetEntity mapset : BssMapsetRepository.search(term, 20, 0)) {
                List<BeatmapEntity> maps = BeatmapRepository.findBySetId(mapset.getSetId());

                if (maps == null || maps.isEmpty()) {
                    continue;
                }

                List<BeatmapEntity> sorted = new ArrayList<>(maps);

                if (mode != -1) {
                    sorted.removeIf(map -> map.getMode() != null && map.getMode() != mode);
                }

                if (sorted.isEmpty()) {
                    continue;
                }

                sorted.sort(Comparator.comparingDouble(
                        map -> map.getDiff() == null ? 0d : map.getDiff()));

                String diffs = sorted.stream()
                        .map(map -> String.format(
                                Locale.US,
                                "[%.2f⭐] %s {cs: %s / od: %s / ar: %s / hp: %s}@%d",
                                map.getDiff() == null ? 0f : map.getDiff(),
                                fix(map.getVersion()),
                                map.getCs(),
                                map.getOd(),
                                map.getAr(),
                                map.getHp(),
                                map.getMode() == null ? 0 : map.getMode()))
                        .collect(Collectors.joining(","));

                lines.add(String.format(
                        Locale.US,
                        "%d.osz|%s|%s|%s|%d|10.0|%s|%d|0|%d|0|0|0|%s",
                        mapset.getSetId(),
                        fix(mapset.getArtist()),
                        fix(mapset.getTitle()),
                        fix(mapset.getCreatorName()),
                        mapset.getStatus(),
                        mapset.getLastUpdate() == null ? "" : mapset.getLastUpdate().toString(),
                        mapset.getSetId(),
                        Boolean.TRUE.equals(mapset.getHasVideo()) ? 1 : 0,
                        diffs));
            }
        } catch (Exception e) {
            // A broken local listing must never break osu!direct itself.
            return List.of();
        }

        return lines;
    }

    private static String fix(String s) {
        return s == null ? "" : s.replace("|", "I");
    }
}