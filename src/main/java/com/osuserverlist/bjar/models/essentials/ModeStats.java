package com.osuserverlist.bjar.models.essentials;

import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.modules.datastore.Redis;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ModeStats {
    private int mode = 0;
    private long rankedScore = 0;
    private float accuracy = 0;
    private int playCount = 0;
    private long totalScore = 0;
    private int maxCombo = 0;
    private long globalRank = 0;
    private int pp = 0;
    private int totalHits = 0;
    private int playtime = 0;

    private int xhCount = 0;
    private int xCount = 0;
    private int shCount = 0;
    private int sCount = 0;
    private int aCount = 0;

    public ModeStats(ModeStats stats) {
        this.mode = stats.mode;
        this.rankedScore = stats.rankedScore;
        this.accuracy = stats.accuracy;
        this.playCount = stats.playCount;
        this.totalScore = stats.totalScore;
        this.maxCombo = stats.maxCombo;
        this.globalRank = stats.globalRank;
        this.pp = stats.pp;
        this.totalHits = stats.totalHits;
        this.playtime = stats.playtime;
    }

    public static ModeStats fromEntity(StatsEntity statsEntity, int mode, Player player) {
        ModeStats stats = new ModeStats();
        stats.setMode(mode);
        stats.setPlayCount(statsEntity.getPlays());
        stats.setTotalScore(statsEntity.getTotalScore());
        stats.setRankedScore(statsEntity.getRankedScore());
        stats.setAccuracy(statsEntity.getAccuracy());
        stats.setMaxCombo(statsEntity.getMaxCombo());
        stats.setPp(statsEntity.getPp());
        stats.setTotalHits(statsEntity.getTotalHits());
        stats.setPlaytime(statsEntity.getPlaytime());

        stats.setXhCount(statsEntity.getXhCount());
        stats.setXCount(statsEntity.getXCount());
        stats.setShCount(statsEntity.getShCount());
        stats.setSCount(statsEntity.getSCount());
        stats.setACount(statsEntity.getACount());

        Long rank = Redis.getClient().zrevrank(
                "bjar:leaderboard:" + mode,
                String.valueOf(player.getId()));

        stats.setGlobalRank(rank != null ? Math.toIntExact(rank) + 1 : 0);
        return stats;
    }

    /** Upper bound for a single play, used to drop nonsense durations. */
    private static final int MAX_PLAY_SECONDS = 6 * 60 * 60;

    public void addScore(Score s) {
        totalScore += s.getScore();
        playCount++;
        // Playtime is the length of the play, not the moment it happened:
        // getPlaytime() is a timestamp, so dividing it used to add roughly
        // two hundred days of playtime per submitted score. A single play
        // never runs for hours, so anything above that is ignored.
        playtime += Math.max(0, Math.min(MAX_PLAY_SECONDS, s.getTimeElapsed() / 1000));
        totalHits += s.getN300()
                + s.getN100()
                + s.getN50()
                + s.getNmiss();

        if(s.isPassed()) {
            maxCombo = Math.max(maxCombo, s.getMax_combo());

            if (accuracy == 0) {
                accuracy = (float) s.getAccuracy();
            } else {
                accuracy = calculateAccuracy((float) s.getAccuracy(), accuracy);
            }

            switch(Grade.fromString(s.getGrade())) {
                case XH:
                    xhCount++;
                    break;
                case X:
                    xCount++;
                    break;
                case SH:
                    shCount++;
                    break;
                case S:
                    sCount++;
                    break;
                case A:
                    aCount++;
                    break;
                default:
                    break;

            }
        }
    }

    /**
     * Adds the ranked part of a score. The score itself is already counted
     * by {@link #addScore(Score)}, which every submission goes through, so
     * calling it again here counted plays, playtime, total score, hits and
     * grades twice for every personal best on a ranked map.
     */
    public void addRankedScore(Score s, double pp) {
        rankedScore += s.getScore();

        this.pp = (int) pp;
    }

    private float calculateAccuracy(float scoreAcc, float totalAcc) {
        if (scoreAcc > 1.0f) {
            scoreAcc /= 100.0f; // Convert from percentage to decimal if needed
        }
        if (totalAcc > 1.0f) {
            totalAcc /= 100.0f; // Convert from percentage to decimal if needed
        }
        return (scoreAcc + totalAcc) / 2;
    }

    public enum Grade {
        XH, X, SH, S, A, B, C, D;

        public static Grade fromString(String gradeStr) {
            switch (gradeStr) {
                case "XH":
                    return XH;
                case "X":
                    return X;
                case "SH":
                    return SH;
                case "S":
                    return S;
                case "A":
                    return A;
                case "B":
                    return B;
                case "C":
                    return C;
                case "D":
                    return D;
                default:
                    throw new IllegalArgumentException("Unknown grade: " + gradeStr);
            }
        }
    }
}
