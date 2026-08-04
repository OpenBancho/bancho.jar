package com.osuserverlist.bjar.models.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * Documentation-only DTOs describing the JSON shapes returned by the v1 API.
 *
 * <p>The handlers themselves return ordered {@code Map} instances at runtime
 * (see {@link ApiMappers}); these POJOs exist purely so the Javalin OpenAPI
 * annotation processor can generate accurate schemas for the Swagger UI served
 * at {@code /api/docs}. Field names are snake_case to match the JSON output.
 */
public final class ApiDto {

    // ----- item schemas --------------------------------------------------

    @Data
    public static class OnlinePlayer {
        private int id;
        private String name;
    }

    @Data
    public static class SearchPlayer {
        private int id;
        private String name;
        private String country;
    }

    @Data
    public static class PlayerRef {
        private int id;
        private String name;
        private String country;
    }

    @Data
    public static class PlayerInfo {
        private int id;
        private String name;
        private String country;
        private int priv;
        private int clan_id;
        private int preferred_mode;
        private int play_style;
        private int creation_time;
        private int latest_activity;
    }

    /**
     * Per-mode statistics. {@code rank}, {@code country_rank},
     * {@code level} and {@code level_progress} are derived on request and
     * only present on get_player_details.
     */
    @Data
    public static class Stats {
        private int mode;
        private long tscore;
        private long rscore;
        private int pp;
        private int plays;
        private int playtime;
        private float acc;
        private int max_combo;
        private int total_hits;
        private int replay_views;
        private int xh_count;
        private int x_count;
        private int sh_count;
        private int s_count;
        private int a_count;
    }

    @Data
    public static class Beatmap {
        private long id;
        private long set_id;
        private String md5;
        private String artist;
        private String title;
        private String version;
        private String creator;
        private String filename;
        private int status;
        private int mode;
        private float bpm;
        private float cs;
        private float ar;
        private float od;
        private float hp;
        private float diff;
        private int max_combo;
        private int total_length;
        private int plays;
        private int passes;
    }

    @Data
    public static class Score {
        private long id;
        private String map_md5;
        private long score;
        private float pp;
        private float acc;
        private int max_combo;
        private int mods;
        private int n300;
        private int n100;
        private int n50;
        private int nmiss;
        private int ngeki;
        private int nkatu;
        private String grade;
        private int status;
        private int mode;
        private String play_time;
        private int time_elapsed;
        private boolean perfect;
        private Beatmap beatmap;
    }

    @Data
    public static class ScoreWithPlayer {
        private long id;
        private String map_md5;
        private long score;
        private float pp;
        private float acc;
        private int max_combo;
        private int mods;
        private int n300;
        private int n100;
        private int n50;
        private int nmiss;
        private int ngeki;
        private int nkatu;
        private String grade;
        private int status;
        private int mode;
        private String play_time;
        private int time_elapsed;
        private boolean perfect;
        private PlayerRef player;
    }

    @Data
    public static class LeaderboardEntry {
        private int rank;
        private int id;
        private String name;
        private String country;
        private int mode;
        private int pp;
        private long rscore;
        private long tscore;
        private float acc;
        private int plays;
        private int max_combo;
    }

    @Data
    public static class MostPlayed {
        private String map_md5;
        private long map_id;
        private long set_id;
        private String artist;
        private String title;
        private String version;
        private long playcount;
    }

    @Data
    public static class Beatmapset {
        private int set_id;
        private int creator_id;
        private String creator_name;
        private String artist;
        private String title;
        private String subject;
        private String message;
        private int status;
        private int revision;
        private int topic_id;
        private boolean has_video;
        private int filesize;
        private int filesize_novideo;
        private String submission_date;
        private String last_update;
        private List<Beatmap> difficulties;
    }

    @Data
    public static class Counts {
        private long online;
        private long total;
    }

    @Data
    public static class StatsResponse {
        private int onlinePlayers;
        private long totalPlayers;
        private long maps;
        private long scores;
    }

    @Data
    public static class PlayerInfoFull {
        private PlayerInfo info;
        private Map<String, Stats> stats;
    }

    // ----- paginated envelopes ------------------------------------------

    @Data
    public static class PaginatedOnline {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<OnlinePlayer> results;
    }

    @Data
    public static class PaginatedSearchPlayers {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<SearchPlayer> results;
    }

    @Data
    public static class PaginatedLeaderboard {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<LeaderboardEntry> results;
    }

    @Data
    public static class PaginatedPlayerScores {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<Score> results;
    }

    @Data
    public static class PaginatedMapScores {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<ScoreWithPlayer> results;
    }

    @Data
    public static class PaginatedMostPlayed {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<MostPlayed> results;
    }

    @Data
    public static class PaginatedBeatmapsets {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<Beatmapset> results;
    }

    // ----- scalar (non-paginated) responses -----------------------------#

    @Data
    public static class PlayerInfoResponse {
        private String status;
        private PlayerInfoFull player;
    }

    @Data
    public static class MapInfoResponse {
        private String status;
        private Beatmap map;
    }

    @Data
    public static class ScoreInfoResponse {
        private String status;
        private Score score;
    }

    @Data
    public static class ErrorResponse {
        private String status;
    }

    /** {@code { "status": "success" }}, returned by the write endpoints. */
    @Data
    public static class SuccessResponse {
        private String status;
    }

    // ----- registration --------------------------------------------------

    @Data
    public static class RegisterRequest {
        private String username;
        private String email;
        private String password;

        /**
         * The token the Cloudflare Turnstile widget produced. Required whenever
         * the server has TURNSTILE_SECRET_KEY set.
         */
        @JsonProperty("cf-turnstile-response")
        private String cfTurnstileResponse;

        /** Scopes for the token pair issued on success, e.g. {@code identify profile}. */
        private String scope;
        private String client_id;
    }

    @Data
    public static class RegisterResponse {
        private String status;
        private TokenUser user;
        private String access_token;
        private String token_type;
        private long expires_in;
        private String refresh_token;
        private long refresh_expires_in;
        private String scope;

        /**
         * Always false here: a brand new account still has to log into the game once before
         * its token opens anything.
         */
        private boolean verified;

        private String message;
    }

    /** A rejected registration: one readable line, plus the messages per field. */
    @Data
    public static class RegisterErrorResponse {
        private String status;
        private Map<String, List<String>> errors;
    }

    // ----- oauth2 --------------------------------------------------------

    @Data
    public static class TokenRequest {
        /** {@code password} or {@code refresh_token}. */
        private String grant_type;
        private String username;
        private String password;
        /** Alternative to {@code password}: the md5 of the password, as the game client sends it. */
        private String password_md5;
        private String refresh_token;
        /** Space separated list, e.g. {@code identify profile}. */
        private String scope;
        private String client_id;
    }

    @Data
    public static class TokenResponse {
        private String access_token;
        private String token_type;
        private long expires_in;
        private String refresh_token;
        private long refresh_expires_in;
        private String scope;
    }

    @Data
    public static class RevokeRequest {
        /** Omit to revoke whatever the cookies carry. */
        private String token;
        /** {@code access_token} or {@code refresh_token} (default). */
        private String token_type_hint;
    }

    @Data
    public static class TokenUser {
        private int id;
        private String name;
        private int priv;

        /**
         * False until the account has logged into the game once. While it is false the token
         * is accepted only by the userinfo endpoint; everything else answers {@code 401}.
         */
        private boolean verified;
    }

    @Data
    public static class UserInfoResponse {
        private String status;
        private TokenUser user;
        private String scope;
        private String client_id;
        private long expires_at;

        /** Whether the account behind the token has completed its in-game login. */
        private boolean verified;

        /** Only present when {@code verified} is false: what the owner has to do about it. */
        private String message;
    }

    /** RFC 6749 error body used by the oauth endpoints. */
    @Data
    public static class OAuthErrorResponse {
        private String error;
        private String error_description;
    }

    // ----- self service --------------------------------------------------

    @Data
    public static class SelfInfo {
        private int id;
        private String name;
        private String country;
        private int priv;
        private int clan_id;
        private int clan_priv;
        private int preferred_mode;
        private int play_style;
        private int creation_time;
        private int latest_activity;
        private String email;
        private long silence_end;
        private long donor_end;
        private String userpage_content;
        private String custom_badge_name;
        private String custom_badge_icon;
    }

    @Data
    public static class SelfResponse {
        private String status;
        private SelfInfo info;
        private Map<String, Stats> stats;
        private String scope;
    }

    @Data
    public static class SelfUpdateRequest {
        private String userpage_content;
        private Integer preferred_mode;
        /** Mouse, keyboard, tablet and touch as a bitmask (0-15). */
        private Integer play_style;
        private String custom_badge_name;
        private String custom_badge_icon;
    }

    @Data
    public static class SelfEmailRequest {
        private String email;
        private String current_password;
        /** Alternative to {@code current_password}. */
        private String current_password_md5;
    }

    @Data
    public static class SelfPasswordRequest {
        private String new_password;
        private String current_password;
        private String current_password_md5;
    }

    @Data
    public static class SelfDeleteRequest {
        private String current_password;
        private String current_password_md5;
    }

    // ----- moderation and administration ---------------------------------

    @Data
    public static class RestrictRequest {
        private int user_id;
        private String reason;
    }

    @Data
    public static class WipeRequest {
        private int user_id;
        private int mode;
    }

    @Data
    public static class AlertRequest {
        private String message;
    }

    @Data
    public static class AlertResponse {
        private String status;
        /** Number of online players the alert reached. */
        private int delivered;
    }

    @Data
    public static class DonatorRequest {
        private int user_id;
        /** Duration such as {@code 30d}, {@code 12h} or {@code 0} to remove it. */
        private String duration;
    }

    @Data
    public static class DonatorResponse {
        private String status;
        private long donor_end;
    }

    @Data
    public static class PrivilegesRequest {
        private int user_id;
        /** Privilege names, e.g. {@code ["NOMINATOR", "MODERATOR"]}. */
        private List<String> privs;
    }

    @Data
    public static class PrivilegesResponse {
        private String status;
        /** The resulting privilege bitmask. */
        private int priv;
    }

    @Data
    public static class BeatmapStatusRequest {
        private long beatmap_id;
        /** Ranked status: -2 graveyard, -1 WIP, 0 pending, 1 ranked, 2 approved, 3 qualified, 4 loved. */
        private int status;
        /** Keep the status when the map is updated (default true). */
        private boolean frozen;
    }

    @Data
    public static class CountryRequest {
        private int user_id;
        /** Two letter country code. */
        private String country;
    }

    @Data
    public static class NameRequest {
        private int user_id;
        private String name;
    }

    // ----- password resets -----------------------------------------------

    @Data
    public static class PasswordResetLinkRequest {
        private int user_id;
        /** Optional. Clamped to 10 minutes - 7 days; defaults to 24 hours. */
        private int expires_in_hours;
    }

    @Data
    public static class PasswordResetLinkResponse {
        private String status;
        private int user_id;
        /** The ticket itself. Anyone holding it can set the account's password. */
        private String token;
        /** Where to redeem it, so a frontend does not hard code the route. */
        private String path;
        /** Unix seconds. */
        private long expires_at;
        /** Seconds from now. */
        private long expires_in;
    }

    @Data
    public static class PasswordResetCheckResponse {
        private String status;
        private int user_id;
        /** Shown to whoever opens the link, so a misdirected one is obvious. */
        private String username;
        private long expires_at;
    }

    @Data
    public static class PasswordResetRequest {
        private String token;
        private String new_password;
    }

    // ----- beatmap sets --------------------------------------------------

    /** A beatmap set as the search and the set endpoint return it. */
    @Data
    public static class BeatmapsetSummary {
        private long set_id;
        /** "osu!" for a mirrored set, "private" for one submitted here. */
        private String server;
        private String artist;
        private String title;
        private String creator;
        /** Only known for sets submitted here. */
        private Integer creator_id;
        /** Ranked status of the hardest difficulty. */
        private int status;
        private int mode;
        private float bpm;
        private int total_length;
        private String last_update;
        private long plays;
        private long passes;
        private int difficulty_count;
        /** True while the set can still be downloaded from this server. */
        private boolean hosted;
        private Boolean has_video;
        private Integer revision;
        private String submission_date;
        private List<Beatmap> difficulties;
    }

    @Data
    public static class PaginatedBeatmapsetSearch {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<BeatmapsetSummary> results;
    }

    @Data
    public static class BeatmapsetResponse {
        private String status;
        private BeatmapsetSummary beatmapset;
    }

    // ----- profile extras -----------------------------------------------

    @Data
    public static class CountryCount {
        private String country;
        private int players;
    }

    @Data
    public static class CountriesResponse {
        private String status;
        private int mode;
        private List<CountryCount> countries;
    }

    @Data
    public static class PlaycountMonth {
        /** Month as yyyy-MM. */
        private String month;
        private int plays;
    }

    @Data
    public static class PlaycountsResponse {
        private String status;
        private int mode;
        private List<PlaycountMonth> months;
    }

    @Data
    public static class Achievement {
        private int id;
        /** Icon name, without extension, under /medals/client on the assets host. */
        private String file;
        private String name;
        private String description;
        /** Whether the player this was asked for owns the medal. */
        private boolean unlocked;
    }

    @Data
    public static class AchievementsResponse {
        private String status;
        /** Medals the player owns. */
        private int count;
        /** Medals that exist on the server. */
        private int total;
        private List<Achievement> results;
    }

    // ----- admin panel --------------------------------------------------

    @Data
    public static class SilenceRequest {
        private int user_id;
        /** Duration such as 30m, 2h, 1d, or a bare number of seconds. */
        private String duration;
        private String reason;
    }

    @Data
    public static class SilenceResponse {
        private String status;
        /** Unix second the silence expires at. */
        private long silence_end;
    }

    @Data
    public static class NoteRequest {
        private int user_id;
        private String message;
    }

    /** One line of an account's staff history. */
    @Data
    public static class StaffLogEntry {
        private int id;
        /** The staff member who acted, or 0 when the server itself did. */
        private int from_id;
        private String from_name;
        private int to_id;
        private String to_name;
        /** restrict, unrestrict, silence, unsilence, wipe, supporter, privileges, name, country, note. */
        private String action;
        private String message;
        /** ISO-8601 local date-time. */
        private String time;
    }

    @Data
    public static class PaginatedStaffLogs {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<StaffLogEntry> results;
    }

    /** A player as the moderation list shows them. */
    @Data
    public static class AdminPlayer {
        private int id;
        private String name;
        private String country;
        private int priv;
        private boolean restricted;
        private boolean silenced;
        private long silence_end;
        private long donor_end;
        private boolean supporter;
        private boolean online;
        /** Human readable privilege names, for badges. */
        private List<String> roles;
        private long creation_time;
        private long latest_activity;
    }

    @Data
    public static class PaginatedAdminPlayers {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<AdminPlayer> results;
    }

    @Data
    public static class AdminPlayerResponse {
        private String status;
        private AdminPlayer player;
        private String email;
        /** The account's staff history, newest first. */
        private List<StaffLogEntry> logs;
        /** Per-mode totals, so a moderator can judge an account without leaving the page. */
        private List<Stats> stats;
    }

    /** A pending rank request as the nominator queue shows it. */
    @Data
    public static class AdminRequest {
        private int map_id;
        private int set_id;
        private String artist;
        private String title;
        private String version;
        private String creator;
        private int status;
        private int mode;
        private double stars;
        private int requested_by_id;
        private String requested_by;
        private long requested_at;
        private boolean active;
    }

    @Data
    public static class PaginatedAdminRequests {
        private String status;
        private int offset;
        private int limit;
        private long count;
        private List<AdminRequest> results;
    }

    @Data
    public static class ResolveRequestRequest {
        private int map_id;
        /** Either accept or reject. */
        private String action;
        /** Optional target status when accepting: 1 ranked, 2 approved, 4 loved. Defaults to 1. */
        private int status;
        /** When true, the whole beatmapset is resolved rather than the single difficulty. */
        private boolean whole_set;
    }

    /** Server load, for the developer page. */
    @Data
    public static class SystemStatsResponse {
        private String status;
        private String version;
        /** Milliseconds the JVM has been up. */
        private long uptime_ms;
        private long heap_used;
        private long heap_committed;
        private long heap_max;
        private long non_heap_used;
        /** Bytes the JVM believes it can still allocate. */
        private long memory_free;
        private long memory_total;
        private int threads;
        private int threads_peak;
        private int cpu_cores;
        /** Recent JVM CPU load, 0..1, or -1 when the platform will not say. */
        private double cpu_process;
        /** Recent system-wide CPU load, 0..1, or -1 when the platform will not say. */
        private double cpu_system;
        /** One-minute load average, or -1 on platforms without one. */
        private double load_average;
        private long gc_count;
        private long gc_time_ms;
        /** Live bancho sessions, bots excluded. */
        private int online_players;
        private int registered_players;
        private int restricted_players;
        private int silenced_players;
        private int multiplayer_matches;
        private int chat_channels;
        private String java_version;
        private String os;
    }

    /** What the caller is allowed to see and do. Drives the sidebar. */
    @Data
    public static class AdminAccessResponse {
        private String status;
        private int id;
        private String name;
        private int priv;
        private List<String> roles;
        /** Section keys the caller may open: requests, moderation, logs, server. */
        private List<String> sections;
        /** Action keys the caller may use: restrict, silence, note, wipe, supporter, privileges, rename, country, alert, rank. */
        private List<String> actions;
    }
}
