-- Secondary profile colour (supporter perk): the colour of the panels of the profile page.
-- NULL means the panels follow the primary colour, which is what every existing row gets.

ALTER TABLE `users`
	ADD COLUMN `profile_color_secondary` varchar(7) DEFAULT NULL AFTER `profile_color`;
