package com.osuserverlist.bjar.handlers.osu;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.database.AchievementEntity;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.essentials.ModeStats;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.essentials.Score;
import com.osuserverlist.bjar.models.osu.GameMode;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.models.osu.SubmitResponse;
import com.osuserverlist.bjar.modules.datastore.Redis;
import com.osuserverlist.bjar.modules.main.Cryptography;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.modules.osu.OsuMapDownloader;
import com.osuserverlist.bjar.modules.util.MevlParser;
import com.osuserverlist.bjar.packets.server.ChatServerPackets.SendMessagePacket;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserStatsPacket;
import com.osuserverlist.bjar.repos.AchievementRepository;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.ScoreRepository;
import com.osuserverlist.bjar.repos.StatsRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.UploadedFile;

@Host("osu.")
@Path("/web/osu-submit-modular-selector.php")
@HttpMethod("POST")
public class OsuSubmitModularHandler implements Handler {

    private final static Logger logger = LoggerFactory.getLogger(OsuSubmitModularHandler.class);

    /** How long an identical submission is treated as a replay of the previous one. */
    private static final int DUPLICATE_WINDOW_SECONDS = 60;

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        SubmitResponse submitResponse = SubmitResponse.fromContext(ctx);

        String keyStr = ("osu!-scoreburgr---------" + submitResponse.getOsuVersion());
        keyStr = String.format("%-32s", keyStr).substring(0, 32);
        byte[] aesKey = keyStr.getBytes(StandardCharsets.UTF_8);

        byte[] decryptedBytes = Cryptography.decryptRijndaelCBC(submitResponse.getScoreEncrypted(), aesKey,
                submitResponse.getIv());
        String decrypted = new String(decryptedBytes, StandardCharsets.UTF_8);
        String[] data = decrypted.split(":");

        if (data.length < 16) {
            ctx.status(400).result("Malformed decrypted score data.");
            return;
        }

        Server server = App.server;
        String playerIdent = String.format("%s|%s", data[1].stripTrailing(), ctx.formParam("pass"));

        Player p = server.playerManager.getByApiIdent(playerIdent);
        if (p == null) {
            ctx.status(401).result("Invalid credentials.");
            return;
        }

        UploadedFile fileUpload = ctx.uploadedFile("score");
        if (fileUpload == null) {
            ctx.status(400).result("No replay file uploaded.");
            return;
        }

        Score s = Score.fromSubmission(data, p);

        if (!isPlausible(s)) {
            logger.warn("Rejected an impossible score from player {}: {}", p, decrypted);
            ctx.status(400).result("Malformed score data.");
            return;
        }

        GameMode realGameMode = GameMode.fromValue(s.getMode(), s.getMods());

        BeatmapEntity beatmap = server.osuAPIHandler.getBeatmapByHash(submitResponse.getUpdatedBeatmapHash());
        if (beatmap == null) {
            ctx.status(400).result("Beatmap not found.");
            return;
        }

        s.setBeatmapId(beatmap.getId());

        byte[] mapData = OsuMapDownloader.downloadMap(s.getBeatmapId());
        double pp = server.performance.calculate(s, mapData);
        s.setPp(pp);
        // Keep the checksum the client computed for this play. It covers the
        // score itself, which makes it a reliable marker of a replayed
        // submission. Fall back to a server-side value if it is malformed,
        // since the column is limited to 32 characters.
        String clientChecksum = data[2] == null ? "" : data[2].trim();

        if (clientChecksum.length() != 32) {
            clientChecksum = Cryptography.generateChecksum(s.toString());
        }

        s.setChecksum(clientChecksum);

        if (ScoreRepository.existsRecentByChecksum(
                p.getId(), s.getChecksum(), LocalDateTime.now().minusSeconds(DUPLICATE_WINDOW_SECONDS))) {

            logger.warn("Ignored a duplicate submission from player {} (checksum {})", p, s.getChecksum());

            // The original submission already produced a response; answering
            // with an empty body keeps the client quiet instead of showing an
            // error for what is usually a network retry.
            ctx.result("");
            return;
        }

        ScoreEntity bestScoreEntity = ScoreRepository.getBestScore(s.getPlayerId(), beatmap.getMd5(),
                realGameMode.getValue());

        Score bestScore = (bestScoreEntity != null) ? Score.fromEntity(bestScoreEntity, beatmap) : null;

        boolean hasPreviousBest = bestScore != null;

        boolean isPersonalBest = !hasPreviousBest || s.getScore() > bestScore.getScore();

        int prevMapRank = 0;
        if (hasPreviousBest) {
            prevMapRank = ScoreRepository.getPreviousRank(beatmap.getMd5(), realGameMode.getValue(), p.getEntity(),
                    bestScore.getScore());
        }

        int scoreStatus = (isPersonalBest && s.isPassed()) ? 2 : 0;

        ScoreEntity scoreEntity = new ScoreEntity();
        scoreEntity.setUser(p.getEntity());
        scoreEntity.setMapMd5(beatmap.getMd5());
        scoreEntity.setMode(realGameMode.getValue());
        scoreEntity.setScore(s.getScore());
        scoreEntity.setMaxCombo(s.getMax_combo());
        scoreEntity.setAcc((float) s.getAccuracy());
        scoreEntity.setPp((float) s.getPp());
        scoreEntity.setStatus(scoreStatus);
        scoreEntity.setOnlineChecksum(s.getChecksum());
        scoreEntity.setMods(s.getMods());
        // The client reports the finish time for a pass and the fail time
        // for a fail, both in milliseconds. This is what the playtime of
        // the player is built from.
        s.setTimeElapsed(s.isPassed() ? submitResponse.getScoreTime() : submitResponse.getFailTime());

        scoreEntity.setTimeElapsed(s.getTimeElapsed());
        scoreEntity.setPlayTime(LocalDateTime.now());
        scoreEntity.setN300(s.getN300());
        scoreEntity.setN100(s.getN100());
        scoreEntity.setN50(s.getN50());
        scoreEntity.setNmiss(s.getNmiss());
        scoreEntity.setNgeki(s.getNgeki());
        scoreEntity.setNkatu(s.getNkatu());
        scoreEntity.setClientFlags(s.getFlags());
        scoreEntity.setGrade(s.getGrade());
        scoreEntity.setPerfect(s.isPerfect());

        ScoreRepository.save(scoreEntity);

        Long newScoreId = scoreEntity.getId();
        if (newScoreId == null) {
            ctx.status(500).result("Failed to retrieve score ID.");
            return;
        }

        s.setId(newScoreId);

        // Demote the old personal best
        if (isPersonalBest && bestScore != null && bestScore.getId() != -1) {
            bestScoreEntity.setStatus(0);
            ScoreRepository.save(bestScoreEntity);
        }

        // The counters of the map itself. Every submission is a play, only
        // a finished one is a pass. Nothing ever called these, so every map
        // stayed at zero plays no matter how much it was played.
        if (s.isPassed()) {
            BeatmapRepository.incrementPlayAndPass(beatmap.getId());
            beatmap.setPasses((beatmap.getPasses() == null ? 0 : beatmap.getPasses()) + 1);
        } else {
            BeatmapRepository.incrementPlays(beatmap.getId());
        }

        // Kept in sync in memory as well, because the response below hands
        // the play and pass count back to the client.
        beatmap.setPlays((beatmap.getPlays() == null ? 0 : beatmap.getPlays()) + 1);

        int rank = ScoreRepository.getRank(beatmap.getMd5(), realGameMode.getValue(), s.getScore());

        if (s.isPassed() && rank == 1 && Privileges.fromInt(p.getServerPrivileges()).contains(Privileges.UNRESTRICTED)) {
            String ann = String.format(
                    "\u0001ACTION achieved #1 on %s with %.2f%% for %.2fpp",
                    beatmap.toEmbed(),
                    s.getAccuracy() * 100.0,
                    s.getPp());

            server.channelManager.get("#announce").getPlayers().forEach(pl -> {
                pl.sendPacket(new SendMessagePacket(p.getUsername(), ann, "#announce", p.getId()));
            });
        }

        // Weighted PP: only meaningful when this is a new personal best.
        // Using status=1 is safe here because we just finished demoting the old PB.
        
        double totalPp = 0.0;
        if (isPersonalBest && s.isPassed()) {
            totalPp = ScoreRepository.calculateWeightedPp(p.getId(), realGameMode.getValue());
        }

        ModeStats playerStats = p.getModeStats()[realGameMode.getValue()];
        ModeStats oldStats = new ModeStats(playerStats); // deep-copy before mutation

        playerStats.addScore(s);
        if (isPersonalBest && s.isPassed() && beatmap.getStatus() == 1) {
            playerStats.addRankedScore(s, totalPp);
            Redis.getClient().zadd("bjar:leaderboard:" + realGameMode.getValue(), totalPp, String.valueOf(p.getId()));
            Long redisRank = Redis.getClient().zrevrank("bjar:leaderboard:" + realGameMode.getValue(),
                    String.valueOf(p.getId()));
            playerStats.setGlobalRank((redisRank != null ? Math.toIntExact(redisRank) : -1) + 1);
        }

        p.sendPacket(new UserStatsPacket(p));

        // ---- Build response ----

        List<String> chart1 = new ArrayList<>();
        chart1.add("chartId:beatmap");
        chart1.add("chartUrl:https://osu.ppy.sh/b/" + beatmap.getId());
        chart1.add("chartName:Beatmap Ranking");
        chart1.add(addChart("rank", prevMapRank, rank));
        chart1.add(addChart("score", hasPreviousBest ? bestScore.getScore() : 0, s.getScore()));
        chart1.add(addChart("maxCombo", hasPreviousBest ? bestScore.getMax_combo() : 0, s.getMax_combo()));
        chart1.add(addChart("accuracy", hasPreviousBest ? bestScore.getAccuracy() * 100 : 0, s.getAccuracy() * 100));
        chart1.add(
                addChart("pp", hasPreviousBest ? (int) Math.ceil(bestScore.getPp()) : 0, (int) Math.ceil(s.getPp())));
        chart1.add("onlineScoreId:" + s.getId());

        List<String> chart2 = new ArrayList<>();
        chart2.add("chartId:overall");
        chart2.add("chartUrl:https://osu.ppy.sh/u/" + s.getPlayerId());
        chart2.add("chartName:User Ranking");

        if (isPersonalBest) {
            chart2.add(addChart("rank", oldStats.getGlobalRank(), playerStats.getGlobalRank()));
            chart2.add(addChart("accuracy", (int) (oldStats.getAccuracy() * 100),
                    (int) (playerStats.getAccuracy() * 100)));
            chart2.add(addChart("maxCombo", oldStats.getMaxCombo(), playerStats.getMaxCombo()));
            chart2.add(addChart("rankedScore", oldStats.getRankedScore(), playerStats.getRankedScore()));
            chart2.add(addChart("totalScore", oldStats.getTotalScore(), playerStats.getTotalScore()));
            chart2.add(addChart("pp", (int) Math.ceil(oldStats.getPp()), (int) Math.ceil(playerStats.getPp())));
        } else {
            long curRank = playerStats.getGlobalRank();
            chart2.add(addChart("rank", curRank, curRank));
            chart2.add(addChart("accuracy", (int) (playerStats.getAccuracy() * 100),
                    (int) (playerStats.getAccuracy() * 100)));
            chart2.add(addChart("maxCombo", playerStats.getMaxCombo(), playerStats.getMaxCombo()));
            chart2.add(addChart("rankedScore", playerStats.getRankedScore(), playerStats.getRankedScore()));
            chart2.add(addChart("totalScore", playerStats.getTotalScore(), playerStats.getTotalScore()));
            chart2.add(addChart("pp", (int) Math.ceil(playerStats.getPp()), (int) Math.ceil(playerStats.getPp())));
        }

        List<AchievementEntity> newlyUnlocked = new ArrayList<>();
        List<String> achievementStr = new ArrayList<>();

        for (AchievementEntity achievement : server.achievementManager.getAll()) {
            if (!s.isPassed())
                break;
            if (p.getUnlockedAchievements().contains(achievement.getId()))
                continue;

            if (MevlParser.evaluate(achievement.getConditionCompiled(), s, beatmap)) {
                p.getUnlockedAchievements().add(achievement.getId());
                newlyUnlocked.add(achievement);

                achievementStr.add(
                        achievement.getFile()
                                + "+"
                                + achievement.getName()
                                + "+"
                                + achievement.getDescription());
            }
        }

        chart2.add("achievements-new:" + String.join("/", achievementStr));

        logger.info(
                "Player {} submitted a score on {} ({}pp, PB={})",
                p,
                beatmap.getArtist() + " - " + beatmap.getTitle(),
                (int) Math.ceil(s.getPp()),
                isPersonalBest);

        List<String> responseLines = new ArrayList<>();

        responseLines.add(String.join("|",
                "beatmapId:" + beatmap.getId(),
                "beatmapSetId:" + beatmap.getSetId(),
                "beatmapPlaycount:" + beatmap.getPlays(),
                "beatmapPasscount:" + beatmap.getPasses(),
                "approvedDate:" + beatmap.getLastUpdate()));

        responseLines.add(String.join("|", chart1));
        responseLines.add(String.join("|", chart2));

        ctx.result(String.join("\n", responseLines));

        scheduleBackgroundSubmitTasks(server, p, s, realGameMode, playerStats, fileUpload, newlyUnlocked);

    }

    /**
     * Save replay file, update player stats, and persist any newly unlocked
     * achievements in the background.
     * This is done asynchronously to avoid blocking the main request thread.
     */
    private void scheduleBackgroundSubmitTasks(Server server, Player p, Score s, GameMode realGameMode,
            ModeStats playerStats, UploadedFile fileUpload, List<AchievementEntity> newlyUnlocked) {
        server.executor.submit(() -> {
            try {
                byte[] fileBytes = fileUpload.content().readAllBytes();
                Files.write(Paths.get("data/replays").resolve(s.getId() + ".osr"), fileBytes);
            } catch (Exception e) {
                logger.error("Error saving replay file for score {}: {}", s.getId(), e.getMessage());
            }

            StatsEntity statsEntity = StatsRepository.find(p.getId(), realGameMode.getValue());
            statsEntity.setPlays(playerStats.getPlayCount());
            statsEntity.setTotalScore(playerStats.getTotalScore());
            statsEntity.setRankedScore(playerStats.getRankedScore());
            statsEntity.setAccuracy(playerStats.getAccuracy());
            statsEntity.setMaxCombo(playerStats.getMaxCombo());
            statsEntity.setPp(playerStats.getPp());
            statsEntity.setTotalHits(playerStats.getTotalHits());
            statsEntity.setPlaytime(playerStats.getPlaytime());
            statsEntity.setXhCount(playerStats.getXhCount());
            statsEntity.setXCount(playerStats.getXCount());
            statsEntity.setShCount(playerStats.getShCount());
            statsEntity.setSCount(playerStats.getSCount());;
            statsEntity.setACount(playerStats.getACount());
            StatsRepository.update(statsEntity);

            if (!newlyUnlocked.isEmpty()) {
                for (AchievementEntity achievement : newlyUnlocked) {
                    AchievementRepository.unlock(p.getEntity(), achievement);
                }
            }

        });
    }

    /**
     * Rejects values no client can legitimately produce.
     *
     * <p>This deliberately only covers what is impossible rather than what is
     * merely unlikely: negative counters, a negative total, and an unknown
     * play mode. Anything requiring knowledge of the beatmap is left to the
     * recalculation pipeline, where a mistake cannot cost a player a score.</p>
     */
    private static boolean isPlausible(Score s) {
        if (s.getMode() < 0 || s.getMode() > 3) {
            return false;
        }

        if (s.getScore() < 0 || s.getMax_combo() < 0) {
            return false;
        }

        return s.getN300() >= 0
                && s.getN100() >= 0
                && s.getN50() >= 0
                && s.getNgeki() >= 0
                && s.getNkatu() >= 0
                && s.getNmiss() >= 0;
    }

    private String addChart(String name, Object prev, Object after) {
        String before = (prev != null) ? prev.toString() : "";
        String afterStr = (after != null) ? after.toString() : "";
        return name + "Before:" + before + "|" + name + "After:" + afterStr;
    }
}