# Public API — OAuth2

Authentication is OAuth2 with short-lived access tokens and rotating refresh tokens. The API
does the whole job itself: it verifies the credentials, issues the tokens, stores them in Redis
and checks them on every request. Nothing about a caller's identity or privileges is ever taken
from the client.

## Model

- **Access token** — opaque, 256 bits of randomness, valid one hour by default. Sent as
  `Authorization: Bearer <token>`.
- **Refresh token** — opaque, valid 30 days by default. Only ever sent to `/api/v1/oauth/token`.
- **Rotation** — every refresh issues a brand new pair and kills the one presented.
- **Replay detection** — all refresh tokens descending from one login share a `familyId`, and the
  family remembers the single token that is currently live. Presenting an already-rotated token
  means either the client or a thief is holding a copy, and there is no way to tell which, so the
  entire family is revoked and the user has to log in again.
- **Storage** — `bjar:oauth:access:*`, `bjar:oauth:refresh:*`, `bjar:oauth:family:*`. Redis expiry
  is the source of truth for a token's lifetime.
- **Cookies** — the token endpoint also sets `bjar_access` and `bjar_refresh` as HttpOnly cookies
  for browser clients that would rather not keep tokens in JavaScript. The refresh cookie is
  scoped to `/api/v1/oauth`, so it never travels with ordinary requests. Header beats cookie.
  They are issued for `.<DOMAIN>`, so the whole server shares one login.

Because access tokens are short lived and privileges are re-read from the database on every
request, revoking someone's rights takes effect on their very next call.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `OAUTH_ACCESS_TTL_MINUTES` | `60` | Access token lifetime |
| `OAUTH_REFRESH_TTL_DAYS` | `30` | Refresh token lifetime; reset on every rotation |

That is the whole of it. Cookie flags are deliberately not configurable:

- **Domain** follows `DOMAIN`, as `.<DOMAIN>`, so one login covers `api.`, `osu.` and the
  frontend without a second setting that can silently disagree with the first.
- **Secure** follows `LEVEL`: on under `PROD`, off under `DEV` so local http still works.
- **SameSite** is always `Lax`. Subdomains of one site are same-site, so `Lax` already lets the
  cookies through while refusing to ride along with genuinely cross-site requests.
- **HttpOnly** is always on.

## Token endpoint

`POST /api/v1/oauth/token`, on the `api.` host. Accepts form fields (as the spec describes) or a
JSON body (as the rest of this API does).

### Password grant

| Field | Required | Notes |
| --- | --- | --- |
| `grant_type` | yes | `password` |
| `username` | yes | |
| `password` | yes | or `password_md5` for clients that already hash it |
| `scope` | no | space separated; defaults to `identify` |
| `client_id` | no | recorded in the audit log |

```json
{
  "access_token": "…",
  "token_type": "Bearer",
  "expires_in": 3600,
  "refresh_token": "…",
  "refresh_expires_in": 2592000,
  "scope": "identify admin"
}
```

### Refresh grant

| Field | Required | Notes |
| --- | --- | --- |
| `grant_type` | yes | `refresh_token` |
| `refresh_token` | no | falls back to the `bjar_refresh` cookie |

The response is identical, with a new pair. The old refresh token is dead from that moment.

### Errors

RFC 6749 shape: `{"error": "invalid_grant", "error_description": "…"}`.

| Code | `error` | When |
| --- | --- | --- |
| `400` | `invalid_request` | Missing parameters |
| `400` | `unsupported_grant_type` | Anything but `password` or `refresh_token` |
| `401` | `invalid_grant` | Bad credentials, or a dead/replayed refresh token |
| `503` | `temporarily_unavailable` | Redis is down |

## Other OAuth endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/oauth/revoke` | Revoke a token; `token` plus optional `token_type_hint`, or just the cookies. Always answers `200`, per RFC 7009. Revoking a refresh token takes its whole family with it |
| `GET` | `/api/v1/oauth/userinfo` | The identity, scope and expiry behind the current access token |

## Scopes

| Scope | Grants |
| --- | --- |
| `identify` | Read your own identity (`userinfo`, `me`). Default when nothing is requested |
| `profile` | Change your own account: profile, email, password, deletion |
| `moderation` | Acting on players: restrict, unrestrict, alert, country |
| `beatmaps` | Acting on beatmaps: status changes |
| `admin` | Acting on the server: wipe, donator, privileges, rename |

Scope narrows what a token may do; it never widens it. A token with `admin` scope on a normal
account still gets `403`.

Every protected endpoint checks two independent things: the **scope** the token was granted,
and the **privilege** the account holds. They answer different questions — what this token may
do on your behalf, and who you are on the server. A moderation bot asks for `moderation` only,
so a leak of its token cannot rank a map or hand out rights.

## Self service endpoints

Everything a signed in player may do to their own account. None of these take a user id: the
account is always the one behind the access token, so they cannot be aimed at anybody else.

| Path | Method | Scope | Body |
| --- | --- | --- | --- |
| `/api/v1/me` | `GET` | `identify` | — |
| `/api/v1/me/update` | `POST` | `profile` | any of `userpage_content`, `preferred_mode`, `play_style`, `custom_badge_name`, `custom_badge_icon` |
| `/api/v1/me/email` | `POST` | `profile` | `current_password`, `email` |
| `/api/v1/me/password` | `POST` | `profile` | `current_password`, `new_password` |
| `/api/v1/me/delete` | `POST` | `profile` | `current_password`, `confirm: true` |

- `GET /api/v1/me` returns everything `get_player_details` does, plus the private fields:
  email, silence and donor expiry, userpage, badge and clan rank.
- Writes additionally require the `UNRESTRICTED` privilege, so a restricted account can read
  its data and leave, but not rewrite its profile.
- Sending `null` clears `userpage_content` and the badge fields. Omitted fields are left alone,
  and a body that changes nothing answers `400`.
- Custom badges require `SUPPORTER` or `PREMIUM`, matching the in-game rule.
- Email, password and deletion re-check `current_password` (or `current_password_md5`), so a
  stolen access token alone cannot take an account over. A wrong password answers `401`.
- Changing the password or deleting the account revokes the current token family, expires both
  cookies and disconnects every live bancho session. Access tokens issued to *other* sessions
  survive until they expire; that is the trade-off for keeping them un-indexed and short lived.
- Deletion is permanent and removes scores, per-mode stats, friend and block rows in both
  directions, achievements and every leaderboard entry.

## Protected endpoints

All `POST`, all JSON, all requiring `Authorization: Bearer`.

| Path | Scope | Privilege | Body |
| --- | --- | --- | --- |
| `/api/v1/admin/restrict` | `moderation` | `MODERATOR` | `user_id`, `reason` (optional) |
| `/api/v1/admin/unrestrict` | `moderation` | `MODERATOR` | `user_id`, `reason` (optional) |
| `/api/v1/admin/alert` | `moderation` | `MODERATOR` | `message` |
| `/api/v1/admin/user/country` | `moderation` | `MODERATOR` | `user_id`, `country` (two letters) |
| `/api/v1/admin/beatmap/status` | `beatmaps` | `NOMINATOR` | `beatmap_id`, `status`, `frozen` (optional, default `true`) |
| `/api/v1/admin/wipe` | `admin` | `ADMINISTRATOR` | `user_id`, `mode` (0-8) |
| `/api/v1/admin/donator` | `admin` | `ADMINISTRATOR` | `user_id`, `duration` (`30d`, `2w`, `12h`, bare number = seconds) |
| `/api/v1/admin/privileges/add` | `admin` | `ADMINISTRATOR` | `user_id`, `privs` (array of names) |
| `/api/v1/admin/privileges/remove` | `admin` | `ADMINISTRATOR` | `user_id`, `privs` (array of names) |
| `/api/v1/admin/user/name` | `admin` | `ADMINISTRATOR` | `user_id`, `name` |

- One privilege per action, with no seniority between them. A moderator cannot rank beatmaps,
  a nominator cannot restrict players, and an administrator can do neither unless they also
  hold those bits. Give an account every bit its job needs; that is what the bitmask is for.
- `DEVELOPER` is not a shortcut either. It marks dangerous in-game commands, not API access.
- Nobody may restrict, wipe or re-privilege their own account.
- Privilege names accept the usual aliases: `normal`, `verified`, `whitelisted`, `supporter`,
  `donator`, `premium`, `alumni`, `tourney_manager`, `nominator`, `bat`, `mod`, `moderator`,
  `admin`, `administrator`, `developer`, `dangerous`.

### Status codes

| Code | Meaning |
| --- | --- |
| `200` | Applied |
| `400` | Missing or malformed field |
| `401` | No token, or it is expired or revoked |
| `403` | Missing scope, or not privileged enough |
| `404` | No such user or beatmap |

Errors outside the token endpoint use the existing envelope: `{"status": "message"}`.

Every action is logged with the acting administrator's id, so changes are attributable.

## Example

```bash
# log in
curl -X POST https://api.example.com/api/v1/oauth/token \
  -H 'Content-Type: application/json' \
  -d '{"grant_type":"password","username":"suka","password":"hunter2","scope":"identify moderation"}'

# use the access token
curl -X POST https://api.example.com/api/v1/admin/alert \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  -d '{"message":"server restarting in 5 minutes"}'

# renew it
curl -X POST https://api.example.com/api/v1/oauth/token \
  -H 'Content-Type: application/json' \
  -d '{"grant_type":"refresh_token","refresh_token":"<refresh_token>"}'

# log out
curl -X POST https://api.example.com/api/v1/oauth/revoke \
  -H 'Content-Type: application/json' \
  -d '{"token":"<refresh_token>"}'
```

Browser clients send `credentials: "include"` instead of a header and let the cookies do the
work. Since the cookies are scoped to `.<DOMAIN>`, any subdomain of the server can use them.
That still requires a concrete `Access-Control-Allow-Origin` plus
`Access-Control-Allow-Credentials: true` — a wildcard origin will make the browser drop the
cookies.

## Layout

| File | Role |
| --- | --- |
| `modules/api/OAuthToken.java` | Token record |
| `modules/api/TokenStore.java` | Issue, resolve, rotate, revoke; Redis storage and cookies |
| `modules/admin/AdminActions.java` | The actions themselves, transport agnostic |
| `modules/admin/AdminPrivileges.java` | Privilege name resolution |
| `handlers/api/ApiAuth.java` | Bearer lookup, scope and privilege checks, body parsing |
| `handlers/api/OAuthRoutes.java` | Token, revoke, userinfo |
| `handlers/api/SelfApiRoutes.java` | Self service: read, profile, email, password, deletion |
| `handlers/api/AdminApiRoutes.java` | The ten protected endpoints |
