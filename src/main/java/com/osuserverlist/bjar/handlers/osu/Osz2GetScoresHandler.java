package com.osuserverlist.bjar.handlers.osu;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.Server;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.models.essentials.Score;
import com.osuserverlist.bjar.models.osu.GameMode;
import com.osuserverlist.bjar.models.osu.MapWebRankedStatus;
import com.osuserverlist.bjar.models.osu.Mods;
import com.osuserverlist.bjar.models.osu.OsuClientModels.LeaderboardType;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.packets.server.UserServerPackets.UserStatsPacket;
import com.osuserverlist.bjar.repos.ScoreRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import me.skiincraft.api.ousu.entity.objects.Approval;

@Host("osu.")
@Path("/web/osu-osz2-getscores.php")
@HttpMethod("GET")
public class Osz2GetScoresHandler implements Handler {

    private static final Logger logger = LoggerFactory.getLogger(Osz2GetScoresHandler.class);

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        respond(ctx);
    }

    /**
     * Builds the leaderboard answer.
     *
     * <p>Shared with {@code /web/osu-getscores.php}: pre-osz2 clients ask the
     * same question with fewer parameters and different credential names, so
     * every value is read leniently and falls back to what those clients assume
     * (no mods, local leaderboard).</p>
     */
    public static void respond(@NotNull Context ctx) throws Exception {
        int mode = ctx.queryParamAsClass("m", Integer.class).getOrDefault(0);
        int mods = ctx.queryParamAsClass("mods", Integer.class).getOrDefault(0);
        int leaderboardType = ctx.queryParamAsClass("v", Integer.class).getOrDefault(1);

        String username = ctx.queryParam("us") != null ? ctx.queryParam("us") : ctx.queryParam("u");
        String passwordHash = ctx.queryParam("ha") != null ? ctx.queryParam("ha") : ctx.queryParam("h");

        Server server = App.server;

        Player player = server.playerManager.getByApiIdent(username + "|" + passwordHash);
        if (player == null) {
            ctx.status(401).result("Invalid credentials.");
            return;
        }

        GameMode gameMode = GameMode.fromValue(mode, mods);
        LeaderboardType type = LeaderboardType.getById(leaderboardType);

        boolean relax = (mods & Mods.Relax.getValue()) != 0
                || (mods & Mods.Relax2.getValue()) != 0;

        if (player.isRelaxEnabled() != relax) {
            player.sendPacket(new UserStatsPacket(player));
        }

        player.setRelaxEnabled(relax);
        player.setRealGameMode(gameMode.getValue());

        BeatmapEntity beatmap = server.osuAPIHandler.getBeatmapByHash(ctx.queryParam("c"));
        if (beatmap == null) {
            ctx.result("-1|false");
            return;
        }

        ScoreEntity ownScoreEntity = ScoreRepository.getBestScore(
                player.getEntity(),
                beatmap.getMd5(),
                gameMode.getValue());

        Score ownScore = ownScoreEntity != null ? Score.fromEntity(ownScoreEntity, beatmap) : null;

        List<ScoreEntity> scores = ScoreRepository.getLeaderboard(
                beatmap,
                gameMode.getValue(),
                type,
                mods,
                player);

        List<Score> scoreList = scores.stream()
                .map(scoreEntity -> Score.fromEntity(scoreEntity, beatmap))
                .toList();

        assignRanks(scoreList);

        if (ownScore != null) {
            syncOwnScoreRank(ownScore, scoreList, player.getId());
        }

        logger.debug(
                "Generated {} leaderboard for player {} on map <{}> ({} entries)",
                type,
                player,
                beatmap.getId(),
                scoreList.size());

        ctx.result(buildResponse(beatmap, ownScore, scoreList));
    }

    private static void assignRanks(List<Score> scores) {
        if (scores.isEmpty()) {
            return;
        }

        int currentRank = 1;
        int position = 1;
        long previousScore = scores.getFirst().getScore();

        scores.getFirst().setRank(1);

        for (int i = 1; i < scores.size(); i++) {
            position++;

            Score score = scores.get(i);

            if (score.getScore() != previousScore) {
                currentRank = position;
            }

            score.setRank(currentRank);
            previousScore = score.getScore();
        }
    }

    private static void syncOwnScoreRank(Score ownScore, List<Score> leaderboard, int playerId) {
        leaderboard.stream()
                .filter(score -> score.getPlayerId() == playerId)
                .findFirst()
                .ifPresent(score -> ownScore.setRank(score.getRank()));
    }

    private static String buildResponse(BeatmapEntity beatmap,
            Score ownScore,
            List<Score> scores) {

        StringBuilder sb = new StringBuilder();

        sb.append(String.format(
                "%d|false|%d|%d|%d|0\n0\n%s - %s [%s]\n0.0",
                MapWebRankedStatus.fromApproval(
                        Approval.getById(beatmap.getStatus())).getId(),
                beatmap.getId(),
                beatmap.getSetId(),
                scores.size(),
                beatmap.getArtist(),
                beatmap.getTitle(),
                beatmap.getVersion()));

        if (ownScore != null) {
            sb.append(Score.buildScoreWebString(
                    ownScore,
                    ownScore.getId(),
                    1));
        } else {
            sb.append('\n');
        }

        for (Score score : scores) {
            sb.append(Score.buildScoreWebString(
                    score,
                    score.getId(),
                    1));
        }

        return sb.toString();
    }
}
