package com.osuserverlist.bjar.modules.account;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one time passwords, the kind Google Authenticator, Aegis and every other
 * authenticator app produces.
 *
 * <p>This is RFC 6238 with the parameters those apps assume when a QR code does not say
 * otherwise: HMAC-SHA1 over a 30 second counter, truncated to six digits. Nothing here is
 * configurable on purpose - an authenticator that has already been set up cannot be told about
 * a changed digit count or period, so these are numbers we have to live with rather than
 * settings.
 *
 * <p>There is no dependency behind it: {@code javax.crypto} does the only arithmetic that
 * matters, and Base32 - which the JDK does not ship - is the twenty lines at the bottom. The
 * secret is stored as that Base32 text, because that is the form both the QR code and a player
 * typing it into their phone by hand need.
 */
public final class TotpService {

    /** RFC 4648 Base32, the alphabet every authenticator app expects in a QR code. */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** 160 bits: the size RFC 4226 recommends and what authenticator apps assume. */
    private static final int SECRET_BYTES = 20;

    public static final int DIGITS = 6;

    public static final long STEP_SECONDS = 30L;

    /**
     * One step either side of now, so a phone whose clock is half a minute out - or a player
     * who types the last digit as the code rolls over - still gets in. Wider than this starts
     * to matter: every extra step is another code an attacker may guess.
     */
    private static final int WINDOW = 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpService() {
    }

    /** A fresh secret, in the Base32 form that goes into the QR code. */
    public static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);

        return encode(bytes);
    }

    /** Whether an account has a usable secret, which is what "2FA is on" means here. */
    public static boolean enabled(String secret) {
        return secret != null && !secret.isBlank();
    }

    /**
     * Whether a code belongs to a secret right now.
     *
     * <p>Spaces and dashes are dropped first: authenticator apps show {@code 123 456} and people
     * copy what they see.
     */
    public static boolean verify(String secret, String code) {
        if (!enabled(secret) || code == null) {
            return false;
        }

        String digits = code.replaceAll("[^0-9]", "");

        if (digits.length() != DIGITS) {
            return false;
        }

        byte[] key = decode(secret);

        if (key.length == 0) {
            return false;
        }

        long counter = System.currentTimeMillis() / 1000L / STEP_SECONDS;

        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            if (constantEquals(digits, code(key, counter + offset))) {
                return true;
            }
        }

        return false;
    }

    /**
     * The {@code otpauth://} address an authenticator app reads out of a QR code.
     *
     * <p>The label carries the issuer twice - once in front of the account name and once as a
     * parameter - which is what the Key Uri Format asks for and what makes older apps group the
     * entry under the server's name instead of listing a bare username.
     */
    public static String otpauthUri(String issuer, String account, String secret) {
        return "otpauth://totp/" + escape(issuer) + ":" + escape(account)
                + "?secret=" + secret
                + "&issuer=" + escape(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }

    /** The secret in groups of four, for somebody typing it in by hand. */
    public static String formatSecret(String secret) {
        if (secret == null) {
            return "";
        }

        StringBuilder out = new StringBuilder();

        for (int i = 0; i < secret.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }

            out.append(secret.charAt(i));
        }

        return out.toString();
    }

    private static String escape(String value) {
        // URLEncoder is built for form bodies, where a space is a plus sign. In a path it is not.
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** The six digits belonging to one counter value. */
    private static String code(byte[] key, long counter) {
        byte[] message = new byte[8];
        long value = counter;

        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (value & 0xffL);
            value >>>= 8;
        }

        byte[] mac;

        try {
            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(key, "HmacSHA1"));
            mac = hmac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable", e);
        }

        // Dynamic truncation, RFC 4226 section 5.3: the last nibble picks which four bytes of
        // the digest the code is made of, so every counter uses a different part of it.
        int index = mac[mac.length - 1] & 0x0f;

        int binary = ((mac[index] & 0x7f) << 24)
                | ((mac[index + 1] & 0xff) << 16)
                | ((mac[index + 2] & 0xff) << 8)
                | (mac[index + 3] & 0xff);

        return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
    }

    /** Compared without an early exit, so the answer does not leak how much matched. */
    private static boolean constantEquals(String left, String right) {
        if (left.length() != right.length()) {
            return false;
        }

        int difference = 0;

        for (int i = 0; i < left.length(); i++) {
            difference |= left.charAt(i) ^ right.charAt(i);
        }

        return difference == 0;
    }

    private static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();

        int buffer = 0;
        int bits = 0;

        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;

            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }

        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }

        return out.toString();
    }

    /** Anything that is not a Base32 character is skipped, padding included. */
    static byte[] decode(String secret) {
        String upper = secret.trim().toUpperCase(Locale.ROOT);

        byte[] out = new byte[upper.length() * 5 / 8];

        int buffer = 0;
        int bits = 0;
        int written = 0;

        for (int i = 0; i < upper.length(); i++) {
            int value = ALPHABET.indexOf(upper.charAt(i));

            if (value < 0) {
                continue;
            }

            buffer = (buffer << 5) | value;
            bits += 5;

            if (bits >= 8) {
                out[written++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }

        if (written == out.length) {
            return out;
        }

        byte[] trimmed = new byte[written];
        System.arraycopy(out, 0, trimmed, 0, written);

        return trimmed;
    }
}
