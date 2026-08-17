package com.osuserverlist.bjar.models.database;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A named badge handed to accounts by staff, shown on the profile and the
 * leaderboard. Membership lives in {@link GroupMemberEntity}.
 */
@Data
@Entity
@Table(name = "player_groups")
public class GroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "name", length = 32, nullable = false, unique = true)
    private String name;

    /** A short emoji or symbol shown in front of the name; empty for none. */
    @Column(name = "icon", length = 16, nullable = false)
    private String icon;

    /** Six hex digits, no leading hash. */
    @Column(name = "colour", length = 6, nullable = false)
    private String colour;

    @Column(name = "description", length = 128, nullable = false)
    private String description;
}
