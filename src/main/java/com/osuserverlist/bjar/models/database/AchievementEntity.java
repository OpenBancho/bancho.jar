package com.osuserverlist.bjar.models.database;

import java.io.Serializable;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "achievements")
public class AchievementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "file", length = 128, nullable = false, unique = true)
    private String file;

    @Column(name = "name", length = 128, nullable = false, unique = true)
    private String name;

    @Column(name = "desc", length = 256, nullable = false, unique = true)
    private String description;

    @Column(name = "cond", length = 64, nullable = false)
    private String condition;

    @Transient
    private Serializable conditionCompiled;
}
