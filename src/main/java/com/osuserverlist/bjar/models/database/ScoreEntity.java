package com.osuserverlist.bjar.models.database;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "scores")
public class ScoreEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "map_md5", length = 32, nullable = false)
    private String mapMd5;

    @Column(name = "score", nullable = false)
    private Long score;

    @Column(name = "pp", nullable = false)
    private Float pp;

    @Column(name = "acc", nullable = false)
    private Float acc;

    @Column(name = "max_combo", nullable = false)
    private Integer maxCombo;

    @Column(name = "mods", nullable = false)
    private Integer mods;

    @Column(name = "n300", nullable = false)
    private Integer n300;

    @Column(name = "n100", nullable = false)
    private Integer n100;

    @Column(name = "n50", nullable = false)
    private Integer n50;

    @Column(name = "nmiss", nullable = false)
    private Integer nmiss;

    @Column(name = "ngeki", nullable = false)
    private Integer ngeki;

    @Column(name = "nkatu", nullable = false)
    private Integer nkatu;

    @Column(name = "grade", length = 2, nullable = false)
    private String grade;

    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "mode", nullable = false)
    private Integer mode;

    @Column(name = "play_time", nullable = false)
    private LocalDateTime playTime;

    @Column(name = "time_elapsed", nullable = false)
    private Integer timeElapsed;

    @Column(name = "client_flags", nullable = false)
    private Integer clientFlags;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userid", nullable = false)
    private UserEntity user;

    @Column(name = "perfect", nullable = false)
    private Boolean perfect;

    @Column(name = "online_checksum", length = 32, nullable = false)
    private String onlineChecksum;
}
