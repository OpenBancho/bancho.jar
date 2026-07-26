package com.osuserverlist.bjar.repos;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.osuserverlist.bjar.models.database.BeatmapEntity;

import io.ebean.DB;

public class BeatmapRepository {
    public static BeatmapEntity findById(long id) {
        return DB.find(BeatmapEntity.class, id);
    }

    public static BeatmapEntity findByMd5(String md5) {
        return DB.find(BeatmapEntity.class)
                .where()
                .eq("md5", md5)
                .findOne();
    }

    /**
     * Looks a map up by the name of its .osu file.
     *
     * <p>Filenames are not unique. The same difficulty can exist under an official
     * id and again under a locally submitted one, and two unrelated sets can carry
     * identically named difficulties. The row with the highest ranked status wins,
     * then the most recently updated one, so the answer is both deterministic and
     * the most useful of the candidates.</p>
     */
    public static BeatmapEntity findByFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }

        return DB.find(BeatmapEntity.class)
                .where()
                .eq("filename", filename)
                .orderBy("status desc, lastUpdate desc, id desc")
                .setMaxRows(1)
                .findOne();
    }

    /** Every row sharing one filename, best candidate first. */
    public static List<BeatmapEntity> findAllByFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return List.of();
        }

        return DB.find(BeatmapEntity.class)
                .where()
                .eq("filename", filename)
                .orderBy("status desc, lastUpdate desc, id desc")
                .findList();
    }

    /**
     * Resolves many filenames at once.
     *
     * <p>The client asks about its whole Songs folder in one request, so doing this
     * as a single query instead of one per name is the difference between a handful
     * of milliseconds and thousands of round trips.</p>
     *
     * @return one entry per filename that matched, keyed by the filename.
     */
    public static Map<String, BeatmapEntity> findByFilenames(Collection<String> filenames) {
        if (filenames == null || filenames.isEmpty()) {
            return Map.of();
        }

        List<BeatmapEntity> rows = DB.find(BeatmapEntity.class)
                .where()
                .in("filename", filenames)
                .orderBy("status asc, lastUpdate asc, id asc")
                .findList();

        Map<String, BeatmapEntity> byFilename = new HashMap<>();

        // Ascending order means the best candidate overwrites the weaker ones,
        // leaving the same winner findByFilename would have picked.
        for (BeatmapEntity row : rows) {
            byFilename.put(row.getFilename(), row);
        }

        return byFilename;
    }

    public static List<BeatmapEntity> findBySetId(long setId) {
        return DB.find(BeatmapEntity.class)
                .where()
                .eq("setId", setId)
                .findList();
    }

    public static BeatmapEntity findFirstBySetId(long setId) {
        return DB.find(BeatmapEntity.class)
                .where()
                .eq("setId", setId)
                .setMaxRows(1)
                .findOne();
    }

    public static List<BeatmapEntity> findByStatus(int status) {
        return DB.find(BeatmapEntity.class)
                .where()
                .eq("status", status)
                .findList();
    }

    public static List<BeatmapEntity> findByMode(int mode) {
        return DB.find(BeatmapEntity.class)
                .where()
                .eq("mode", mode)
                .findList();
    }

    public static boolean exists(long id) {
        return DB.find(BeatmapEntity.class)
                .where()
                .idEq(id)
                .exists();
    }

    public static boolean existsMd5(String md5) {
        return DB.find(BeatmapEntity.class)
                .where()
                .eq("md5", md5)
                .exists();
    }

    public static void save(BeatmapEntity beatmap) {
        DB.save(beatmap);
    }

    public static void update(BeatmapEntity beatmap) {
        DB.update(beatmap);
    }

    public static void delete(BeatmapEntity beatmap) {
        DB.delete(beatmap);
    }

    public static void deleteById(long id) {
        DB.delete(BeatmapEntity.class, id);
    }

    public static void incrementPlays(long id) {
        DB.sqlUpdate("""
                UPDATE maps
                SET plays = plays + 1
                WHERE id = ?
                """)
                .setParameter(1, id)
                .execute();
    }

    public static void incrementPasses(long id) {
        DB.sqlUpdate("""
                UPDATE maps
                SET passes = passes + 1
                WHERE id = ?
                """)
                .setParameter(1, id)
                .execute();
    }

    public static void incrementPlayAndPass(long id) {
        DB.sqlUpdate("""
                UPDATE maps
                SET plays = plays + 1,
                    passes = passes + 1
                WHERE id = ?
                """)
                .setParameter(1, id)
                .execute();
    }

    public static List<BeatmapEntity> findAll() {
        return DB.find(BeatmapEntity.class)
                .findList();
    }

    public static int updateStatusById(long id, int status, boolean frozen) {
        return DB.sqlUpdate("""
                UPDATE maps
                SET status = :status,
                    frozen = :frozen
                WHERE id = :id
                """)
                .setParameter("status", status)
                .setParameter("frozen", frozen)
                .setParameter("id", id)
                .execute();
    }

    public static int updateStatusBySetId(long setId, int status, boolean frozen) {
        return DB.sqlUpdate("""
                UPDATE maps
                SET status = :status,
                    frozen = :frozen
                WHERE set_id = :setId
                """)
                .setParameter("status", status)
                .setParameter("frozen", frozen)
                .setParameter("setId", setId)
                .execute();
    }

    public static long count() {
        return DB.find(BeatmapEntity.class)
                .findCount();
    }
}
