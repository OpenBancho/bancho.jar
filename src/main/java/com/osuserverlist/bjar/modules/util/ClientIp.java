package com.osuserverlist.bjar.modules.util;

import io.javalin.http.Context;

/** The address of the real client. Behind nginx the socket address is always 127.0.0.1; nginx puts the real one in X-Real-IP. */
public final class ClientIp {
    private ClientIp() {
    }

    public static String of(Context ctx) {
        String real = ctx.header("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return ctx.ip();
    }
}
