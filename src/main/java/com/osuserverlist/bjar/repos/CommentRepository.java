package com.osuserverlist.bjar.repos;

import java.util.List;

import com.osuserverlist.bjar.models.database.CommentEntity;
import com.osuserverlist.bjar.models.database.CommentEntity.TargetType;

import io.ebean.DB;

public final class CommentRepository {

    private CommentRepository() {
    }

    /**
     * Every comment the client should draw over one replay: the ones left on that
     * replay, on the difficulty being played, and on the song as a whole.
     */
    public static List<CommentEntity> findForReplay(long scoreId, long mapId, long mapSetId) {
        return DB.find(CommentEntity.class)
                .where()
                .or()
                .and()
                .eq("targetType", TargetType.replay)
                .eq("targetId", (int) scoreId)
                .endAnd()
                .and()
                .eq("targetType", TargetType.map)
                .eq("targetId", (int) mapId)
                .endAnd()
                .and()
                .eq("targetType", TargetType.song)
                .eq("targetId", (int) mapSetId)
                .endAnd()
                .endOr()
                .orderBy("time asc")
                .findList();
    }

    public static List<CommentEntity> findByTarget(TargetType type, int targetId) {
        return DB.find(CommentEntity.class)
                .where()
                .eq("targetType", type)
                .eq("targetId", targetId)
                .orderBy("time asc")
                .findList();
    }

    public static void save(CommentEntity comment) {
        DB.save(comment);
    }

    public static void delete(CommentEntity comment) {
        DB.delete(comment);
    }

    public static int deleteByUser(int userId) {
        return DB.find(CommentEntity.class)
                .where()
                .eq("userid", userId)
                .delete();
    }
}
