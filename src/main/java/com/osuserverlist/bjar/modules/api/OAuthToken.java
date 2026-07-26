package com.osuserverlist.bjar.modules.api;

import lombok.Data;

/**
 * A single issued OAuth2 token, access or refresh.
 *
 * <p>Only the opaque token string ever leaves the server; everything below is kept in Redis
 * and looked up on each request, so a client can neither read nor forge its own identity or
 * privileges.</p>
 */
@Data
public class OAuthToken {

    /** {@code access} or {@code refresh}. */
    private String type;

    /** The opaque token string. Never serialised into the stored value. */
    private transient String token;

    /**
     * Identifies the chain of refresh tokens this one descends from.
     *
     * <p>Rotation keeps the family but replaces the token, which is what makes replay of an
     * already-used refresh token detectable.</p>
     */
    private String familyId;

    private int userId;

    private String username;

    private int privileges;

    /** Space separated scopes, in the OAuth2 style. */
    private String scope;

    /** Which client asked for the token; free-form, for the audit log. */
    private String clientId;

    /** Unix seconds. */
    private long issuedAt;

    /** Unix seconds. */
    private long expiresAt;

    private String ip;
}
