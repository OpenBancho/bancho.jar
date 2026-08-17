package com.osuserverlist.bjar.models.database;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One account's membership in one group. The composite key makes membership
 * idempotent: the same pair can never be written twice.
 */
@Data
@Entity
@Table(name = "player_group_members")
public class GroupMemberEntity {

    @EmbeddedId
    private GroupMemberId id;

    @MapsId("userid")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userid")
    private UserEntity user;

    @MapsId("groupid")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "groupid")
    private GroupEntity group;
}
