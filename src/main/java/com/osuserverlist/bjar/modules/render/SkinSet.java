package com.osuserverlist.bjar.modules.render;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

/**
 * The pictures a replay is drawn with.
 *
 * <p>Every video uses the server's skin ({@code render/skin-misuzu} in the jar), whatever beatmap it shows, so the clips
 * on the front page look alike. An element the skin does not have falls back to the default skin bundled under
 * {@code render/skin}. A name is tried as {@code name@2x.png}, then {@code name.png}.
 *
 * <p>From the beatmap's own {@code .osz} only what belongs to the map is used: its background, its audio and its hit
 * sound files. Its skin pictures are ignored unless {@link #USE_BEATMAP_SKIN} is switched on.
 */
public final class SkinSet {

    /** An image together with the factor that turns its pixels into 640x480-plane "logical" units. */
    public record Img(BufferedImage image, double logical) {
        public double w() {
            return image.getWidth() * logical;
        }

        public double h() {
            return image.getHeight() * logical;
        }

        /** The skin hides an element by supplying a one pixel transparent picture for it. */
        public boolean hidden() {
            return image.getWidth() <= 2 && image.getHeight() <= 2;
        }
    }

    /** When true the pictures packed in the beatmap's osz take precedence over the server skin. */
    private static final boolean USE_BEATMAP_SKIN = false;

    private static final String MAIN = "/render/skin-misuzu/";
    private static final String DEFAULT = "/render/skin/";

    private static final long MAX_ENTRY = 8L * 1024 * 1024;

    private final Map<String, byte[]> packed = new HashMap<>();
    // Frames are drawn by several threads at once, so the caches are concurrent. A name nobody has is remembered too,
    // otherwise every frame would look for the missing file again.
    private final Map<String, Img> cache = new ConcurrentHashMap<>();
    private final Map<String, Img> tinted = new ConcurrentHashMap<>();
    private final Set<String> missing = ConcurrentHashMap.newKeySet();
    private final Map<String, String> ini = new HashMap<>();

    /** Reads the beatmap's osz (images, sounds) and the server skin's skin.ini. {@code osz} may be null. */
    public SkinSet(Path osz) {
        try (InputStream in = SkinSet.class.getResourceAsStream(MAIN + "skin.ini")) {
            if (in != null) {
                readIni(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // no ini: the defaults are used
        }

        if (osz == null) {
            return;
        }

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(osz))) {
            ZipEntry e;

            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName().toLowerCase(Locale.ROOT).replace('\\', '/');
                boolean sample = name.matches("((normal|soft|drum)-(hit|slider)[a-z]*[0-9]*|spinner(spin|bonus))\\.(wav|mp3|ogg)");

                if (e.isDirectory() || name.contains("/") || !(sample || name.endsWith(".png") || name.endsWith(".jpg")
                        || name.endsWith(".jpeg") || name.equals("skin.ini"))) {
                    continue;
                }

                packed.put(name, zip.readNBytes((int) MAX_ENTRY));
            }
        } catch (IOException ignored) {
            // an unreadable osz just means no background, sounds or beatmap skin
        }

        if (USE_BEATMAP_SKIN && packed.get("skin.ini") != null) {
            readIni(new String(packed.get("skin.ini"), StandardCharsets.UTF_8));
        }
    }

    private void readIni(String text) {
        for (String line : text.split("\\r?\\n")) {
            String l = line.strip();
            int i = l.indexOf(':');

            if (i > 0 && !l.startsWith("//") && !l.startsWith("[")) {
                ini.putIfAbsent(l.substring(0, i).strip().toLowerCase(Locale.ROOT), l.substring(i + 1).strip());
            }
        }
    }

    /** The bytes of a file packed in the osz, by case-insensitive name. Null if there is none. */
    public byte[] file(String name) {
        return packed.get(name.toLowerCase(Locale.ROOT));
    }

    /** A picture of the beatmap itself (its background). */
    public BufferedImage beatmapImage(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        byte[] data = packed.get(name.toLowerCase(Locale.ROOT).replace('\\', '/'));

        if (data == null) {
            return null;
        }

        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ skin.ini

    public String iniValue(String key) {
        return ini.get(key.toLowerCase(Locale.ROOT));
    }

    public boolean iniFlag(String key, boolean fallback) {
        String v = iniValue(key);
        return v == null ? fallback : !v.equals("0");
    }

    /** A number from skin.ini, or null when the skin does not set it. */
    public Double iniNumber(String key) {
        try {
            String v = iniValue(key);
            return v == null ? null : Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A "r,g,b" colour from skin.ini, or null. */
    public Color iniColor(String key) {
        String v = iniValue(key);

        if (v == null) {
            return null;
        }

        try {
            String[] c = v.split(",");
            return new Color(Integer.parseInt(c[0].strip()), Integer.parseInt(c[1].strip()), Integer.parseInt(c[2].strip()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Color comboColor(int index) {
        return iniColor("combo" + (index + 1));
    }

    public int comboColorCount() {
        int n = 0;

        while (comboColor(n) != null) {
            n++;
        }

        return n;
    }

    // ------------------------------------------------------------------ pictures

    /** The picture for {@code name}: the server skin's, else the bundled default. Null if neither has it. */
    public Img get(String name) {
        return cached(name, name, true);
    }

    /** The bundled default skin's picture, for places where the server skin deliberately hides the element. */
    public Img getDefault(String name) {
        return cached("default:" + name, name, false);
    }

    private Img cached(String key, String name, boolean useMain) {
        Img hit = cache.get(key);

        if (hit != null) {
            return hit;
        }

        if (missing.contains(key)) {
            return null;
        }

        Img img = load(name, useMain);

        if (img == null) {
            missing.add(key);
        } else {
            cache.put(key, img);
        }

        return img;
    }

    /** True when the server skin itself draws {@code name} (not hidden, not left to the default). */
    public boolean has(String name) {
        Img img = get(name);
        return img != null && !img.hidden() && inMain(name);
    }

    private boolean inMain(String name) {
        return SkinSet.class.getResource(MAIN + name + "@2x.png") != null
                || SkinSet.class.getResource(MAIN + name + ".png") != null;
    }

    /** Like {@link #get} but multiplied by a colour; cached. */
    public Img tinted(String name, Color color) {
        return tinted.computeIfAbsent(name + "#" + color.getRGB(), k -> {
            Img base = get(name);

            if (base == null) {
                return null;
            }

            BufferedImage src = base.image();
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            int cr = color.getRed();
            int cg = color.getGreen();
            int cb = color.getBlue();

            for (int y = 0; y < src.getHeight(); y++) {
                for (int x = 0; x < src.getWidth(); x++) {
                    int p = src.getRGB(x, y);
                    int a = p >>> 24;
                    int r = ((p >> 16) & 255) * cr / 255;
                    int g = ((p >> 8) & 255) * cg / 255;
                    int b = (p & 255) * cb / 255;
                    out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            return new Img(out, base.logical());
        });
    }

    /** A picture painted a single colour, keeping its shape (alpha); for lit states of dark sprites. Cached. */
    public Img recolored(String name, Color color) {
        return tinted.computeIfAbsent("flat:" + name + "#" + color.getRGB(), k -> {
            Img base = get(name);

            if (base == null) {
                return null;
            }

            BufferedImage src = base.image();
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            int rgb = color.getRGB() & 0xFFFFFF;

            for (int y = 0; y < src.getHeight(); y++) {
                for (int x = 0; x < src.getWidth(); x++) {
                    out.setRGB(x, y, (src.getRGB(x, y) & 0xFF000000) | rgb);
                }
            }

            return new Img(out, base.logical());
        });
    }

    private Img load(String name, boolean useMain) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (USE_BEATMAP_SKIN && useMain) {
            for (String candidate : new String[] { lower + "@2x.png", lower + ".png" }) {
                byte[] data = packed.get(candidate);
                Img img = data == null ? null : decode(data, candidate.contains("@2x") ? 0.5 : 1.0);

                if (img != null) {
                    return img;
                }
            }
        }

        for (String dir : useMain ? new String[] { MAIN, DEFAULT } : new String[] { DEFAULT }) {
            for (String candidate : new String[] { name + "@2x.png", name + ".png" }) {
                try (InputStream in = SkinSet.class.getResourceAsStream(dir + candidate)) {
                    if (in != null) {
                        Img img = decode(in.readAllBytes(), candidate.contains("@2x") ? 0.5 : 1.0);

                        if (img != null) {
                            return img;
                        }
                    }
                } catch (IOException ignored) {
                    // try the next place
                }
            }
        }

        return null;
    }

    private static Img decode(byte[] data, double logical) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            return img == null ? null : new Img(img, logical);
        } catch (IOException e) {
            return null;
        }
    }
}
