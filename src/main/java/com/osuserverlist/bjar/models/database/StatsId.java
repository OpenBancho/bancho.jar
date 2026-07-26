package com.osuserverlist.bjar.models.database;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class StatsId implements Serializable {

    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "mode", nullable = false)
    private Integer mode;
}
