package com.osuserverlist.bjar.packets.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.ScoreEntity;
import com.osuserverlist.bjar.models.essentials.Player;
import com.osuserverlist.bjar.modules.packets.BanchoPacketReader;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPacket;
import com.osuserverlist.bjar.modules.packets.ClientPacketEngine.ClientPackets;
import com.osuserverlist.bjar.packets.BanchoPacket;
import com.osuserverlist.bjar.packets.server.BeatmapServerPackets.BeatmapInfo;
import com.osuserverlist.bjar.packets.server.BeatmapServerPackets.BeatmapInfoReplyPacket;
import com.osuserverlist.bjar.packets.server.BeatmapServerPackets.ClientRank;
import com.osuserverlist.bjar.repos.BeatmapRepository;

import io.ebean.DB;

/**
 * Beatmap information panel.
 *
 * <p>Opening the panel in the client sends the names of the .osu files it has
 * locally, plus a handful of ids for maps it already recognises. The answer
 * carries the ranked status of every map the server knows and the player's best
 * grade in each vanilla mode; maps that are unknown here are simply left out.</p>
 */
public class BeatmapInfoPackets {

    private static final Logger logger = LoggerFactory.getLogger(BeatmapInfoPackets.class);

    /** A score that currently counts as the player's best on that map. */
    private static final int STATUS_BEST = 2;

    private static final int VANILLA_MODES = 4;

    /** The client cannot render more than this in one go. */
    private static final int MAX_ENTRIES = 100;

    @ClientPacket(ClientPackets.BEATMAP_INFO_REQUEST)
    public boolean beatmapInfoRequest(BanchoPacket packet, BanchoPacketReader reader, Player player)
            throws IOException {

        List<String> filenames = readStringList(reader);
        List<Integer> ids = readIntList(reader);

        List<BeatmapInfo> answers = new ArrayList<>();

        for (int index = 0; index < filenames.size() && answers.size() < MAX_ENTRIES; index++) {
            BeatmapEntity beatmap = BeatmapRepository.findByFilename(filenames.get(index));

            BeatmapInfo info = toInfo(index, beatmap, player.getId());

            if (info != null) {
                answers.add(info);
            }
        }

        for (int i = 0; i < ids.size() && answers.size() < MAX_ENTRIES; i++) {
            BeatmapEntity beatmap = BeatmapRepository.findById(ids.get(i));

            BeatmapInfo info = toInfo(filenames.size() + i, beatmap, player.getId());

            if (info != null) {
                answers.add(info);
            }
        }

        logger.debug("Player {} requested beatmap info for {} files and {} ids, answered with {} entries",
                player, filenames.size(), ids.size(), answers.size());

        player.sendPacket(new BeatmapInfoReplyPacket(answers));
        return true;
    }

    private BeatmapInfo toInfo(int index, BeatmapEntity beatmap, int userId) {
        if (beatmap == null || beatmap.getStatus() == null) {
            return null;
        }

        int[] grades = gradesFor(beatmap.getMd5(), userId);

        return new BeatmapInfo(
                index,
                beatmap.getId() == null ? 0 : beatmap.getId().intValue(),
                beatmap.getSetId() == null ? 0 : beatmap.getSetId().intValue(),
                0, // Forum threads do not exist on this server.
                beatmap.getStatus(),
                grades[0],
                grades[1],
                grades[2],
                grades[3],
                beatmap.getMd5());
    }

    /** The player's best grade per vanilla mode, in client numbering. */
    private int[] gradesFor(String mapMd5, int userId) {
        int[] grades = {
                ClientRank.NONE.value,
                ClientRank.NONE.value,
                ClientRank.NONE.value,
                ClientRank.NONE.value
        };

        if (mapMd5 == null || mapMd5.isBlank()) {
            return grades;
        }

        List<ScoreEntity> scores = DB.find(ScoreEntity.class)
                .where()
                .eq("mapMd5", mapMd5)
                .eq("user.id", userId)
                .eq("status", STATUS_BEST)
                .lt("mode", VANILLA_MODES)
                .findList();

        for (ScoreEntity score : scores) {
            Integer mode = score.getMode();

            if (mode == null || mode < 0 || mode >= VANILLA_MODES) {
                continue;
            }

            grades[mode] = ClientRank.fromGrade(score.getGrade());
        }

        return grades;
    }

    /** i32 count followed by that many strings. */
    private List<String> readStringList(BanchoPacketReader reader) throws IOException {
        List<String> values = new ArrayList<>();

        if (reader.remainingInPacket() < 4) {
            return values;
        }

        int count = reader.readInt();

        for (int i = 0; i < count && reader.remainingInPacket() > 0; i++) {
            values.add(reader.readString());
        }

        return values;
    }

    /** i32 count followed by that many i32 values. */
    private List<Integer> readIntList(BanchoPacketReader reader) {
        List<Integer> values = new ArrayList<>();

        if (reader.remainingInPacket() < 4) {
            return values;
        }

        int count = reader.readInt();

        for (int i = 0; i < count && reader.remainingInPacket() >= 4; i++) {
            values.add(reader.readInt());
        }

        return values;
    }
}
