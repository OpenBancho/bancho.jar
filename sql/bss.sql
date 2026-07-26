-- Beatmap Submission System
--
-- Run this once against an existing database, after sql/base.sql.
-- Locally submitted sets keep their own bookkeeping here; the `maps` and
-- `mapsets` rows they produce use the existing `private` server value, so the
-- rest of the server (scores, leaderboards, pp) needs no change.

CREATE TABLE IF NOT EXISTS `bss_mapsets` (
  `set_id`            INT          NOT NULL,
  `creator_id`        INT          NOT NULL,
  `creator_name`      VARCHAR(32)  NOT NULL,
  `artist`            VARCHAR(128)     NULL,
  `title`             VARCHAR(128)     NULL,
  `osz2_hash`         VARCHAR(32)      NULL,
  `topic_id`          INT              NULL,
  `subject`           VARCHAR(128)     NULL,
  `message`           TEXT             NULL,
  `status`            INT          NOT NULL DEFAULT 0,
  `submission_date`   DATETIME         NULL,
  `last_update`       DATETIME         NULL,
  `revision`          INT          NOT NULL DEFAULT 0,
  `has_video`         TINYINT(1)   NOT NULL DEFAULT 0,
  `filesize`          INT          NOT NULL DEFAULT 0,
  `filesize_novideo`  INT          NOT NULL DEFAULT 0,
  `active`            TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (`set_id`),
  KEY `bss_mapsets_creator_id` (`creator_id`),
  KEY `bss_mapsets_active` (`active`),
  KEY `bss_mapsets_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
