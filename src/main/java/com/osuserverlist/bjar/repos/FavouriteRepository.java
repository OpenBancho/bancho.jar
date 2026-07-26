package com.osuserverlist.bjar.repos;

import java.time.Instant;
import java.util.List;

import com.osuserverlist.bjar.models.database.FavouriteEntity;
import com.osuserverlist.bjar.models.database.FavouriteId;

import io.ebean.DB;

public final class FavouriteRepository {

    private FavouriteRepository() {
    }

    public static List<FavouriteEntity> findByUser(int userId) {
        return DB.find(FavouriteEntity.class)
                .where()
                .eq("id.userid", userId)
                .orderBy("createdAt desc")
                .findList();
    }

    /** Set ids only, in the order the client expects to render them. */
    public static List<Integer> findSetIdsByUser(int userId) {
        return findByUser(userId)
                .stream()
                .map(favourite -> favourite.getId().getSetid())
                .toList();
    }

    public static boolean exists(int userId, int setId) {
        return DB.find(FavouriteEntity.class)
                .where()
                .eq("id.userid", userId)
                .eq("id.setid", setId)
                .exists();
    }

    /**
     * Stars a set.
     *
     * @return false when the player had already favourited it, so the caller can
     *         tell the client instead of silently doing nothing.
     */
    public static boolean add(int userId, int setId) {
        if (exists(userId, setId)) {
            return false;
        }

        FavouriteEntity favourite = new FavouriteEntity();
        favourite.setId(new FavouriteId(userId, setId));
        favourite.setCreatedAt((int) Instant.now().getEpochSecond());

        DB.save(favourite);
        return true;
    }

    public static void remove(int userId, int setId) {
        DB.delete(FavouriteEntity.class, new FavouriteId(userId, setId));
    }

    public static int countByUser(int userId) {
        return DB.find(FavouriteEntity.class)
                .where()
                .eq("id.userid", userId)
                .findCount();
    }
}
