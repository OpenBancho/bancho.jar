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
public class RatingId implements Serializable {

    private Integer userid;

    @Column(name = "map_md5", length = 32)
    private String mapMd5;
}
