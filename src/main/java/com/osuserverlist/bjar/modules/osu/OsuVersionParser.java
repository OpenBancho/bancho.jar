package com.osuserverlist.bjar.modules.osu;

import lombok.Value;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OsuVersionParser {

    // b20250718
    // b20250718.2
    // b20250718cuttingedge
    // b20250718.2cuttingedge
    private static final Pattern OSU_VERSION = Pattern.compile(
            "^b(?<date>\\d{8})(?:\\.(?<revision>\\d+))?(?<stream>[A-Za-z]+)?$"
    );

    public static OsuVersion parse(String versionString) {
        Matcher matcher = OSU_VERSION.matcher(versionString);

        if (!matcher.matches()) {
            return null;
        }

        String date = matcher.group("date");

        return new OsuVersion(
                LocalDate.of(
                        Integer.parseInt(date.substring(0, 4)),
                        Integer.parseInt(date.substring(4, 6)),
                        Integer.parseInt(date.substring(6, 8))
                ),
                matcher.group("revision") == null
                        ? null
                        : Integer.parseInt(matcher.group("revision")),
                OsuStream.fromString(
                        matcher.group("stream") == null
                                ? "stable"
                                : matcher.group("stream")
                )
        );
    }

    @Value
    public static class OsuVersion {
        LocalDate date;
        Integer revision;
        OsuStream stream;
    }

    public enum OsuStream {
        STABLE,
        BETA,
        CUTTINGEDGE,
        TOURNAMENT;

        public static OsuStream fromString(String value) {
            if (value == null) {
                return STABLE;
            }

            switch (value.toLowerCase()) {
                case "beta":
                    return BETA;
                case "cuttingedge":
                    return CUTTINGEDGE;
                case "tourney":
                case "tournament":
                    return TOURNAMENT;
                default:
                    return STABLE;
            }
        }
    }
}
