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
public class FavouriteId implements Serializable {

    private Integer userid;
    private Integer setid;
}
