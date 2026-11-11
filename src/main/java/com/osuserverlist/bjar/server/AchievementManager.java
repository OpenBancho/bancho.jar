package com.osuserverlist.bjar.server;

import java.util.Collection;
import java.util.List;

import org.mvel2.MVEL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.database.AchievementEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.util.MevlParser.PythonMevlRewriter;
import com.osuserverlist.bjar.repos.AchievementRepository;

public class AchievementManager {
    private final static Logger logger = LoggerFactory.getLogger(AchievementManager.class);
    private List<AchievementEntity> achievements;

    public void populate() {
        achievements = new java.util.ArrayList<>(AchievementRepository.findAll());
        // MVEL can run arbitrary code, and the conditions come from the database: only plain comparisons are accepted.
        achievements.removeIf(achievement -> {
            String rewritten = PythonMevlRewriter.rewrite(achievement.getCondition());

            if (!isSafeCondition(rewritten)) {
                logger.warn("Skipping achievement <{}>: its condition is not a plain comparison", achievement.getId());
                return true;
            }

            achievement.setCondition(rewritten);
            achievement.setConditionCompiled(MVEL.compileExpression(rewritten));
            return false;
        });

        logger.info("Loaded <{}> achievements", achievements.size());
    }

    private static final java.util.regex.Pattern SAFE_CHARS = java.util.regex.Pattern.compile("^[A-Za-z0-9_.()<>=!&|+\\-*/%, ]*$");
    private static final java.util.Set<String> SAFE_WORDS = java.util.Set.of(
            "score", "mode_vn", "perfect", "mods", "sr", "max_combo", "and", "or", "not", "in", "true", "false", "True", "False");

    static boolean isSafeCondition(String condition) {
        if (condition == null || !SAFE_CHARS.matcher(condition).matches()) {
            return false;
        }

        for (String word : condition.split("[^A-Za-z_]+")) {
            if (!word.isEmpty() && !SAFE_WORDS.contains(word)) {
                return false;
            }
        }

        return true;
    }

    public void loadForPlayer(Player player) {
        AchievementRepository.findByUser(player.getEntity()).forEach(ach -> {
            player.getUnlockedAchievements().add(ach.getId());
        });
    }

    public Collection<AchievementEntity> getAll() {
        return achievements;
    }

}
