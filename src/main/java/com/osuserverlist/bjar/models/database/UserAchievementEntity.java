package com.osuserverlist.bjar.models.database;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_achievements")
public class UserAchievementEntity {

    @EmbeddedId
    private UserAchievementId id;

    @MapsId("userid")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userid", nullable = false)
    private UserEntity user;

    @MapsId("achid")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "achid", nullable = false)
    private AchievementEntity achievement;
}
