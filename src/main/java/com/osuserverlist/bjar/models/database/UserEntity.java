package com.osuserverlist.bjar.models.database;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "name", length = 32, nullable = false)
    private String name;

    @Column(name = "safe_name", length = 32, nullable = false)
    private String safeName;

    @Column(name = "email", length = 254, nullable = false)
    private String email;

    @Column(name = "priv", nullable = false)
    private Integer privileges = 1;

    @Column(name = "pw_bcrypt", length = 60, nullable = false)
    private String passwordHash;

    @Column(name = "country", length = 2, nullable = false)
    private String country = "xx";

    @Column(name = "silence_end")
    private Integer silenceEnd = 0;

    @Column(name = "donor_end")
    private Integer donorEnd = 0;

    @Column(name = "creation_time")
    private Integer creationTime = 0;

    @Column(name = "latest_activity")
    private Integer latestActivity = 0;

    @Column(name = "clan_id")
    private Integer clanId = 0;

    @Column(name = "clan_priv")
    private Boolean clanPriv = false;

    @Column(name = "preferred_mode")
    private Integer preferredMode = 0;

    @Column(name = "play_style")
    private Integer playStyle = 0;

    @Column(name = "custom_badge_name", length = 16)
    private String customBadgeName;

    @Column(name = "custom_badge_icon", length = 64)
    private String customBadgeIcon;

    /** Path of the uploaded profile cover, written by the banner upload route. */
    @Column(name = "custom_banner", length = 64)
    private String customBanner;

    @Column(name = "userpage_content", length = 2048)
    private String userpageContent;

    @Column(name = "api_key", length = 36)
    private String apiKey;
}
