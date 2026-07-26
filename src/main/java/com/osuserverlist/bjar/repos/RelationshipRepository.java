package com.osuserverlist.bjar.repos;

import java.util.List;

import com.osuserverlist.bjar.models.database.RelationshipEntity;
import com.osuserverlist.bjar.models.database.RelationshipEntity.RelationshipType;
import com.osuserverlist.bjar.models.database.RelationshipId;
import com.osuserverlist.bjar.models.database.UserEntity;

import io.ebean.DB;

public final class RelationshipRepository {

    public static List<RelationshipEntity> getFriends(UserEntity user) {
        return DB.find(RelationshipEntity.class)
                .where()
                .eq("owner", user)
                .eq("type", RelationshipType.friend)
                .findList();
    }

    public static List<RelationshipEntity> getBlocks(UserEntity user) {
        return DB.find(RelationshipEntity.class)
                .where()
                .eq("owner", user)
                .eq("type", RelationshipType.block)
                .findList();
    }

    public static List<UserEntity> getFriendUsers(UserEntity user) {
        return getFriends(user)
                .stream()
                .map(RelationshipEntity::getTarget)
                .toList();
    }

    public static List<UserEntity> getBlockedUsers(UserEntity user) {
        return getBlocks(user)
                .stream()
                .map(RelationshipEntity::getTarget)
                .toList();
    }

    public static void addFriend(UserEntity owner, UserEntity target) {

        RelationshipEntity relationship = new RelationshipEntity();
        relationship.setId(new RelationshipId(owner.getId(), target.getId()));
        relationship.setOwner(owner);
        relationship.setTarget(target);
        relationship.setType(RelationshipType.friend);

        DB.save(relationship);
    }

    public static void addBlock(UserEntity owner, UserEntity target) {

        RelationshipEntity relationship = new RelationshipEntity();
        relationship.setId(new RelationshipId(owner.getId(), target.getId()));
        relationship.setOwner(owner);
        relationship.setTarget(target);
        relationship.setType(RelationshipType.block);

        DB.save(relationship);
    }

    public static void remove(UserEntity owner, UserEntity target) {
        DB.delete(RelationshipEntity.class,
                new RelationshipId(owner.getId(), target.getId()));
    }

    public static void removeFriend(UserEntity owner, UserEntity target) {
        DB.find(RelationshipEntity.class)
                .where()
                .eq("owner", owner)
                .eq("target", target)
                .eq("type", RelationshipType.friend)
                .delete();
    }

    public static void removeBlock(UserEntity owner, UserEntity target) {
        DB.find(RelationshipEntity.class)
                .where()
                .eq("owner", owner)
                .eq("target", target)
                .eq("type", RelationshipType.block)
                .delete();
    }

    public static boolean isFriend(UserEntity owner, UserEntity target) {
        return DB.find(RelationshipEntity.class)
                .where()
                .eq("owner", owner)
                .eq("target", target)
                .eq("type", RelationshipType.friend)
                .exists();
    }

    public static boolean isBlocked(UserEntity owner, UserEntity target) {
        return DB.find(RelationshipEntity.class)
                .where()
                .eq("owner", owner)
                .eq("target", target)
                .eq("type", RelationshipType.block)
                .exists();
    }
}
