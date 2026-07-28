package com.osuserverlist.bjar.repos;

import java.util.List;

import com.osuserverlist.bjar.models.database.LogEntity;

import io.ebean.DB;

/** Reads and writes the staff action history. */
public final class LogRepository {

    private LogRepository() {
    }

    /** Records one action. Never throws: a failed log must not fail the action. */
    public static void write(int fromId, int toId, String action, String message) {
        LogEntity entry = new LogEntity();

        entry.setFromId(fromId);
        entry.setToId(toId);
        entry.setAction(action);
        entry.setMessage(message);

        DB.save(entry);
    }

    /** The history of one account, newest first. */
    public static List<LogEntity> findByTarget(int userId, int offset, int limit) {
        return DB.find(LogEntity.class)
                .where()
                .eq("toId", userId)
                .orderBy("time desc, id desc")
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findList();
    }

    /** How many entries one account has. */
    public static long countByTarget(int userId) {
        return DB.find(LogEntity.class)
                .where()
                .eq("toId", userId)
                .findCount();
    }

    /** The whole history, newest first, optionally narrowed to one action. */
    public static List<LogEntity> findRecent(String action, int offset, int limit) {
        var query = DB.find(LogEntity.class).where();

        if (action != null && !action.isBlank()) {
            query = query.eq("action", action.trim());
        }

        return query
                .orderBy("time desc, id desc")
                .setFirstRow(offset)
                .setMaxRows(limit)
                .findList();
    }

    /** How many entries match, optionally narrowed to one action. */
    public static long countRecent(String action) {
        var query = DB.find(LogEntity.class).where();

        if (action != null && !action.isBlank()) {
            query = query.eq("action", action.trim());
        }

        return query.findCount();
    }
}
