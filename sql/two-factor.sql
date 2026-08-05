-- Two factor authentication (TOTP, the authenticator app kind).
--
-- One column: the Base32 secret of the account's authenticator. NULL means 2FA is off, which
-- is what every existing row gets, so applying this changes nothing about how anybody logs in
-- until they turn it on themselves in the settings.
--
-- Everything else the feature needs is short lived and lives in Redis instead: the secret
-- somebody is in the middle of scanning, the login that is waiting for a code, and the
-- machines that have already answered one.

ALTER TABLE `users`
	ADD COLUMN `totp_secret` varchar(64) DEFAULT NULL AFTER `pw_bcrypt`;
