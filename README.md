> [!WARNING]  
> At the moment the server is not recommended to use in production

## Roadmap

- [x] Authentication
- [x] Leaderboards
- [x] Score Submission
    - [x] All Modes Supported
    - [x] RX & AP Support
    - [x] PP Calculation (Only Osu-Native supported rn)
- [x] Chat
    - [x] Disconnect and Join propagation
    - [x] Sending messages (global/private)
- [x] Player Handling
    - [x] Presence, Auto Disconnect
    - [x] User Stats (Also RX & AP)
    - [x] Friends
    - [x] Silence Info
    - [x] Privileges
- [x] Bots
    - [x] Bot online Handling
    - [x] Commands
    - [x] Announce on #1
- [x] Multiplayer
- [x] Spectating
- [x] Restriction
- [x] Achievements
- [ ] Web Redirects
- [x] osu!Direct
- [x] BSS
- [x] IRC
- [x] Tourney Client

### Backend

- [x] API
- [ ] Action Notifications (Pubsub)
- [ ] Plugin Framework
- [x] Asset downloading
- [x] Configuration
    - [x] Welcome message and metadata
    - [x] Seasonal & Main Menu Icon
    - [x] .env for Secrets

## IRC

The server ships with a built-in IRC gateway so users can chat from HexChat, mIRC, or IRC bots.

1. Enable it in `.env`:

```env
IRC_ENABLED=true
IRC_PORT=6667
```

2. Connect with any IRC client:
   - **Server**: your bancho host, port `IRC_PORT` (default `6667`)
   - **Nick**: your account username (spaces replaced with `_`)
   - **Server password (PASS)**: your account password (or its MD5 hash)

Supported commands: `JOIN`, `PART`, `PRIVMSG` (channels & DMs, including `!commands`), `LIST`, `NAMES`, `TOPIC`, `WHOIS`, `WHO`, `ISON`, `MOTD`, `PING`/`PONG`, `QUIT`. Chat is fully bridged with in-game players in both directions.

#### Spectating from IRC

You can spectate a live player straight from IRC:
- `JOIN #spec_<nick>` starts spectating that player (spaces in the nick become `_`). The osu! host and any fellow spectators are notified, and spectator chat is bridged both ways.
- `PART` (any of the spectator channel names, e.g. `PART #spec_<nick>`) stops spectating. Disconnecting also stops it automatically.
- Replay frames have no IRC representation, so you follow the session through chat rather than gameplay video.

#### Multiplayer & lobby from IRC

You can also take part in multiplayer chat from IRC (handy for tournament referees):
- `JOIN #lobby` joins the multiplayer lobby chat.
- `JOIN #mp_<id> [password]` (or `JOIN #multi_<id> [password]`) joins a live match's chat by its match id. If the match is password-protected, supply the password as the channel key. Messages are bridged to and from the in-game `#multiplayer` chat, and `!mp` commands work as usual.
- `PART` the same channel name to leave.

Notes:
- Joining a match channel from IRC does not take a player slot; you are a chat participant only.
- You can be in only one match channel at a time; `PART` your current match before joining another.
- Logging in via IRC and the osu! client with the same account at the same time is possible, but a new osu! login kicks the existing session with the same user id.

### Key Directories

| Directory | Description |
|-----------|-------------|
| `commands` | Bancho command handlers |
| `irc` | IRC gateway (server, sessions, packet translation) |
| `handlers` | Web handlers |
| `models` | Data class files |
| `modules` | Utility classes |
| `packets` | Packet writers/handlers |
| `repos` | Database repository utilities |
| `server` | Application services |

## Acknowledgements

This project builds upon the work of several open-source projects and contributors. We'd like to thank:

- **[7mochi](https://github.com/7mochi)** for developing **[osu-native-jar](https://github.com/7mochi/osu-native-jar)** and **[osz2.jar](https://github.com/7mochi/osz2.jar)**.
- **[Lekuruu](https://github.com/Lekuruu)** for maintaining the excellent **[bancho-documentation](https://github.com/Lekuruu/bancho-documentation)**.
- **[osuAkatsuki](https://github.com/osuAkatsuki)** and the **[bancho.py](https://github.com/osuAkatsuki/bancho.py)** project, whose database schema and parts of the server logic served as a foundation for this project (heavily modified).
- **[ekgame](https://github.com/ekgame)** for creating **[bancho-api](https://github.com/ekgame/bancho-api)**, the first Java Bancho implementation, which inspired parts of this project.
