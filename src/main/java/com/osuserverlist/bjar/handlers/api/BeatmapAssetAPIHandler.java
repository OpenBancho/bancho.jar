package com.osuserverlist.bjar.handlers.api;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.render.BestReplayService;
import com.osuserverlist.bjar.modules.render.OsuMap;
import com.osuserverlist.bjar.repos.BeatmapRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/beatmap_asset?id=|md5=&amp;kind=osu|audio|background — one file of a beatmap, for the live spectator
 * on the website, which draws the play in the browser and so needs the map itself rather than a rendered video.
 *
 * <p>Only beatmaps the server knows are served, and only these three files of them: the {@code .osu} the id names (its
 * md5 checked, the same lookup the replay renderer does), and the song and the background that .osu refers to, read
 * from the set archive. The site keeps a copy of each, so this is asked once per map.
 */
@Host({"api.", "server", ""})
@com.osuserverlist.bjar.modules.main.WebEngine.Path("/api/v1/beatmap_asset")
@HttpMethod("GET")
public class BeatmapAssetAPIHandler implements Handler {

    private static final long MAX_AUDIO = 40L * 1024 * 1024;
    private static final long MAX_IMAGE = 20L * 1024 * 1024;

    /** One download of a set at a time; a second viewer waits for the archive the first one fetched. */
    private static final Map<Long, Object> SET_LOCKS = new ConcurrentHashMap<>();

    @Override
    @OpenApi(
        summary = "Beatmap file",
        description = "The .osu, the song or the background of a beatmap the server knows.",
        tags = { "Beatmaps" },
        queryParams = {
            @OpenApiParam(name = "id", type = Integer.class, description = "Beatmap id."),
            @OpenApiParam(name = "md5", type = String.class, description = "Beatmap md5, instead of the id."),
            @OpenApiParam(name = "kind", type = String.class, required = true, description = "osu, audio or background.")
        },
        responses = {
            @OpenApiResponse(status = "200", description = "The file"),
            @OpenApiResponse(status = "404", description = "Unknown beatmap, or the file is not available")
        },
        path = "/api/v1/beatmap_asset"
    )
    public void handle(@NotNull Context ctx) throws Exception {
        String kind = ctx.queryParam("kind") == null ? "" : ctx.queryParam("kind");
        BeatmapEntity map = find(ctx);

        if (map == null || !(kind.equals("osu") || kind.equals("audio") || kind.equals("background"))) {
            ctx.status(404).json(Map.of("error", "not found"));
            return;
        }

        Path osz = null;
        byte[] osu = BestReplayService.findOsu(map, null);

        if (osu == null || !kind.equals("osu")) {
            synchronized (SET_LOCKS.computeIfAbsent(map.getSetId(), k -> new Object())) {
                osz = BestReplayService.findOsz(map.getSetId());
            }

            if (osu == null && osz != null) {
                osu = BestReplayService.findOsu(map, osz);
            }
        }

        if (osu == null) {
            ctx.status(404).json(Map.of("error", "beatmap file not available"));
            return;
        }

        ctx.header("Cache-Control", "public, max-age=86400");

        if (kind.equals("osu")) {
            ctx.contentType("text/plain; charset=utf-8").result(osu);
            return;
        }

        if (osz == null) {
            ctx.status(404).json(Map.of("error", "beatmap set not available"));
            return;
        }

        OsuMap parsed = OsuMap.parse(osu);
        String name = kind.equals("audio") ? parsed.audioFile : parsed.backgroundFile;
        byte[] data = name == null || name.isBlank() ? null
                : entry(osz, name, kind.equals("audio") ? MAX_AUDIO : MAX_IMAGE);

        if (data == null) {
            ctx.status(404).json(Map.of("error", kind + " not in the set"));
            return;
        }

        ctx.contentType(contentType(name)).result(data);
    }

    private static BeatmapEntity find(Context ctx) {
        String md5 = ctx.queryParam("md5");

        if (md5 != null && md5.matches("[0-9a-fA-F]{32}")) {
            return BeatmapRepository.findByMd5(md5.toLowerCase(Locale.ROOT));
        }

        try {
            long id = Long.parseLong(ctx.queryParam("id"));
            return id > 0 ? BeatmapRepository.findById(id) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** An entry of the archive by name, ignoring case and the slash direction, as the game does. */
    private static byte[] entry(Path osz, String name, long max) {
        String wanted = name.replace('\\', '/').toLowerCase(Locale.ROOT);

        try (ZipFile zip = new ZipFile(osz.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();

                if (!e.getName().replace('\\', '/').toLowerCase(Locale.ROOT).equals(wanted)) {
                    continue;
                }

                if (e.getSize() > max) {
                    return null;
                }

                try (InputStream in = zip.getInputStream(e)) {
                    byte[] data = in.readNBytes((int) max + 1);
                    return data.length > max ? null : data;
                }
            }
        } catch (Exception ignored) {
            // a broken archive is a missing file
        }

        return null;
    }

    private static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);

        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";

        return "application/octet-stream";
    }
}
