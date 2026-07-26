package com.osuserverlist.bjar.models.database;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "channels")
public class ChannelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "name", length = 32, nullable = false, unique = true)
    private String name;

    @Column(name = "topic", length = 256, nullable = false)
    private String topic;

    @Column(name = "read_priv", nullable = false)
    private Integer readPriv = 1;

    @Column(name = "write_priv", nullable = false)
    private Integer writePriv = 2;

    @Column(name = "auto_join", nullable = false)
    private Boolean autoJoin = false;
}
