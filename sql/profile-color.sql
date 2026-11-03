-- Profile colour scheme (supporter perk).
--
-- One column: the accent colour of the profile page as #rrggbb. NULL means the default
-- colours, which is what every existing row gets, so applying this changes nothing about
-- how any profile looks until a supporter picks a colour.

ALTER TABLE `users`
	ADD COLUMN `profile_color` varchar(7) DEFAULT NULL AFTER `custom_background`;
