package com.osuserverlist.bjar.models.database;

import java.io.Serializable;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class RelationshipId implements Serializable {

    private Integer user1;
    private Integer user2;
}
