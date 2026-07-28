package com.osuserverlist.bjar.handlers.api.admin;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.osuserverlist.bjar.App;
import com.osuserverlist.bjar.handlers.api.oauth.ApiAuth;
import com.osuserverlist.bjar.models.api.ApiDto;
import com.osuserverlist.bjar.models.database.UserEntity;
import com.osuserverlist.bjar.modules.api.OAuthToken;
import com.osuserverlist.bjar.modules.main.Application.BuildInfo;
import com.osuserverlist.bjar.modules.main.WebEngine.Host;
import com.osuserverlist.bjar.modules.main.WebEngine.HttpMethod;
import com.osuserverlist.bjar.modules.main.WebEngine.Path;
import com.osuserverlist.bjar.repos.UserRepository;

import io.ebean.DB;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * GET /api/v1/admin/system - what the process is doing right now.
 *
 * <p>Everything here comes from the JVM's own management beans and from counters the server
 * already keeps, so the endpoint costs a few microseconds and needs no metrics stack behind
 * it. That is the whole point: a small server should be able to answer "is it the host or is
 * it me" without first deploying Prometheus.</p>
 *
 * <p>There is no history in this response, only the present moment. Anything that wants a
 * graph should poll this and keep its own samples, which is what the panel does.</p>
 */
@Host({"api.", "server", ""})
@Path("/api/v1/admin/system")
@HttpMethod("GET")
public final class SystemHandler implements Handler {

    /** Sentinel for a figure this JVM refuses to report, so callers can hide the row. */
    private static final double UNAVAILABLE = -1.0;

    @Override
    @OpenApi(
        summary = "Server load and process statistics",
        description = "Memory, threads, garbage collection, processor use, uptime and account counters. "
                + "Requires the admin scope and the DEVELOPER privilege.",
        tags = { "Administration" },
        headers = {
            @OpenApiParam(
                name = "Authorization",
                description = "Bearer access token. May be omitted when the bjar_access cookie is sent."
            )
        },
        responses = {
            @OpenApiResponse(
                status = "200",
                content = { @OpenApiContent(from = ApiDto.SystemStatsResponse.class) },
                description = "A single sample taken when the request arrived"
            ),
            @OpenApiResponse(
                status = "401",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "Missing, expired or revoked access token"
            ),
            @OpenApiResponse(
                status = "403",
                content = { @OpenApiContent(from = ApiDto.ErrorResponse.class) },
                description = "The token lacks the admin scope, or the account lacks the DEVELOPER privilege"
            )
        },
        path = "/api/v1/admin/system"
    )
    public void handle(@NotNull Context ctx) {
        OAuthToken session = ApiAuth.require(ctx);
        if (session == null || !ApiAuth.requireDeveloper(ctx, session)) {
            return;
        }

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

        Runtime jvm = Runtime.getRuntime();

        long gcCount = 0;
        long gcTime = 0;

        // Collectors report -1 when they do not keep a figure, which must not be
        // summed into the total or the total becomes nonsense.
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = collector.getCollectionCount();
            long time = collector.getCollectionTime();

            if (count > 0) {
                gcCount += count;
            }

            if (time > 0) {
                gcTime += time;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();

        body.put("status", "success");
        body.put("version", BuildInfo.VERSION);
        body.put("java_version", BuildInfo.JAVA_VERSION);
        body.put("os", os.getName() + " " + os.getVersion() + " (" + os.getArch() + ")");

        body.put("uptime_ms", runtime.getUptime());
        body.put("sampled_at", Instant.now().toString());

        body.put("heap_used", memory.getHeapMemoryUsage().getUsed());
        body.put("heap_committed", memory.getHeapMemoryUsage().getCommitted());
        body.put("heap_max", memory.getHeapMemoryUsage().getMax());
        body.put("non_heap_used", memory.getNonHeapMemoryUsage().getUsed());
        body.put("memory_free", jvm.freeMemory());
        body.put("memory_total", jvm.totalMemory());

        body.put("threads", threads.getThreadCount());
        body.put("threads_peak", threads.getPeakThreadCount());

        body.put("cpu_cores", os.getAvailableProcessors());
        body.put("cpu_process", processCpu(os));
        body.put("cpu_system", systemCpu(os));
        body.put("load_average", os.getSystemLoadAverage());

        body.put("gc_count", gcCount);
        body.put("gc_time_ms", gcTime);

        body.put("online_players", App.server.playerManager.getOnlineCount());
        body.put("multiplayer_matches", App.server.matchManager.getAll().size());
        body.put("chat_channels", App.server.channelManager.getAll().size());

        body.put("registered_players", UserRepository.count());
        body.put("restricted_players", restrictedCount());
        body.put("silenced_players", silencedCount());

        // A snapshot is stale the moment it is taken, so no cache may keep it.
        ctx.header("Cache-Control", "private, no-store");
        ctx.json(body);
    }

    /**
     * Processor use by this process, as a fraction between 0 and 1.
     *
     * <p>The figure lives on a Sun specific interface. Rather than depend on it at compile time,
     * it is read reflectively and reported as unavailable on any JVM that does not offer it.
     * A missing number is not an error; pretending it is zero would be.</p>
     */
    private static double processCpu(OperatingSystemMXBean os) {
        return probe(os, "getProcessCpuLoad");
    }

    /** Processor use across the whole machine, as a fraction between 0 and 1. */
    private static double systemCpu(OperatingSystemMXBean os) {
        return probe(os, "getCpuLoad");
    }

    private static double probe(OperatingSystemMXBean os, String method) {
        try {
            Object value = os.getClass().getMethod(method).invoke(os);

            if (value instanceof Double reading && reading >= 0.0) {
                return reading;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Older or non-HotSpot JVMs simply do not have it.
        }

        return UNAVAILABLE;
    }

    /** Accounts without the UNRESTRICTED bit, which is how a restriction is stored. */
    private static long restrictedCount() {
        try {
            return DB.find(UserEntity.class).where().raw("priv & 1 = 0").findCount();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** Accounts whose silence has not yet expired. */
    private static long silencedCount() {
        try {
            long now = Instant.now().getEpochSecond();

            return DB.find(UserEntity.class).where().gt("silenceEnd", now).findCount();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
