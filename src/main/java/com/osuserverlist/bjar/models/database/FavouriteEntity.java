package com.osuserverlist.bjar.models.database;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A beatmap set the player starred from inside the game.
 *
 * <p>The table is keyed by (user, set), so a set can only be favourited once and
 * the duplicate check is enforced by the database rather than by the handler.</p>
 */
@Data
@Entity
@Table(name = "favourites")
public class FavouriteEntity {

    @EmbeddedId
    private FavouriteId id;

    @Column(name = "created_at", nullable = false)
    private Integer createdAt = 0;
}
