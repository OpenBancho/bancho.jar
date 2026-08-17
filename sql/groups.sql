-- Groups: named badges staff can hand to accounts, shown on the profile and
-- the leaderboard.
--
-- Two tables. player_groups holds the group itself; player_group_members is
-- the many-to-many between groups and accounts, so an account can carry any
-- number of badges and a group any number of members.
--
-- The main table is deliberately not called `groups`: GROUPS is a reserved
-- word since MySQL 8.0.2, and the ORM does not quote identifiers in the SQL
-- it generates.

CREATE TABLE `player_groups` (
	`id` int NOT NULL AUTO_INCREMENT,
	`name` varchar(32) NOT NULL,
	-- A short emoji or symbol shown in front of the name. Empty means none.
	`icon` varchar(16) NOT NULL DEFAULT '',
	-- Six hex digits, no leading hash. The site's accent by default.
	`colour` char(6) NOT NULL DEFAULT 'ff4d8d',
	`description` varchar(128) NOT NULL DEFAULT '',
	PRIMARY KEY (`id`),
	UNIQUE KEY `player_groups_name_uindex` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE `player_group_members` (
	`userid` int NOT NULL,
	`groupid` int NOT NULL,
	PRIMARY KEY (`userid`, `groupid`),
	KEY `player_group_members_groupid_index` (`groupid`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
