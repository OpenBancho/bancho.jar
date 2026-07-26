package com.osuserverlist.bjar.repos;

import java.util.List;

import com.osuserverlist.bjar.models.database.AchievementEntity;
import com.osuserverlist.bjar.models.database.UserAchievementEntity;
import com.osuserverlist.bjar.models.database.UserAchievementId;
import com.osuserverlist.bjar.models.database.UserEntity;

import io.ebean.DB;

public final class AchievementRepository {

    public static List<AchievementEntity> findAll() {
        return DB.find(AchievementEntity.class).findList();
    }

    public static List<AchievementEntity> findByUser(UserEntity user) {
        return DB.find(UserAchievementEntity.class)
                .where()
                .eq("user", user)
                .findList()
                .stream()
                .map(UserAchievementEntity::getAchievement)
                .toList();
    }

    public static boolean has(UserEntity user, AchievementEntity achievement) {
        return DB.find(UserAchievementEntity.class)
                .where()
                .eq("user", user)
                .eq("achievement", achievement)
                .exists();
    }

    public static void unlock(UserEntity user, AchievementEntity achievement) {

        UserAchievementEntity entity = new UserAchievementEntity();
        entity.setId(new UserAchievementId(user.getId(), achievement.getId()));
        entity.setUser(user);
        entity.setAchievement(achievement);

        DB.save(entity);
    }

    public static void remove(UserEntity user, AchievementEntity achievement) {
        DB.delete(
                UserAchievementEntity.class,
                new UserAchievementId(user.getId(), achievement.getId()));
    }
}
