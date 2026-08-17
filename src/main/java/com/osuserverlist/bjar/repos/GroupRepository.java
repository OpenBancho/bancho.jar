package com.osuserverlist.bjar.repos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.osuserverlist.bjar.models.database.GroupEntity;
import com.osuserverlist.bjar.models.database.GroupMemberEntity;
import com.osuserverlist.bjar.models.database.GroupMemberId;
import com.osuserverlist.bjar.models.database.UserEntity;

import io.ebean.DB;

public final class GroupRepository {

    private GroupRepository() {
    }

    /** Every group, ordered by name. */
    public static List<GroupEntity> findAll() {
        // Sorted in Java rather than in SQL: the list is a handful of rows,
        // and an order-by on a lower-cased column is not portable.
        List<GroupEntity> groups = new ArrayList<>(DB.find(GroupEntity.class).findList());
        groups.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        return groups;
    }

    public static GroupEntity findById(int id) {
        return DB.find(GroupEntity.class, id);
    }

    /** Whether a group other than {@code exceptId} already carries this name. */
    public static boolean nameTaken(String name, int exceptId) {
        return DB.find(GroupEntity.class)
                .where()
                .ieq("name", name)
                .ne("id", exceptId)
                .exists();
    }

    public static void save(GroupEntity group) {
        DB.save(group);
    }

    /** Deletes the group and every membership in it. */
    public static void delete(GroupEntity group) {
        DB.find(GroupMemberEntity.class)
                .where()
                .eq("group", group)
                .delete();

        DB.delete(group);
    }

    /** The groups one account belongs to, ordered by name. */
    public static List<GroupEntity> groupsOf(int userId) {
        List<GroupMemberEntity> rows = DB.find(GroupMemberEntity.class)
                .fetch("group")
                .where()
                .eq("id.userid", userId)
                .findList();

        List<GroupEntity> groups = new ArrayList<>();

        for (GroupMemberEntity row : rows) {
            if (row.getGroup() != null) {
                groups.add(row.getGroup());
            }
        }

        groups.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        return groups;
    }

    /**
     * The groups of many accounts at once, keyed by user id. Lists like the
     * leaderboard use this so a page of fifty players costs one query instead
     * of fifty.
     */
    public static Map<Integer, List<GroupEntity>> groupsOfUsers(Collection<Integer> userIds) {
        Map<Integer, List<GroupEntity>> result = new LinkedHashMap<>();

        if (userIds == null || userIds.isEmpty()) {
            return result;
        }

        List<GroupMemberEntity> rows = DB.find(GroupMemberEntity.class)
                .fetch("group")
                .where()
                .in("id.userid", userIds)
                .findList();

        for (GroupMemberEntity row : rows) {
            if (row.getGroup() == null || row.getId() == null) {
                continue;
            }

            result.computeIfAbsent(row.getId().getUserid(), key -> new ArrayList<>())
                    .add(row.getGroup());
        }

        for (List<GroupEntity> groups : result.values()) {
            groups.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }

        return result;
    }

    /** The accounts in one group, ordered by name. */
    public static List<UserEntity> membersOf(int groupId) {
        List<GroupMemberEntity> rows = DB.find(GroupMemberEntity.class)
                .fetch("user")
                .where()
                .eq("id.groupid", groupId)
                .findList();

        List<UserEntity> members = new ArrayList<>();

        for (GroupMemberEntity row : rows) {
            if (row.getUser() != null) {
                members.add(row.getUser());
            }
        }

        members.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        return members;
    }

    public static int countMembers(int groupId) {
        return DB.find(GroupMemberEntity.class)
                .where()
                .eq("id.groupid", groupId)
                .findCount();
    }

    public static boolean isMember(int userId, int groupId) {
        return DB.find(GroupMemberEntity.class, new GroupMemberId(userId, groupId)) != null;
    }

    /** Writes the membership. The caller checks {@link #isMember} first. */
    public static void addMember(int userId, int groupId) {
        GroupMemberEntity member = new GroupMemberEntity();
        member.setId(new GroupMemberId(userId, groupId));
        // References, not loads: both rows were checked to exist by the caller.
        member.setUser(DB.reference(UserEntity.class, userId));
        member.setGroup(DB.reference(GroupEntity.class, groupId));

        DB.save(member);
    }

    public static void removeMember(int userId, int groupId) {
        DB.delete(GroupMemberEntity.class, new GroupMemberId(userId, groupId));
    }

    /** The public shape of a group: what profiles and leaderboards show. */
    public static Map<String, Object> publicGroup(GroupEntity group) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", group.getId());
        map.put("name", group.getName());
        map.put("icon", group.getIcon());
        map.put("colour", group.getColour());

        return map;
    }

    /** The public groups of one account, ready to be serialised. */
    public static List<Map<String, Object>> publicGroupsOf(int userId) {
        List<Map<String, Object>> list = new ArrayList<>();

        for (GroupEntity group : groupsOf(userId)) {
            list.add(publicGroup(group));
        }

        return list;
    }
}
