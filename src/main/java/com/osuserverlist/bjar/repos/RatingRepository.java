package com.osuserverlist.bjar.repos;

import java.util.List;
import java.util.OptionalDouble;

import com.osuserverlist.bjar.models.database.RatingEntity;
import com.osuserverlist.bjar.models.database.RatingId;

import io.ebean.DB;

public final class RatingRepository {

    private RatingRepository() {
    }

    public static RatingEntity find(int userId, String mapMd5) {
        return DB.find(RatingEntity.class)
                .where()
                .eq("id.userid", userId)
                .eq("id.mapMd5", mapMd5)
                .findOne();
    }

    public static boolean hasRated(int userId, String mapMd5) {
        return DB.find(RatingEntity.class)
                .where()
                .eq("id.userid", userId)
                .eq("id.mapMd5", mapMd5)
                .exists();
    }

    public static List<RatingEntity> findByMap(String mapMd5) {
        return DB.find(RatingEntity.class)
                .where()
                .eq("id.mapMd5", mapMd5)
                .findList();
    }

    /** Zero when nobody has voted yet, which the client renders as an unrated map. */
    public static double averageForMap(String mapMd5) {
        OptionalDouble average = findByMap(mapMd5)
                .stream()
                .mapToInt(RatingEntity::getRating)
                .average();

        return average.orElse(0.0);
    }

    public static void save(int userId, String mapMd5, int rating) {
        RatingEntity entity = new RatingEntity();
        entity.setId(new RatingId(userId, mapMd5));
        entity.setRating(rating);

        DB.save(entity);
    }
}
