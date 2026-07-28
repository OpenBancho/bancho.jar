-- Admin panel additions.
--
-- The panel needs a history of what staff did to an account: until now every
-- moderation action only reached the log file, which a web page cannot read and
-- which is rotated away. This table is that history, and it is the only new
-- storage the panel needs.
--
-- It is deliberately NOT the inherited `logs` table. That one comes from the
-- bancho.py schema this project grew out of, no Java code writes to it, and its
-- columns are called `from` and `to` - both reserved words in SQL, which every
-- query would then have to quote. A separate table costs nothing, keeps the
-- inherited one untouched in case anything outside this project still reads it,
-- and lets the columns have names that do not need escaping.

CREATE TABLE
    IF NOT EXISTS `staff_logs` (
        `id` INT NOT NULL AUTO_INCREMENT,
        -- The staff member who acted. 0 is the server itself.
        `from_id` INT NOT NULL,
        -- The account that was acted upon. For a rank decision this is the
        -- beatmap id instead, which is why there is no foreign key here.
        `to_id` INT NOT NULL,
        -- A stable machine name: restrict, unrestrict, silence, unsilence,
        -- wipe, supporter, privileges, name, country, note, rank.
        `action` VARCHAR(32) NOT NULL,
        -- The reason or detail the staff member typed.
        `msg` VARCHAR(2048) NULL DEFAULT NULL,
        `time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (`id`),
        -- The panel reads a single player's history, newest first.
        INDEX `staff_logs_to_id_time` (`to_id`, `time` DESC),
        INDEX `staff_logs_from_id_time` (`from_id`, `time` DESC),
        INDEX `staff_logs_action` (`action`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
