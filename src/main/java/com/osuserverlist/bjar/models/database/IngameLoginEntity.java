package com.osuserverlist.bjar.models.database;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "ingame_logins")
public class IngameLoginEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "userid")
    private UserEntity user;

    @Column(name = "ip", length = 45, nullable = false)
    private String ip;

    @Column(name = "osu_ver", length = 32, nullable = false)
    private String osuVer;

    @Column(name = "osu_stream", length = 25, nullable = false)
    private String osuStream;

    @Column(name = "datetime", nullable = false)
    private LocalDateTime dateTime;
}
