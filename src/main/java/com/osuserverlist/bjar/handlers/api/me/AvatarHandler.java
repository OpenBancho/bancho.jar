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
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.WebEngine;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;

import io.javalin.http.Context;
import io.javalin.http.Handler;

/** Secure upload of the current user's avatar. */
@Host({"api.", "server", ""})
@Path("/api/v1/me/avatar")
@WebEngine.HttpMethod("POST")
public final class AvatarHandler implements Handler {
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SIDE = 4096;
    private static final long MAX_PIXELS = 16_000_000L;
    /** Longest edge of the stored avatar; the player's own proportions are kept. */
    private static final int MAX_OUTPUT_SIDE = 512;
    private static final java.nio.file.Path AVATAR_DIR =
            java.nio.file.Path.of("data", "assets", "avatars").toAbsolutePath().normalize();

    static {
        // Never let ImageIO spill attacker-controlled data into its shared disk cache.
        ImageIO.setUseCache(false);
    }

    @Override
    public void handle(@NotNull Context ctx) {
        OAuthToken token = ApiAuth.require(ctx);
        if (token == null || !ApiAuth.requireProfile(ctx, token)) {
            return;
        }

        String contentType = ctx.contentType();
        if (contentType == null || !contentType.equalsIgnoreCase("image/png")) {
            ctx.status(415).json(Map.of("status", "Only a cropped PNG avatar is accepted."));
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

        // Reject renamed files and polyglot prefixes before invoking a decoder.
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

        BufferedImage avatar = normalise(source);
        java.nio.file.Path target = AVATAR_DIR.resolve(token.getUserId() + ".png").normalize();
        java.nio.file.Path temporary = null;

        if (!target.getParent().equals(AVATAR_DIR)) {
            ctx.status(500).json(Map.of("status", "Could not store the avatar."));
            return;
        }

        try {
            Files.createDirectories(AVATAR_DIR);
            temporary = Files.createTempFile(AVATAR_DIR, ".avatar-", ".png");

            if (!ImageIO.write(avatar, "png", temporary.toFile())) {
                throw new IOException("PNG writer unavailable");
            }

            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }

            // The stored file is a fresh server-generated PNG: no original name,
            // metadata, scripts, appended bytes or alternate image frames survive.
            ctx.header("Cache-Control", "no-store");
            ctx.json(Map.of("status", "success", "avatar", token.getUserId() + ".png"));
            MeSupport.logger.info("User <{}> updated their avatar from <{}>",
                    token.getUserId(), ctx.ip());
        } catch (IOException e) {
            MeSupport.logger.error("Could not store avatar for user <{}>", token.getUserId(), e);
            ctx.status(500).json(Map.of("status", "Could not store the avatar."));
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort cleanup after a failed move/write.
                }
            }
        }
    }

    private static BufferedImage decodePng(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return null;

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;

            ImageReader reader = readers.next();
            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName())) return null;
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
     * Re-encodes the upload at a bounded size. The frontend already applied the
     * player's crop, so nothing is cut here; this exists so the stored file is
     * always a freshly rendered image rather than the uploaded bytes.
     */
    private static BufferedImage normalise(BufferedImage source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        double factor = longest > MAX_OUTPUT_SIDE ? (double) MAX_OUTPUT_SIDE / longest : 1;
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));

        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static void tooLarge(Context ctx) {
        ctx.status(413).json(Map.of("status", "The avatar is too large (4 MB maximum)."));
    }

    private static void invalid(Context ctx) {
        ctx.status(400).json(Map.of("status", "The file is not a safe, valid PNG image."));
    }
}
