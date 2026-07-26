package com.osuserverlist.bjar.models.api;

import java.util.List;
import java.util.Map;

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

    private ApiDto() {
    }

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
    }

    @Data
    public static class UserInfoResponse {
        private String status;
        private TokenUser user;
        private String scope;
        private String client_id;
        private long expires_at;
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
}
