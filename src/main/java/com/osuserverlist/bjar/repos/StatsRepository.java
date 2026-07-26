package com.osuserverlist.bjar.repos;

import java.util.List;

import com.osuserverlist.bjar.models.database.StatsEntity;
import com.osuserverlist.bjar.models.database.StatsId;

import io.ebean.DB;

public class StatsRepository {

    public static StatsEntity find(int userId, int mode) {
        return DB.find(StatsEntity.class, new StatsId(userId, mode));
    }

    public static List<StatsEntity> findByUser(int userId) {
        return DB.find(StatsEntity.class)
                .where()
                .eq("id.id", userId)
                .findList();
    }

    public static List<StatsEntity> findByMode(int mode) {
        return DB.find(StatsEntity.class)
                .where()
                .eq("id.mode", mode)
                .findList();
    }

    public static List<StatsEntity> getLeaderboardByPp(int mode) {
        return DB.find(StatsEntity.class)
                .where()
                .eq("id.mode", mode)
                .orderBy("pp desc")
                .findList();
    }

    public static boolean exists(int userId, int mode) {
        return DB.find(StatsEntity.class)
                .setId(new StatsId(userId, mode))
                .exists();
    }

    public static void save(StatsEntity stats) {
        DB.save(stats);
    }

    public static void update(StatsEntity stats) {
        DB.update(stats);
    }

    public static void delete(StatsEntity stats) {
        DB.delete(stats);
    }

    public static void delete(int userId, int mode) {
        DB.delete(StatsEntity.class, new StatsId(userId, mode));
    }

    public static List<StatsEntity> findAllByUser(int userId) {
        return DB.find(StatsEntity.class)
                .where()
                .eq("id.id", userId)
                .in("id.mode", 0, 1, 2, 3, 4, 5, 6, 8)
                .orderBy("id.mode")
                .findList();
    }

    public static List<StatsEntity> findActiveByMode(int mode) {
        return DB.find(StatsEntity.class)
                .where()
                .eq("id.mode", mode)
                .gt("plays", 0)
                .findList();
    }
}
