package com.osuserverlist.bjar.modules.osu;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.models.database.BeatmapEntity;
import com.osuserverlist.bjar.models.database.BssMapsetEntity;
import com.osuserverlist.bjar.models.database.MapsetEntity;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.models.osu.BssStatusCode;
import com.osuserverlist.bjar.models.osu.Privileges;
import com.osuserverlist.bjar.models.osu.RankedStatus;
import com.osuserverlist.bjar.repos.BeatmapRepository;
import com.osuserverlist.bjar.repos.BssMapsetRepository;

import io.ebean.DB;
import io.ebean.SqlRow;
import io.github.nanamochi.osz2.Package;
import io.github.nanamochi.osz2.PackageFile;
import io.github.nanamochi.osz2.model.MetadataType;
import io.github.nanamochi.osz2.util.PatchUtil;

/**
 * Core logic of the Beatmap Submission System.
 *
 * <p>Locally submitted content lives in a dedicated id range that starts at
 * {@code BSS_ID_OFFSET} (one billion by default). Official osu! sets and maps
 * are far below that mark, so a plain numeric comparison is enough to tell
 * local content apart from foreign content anywhere in the codebase &mdash; which
 * is exactly what keeps the download route from conflicting with the mirror.</p>
 */
public final class BeatmapSubmissionService {

    private static final Logger logger = LoggerFactory.getLogger(BeatmapSubmissionService.class);

    /** Default start of the private id range. */
    public static final int DEFAULT_ID_OFFSET = 1_000_000_000;

    private static final Object ID_LOCK = new Object();

    private BeatmapSubmissionService() {
    }

    /** Thrown for every expected, user facing submission failure. */
    public static class BssException extends Exception {

        private final transient BssStatusCode code;

        public BssException(BssStatusCode code) {
            super(code.getMessage());
            this.code = code;
        }

        public BssStatusCode getCode() {
            return code;
        }
    }

    /** Result of a {@code bmsubmit-getid} negotiation. */
    public record PreparedSubmission(
            BssMapsetEntity mapset,
            List<Long> beatmapIds,
            boolean fullSubmit,
            int remainingQuota) {
    }

    // ------------------------------------------------------------------
    // Configuration helpers
    // ------------------------------------------------------------------

    public static boolean isEnabled() {
        return App.server.enviromentConfig.isBssEnabled();
    }

    public static int idOffset() {
        int configured = App.server.enviromentConfig.getBssIdOffset();
        return configured > 0 ? configured : DEFAULT_ID_OFFSET;
    }

    /**
     * True when the id belongs to the private range, i.e. it was handed out by
     * this server and must never be requested from osu.ppy.sh.
     */
    public static boolean isLocalId(long id) {
        return id >= idOffset();
    }

    /**
     * True when the set is hosted by this server and can be served directly
     * instead of being redirected to the mirror.
     */
    public static boolean isLocalSet(int setId) {
        if (!isLocalId(setId)) {
            return false;
        }

        return BssMapsetRepository.isActive(setId);
    }

    // ------------------------------------------------------------------
    // Id allocation
    // ------------------------------------------------------------------

    public static int allocateSetId() {
        synchronized (ID_LOCK) {
            int offset = idOffset();

            Integer fromBss = BssMapsetRepository.maxSetId();
            Integer fromMapsets = maxOf("SELECT MAX(id) AS max_id FROM mapsets WHERE id >= :offset", offset);

            int next = offset;

            if (fromBss != null) {
                next = Math.max(next, fromBss + 1);
            }

            if (fromMapsets != null) {
                next = Math.max(next, fromMapsets + 1);
            }

            return next;
        }
    }

    public static long allocateBeatmapId(Set<Long> alreadyAllocated) {
        synchronized (ID_LOCK) {
            int offset = idOffset();

            Integer fromMaps = maxOf("SELECT MAX(id) AS max_id FROM maps WHERE id >= :offset", offset);

            long next = fromMaps == null ? offset : fromMaps + 1L;

            while (alreadyAllocated.contains(next)) {
                next++;
            }

            alreadyAllocated.add(next);

            return next;
        }
    }

    private static Integer maxOf(String sql, int offset) {
        SqlRow row = DB.sqlQuery(sql)
                .setParameter("offset", offset)
                .findOne();

        return row == null ? null : row.getInteger("max_id");
    }

    // ------------------------------------------------------------------
    // Submission negotiation (bmsubmit-getid)
    // ------------------------------------------------------------------

    /**
     * Reserves (or re-uses) a set id and the difficulty ids for an upcoming
     * upload.
     *
     * @param user             authenticated submitter.
     * @param requestedSetId   set id sent by the client, {@code <= 0} for a new set.
     * @param clientBeatmapIds difficulty ids sent by the client, {@code <= 0}
     *                         for difficulties that do not have an id yet.
     */
    public static PreparedSubmission prepare(UserEntity user, int requestedSetId, List<Long> clientBeatmapIds)
            throws BssException {

        if (!isEnabled()) {
            throw new BssException(BssStatusCode.DISABLED);
        }

        if (user == null) {
            throw new BssException(BssStatusCode.INVALID_CREDENTIALS);
        }

        if (!Privileges.has(user.getPrivileges(), Privileges.UNRESTRICTED)) {
            throw new BssException(BssStatusCode.NO_PERMISSION);
        }

        int maxPending = Math.max(0, App.server.enviromentConfig.getBssMaxPendingSets());
        int pending = BssMapsetRepository.countPendingByCreator(user.getId());

        BssMapsetEntity mapset;
        boolean fullSubmit;

        if (requestedSetId > 0 && BssMapsetRepository.exists(requestedSetId)) {
            mapset = BssMapsetRepository.findBySetId(requestedSetId);

            if (!mapset.getCreatorId().equals(user.getId())) {
                throw new BssException(BssStatusCode.NOT_OWNER);
            }

            if (mapset.getStatus() >= RankedStatus.Ranked.getId()) {
                throw new BssException(BssStatusCode.ALREADY_RANKED);
            }

            // A patch can only be applied when we still hold the previous package.
            fullSubmit = !BssStorage.hasOsz2(mapset.getSetId());
        } else {
            if (pending >= maxPending) {
                throw new BssException(BssStatusCode.QUOTA_EXCEEDED);
            }

            mapset = new BssMapsetEntity();
            mapset.setSetId(allocateSetId());
            mapset.setCreatorId(user.getId());
            mapset.setCreatorName(truncate(user.getName(), 32));
            mapset.setStatus(RankedStatus.Pending.getId());
            mapset.setSubmissionDate(LocalDateTime.now());
            mapset.setLastUpdate(LocalDateTime.now());
            mapset.setRevision(0);
            mapset.setActive(true);

            BssMapsetRepository.save(mapset);

            pending++;
            fullSubmit = true;
        }

        List<Long> beatmapIds = reserveBeatmapIds(mapset.getSetId(), clientBeatmapIds);

        return new PreparedSubmission(
                mapset,
                beatmapIds,
                fullSubmit,
                Math.max(0, maxPending - pending));
    }

    /**
     * Keeps every id the client already owns for this set and mints a fresh one
     * for each new difficulty.
     */
    private static List<Long> reserveBeatmapIds(int setId, List<Long> clientBeatmapIds) {
        Set<Long> used = new HashSet<>();
        List<Long> result = new ArrayList<>();

        for (Long candidate : clientBeatmapIds) {
            boolean reusable = candidate != null
                    && candidate > 0
                    && isLocalId(candidate)
                    && !used.contains(candidate)
                    && belongsToSet(candidate, setId);

            if (reusable) {
                used.add(candidate);
                result.add(candidate);
                continue;
            }

            result.add(allocateBeatmapId(used));
        }

        return result;
    }

    /**
     * Re-extracts one difficulty from the package of its set.
     *
     * <p>The loose {@code .osu} files under {@code data/maps} are a cache: the
     * authoritative copy is the osz2 package kept for every submitted set. When a
     * loose file goes missing &mdash; a half-finished upload, a cleaned data
     * directory, a set imported before extraction existed &mdash; scoring on that
     * map would otherwise break permanently. Rebuilding it from the package costs
     * one read and makes the loss self-healing.</p>
     *
     * @return the file contents, also written back to disk, or {@code null} when the
     *         package cannot supply it.
     */
    public static byte[] restoreBeatmapFile(long beatmapId) {
        if (!isLocalId(beatmapId)) {
            return null;
        }

        BeatmapEntity beatmap = BeatmapRepository.findById(beatmapId);

        if (beatmap == null || beatmap.getSetId() == null) {
            return null;
        }

        int setId = beatmap.getSetId().intValue();

        if (!BssStorage.hasOsz2(setId)) {
            return null;
        }

        try {
            Package pkg = Package.fromBytes(BssStorage.readOsz2(setId));

            for (PackageFile file : pkg.getFiles()) {
                if (!file.isBeatmap()) {
                    continue;
                }

                Integer id = pkg.getBeatmapIds().get(file.getFilename());

                if (id == null || id.longValue() != beatmapId) {
                    continue;
                }

                byte[] content = file.getContent();

                if (content == null || content.length == 0) {
                    return null;
                }

                BssStorage.writeBeatmap(beatmapId, content);

                return content;
            }
        } catch (IOException | RuntimeException e) {
            logger.error("BSS: failed to restore map <{}> from the package of set {}",
                    beatmapId, setId, e);
        }

        return null;
    }

    private static boolean belongsToSet(long beatmapId, int setId) {
        BeatmapEntity existing = BeatmapRepository.findById(beatmapId);

        // Unknown ids inside our own range are fine: they were reserved by a
        // previous getid call whose upload never completed.
        return existing == null
                || existing.getSetId() == null
                || existing.getSetId() == (long) setId;
    }

    // ------------------------------------------------------------------
    // Upload processing (bmsubmit-upload)
    // ------------------------------------------------------------------

    /**
     * Validates, stores and indexes an uploaded osz2 package.
     *
     * <p>Besides persisting the package this generates the plain {@code .osz}
     * files that {@code /d/{id}} serves, extracts every difficulty into
     * {@code data/maps} (so scores, leaderboards and pp work without any
     * further change) and refreshes the {@code maps} / {@code mapsets} rows.</p>
     */
    public static void ingest(BssMapsetEntity mapset, UserEntity user, byte[] osz2Bytes) throws BssException {
        try {
            Package pkg = Package.fromBytes(osz2Bytes);

            // Keep the bytes exactly as the client produced them. The next
            // incremental upload is a diff against this, not against the
            // re-exported copy stored below.
            BssStorage.writePatchBase(mapset.getSetId(), osz2Bytes);

            pkg.setBeatmapSetID(mapset.getSetId());

            List<PackageFile> beatmaps = pkg.getFiles().stream()
                    .filter(PackageFile::isBeatmap)
                    .toList();

            if (beatmaps.isEmpty()) {
                throw new BssException(BssStatusCode.INVALID_PACKAGE);
            }

            Set<Long> used = new HashSet<>();

            // 1. Make sure every difficulty carries an id from our range.
            for (PackageFile file : beatmaps) {
                Integer current = pkg.getBeatmapIds().get(file.getFilename());

                long beatmapId;

                if (current != null && current > 0 && isLocalId(current)
                        && !used.contains((long) current)
                        && belongsToSet(current, mapset.getSetId())) {
                    beatmapId = current;
                } else {
                    beatmapId = allocateBeatmapId(used);
                }

                used.add(beatmapId);

                pkg.setBeatmapID(file.getFilename(), (int) beatmapId);
            }

            // 2. Persist the canonical package. It doubles as the patch base of
            //    the next incremental upload.
            byte[] normalized = pkg.export();

            BssStorage.writeOsz2(mapset.getSetId(), normalized);

            // 3. Build the plain archives served by the download route.
            Package repacked = Package.fromBytes(normalized);

            byte[] withVideo = repacked.createOszPackage(true);
            BssStorage.writeOsz(mapset.getSetId(), false, withVideo);

            List<String> videoFiles = repacked.getFiles().stream()
                    .filter(PackageFile::isVideo)
                    .map(PackageFile::getFilename)
                    .toList();

            byte[] withoutVideo = withVideo;

            if (!videoFiles.isEmpty()) {
                videoFiles.forEach(repacked::removeFile);
                withoutVideo = repacked.createOszPackage(true);
            }

            BssStorage.writeOsz(mapset.getSetId(), true, withoutVideo);

            // 4. Index every difficulty.
            Package indexed = Package.fromBytes(normalized);

            List<Long> storedIds = new ArrayList<>();
            String artist = null;
            String title = null;

            for (PackageFile file : indexed.getFiles()) {
                if (!file.isBeatmap()) {
                    continue;
                }

                Integer beatmapId = indexed.getBeatmapIds().get(file.getFilename());

                if (beatmapId == null || beatmapId <= 0) {
                    continue;
                }

                byte[] content = file.getContent();

                if (content == null) {
                    continue;
                }

                BssStorage.writeBeatmap(beatmapId, content);

                OsuFileParser.ParsedBeatmap parsed = OsuFileParser.parse(content);

                artist = firstNonBlank(artist, parsed.getArtist());
                title = firstNonBlank(title, parsed.getTitle());

                upsertBeatmap(mapset, user, beatmapId, file.getFilename(), content, parsed);

                storedIds.add(beatmapId.longValue());
            }

            if (storedIds.isEmpty()) {
                throw new BssException(BssStatusCode.INVALID_PACKAGE);
            }

            // 5. Drop difficulties the creator deleted in this revision.
            removeStaleBeatmaps(mapset.getSetId(), storedIds);

            ensureMapsetRow(mapset.getSetId());

            // 6. Refresh the set metadata.
            mapset.setArtist(truncate(firstNonBlank(
                    indexed.getMetadata(MetadataType.Artist), artist), 128));
            mapset.setTitle(truncate(firstNonBlank(
                    indexed.getMetadata(MetadataType.Title), title), 128));
            mapset.setCreatorName(truncate(user.getName(), 32));
            mapset.setCreatorId(user.getId());
            mapset.setOsz2Hash(md5(normalized));
            mapset.setHasVideo(!videoFiles.isEmpty());
            mapset.setFilesize(withVideo.length);
            mapset.setFilesizeNoVideo(withoutVideo.length);
            mapset.setLastUpdate(LocalDateTime.now());
            mapset.setRevision(mapset.getRevision() + 1);
            mapset.setActive(true);

            if (mapset.getSubmissionDate() == null) {
                mapset.setSubmissionDate(LocalDateTime.now());
            }

            BssMapsetRepository.save(mapset);

            logger.info("BSS: stored revision {} of set {} ({} difficulties) for user {}",
                    mapset.getRevision(), mapset.getSetId(), storedIds.size(), user.getName());

        } catch (BssException e) {
            throw e;
        } catch (IOException e) {
            logger.error("BSS: failed to process the package of set {}", mapset.getSetId(), e);
            throw new BssException(BssStatusCode.INVALID_PACKAGE);
        } catch (Exception e) {
            logger.error("BSS: unexpected failure while processing set {}", mapset.getSetId(), e);
            throw new BssException(BssStatusCode.ERROR);
        }
    }

    private static void upsertBeatmap(
            BssMapsetEntity mapset,
            UserEntity user,
            long beatmapId,
            String filename,
            byte[] content,
            OsuFileParser.ParsedBeatmap parsed) {

        BeatmapEntity entity = BeatmapRepository.findById(beatmapId);
        boolean isNew = entity == null;

        if (isNew) {
            entity = new BeatmapEntity();
            entity.setId(beatmapId);
            entity.setPlays(0);
            entity.setPasses(0);
        }

        entity.setServer("private");
        entity.setSetId((long) mapset.getSetId());
        entity.setMd5(md5(content));
        entity.setArtist(truncate(fallback(parsed.getArtist(), "Unknown"), 128));
        entity.setTitle(truncate(fallback(parsed.getTitle(), "Unknown"), 128));
        entity.setVersion(truncate(fallback(parsed.getVersion(), "Normal"), 128));
        entity.setCreator(truncate(fallback(parsed.getCreator(), user.getName()), 19));
        entity.setFilename(truncate(filename, 256));
        entity.setLastUpdate(LocalDateTime.now());
        entity.setTotalLength(parsed.getTotalLength());
        entity.setMaxCombo(parsed.getMaxCombo());
        entity.setMode(parsed.getMode());
        entity.setBpm(parsed.getBpm());
        entity.setCs(parsed.getCs());
        entity.setAr(parsed.getAr());
        entity.setOd(parsed.getOd());
        entity.setHp(parsed.getHp());

        if (entity.getFrozen() == null) {
            entity.setFrozen(false);
        }

        // Freshly submitted difficulties always start out pending; a nominator
        // can promote them later through the existing nomination commands.
        if (isNew || !Boolean.TRUE.equals(entity.getFrozen())) {
            entity.setStatus(mapset.getStatus());
        }

        // Star rating is left to the existing recalculation pipeline: it is the
        // single place in the codebase that owns difficulty values.
        if (entity.getDiff() == null) {
            entity.setDiff(0f);
        }

        if (isNew) {
            BeatmapRepository.save(entity);
        } else {
            BeatmapRepository.update(entity);
        }
    }

    private static void removeStaleBeatmaps(int setId, List<Long> keptIds) {
        List<BeatmapEntity> existing = BeatmapRepository.findBySetId(setId);

        if (existing == null) {
            return;
        }

        for (BeatmapEntity entity : existing) {
            if (keptIds.contains(entity.getId())) {
                continue;
            }

            BssStorage.deleteBeatmap(entity.getId());
            BeatmapRepository.delete(entity);
        }
    }

    private static void ensureMapsetRow(int setId) {
        MapsetEntity mapset = DB.find(MapsetEntity.class, setId);

        if (mapset == null) {
            mapset = new MapsetEntity();
            mapset.setId(setId);
            mapset.setServer(MapsetEntity.ServerType.PRIVATE);
            mapset.setLastOsuApiCheck(LocalDateTime.now());

            DB.save(mapset);
            return;
        }

        mapset.setServer(MapsetEntity.ServerType.PRIVATE);
        mapset.setLastOsuApiCheck(LocalDateTime.now());

        DB.update(mapset);
    }

    /**
     * Applies an incremental (bsdiff) upload on top of the package the client
     * already has.
     *
     * <p>The diff is computed by the editor against its own local osz2, so it can
     * only be applied to identical bytes. That is the copy saved by
     * {@link #ingest}, not the canonical package, which is a re-export produced
     * after the beatmap ids were rewritten and therefore differs byte for
     * byte.</p>
     */
    public static byte[] applyPatch(int setId, byte[] patch) throws BssException {
        if (!BssStorage.hasPatchBase(setId)) {
            // Sets uploaded before the base was kept, or a wiped data directory.
            // There is nothing to patch against and guessing would corrupt the set.
            logger.warn("BSS: no patch base for set {}; the client must submit the full package",
                    setId);

            throw new BssException(BssStatusCode.INVALID_PACKAGE);
        }

        try {
            byte[] source = BssStorage.readPatchBase(setId);

            return PatchUtil.applyBsdiffPatch(source, patch);
        } catch (IOException | RuntimeException e) {
            logger.error("BSS: failed to apply the incremental patch of set {}", setId, e);
            throw new BssException(BssStatusCode.INVALID_PACKAGE);
        }
    }

    /**
     * Marks a set as deleted. The row itself is kept so its ids are never
     * handed out twice.
     */
    public static void deactivate(BssMapsetEntity mapset) {
        mapset.setActive(false);

        BssMapsetRepository.save(mapset);
        BssStorage.deleteSet(mapset.getSetId());
    }

    public static Optional<String> downloadFilename(BssMapsetEntity mapset) {
        if (mapset == null) {
            return Optional.empty();
        }

        String name = String.format("%d %s - %s.osz",
                mapset.getSetId(),
                fallback(mapset.getArtist(), "Unknown"),
                fallback(mapset.getTitle(), "Unknown"));

        return Optional.of(name.replaceAll("[\\\\/:*?\"<>|]", "_"));
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    public static String md5(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");

            StringBuilder builder = new StringBuilder();

            for (byte b : digest.digest(data)) {
                builder.append(String.format("%02x", b));
            }

            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }

        return second;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
