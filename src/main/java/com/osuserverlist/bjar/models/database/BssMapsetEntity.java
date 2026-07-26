package com.osuserverlist.bjar.models.database;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Metadata of a beatmap set that was uploaded through the in-game
 * Beatmap Submission System (BSS).
 *
 * <p>Rows in this table are the authoritative marker of a <b>locally hosted</b>
 * set. Everything that is not present here is considered a foreign (osu!)
 * set and is therefore redirected to the configured beatmap mirror when a
 * client requests a download.</p>
 */
@Data
@Entity
@Table(name = "bss_mapsets")
public class BssMapsetEntity {

    @Id
    @Column(name = "set_id")
    private Integer setId;

    @Column(name = "creator_id", nullable = false)
    private Integer creatorId;

    @Column(name = "creator_name", length = 32, nullable = false)
    private String creatorName;

    /** MD5 of the stored .osz2 file, used to detect out-of-sync clients. */
    @Column(name = "osz2_hash", length = 32)
    private String osz2Hash;

    @Column(name = "topic_id", nullable = false)
    private Integer topicId = 0;

    @Column(name = "subject", length = 128)
    private String subject;

    @Lob
    @Column(name = "message")
    private String message;

    @Column(name = "artist", length = 128)
    private String artist;

    @Column(name = "title", length = 128)
    private String title;

    /** {@link com.osuserverlist.bjar.models.osu.RankedStatus} value. */
    @Column(name = "status", nullable = false)
    private Integer status = 0;

    @Column(name = "submission_date", nullable = false)
    private LocalDateTime submissionDate;

    @Column(name = "last_update", nullable = false)
    private LocalDateTime lastUpdate;

    /** Incremented on every successful upload. */
    @Column(name = "revision", nullable = false)
    private Integer revision = 0;

    @Column(name = "has_video", nullable = false)
    private Boolean hasVideo = false;

    @Column(name = "filesize", nullable = false)
    private Integer filesize = 0;

    @Column(name = "filesize_novideo", nullable = false)
    private Integer filesizeNoVideo = 0;

    /**
     * Inactive sets keep their row (so their ids are never reused) but are
     * hidden from osu!direct and are no longer downloadable.
     */
    @Column(name = "active", nullable = false)
    private Boolean active = true;
}
