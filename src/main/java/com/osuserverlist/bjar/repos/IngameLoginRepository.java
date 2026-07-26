package com.osuserverlist.bjar.repos;

import java.time.LocalDateTime;
import java.util.List;

import com.osuserverlist.bjar.models.database.IngameLoginEntity;
import com.osuserverlist.bjar.models.database.UserEntity;

import io.ebean.DB;

public final class IngameLoginRepository {

    public static void log(UserEntity user,
                           String ip,
                           String osuVer,
                           String osuStream) {

        IngameLoginEntity login = new IngameLoginEntity();

        login.setUser(user);
        login.setIp(ip);
        login.setOsuVer(osuVer);
        login.setOsuStream(osuStream);
        login.setDateTime(LocalDateTime.now());

        DB.save(login);
    }

    public static List<IngameLoginEntity> findByUser(UserEntity user) {
        return DB.find(IngameLoginEntity.class)
                .where()
                .eq("user", user)
                .orderBy("dateTime desc")
                .findList();
    }
}
