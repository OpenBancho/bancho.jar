package com.osuserverlist.bjar.repos;

import java.util.List;

import com.osuserverlist.bjar.models.database.MapRequestEntity;

import io.ebean.DB;

public class MapRequestRepository {

    public static MapRequestEntity findById(int id) {
        return DB.find(MapRequestEntity.class, id);
    }

    public static List<MapRequestEntity> findAll() {
        return DB.find(MapRequestEntity.class)
                .findList();
    }

    public static List<MapRequestEntity> findActive() {
        return DB.find(MapRequestEntity.class)
                .where()
                .eq("active", true)
                .orderBy("id asc")
                .findList();
    }

    public static List<MapRequestEntity> findActiveByMapId(int mapId) {
        return DB.find(MapRequestEntity.class)
                .where()
                .eq("mapId", mapId)
                .eq("active", true)
                .findList();
    }

    public static boolean hasActiveRequest(int mapId) {
        return DB.find(MapRequestEntity.class)
                .where()
                .eq("mapId", mapId)
                .eq("active", true)
                .exists();
    }

    public static void save(MapRequestEntity request) {
        DB.save(request);
    }

    public static void update(MapRequestEntity request) {
        DB.update(request);
    }

    public static void delete(MapRequestEntity request) {
        DB.delete(request);
    }

    public static void closeRequest(long mapId, int adminId) {
        DB.sqlUpdate("""
                UPDATE map_requests
                SET active = false,
                    admin_id = :adminId
                WHERE map_id = :mapId
                """)
                .setParameter("adminId", adminId)
                .setParameter("mapId", mapId)
                .execute();
    }

    public static void create(int mapId, int playerId) {
        MapRequestEntity request = new MapRequestEntity();
        request.setMapId(mapId);
        request.setPlayerId(playerId);
        request.setAdminId(0);
        request.setActive(true);

        DB.save(request);
    }
}
