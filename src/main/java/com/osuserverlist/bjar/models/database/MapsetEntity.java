package com.osuserverlist.bjar.models.database;

import java.time.LocalDateTime;

import io.ebean.annotation.EnumValue;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "mapsets")
public class MapsetEntity {

    @Id
    @Column(name = "id")
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(name = "server", nullable = false)
    private ServerType server = ServerType.OSU;

    @Column(name = "last_osuapi_check", nullable = false)
    private LocalDateTime lastOsuApiCheck;

    public static enum ServerType {
        @EnumValue("osu!")
        OSU,

        @EnumValue("private")
        PRIVATE
    }
}
