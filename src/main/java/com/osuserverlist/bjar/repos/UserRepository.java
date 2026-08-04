package com.osuserverlist.bjar.repos;

import java.util.List;

import com.osuserverlist.bjar.models.database.UserEntity;

import io.ebean.DB;

public final class UserRepository {

    public static UserEntity findById(int id) {
        return DB.find(UserEntity.class, id);
    }

    public static UserEntity findByName(String name) {
        return DB.find(UserEntity.class)
                .where()
                .eq("name", name)
                .findOne();
    }

    public static UserEntity findByEmail(String email) {
        return DB.find(UserEntity.class)
                .where()
                .eq("email", email)
                .findOne();
    }

    public static UserEntity findByNameOrEmail(String name, String email) {
        return DB.find(UserEntity.class)
                .where()
                .or()
                    .eq("name", name)
                    .eq("email", email)
                .endOr()
                .findOne();
    }

    public static List<UserEntity> findAll() {
        return DB.find(UserEntity.class).findList();
    }

    public static UserEntity create(String name,
                                    String safeName,
                                    String email,
                                    String passwordHash) {

        UserEntity user = new UserEntity();

        user.setName(name);
        user.setSafeName(safeName);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setCreationTime((int) (System.currentTimeMillis() / 1000));

        DB.save(user);

        return user;
    }

    public static void save(UserEntity user) {
        DB.save(user);
    }

    public static void delete(UserEntity user) {
        DB.delete(user);
    }

    public static boolean exists(int id) {
        return DB.find(UserEntity.class)
                .where()
                .idEq(id)
                .exists();
    }

    public static long count() {
        return DB.find(UserEntity.class)
                .findCount();
    }

    /**
     * How many accounts the public side is allowed to count.
     *
     * <p>A restriction takes the account out of the total too, so the number on
     * the front page and the players who can actually be found agree.
     */
    public static long countPublic() {
        return DB.find(UserEntity.class)
                .where()
                .raw("priv & 3 = 3")
                .findCount();
    }
}
