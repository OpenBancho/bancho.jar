package com.osuserverlist.bjar.models.database;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One player's 1-10 vote on one beatmap.
 *
 * <p>Ratings are keyed by md5 rather than by beatmap id: the client votes on the
 * exact file it has, and a re-upload with the same id is a different map as far
 * as the vote is concerned.</p>
 */
@Data
@Entity
@Table(name = "ratings")
public class RatingEntity {

    @EmbeddedId
    private RatingId id;

    @Column(name = "rating", nullable = false)
    private Integer rating;
}
