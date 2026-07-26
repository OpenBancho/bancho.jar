package com.osuserverlist.bjar.models.database;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "relationships")
public class RelationshipEntity {

    @EmbeddedId
    private RelationshipId id;

    @MapsId("user1")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user1")
    private UserEntity owner;

    @MapsId("user2")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user2")
    private UserEntity target;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private RelationshipType type;

    public static enum RelationshipType {
        friend,
        block
    }
}
