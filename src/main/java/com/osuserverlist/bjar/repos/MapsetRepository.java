package com.osuserverlist.bjar.repos;

import java.time.LocalDateTime;

import com.osuserverlist.bjar.models.database.MapsetEntity;

import io.ebean.DB;

public class MapsetRepository {

    public static MapsetEntity findById(int id) {
        return DB.find(MapsetEntity.class, id);
    }

    public static boolean exists(int id) {
        return DB.find(MapsetEntity.class)
                .select("id")
                .where()
                .idEq(id)
                .exists();
    }

    public static void save(MapsetEntity mapset) {
        DB.save(mapset);
    }

    public static void delete(MapsetEntity mapset) {
        DB.delete(mapset);
    }

    public static MapsetEntity create(int id) {
        MapsetEntity mapset = new MapsetEntity();
        mapset.setId(id);
        mapset.setLastOsuApiCheck(LocalDateTime.now());

        DB.save(mapset);
        return mapset;
    }

    public static void updateLastOsuApiCheck(int id) {
        DB.find(MapsetEntity.class)
                .where()
                .idEq(id)
                .findOneOrEmpty()
                .ifPresent(mapset -> {
                    mapset.setLastOsuApiCheck(LocalDateTime.now());
                    DB.save(mapset);
                });
    }

}
