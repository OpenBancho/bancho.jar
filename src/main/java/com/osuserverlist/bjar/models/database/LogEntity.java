package com.osuserverlist.bjar.models.database;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * One staff action against one account.
 *
 * <p>The log file already records these, but a log file cannot be read by the
 * admin panel and does not survive rotation. This table is the part of the
 * history that has to be queryable: who did what to whom, and why.
 *
 * <p>Mapped to {@code staff_logs}, not to the inherited {@code logs} table: that
 * one arrives with the bancho.py schema, nothing in this project writes to it,
 * and its columns are named {@code from} and {@code to}, which are reserved
 * words that every generated query would have to quote.
 */
@Data
@Entity
@Table(name = "staff_logs")
public class LogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /** The staff member who acted; 0 is the server itself. */
    @Column(name = "from_id", nullable = false)
    private Integer fromId = 0;

    /** The account that was acted upon. */
    @Column(name = "to_id", nullable = false)
    private Integer toId;

    /** Stable machine name of the action, e.g. restrict. */
    @Column(name = "action", length = 32, nullable = false)
    private String action;

    /** The reason or detail the staff member typed. */
    @Column(name = "msg", length = 2048)
    private String message;

    @Column(name = "time", nullable = false)
    private LocalDateTime time = LocalDateTime.now();
}
