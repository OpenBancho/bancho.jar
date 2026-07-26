package com.osuserverlist.bjar.models.database;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "map_requests")
public class MapRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "map_id", nullable = false)
    private Integer mapId;

    @Column(name = "player_id", nullable = false)
    private Integer playerId;

    @Column(name = "admin_id", nullable = false)
    private Integer adminId = 0;

    @Column(name = "datetime")
    private LocalDateTime datetime;

    @Column(name = "active", nullable = false)
    private Boolean active;
}
