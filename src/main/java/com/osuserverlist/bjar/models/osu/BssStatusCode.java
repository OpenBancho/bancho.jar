package com.osuserverlist.bjar.models.osu;

import lombok.Getter;

/**
 * Result codes returned by the legacy Beatmap Submission System endpoints
 * ({@code osu-osz2-bmsubmit-*.php}).
 *
 * <p><b>Important:</b> the stable osu! client never published an official
 * specification for these codes. The values below follow the convention used
 * by the community server implementations the client is known to work with.
 * They are intentionally centralized here so that a single edit is enough to
 * re-tune the protocol should a specific client build disagree.</p>
 *
 * <p>Only {@link #SUCCESS} is guaranteed by the client: any non-zero first
 * line aborts the submission and shows the accompanying message.</p>
 */
@Getter
public enum BssStatusCode {

    /** Submission accepted, the client may continue. */
    SUCCESS(0, ""),

    /** Generic failure, shown when nothing more specific applies. */
    ERROR(1, "An error occurred while processing your submission."),

    /** The account could not be authenticated. */
    INVALID_CREDENTIALS(2, "Authentication failed. Please sign in again."),

    /** The set exists but belongs to somebody else. */
    NOT_OWNER(3, "You are not the creator of this beatmap set."),

    /** The per-user pending-set quota is exhausted. */
    QUOTA_EXCEEDED(4, "You have reached your beatmap submission limit."),

    /** Ranked/approved/loved sets may not be updated any more. */
    ALREADY_RANKED(5, "This beatmap set is ranked and can no longer be updated."),

    /** The account is restricted or lacks the required privileges. */
    NO_PERMISSION(6, "Your account is not allowed to submit beatmaps."),

    /** The uploaded package is malformed or exceeds the size limit. */
    INVALID_PACKAGE(7, "The uploaded beatmap package could not be processed."),

    /** The submission system is turned off on this server. */
    DISABLED(8, "Beatmap submission is disabled on this server.");

    private final int id;
    private final String message;

    BssStatusCode(int id, String message) {
        this.id = id;
        this.message = message;
    }

    /**
     * Renders the code the way the client expects to read it: the numeric code
     * on the first line, optionally followed by a human readable reason.
     */
    public String toResponse() {
        if (this == SUCCESS) {
            return String.valueOf(id);
        }

        return id + "\n" + message;
    }
}
