package com.osuserverlist.bjar.handlers.api.me;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiPagination;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.account.DonorService;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.UserRepository;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * The picture of a custom badge, uploaded rather than linked.
 *
 * <p>A link would leave the badge at the mercy of whatever host it points at,
 * and would let one account decide what every visitor of its profile fetches.
 * The image is therefore stored here, the same way avatars are: the upload is
 * decoded, bounded and written out again as a fresh PNG, so nothing of the
 * original file (name, metadata, appended bytes, extra frames) survives.</p>
 *
 * <p>The stored path is written straight onto the account, so a successful
 * upload is all it takes for the badge picture to change.</p>
 */
@Host({"api.", "server", ""})
@Path("/api/v1/me/badge")
@WebEngine.HttpMethod("POST")
public final class BadgeIconHandler implements Handler {

    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    private static final int MAX_SIDE = 4096;

    private static final long MAX_PIXELS = 16_000_000L;

    /** A badge icon is drawn 48x24, so 192px wide covers every display scale. */
    private static final int MAX_OUTPUT_WIDTH = 192;

    /** Badges are landscape plates: every picture is stored twice as wide as it is tall. */
    private static final int ASPECT_WIDTH = 2;

    private static final int ASPECT_HEIGHT = 1;

    static final java.nio.file.Path BADGE_DIR =
            java.nio.file.Path.of("data", "assets", "badges").toAbsolutePath().normalize();

    /** Where the browser reads it back from; short enough for the 64 char column. */
    static final String PUBLIC_PREFIX = "/api/v1/badge/";

    static {
        ImageIO.setUseCache(false);
    }

    @Override
    public void handle(@NotNull Context ctx) {
        OAuthToken token = ApiAuth.require(ctx);
        if (token == null || !ApiAuth.requireProfile(ctx, token)) {
            return;
        }

        UserEntity user = UserRepository.findById(token.getUserId());
        if (user == null) {
            ApiAuth.notFound(ctx, "No such user.");
            return;
        }

        if (!DonorService.isDonor(user)) {
            ctx.status(403).json(ApiPagination.error("A custom badge is a supporter perk."));
            return;
        }

        String contentType = ctx.contentType();
        if (contentType == null || !contentType.equalsIgnoreCase("image/png")) {
            ctx.status(415).json(Map.of("status", "Only a PNG badge picture is accepted."));
            return;
        }

        long declared = ctx.contentLength();
        if (declared <= 0 || declared > MAX_FILE_BYTES) {
            tooLarge(ctx);
            return;
        }

        byte[] bytes = ctx.bodyAsBytes();
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            tooLarge(ctx);
            return;
        }

        // Renamed files and polyglot prefixes are refused before a decoder runs.
        if (bytes.length < 8
                || (bytes[0] & 0xff) != 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4e
                || bytes[3] != 0x47 || bytes[4] != 0x0d || bytes[5] != 0x0a
                || bytes[6] != 0x1a || bytes[7] != 0x0a) {
            invalid(ctx);
            return;
        }

        BufferedImage source;
        try {
            source = decodePng(bytes);
        } catch (IOException | RuntimeException e) {
            invalid(ctx);
            return;
        }

        if (source == null || source.getWidth() < 16 || source.getHeight() < 16
                || source.getWidth() > MAX_SIDE || source.getHeight() > MAX_SIDE
                || (long) source.getWidth() * source.getHeight() > MAX_PIXELS) {
            invalid(ctx);
            return;
        }

        BufferedImage icon = normalise(source);
        String filename = user.getId() + ".png";
        java.nio.file.Path target = BADGE_DIR.resolve(filename).normalize();
        java.nio.file.Path temporary = null;

        if (!target.getParent().equals(BADGE_DIR)) {
            ctx.status(500).json(Map.of("status", "Could not store the badge picture."));
            return;
        }

        try {
            Files.createDirectories(BADGE_DIR);
            temporary = Files.createTempFile(BADGE_DIR, ".badge-", ".png");

            if (!ImageIO.write(icon, "png", temporary.toFile())) {
                throw new IOException("PNG writer unavailable");
            }

            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }

            // The version guards against a browser showing the previous picture
            // after a replacement, since the path itself never changes.
            String icons = PUBLIC_PREFIX + filename;

            user.setCustomBadgeIcon(icons);
            UserRepository.save(user);

            ctx.header("Cache-Control", "no-store");

            Map<String, Object> response = ApiAuth.success();
            response.put("custom_badge_icon", icons);
            response.put("custom_badge_name", user.getCustomBadgeName());

            ctx.json(response);

            MeSupport.logger.info("User <{}> updated their badge picture from <{}>",
                    user.getId(), com.osuserverlist.bjar.modules.util.ClientIp.of(ctx));
        } catch (IOException e) {
            MeSupport.logger.error("Could not store the badge picture of user <{}>",
                    user.getId(), e);
            ctx.status(500).json(Map.of("status", "Could not store the badge picture."));
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort cleanup after a failed write or move.
                }
            }
        }
    }

    private static BufferedImage decodePng(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return null;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }

            ImageReader reader = readers.next();

            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName())) {
                    return null;
                }

                reader.setInput(input, true, true);

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width < 16 || height < 16 || width > MAX_SIDE || height > MAX_SIDE
                        || (long) width * height > MAX_PIXELS) {
                    return null;
                }

                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Crops the picture to the shape a badge is drawn in, then scales it down.
     *
     * <p>A badge is not a round avatar: it is a wide plate holding a picture and a
     * name, so uploads are centre-cropped to 2:1 rather than to a square. The file
     * then has the same proportions as the slot on the profile, so nothing is
     * letterboxed or squashed there, whatever shape the player started from.</p>
     */
    private static BufferedImage normalise(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        // The largest centred 2:1 rectangle that fits inside the upload.
        int cropWidth = Math.min(width, height * ASPECT_WIDTH / ASPECT_HEIGHT);
        int cropHeight = Math.max(1, cropWidth * ASPECT_HEIGHT / ASPECT_WIDTH);
        int x = (width - cropWidth) / 2;
        int y = (height - cropHeight) / 2;

        double factor = cropWidth > MAX_OUTPUT_WIDTH ? (double) MAX_OUTPUT_WIDTH / cropWidth : 1.0;
        int outWidth = Math.max(1, (int) Math.round(cropWidth * factor));
        int outHeight = Math.max(1, (int) Math.round(cropHeight * factor));

        BufferedImage output = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();

        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);

            graphics.drawImage(source, 0, 0, outWidth, outHeight,
                    x, y, x + cropWidth, y + cropHeight, null);
        } finally {
            graphics.dispose();
        }

        return output;
    }

    private static void tooLarge(Context ctx) {
        ctx.status(413).json(Map.of("status", "The badge picture is too large (2 MB maximum)."));
    }

    private static void invalid(Context ctx) {
        ctx.status(400).json(Map.of("status", "The file is not a safe, valid PNG image."));
    }
}
