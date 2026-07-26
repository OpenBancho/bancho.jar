package com.osuserverlist.bjar.models.database;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.osuserverlist.bjar.App;

import jakarta.persistence.*;
import lombok.Data;
import me.skiincraft.api.ousu.entity.beatmap.Beatmap;

@Data
@Entity
@Table(name = "maps")
public class BeatmapEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "server", nullable = false)
    private String server = "osu!";

    @Column(name = "set_id", nullable = false)
    private Long setId;

    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "md5", length = 32, nullable = false, unique = true)
    private String md5;

    @Column(name = "artist", length = 128, nullable = false)
    private String artist;

    @Column(name = "title", length = 128, nullable = false)
    private String title;

    @Column(name = "version", length = 128, nullable = false)
    private String version;

    @Column(name = "creator", length = 19, nullable = false)
    private String creator;

    @Column(name = "filename", length = 256, nullable = false)
    private String filename;

    @Column(name = "last_update", nullable = false)
    private LocalDateTime lastUpdate;

    @Column(name = "total_length", nullable = false)
    private Integer totalLength;

    @Column(name = "max_combo", nullable = false)
    private Integer maxCombo;

    @Column(name = "frozen", nullable = false)
    private Boolean frozen = false;

    @Column(name = "plays", nullable = false)
    private Integer plays = 0;

    @Column(name = "passes", nullable = false)
    private Integer passes = 0;

    @Column(name = "mode", nullable = false)
    private Integer mode;

    @Column(name = "bpm", nullable = false)
    private Float bpm;

    @Column(name = "cs", nullable = false)
    private Float cs;

    @Column(name = "ar", nullable = false)
    private Float ar;

    @Column(name = "od", nullable = false)
    private Float od;

    @Column(name = "hp", nullable = false)
    private Float hp;

    @Column(name = "diff", nullable = false)
    private Float diff;

    public String toEmbed() {
        return String.format(
            "[https://osu.%s/beatmapsets/%s#/%s %s - %s [%s]]",
            App.server.enviromentConfig.getDomain(),
            setId,
            id,
            artist,
            title,
            version
        );
    }

    public static String toEmbed(long id, long setId, String artist, String title, String version) {
        return String.format(
            "[https://osu.%s/beatmapsets/%s#/%s %s - %s [%s]]",
            App.server.enviromentConfig.getDomain(),
            setId,
            id,
            artist,
            title,
            version
        );
    }

    public static BeatmapEntity fromBeatmap(Beatmap beatmap) {

        BeatmapEntity entity = new BeatmapEntity();

        entity.id = beatmap.getBeatmapId();
        entity.setId = beatmap.getBeatmapSetId();
        entity.status = beatmap.getApprovated().getId();
        entity.md5 = beatmap.getFileMD5();
        entity.artist = beatmap.getArtist();
        entity.title = beatmap.getTitle();
        entity.version = beatmap.getVersion();
        entity.creator = beatmap.getCreatorName();
        entity.filename = getFileName(beatmap);
        entity.lastUpdate = OffsetDateTime.parse(
                beatmap.getLastUpdateDate().toString())
                .toLocalDateTime();
        entity.totalLength = beatmap.getTotalLength();
        entity.maxCombo = beatmap.getMaxCombo();
        entity.frozen = false;
        entity.plays = 0;
        entity.passes = 0;
        entity.mode = beatmap.getGameMode().getId();
        entity.bpm = beatmap.getBPM();
        entity.cs = beatmap.getDifficultApproach();
        entity.ar = beatmap.getDifficultOverall();
        entity.od = beatmap.getDifficultSize();
        entity.hp = beatmap.getDifficultDrain();
        entity.diff = (float) beatmap.getDifficultAim();

        return entity;
    }

    public static String getFileName(Beatmap beatmap) {
        return String.format(
            "%s - %s [%s].osu",
            beatmap.getArtist(),
            beatmap.getTitle(),
            beatmap.getVersion()
        );
    }
}
