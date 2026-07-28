CREATE TABLE
	`achievements` (
		`id` int NOT NULL,
		`file` varchar(128) NOT NULL,
		`name` varchar(128) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`desc` varchar(256) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`cond` varchar(64) NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`channels` (
		`id` int NOT NULL,
		`name` varchar(32) NOT NULL,
		`topic` varchar(256) NOT NULL,
		`read_priv` int NOT NULL DEFAULT '1',
		`write_priv` int NOT NULL DEFAULT '2',
		`auto_join` tinyint (1) NOT NULL DEFAULT '0'
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`clans` (
		`id` int NOT NULL,
		`name` varchar(16) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`tag` varchar(6) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`owner` int NOT NULL,
			`created_at` datetime NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`client_hashes` (
		`userid` int NOT NULL,
		`osupath` char(32) NOT NULL,
		`adapters` char(32) NOT NULL,
		`uninstall_id` char(32) NOT NULL,
		`disk_serial` char(32) NOT NULL,
		`latest_time` datetime NOT NULL,
		`occurrences` int NOT NULL DEFAULT '0'
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`comments` (
		`id` int NOT NULL,
		`target_id` int NOT NULL COMMENT 'replay, map, or set id',
		`target_type` enum ('replay', 'map', 'song') NOT NULL,
		`userid` int NOT NULL,
		`time` int NOT NULL,
		`comment` varchar(80) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`colour` char(6) DEFAULT NULL COMMENT 'rgb hex string'
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`favourites` (
		`userid` int NOT NULL,
		`setid` int NOT NULL,
		`created_at` int NOT NULL DEFAULT '0'
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`ingame_logins` (
		`id` int NOT NULL,
		`userid` int NOT NULL,
		`ip` varchar(45) NOT NULL COMMENT 'maxlen for ipv6',
		`osu_ver` date NOT NULL,
		`osu_stream` varchar(25) NOT NULL,
		`datetime` datetime NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`logs` (
		`id` int NOT NULL,
		`from` int NOT NULL COMMENT 'both from and to are playerids',
		`to` int NOT NULL,
		`action` varchar(32) NOT NULL,
		`msg` varchar(2048) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL,
			`time` datetime NOT NULL ON UPDATE CURRENT_TIMESTAMP
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`mail` (
		`id` int NOT NULL,
		`from_id` int NOT NULL,
		`to_id` int NOT NULL,
		`msg` varchar(2048) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`time` int DEFAULT NULL,
			`read` tinyint (1) NOT NULL DEFAULT '0'
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`maps` (
		`server` enum ('osu!', 'private') NOT NULL DEFAULT 'osu!',
		`id` int NOT NULL,
		`set_id` int NOT NULL,
		`status` int NOT NULL,
		`md5` char(32) NOT NULL,
		`artist` varchar(128) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`title` varchar(128) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`version` varchar(128) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`creator` varchar(19) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`filename` varchar(256) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`last_update` datetime NOT NULL,
			`total_length` int NOT NULL,
			`max_combo` int NOT NULL,
			`frozen` tinyint (1) NOT NULL DEFAULT '0',
			`plays` int NOT NULL DEFAULT '0',
			`passes` int NOT NULL DEFAULT '0',
			`mode` tinyint (1) NOT NULL DEFAULT '0',
			`bpm` float (12, 2) NOT NULL DEFAULT '0.00',
			`cs` float (4, 2) NOT NULL DEFAULT '0.00',
			`ar` float (4, 2) NOT NULL DEFAULT '0.00',
			`od` float (4, 2) NOT NULL DEFAULT '0.00',
			`hp` float (4, 2) NOT NULL DEFAULT '0.00',
			`diff` float (6, 3) NOT NULL DEFAULT '0.000'
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`mapsets` (
		`server` enum ('osu!', 'private') NOT NULL DEFAULT 'osu!',
		`id` int NOT NULL,
		`last_osuapi_check` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`map_requests` (
		`id` int NOT NULL,
		`map_id` int NOT NULL,
		`player_id` int NOT NULL,
		`admin_id` int NOT NULL DEFAULT '0',
		`datetime` datetime DEFAULT CURRENT_TIMESTAMP,
		`active` tinyint (1) NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`performance_reports` (
		`scoreid` bigint UNSIGNED NOT NULL,
		`mod_mode` enum ('vanilla', 'relax', 'autopilot') NOT NULL DEFAULT 'vanilla',
		`os` varchar(64) NOT NULL,
		`fullscreen` tinyint (1) NOT NULL,
		`fps_cap` varchar(16) NOT NULL,
		`compatibility` tinyint (1) NOT NULL,
		`version` varchar(16) NOT NULL,
		`start_time` int NOT NULL,
		`end_time` int NOT NULL,
		`frame_count` int NOT NULL,
		`spike_frames` int NOT NULL,
		`aim_rate` int NOT NULL,
		`completion` tinyint (1) NOT NULL,
		`identifier` varchar(128) DEFAULT NULL COMMENT 'really don''t know much about this yet',
		`average_frametime` int NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`ratings` (
		`userid` int NOT NULL,
		`map_md5` char(32) NOT NULL,
		`rating` tinyint NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`relationships` (
		`user1` int NOT NULL,
		`user2` int NOT NULL,
		`type` enum ('friend', 'block') NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`scores` (
		`id` bigint UNSIGNED NOT NULL,
		`map_md5` char(32) NOT NULL,
		`score` int NOT NULL,
		`pp` float (9, 3) NOT NULL,
		`acc` float (6, 3) NOT NULL,
		`max_combo` int NOT NULL,
		`mods` int NOT NULL,
		`n300` int NOT NULL,
		`n100` int NOT NULL,
		`n50` int NOT NULL,
		`nmiss` int NOT NULL,
		`ngeki` int NOT NULL,
		`nkatu` int NOT NULL,
		`grade` varchar(2) NOT NULL DEFAULT 'N',
		`status` tinyint NOT NULL,
		`mode` tinyint NOT NULL,
		`play_time` datetime NOT NULL,
		`time_elapsed` int NOT NULL,
		`client_flags` int NOT NULL,
		`userid` int NOT NULL,
		`perfect` tinyint (1) NOT NULL,
		`online_checksum` char(32) NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`startups` (
		`id` int NOT NULL,
		`ver_major` tinyint NOT NULL,
		`ver_minor` tinyint NOT NULL,
		`ver_micro` tinyint NOT NULL,
		`datetime` datetime NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`stats` (
		`id` int NOT NULL,
		`mode` tinyint (1) NOT NULL,
		`tscore` bigint UNSIGNED NOT NULL DEFAULT '0',
		`rscore` bigint UNSIGNED NOT NULL DEFAULT '0',
		`pp` int UNSIGNED NOT NULL DEFAULT '0',
		`plays` int UNSIGNED NOT NULL DEFAULT '0',
		`playtime` int UNSIGNED NOT NULL DEFAULT '0',
		`acc` float (6, 3) NOT NULL DEFAULT '0.000',
		`max_combo` int UNSIGNED NOT NULL DEFAULT '0',
		`total_hits` int UNSIGNED NOT NULL DEFAULT '0',
		`replay_views` int UNSIGNED NOT NULL DEFAULT '0',
		`xh_count` int UNSIGNED NOT NULL DEFAULT '0',
		`x_count` int UNSIGNED NOT NULL DEFAULT '0',
		`sh_count` int UNSIGNED NOT NULL DEFAULT '0',
		`s_count` int UNSIGNED NOT NULL DEFAULT '0',
		`a_count` int UNSIGNED NOT NULL DEFAULT '0'
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`tourney_pools` (
		`id` int NOT NULL,
		`name` varchar(16) NOT NULL,
		`created_at` datetime NOT NULL,
		`created_by` int NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`tourney_pool_maps` (
		`map_id` int NOT NULL,
		`pool_id` int NOT NULL,
		`mods` int NOT NULL,
		`slot` tinyint NOT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE
	`users` (
		`id` int NOT NULL,
		`name` varchar(32) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`safe_name` varchar(32) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci NOT NULL,
			`email` varchar(254) NOT NULL,
			`priv` int NOT NULL DEFAULT '1',
			`pw_bcrypt` char(60) NOT NULL,
			`country` char(2) NOT NULL DEFAULT 'xx',
			`silence_end` int NOT NULL DEFAULT '0',
			`donor_end` int NOT NULL DEFAULT '0',
			`creation_time` int NOT NULL DEFAULT '0',
			`latest_activity` int NOT NULL DEFAULT '0',
			`clan_id` int NOT NULL DEFAULT '0',
			`clan_priv` tinyint (1) NOT NULL DEFAULT '0',
			`preferred_mode` int NOT NULL DEFAULT '0',
			`play_style` int NOT NULL DEFAULT '0',
			`custom_badge_name` varchar(16) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL,
			`custom_badge_icon` varchar(64) DEFAULT NULL,
			`userpage_content` varchar(2048) CHARACTER
		SET
			utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL,
			`api_key` char(36) DEFAULT NULL
	) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

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

CREATE TABLE
	`user_achievements` (`userid` int NOT NULL, `achid` int NOT NULL) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE `achievements` ADD PRIMARY KEY (`id`),
ADD UNIQUE KEY `achievements_desc_uindex` (`desc`),
ADD UNIQUE KEY `achievements_file_uindex` (`file`),
ADD UNIQUE KEY `achievements_name_uindex` (`name`);

ALTER TABLE `channels` ADD PRIMARY KEY (`id`),
ADD UNIQUE KEY `channels_name_uindex` (`name`),
ADD KEY `channels_auto_join_index` (`auto_join`);

ALTER TABLE `clans` ADD PRIMARY KEY (`id`),
ADD UNIQUE KEY `clans_name_uindex` (`name`),
ADD UNIQUE KEY `clans_owner_uindex` (`owner`),
ADD UNIQUE KEY `clans_tag_uindex` (`tag`);

ALTER TABLE `client_hashes` ADD PRIMARY KEY (
	`userid`,
	`osupath`,
	`adapters`,
	`uninstall_id`,
	`disk_serial`
);

ALTER TABLE `comments` ADD PRIMARY KEY (`id`);

ALTER TABLE `favourites` ADD PRIMARY KEY (`userid`, `setid`);

ALTER TABLE `ingame_logins` ADD PRIMARY KEY (`id`);

ALTER TABLE `logs` ADD PRIMARY KEY (`id`);

ALTER TABLE `mail` ADD PRIMARY KEY (`id`);

ALTER TABLE `maps` ADD PRIMARY KEY (`server`, `id`),
ADD UNIQUE KEY `maps_id_uindex` (`id`),
ADD UNIQUE KEY `maps_md5_uindex` (`md5`),
ADD KEY `maps_set_id_index` (`set_id`),
ADD KEY `maps_status_index` (`status`),
ADD KEY `maps_filename_index` (`filename`),
ADD KEY `maps_plays_index` (`plays`),
ADD KEY `maps_mode_index` (`mode`),
ADD KEY `maps_frozen_index` (`frozen`);

ALTER TABLE `mapsets` ADD PRIMARY KEY (`server`, `id`),
ADD UNIQUE KEY `nmapsets_id_uindex` (`id`);

ALTER TABLE `map_requests` ADD PRIMARY KEY (`id`);

ALTER TABLE `performance_reports` ADD PRIMARY KEY (`scoreid`, `mod_mode`);

ALTER TABLE `ratings` ADD PRIMARY KEY (`userid`, `map_md5`);

ALTER TABLE `relationships` ADD PRIMARY KEY (`user1`, `user2`);

ALTER TABLE `scores` ADD PRIMARY KEY (`id`),
ADD KEY `scores_map_md5_index` (`map_md5`),
ADD KEY `scores_score_index` (`score`),
ADD KEY `scores_pp_index` (`pp`),
ADD KEY `scores_mods_index` (`mods`),
ADD KEY `scores_status_index` (`status`),
ADD KEY `scores_mode_index` (`mode`),
ADD KEY `scores_play_time_index` (`play_time`),
ADD KEY `scores_userid_index` (`userid`),
ADD KEY `scores_online_checksum_index` (`online_checksum`),
ADD KEY `scores_fetch_leaderboard_generic_index` (`map_md5`, `status`, `mode`);

ALTER TABLE `startups` ADD PRIMARY KEY (`id`);

ALTER TABLE `stats` ADD PRIMARY KEY (`id`, `mode`),
ADD KEY `stats_mode_index` (`mode`),
ADD KEY `stats_pp_index` (`pp`),
ADD KEY `stats_tscore_index` (`tscore`),
ADD KEY `stats_rscore_index` (`rscore`);

ALTER TABLE `tourney_pools` ADD PRIMARY KEY (`id`),
ADD KEY `tourney_pools_users_id_fk` (`created_by`);

ALTER TABLE `tourney_pool_maps` ADD PRIMARY KEY (`map_id`, `pool_id`),
ADD KEY `tourney_pool_maps_mods_slot_index` (`mods`, `slot`),
ADD KEY `tourney_pool_maps_tourney_pools_id_fk` (`pool_id`);

ALTER TABLE `users` ADD PRIMARY KEY (`id`),
ADD UNIQUE KEY `users_email_uindex` (`email`),
ADD UNIQUE KEY `users_name_uindex` (`name`),
ADD UNIQUE KEY `users_safe_name_uindex` (`safe_name`),
ADD UNIQUE KEY `users_api_key_uindex` (`api_key`),
ADD KEY `users_priv_index` (`priv`),
ADD KEY `users_clan_id_index` (`clan_id`),
ADD KEY `users_clan_priv_index` (`clan_priv`),
ADD KEY `users_country_index` (`country`);

ALTER TABLE `user_achievements` ADD PRIMARY KEY (`userid`, `achid`),
ADD KEY `user_achievements_achid_index` (`achid`),
ADD KEY `user_achievements_userid_index` (`userid`);

ALTER TABLE `achievements` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `channels` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `clans` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `comments` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `ingame_logins` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `logs` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `mail` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `map_requests` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `scores` MODIFY `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT;

ALTER TABLE `startups` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `stats` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `tourney_pools` MODIFY `id` int NOT NULL AUTO_INCREMENT;

ALTER TABLE `users` MODIFY `id` int NOT NULL AUTO_INCREMENT;

insert into users (id, name, safe_name, priv, country, silence_end, email, pw_bcrypt, creation_time, latest_activity)
values (1, 'BanchoBot', 'banchobot', 1, 'us', 0, 'bot@osu-server-list.com',
        '_______________________my_cool_bcrypt_______________________', UNIX_TIMESTAMP(), UNIX_TIMESTAMP());

INSERT INTO stats (id, mode) VALUES (1, 0); # vn!std
INSERT INTO stats (id, mode) VALUES (1, 1); # vn!taiko
INSERT INTO stats (id, mode) VALUES (1, 2); # vn!catch
INSERT INTO stats (id, mode) VALUES (1, 3); # vn!mania
INSERT INTO stats (id, mode) VALUES (1, 4); # rx!std
INSERT INTO stats (id, mode) VALUES (1, 5); # rx!taiko
INSERT INTO stats (id, mode) VALUES (1, 6); # rx!catch
INSERT INTO stats (id, mode) VALUES (1, 8); # ap!std


# userid 2 is reserved for ppy in osu!, and the
# client will not allow users to pm this id.
# If you want this, simply remove these two lines.
alter table users auto_increment = 3;
alter table stats auto_increment = 3;

insert into channels (name, topic, read_priv, write_priv, auto_join)
values ('#osu', 'General discussion.', 1, 2, true),
	   ('#announce', 'Exemplary performance and public announcements.', 1, 24576, true),
	   ('#lobby', 'Multiplayer lobby discussion room.', 1, 2, false),
	   ('#supporter', 'General discussion for supporters.', 48, 48, false),
	   ('#staff', 'General discussion for staff members.', 28672, 28672, true),
	   ('#admin', 'General discussion for administrators.', 24576, 24576, true),
	   ('#dev', 'General discussion for developers.', 16384, 16384, true);

insert into achievements (id, file, name, `desc`, cond) values (1, 'osu-skill-pass-1', 'Rising Star', 'Can''t go forward without the first steps.', '(score.mods & 1 == 0) and 1 <= score.sr < 2 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (2, 'osu-skill-pass-2', 'Constellation Prize', 'Definitely not a consolation prize. Now things start getting hard!', '(score.mods & 1 == 0) and 2 <= score.sr < 3 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (3, 'osu-skill-pass-3', 'Building Confidence', 'Oh, you''ve SO got this.', '(score.mods & 1 == 0) and 3 <= score.sr < 4 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (4, 'osu-skill-pass-4', 'Insanity Approaches', 'You''re not twitching, you''re just ready.', '(score.mods & 1 == 0) and 4 <= score.sr < 5 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (5, 'osu-skill-pass-5', 'These Clarion Skies', 'Everything seems so clear now.', '(score.mods & 1 == 0) and 5 <= score.sr < 6 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (6, 'osu-skill-pass-6', 'Above and Beyond', 'A cut above the rest.', '(score.mods & 1 == 0) and 6 <= score.sr < 7 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (7, 'osu-skill-pass-7', 'Supremacy', 'All marvel before your prowess.', '(score.mods & 1 == 0) and 7 <= score.sr < 8 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (8, 'osu-skill-pass-8', 'Absolution', 'My god, you''re full of stars!', '(score.mods & 1 == 0) and 8 <= score.sr < 9 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (9, 'osu-skill-pass-9', 'Event Horizon', 'No force dares to pull you under.', '(score.mods & 1 == 0) and 9 <= score.sr < 10 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (10, 'osu-skill-pass-10', 'Phantasm', 'Fevered is your passion, extraordinary is your skill.', '(score.mods & 1 == 0) and 10 <= score.sr < 11 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (11, 'osu-skill-fc-1', 'Totality', 'All the notes. Every single one.', 'score.perfect and 1 <= score.sr < 2 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (12, 'osu-skill-fc-2', 'Business As Usual', 'Two to go, please.', 'score.perfect and 2 <= score.sr < 3 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (13, 'osu-skill-fc-3', 'Building Steam', 'Hey, this isn''t so bad.', 'score.perfect and 3 <= score.sr < 4 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (14, 'osu-skill-fc-4', 'Moving Forward', 'Bet you feel good about that.', 'score.perfect and 4 <= score.sr < 5 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (15, 'osu-skill-fc-5', 'Paradigm Shift', 'Surprisingly difficult.', 'score.perfect and 5 <= score.sr < 6 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (16, 'osu-skill-fc-6', 'Anguish Quelled', 'Don''t choke.', 'score.perfect and 6 <= score.sr < 7 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (17, 'osu-skill-fc-7', 'Never Give Up', 'Excellence is its own reward.', 'score.perfect and 7 <= score.sr < 8 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (18, 'osu-skill-fc-8', 'Aberration', 'They said it couldn''t be done. They were wrong.', 'score.perfect and 8 <= score.sr < 9 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (19, 'osu-skill-fc-9', 'Chosen', 'Reign among the Prometheans, where you belong.', 'score.perfect and 9 <= score.sr < 10 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (20, 'osu-skill-fc-10', 'Unfathomable', 'You have no equal.', 'score.perfect and 10 <= score.sr < 11 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (21, 'osu-combo-500', '500 Combo', '500 big ones! You''re moving up in the world!', '500 <= score.max_combo < 750 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (22, 'osu-combo-750', '750 Combo', '750 notes back to back? Woah.', '750 <= score.max_combo < 1000 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (23, 'osu-combo-1000', '1000 Combo', 'A thousand reasons why you rock at this game.', '1000 <= score.max_combo < 2000 and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (24, 'osu-combo-2000', '2000 Combo', 'Nothing can stop you now.', '2000 <= score.max_combo and mode_vn == 0');
insert into achievements (id, file, name, `desc`, cond) values (25, 'taiko-skill-pass-1', 'My First Don', 'Marching to the beat of your own drum. Literally.', '(score.mods & 1 == 0) and 1 <= score.sr < 2 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (26, 'taiko-skill-pass-2', 'Katsu Katsu Katsu', 'Hora! Izuko!', '(score.mods & 1 == 0) and 2 <= score.sr < 3 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (27, 'taiko-skill-pass-3', 'Not Even Trying', 'Muzukashii? Not even.', '(score.mods & 1 == 0) and 3 <= score.sr < 4 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (28, 'taiko-skill-pass-4', 'Face Your Demons', 'The first trials are now behind you, but are you a match for the Oni?', '(score.mods & 1 == 0) and 4 <= score.sr < 5 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (29, 'taiko-skill-pass-5', 'The Demon Within', 'No rest for the wicked.', '(score.mods & 1 == 0) and 5 <= score.sr < 6 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (30, 'taiko-skill-pass-6', 'Drumbreaker', 'Too strong.', '(score.mods & 1 == 0) and 6 <= score.sr < 7 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (31, 'taiko-skill-pass-7', 'The Godfather', 'You are the Don of Dons.', '(score.mods & 1 == 0) and 7 <= score.sr < 8 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (32, 'taiko-skill-pass-8', 'Rhythm Incarnate', 'Feel the beat. Become the beat.', '(score.mods & 1 == 0) and 8 <= score.sr < 9 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (33, 'taiko-skill-fc-1', 'Keeping Time', 'Don, then katsu. Don, then katsu..', 'score.perfect and 1 <= score.sr < 2 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (34, 'taiko-skill-fc-2', 'To Your Own Beat', 'Straight and steady.', 'score.perfect and 2 <= score.sr < 3 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (35, 'taiko-skill-fc-3', 'Big Drums', 'Bigger scores to match.', 'score.perfect and 3 <= score.sr < 4 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (36, 'taiko-skill-fc-4', 'Adversity Overcome', 'Difficult? Not for you.', 'score.perfect and 4 <= score.sr < 5 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (37, 'taiko-skill-fc-5', 'Demonslayer', 'An Oni felled forevermore.', 'score.perfect and 5 <= score.sr < 6 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (38, 'taiko-skill-fc-6', 'Rhythm''s Call', 'Heralding true skill.', 'score.perfect and 6 <= score.sr < 7 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (39, 'taiko-skill-fc-7', 'Time Everlasting', 'Not a single beat escapes you.', 'score.perfect and 7 <= score.sr < 8 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (40, 'taiko-skill-fc-8', 'The Drummer''s Throne', 'Percussive brilliance befitting royalty alone.', 'score.perfect and 8 <= score.sr < 9 and mode_vn == 1');
insert into achievements (id, file, name, `desc`, cond) values (41, 'fruits-skill-pass-1', 'A Slice Of Life', 'Hey, this fruit catching business isn''t bad.', '(score.mods & 1 == 0) and 1 <= score.sr < 2 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (42, 'fruits-skill-pass-2', 'Dashing Ever Forward', 'Fast is how you do it.', '(score.mods & 1 == 0) and 2 <= score.sr < 3 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (43, 'fruits-skill-pass-3', 'Zesty Disposition', 'No scurvy for you, not with that much fruit.', '(score.mods & 1 == 0) and 3 <= score.sr < 4 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (44, 'fruits-skill-pass-4', 'Hyperdash ON!', 'Time and distance is no obstacle to you.', '(score.mods & 1 == 0) and 4 <= score.sr < 5 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (45, 'fruits-skill-pass-5', 'It''s Raining Fruit', 'And you can catch them all.', '(score.mods & 1 == 0) and 5 <= score.sr < 6 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (46, 'fruits-skill-pass-6', 'Fruit Ninja', 'Legendary techniques.', '(score.mods & 1 == 0) and 6 <= score.sr < 7 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (47, 'fruits-skill-pass-7', 'Dreamcatcher', 'No fruit, only dreams now.', '(score.mods & 1 == 0) and 7 <= score.sr < 8 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (48, 'fruits-skill-pass-8', 'Lord of the Catch', 'Your kingdom kneels before you.', '(score.mods & 1 == 0) and 8 <= score.sr < 9 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (49, 'fruits-skill-fc-1', 'Sweet And Sour', 'Apples and oranges, literally.', 'score.perfect and 1 <= score.sr < 2 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (50, 'fruits-skill-fc-2', 'Reaching The Core', 'The seeds of future success.', 'score.perfect and 2 <= score.sr < 3 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (51, 'fruits-skill-fc-3', 'Clean Platter', 'Clean only of failure. It is completely full, otherwise.', 'score.perfect and 3 <= score.sr < 4 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (52, 'fruits-skill-fc-4', 'Between The Rain', 'No umbrella needed.', 'score.perfect and 4 <= score.sr < 5 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (53, 'fruits-skill-fc-5', 'Addicted', 'That was an overdose?', 'score.perfect and 5 <= score.sr < 6 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (54, 'fruits-skill-fc-6', 'Quickening', 'A dash above normal limits.', 'score.perfect and 6 <= score.sr < 7 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (55, 'fruits-skill-fc-7', 'Supersonic', 'Faster than is reasonably necessary.', 'score.perfect and 7 <= score.sr < 8 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (56, 'fruits-skill-fc-8', 'Dashing Scarlet', 'Speed beyond mortal reckoning.', 'score.perfect and 8 <= score.sr < 9 and mode_vn == 2');
insert into achievements (id, file, name, `desc`, cond) values (57, 'mania-skill-pass-1', 'First Steps', 'It isn''t 9-to-5, but 1-to-9. Keys, that is.', '(score.mods & 1 == 0) and 1 <= score.sr < 2 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (58, 'mania-skill-pass-2', 'No Normal Player', 'Not anymore, at least.', '(score.mods & 1 == 0) and 2 <= score.sr < 3 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (59, 'mania-skill-pass-3', 'Impulse Drive', 'Not quite hyperspeed, but getting close.', '(score.mods & 1 == 0) and 3 <= score.sr < 4 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (60, 'mania-skill-pass-4', 'Hyperspeed', 'Woah.', '(score.mods & 1 == 0) and 4 <= score.sr < 5 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (61, 'mania-skill-pass-5', 'Ever Onwards', 'Another challenge is just around the corner.', '(score.mods & 1 == 0) and 5 <= score.sr < 6 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (62, 'mania-skill-pass-6', 'Another Surpassed', 'Is there no limit to your skills?', '(score.mods & 1 == 0) and 6 <= score.sr < 7 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (63, 'mania-skill-pass-7', 'Extra Credit', 'See me after class.', '(score.mods & 1 == 0) and 7 <= score.sr < 8 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (64, 'mania-skill-pass-8', 'Maniac', 'There''s just no stopping you.', '(score.mods & 1 == 0) and 8 <= score.sr < 9 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (65, 'mania-skill-fc-1', 'Keystruck', 'The beginning of a new story', 'score.perfect and 1 <= score.sr < 2 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (66, 'mania-skill-fc-2', 'Keying In', 'Finding your groove.', 'score.perfect and 2 <= score.sr < 3 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (67, 'mania-skill-fc-3', 'Hyperflow', 'You can *feel* the rhythm.', 'score.perfect and 3 <= score.sr < 4 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (68, 'mania-skill-fc-4', 'Breakthrough', 'Many skills mastered, rolled into one.', 'score.perfect and 4 <= score.sr < 5 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (69, 'mania-skill-fc-5', 'Everything Extra', 'Giving your all is giving everything you have.', 'score.perfect and 5 <= score.sr < 6 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (70, 'mania-skill-fc-6', 'Level Breaker', 'Finesse beyond reason', 'score.perfect and 6 <= score.sr < 7 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (71, 'mania-skill-fc-7', 'Step Up', 'A precipice rarely seen.', 'score.perfect and 7 <= score.sr < 8 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (72, 'mania-skill-fc-8', 'Behind The Veil', 'Supernatural!', 'score.perfect and 8 <= score.sr < 9 and mode_vn == 3');
insert into achievements (id, file, name, `desc`, cond) values (73, 'all-intro-suddendeath', 'Finality', 'High stakes, no regrets.', 'score.mods == 32');
insert into achievements (id, file, name, `desc`, cond) values (74, 'all-intro-hidden', 'Blindsight', 'I can see just perfectly', 'score.mods & 8');
insert into achievements (id, file, name, `desc`, cond) values (75, 'all-intro-perfect', 'Perfectionist', 'Accept nothing but the best.', 'score.mods & 16384');
insert into achievements (id, file, name, `desc`, cond) values (76, 'all-intro-hardrock', 'Rock Around The Clock', "You can\'t stop the rock.", 'score.mods & 16');
insert into achievements (id, file, name, `desc`, cond) values (77, 'all-intro-doubletime', 'Time And A Half', "Having a right ol\' time. One and a half of them, almost.", 'score.mods & 64');
insert into achievements (id, file, name, `desc`, cond) values (78, 'all-intro-flashlight', 'Are You Afraid Of The Dark?', "Harder than it looks, probably because it\'s hard to look.", 'score.mods & 1024');
insert into achievements (id, file, name, `desc`, cond) values (79, 'all-intro-easy', 'Dial It Right Back', 'Sometimes you just want to take it easy.', 'score.mods & 2');
insert into achievements (id, file, name, `desc`, cond) values (80, 'all-intro-nofail', 'Risk Averse', 'Safety nets are fun!', 'score.mods & 1');
insert into achievements (id, file, name, `desc`, cond) values (81, 'all-intro-nightcore', 'Sweet Rave Party', 'Founded in the fine tradition of changing things that were just fine as they were.', 'score.mods & 512');
insert into achievements (id, file, name, `desc`, cond) values (82, 'all-intro-halftime', 'Slowboat', 'You got there. Eventually.', 'score.mods & 256');
insert into achievements (id, file, name, `desc`, cond) values (83, 'all-intro-spunout', 'Burned Out', 'One cannot always spin to win.', 'score.mods & 4096');

COMMIT;