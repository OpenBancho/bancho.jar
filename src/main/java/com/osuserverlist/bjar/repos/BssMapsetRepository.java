package com.osuserverlist.bjar.repos;

import java.util.List;

import com.osuserverlist.bjar.models.database.BssMapsetEntity;

import io.ebean.DB;
import io.ebean.SqlRow;

public class BssMapsetRepository {

    public static BssMapsetEntity findBySetId(int setId) {
        return DB.find(BssMapsetEntity.class, setId);
    }

    public static boolean exists(int setId) {
        return DB.find(BssMapsetEntity.class)
                .select("setId")
                .where()
                .idEq(setId)
                .exists();
    }

    /**
     * Returns true when the set is hosted by this server and still downloadable.
     */
    public static boolean isActive(int setId) {
        return DB.find(BssMapsetEntity.class)
                .select("setId")
                .where()
                .idEq(setId)
                .eq("active", true)
                .exists();
    }

    public static List<BssMapsetEntity> findByCreator(int creatorId) {
        return DB.find(BssMapsetEntity.class)
                .where()
                .eq("creatorId", creatorId)
                .eq("active", true)
                .findList();
    }

    /**
     * Counts the sets of a user that are still pending, i.e. that count
     * towards the per-user submission quota. Ranked/approved/loved sets are
     * excluded, mirroring the behaviour of the official BSS.
     */
    public static int countPendingByCreator(int creatorId) {
        return DB.find(BssMapsetEntity.class)
                .where()
                .eq("creatorId", creatorId)
                .eq("active", true)
                .lt("status", 1)
                .findCount();
    }

    public static List<BssMapsetEntity> search(String query, int limit, int offset) {
        var expr = DB.find(BssMapsetEntity.class)
                .where()
                .eq("active", true);

        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim() + "%";

            expr = expr.or()
                    .ilike("artist", pattern)
                    .ilike("title", pattern)
                    .ilike("creatorName", pattern)
                    .endOr();
        }

        return expr
                .orderBy()
                .desc("lastUpdate")
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findList();
    }

    /**
     * Highest set id that was ever handed out by the BSS. Returns {@code null}
     * when no set has been submitted yet.
     */
    public static Integer maxSetId() {
        SqlRow row = DB.sqlQuery("SELECT MAX(set_id) AS max_id FROM bss_mapsets")
                .findOne();

        return row == null ? null : row.getInteger("max_id");
    }

    public static void save(BssMapsetEntity mapset) {
        DB.save(mapset);
    }

    public static void delete(BssMapsetEntity mapset) {
        DB.delete(mapset);
    }

    public static long count() {
        return DB.find(BssMapsetEntity.class)
                .findCount();
    }
}
