-- Profile page background (supporter perk).
--
-- One column: the public path of the uploaded picture, e.g. /api/v1/background/42.jpg.
-- NULL means the profile has no custom background, which is what every existing row gets,
-- so applying this changes nothing about how any profile looks until a supporter uploads one.

ALTER TABLE `users`
	ADD COLUMN `custom_background` varchar(64) DEFAULT NULL AFTER `custom_banner`;
