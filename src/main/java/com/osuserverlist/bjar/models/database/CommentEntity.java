package com.osuserverlist.bjar.models.database;

import jakarta.persistence.*;
import lombok.Data;

/**
 * An in-game comment scrolling across the screen during a replay.
 *
 * <p>The target is stored as a bare id plus a type, because the same row shape
 * serves comments left on a replay, on a difficulty and on a whole song.</p>
 */
@Data
@Entity
@Table(name = "comments")
public class CommentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "target_id", nullable = false)
    private Integer targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private TargetType targetType;

    @Column(name = "userid", nullable = false)
    private Integer userid;

    /** Milliseconds into the map at which the comment appears. */
    @Column(name = "time", nullable = false)
    private Integer time;

    @Column(name = "comment", length = 80, nullable = false)
    private String comment;

    /** Six character rgb hex string, supporter only, null for the default colour. */
    @Column(name = "colour", length = 6)
    private String colour;

    public static enum TargetType {
        replay,
        map,
        song
    }
}
