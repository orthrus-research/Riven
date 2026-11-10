package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Synthetic reporting checks only: no maps, terrain, stores, executors or sampling workload. */
public final class RegionalCacheProbeReportingCheck {
    private static final int KEYS = 64, MAX_SUMMARY_BYTES = 32768;

    private RegionalCacheProbeReportingCheck() {}

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = RegionalCacheProbe.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static int counter(String name) throws ReflectiveOperationException {
        return field(name).getInt(null);
    }

    private static JsonObject cacheJson(RegionalCacheProbe.Cache cache, int resident, int overflow)
            throws Exception {
        Method method =
                RegionalCacheProbe.class.getDeclaredMethod(
                        "cacheJson", RegionalCacheProbe.Cache.class, int.class, int.class);
        method.setAccessible(true);
        try {
            return (JsonObject) method.invoke(null, cache, resident, overflow);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            if (wrapped.getCause() instanceof Error failure) throw failure;
            throw wrapped;
        }
    }

    private static RegionalCacheProbe.Cache cache(String name, int sets, int ways) {
        return new RegionalCacheProbe.Cache(
                name,
                sets == 0 ? "cell-memo" : "set-assoc",
                new AtomicReferenceArray<>(sets == 0 ? 2 : sets * ways),
                sets,
                ways);
    }

    /**
     * Stable fixture for a separate frozen-baseline snapshot runner. This method uses only the
     * unchanged Cache fields/constructor, cacheJson signature and storeMode field. All actual
     * counter rows are populated; the serializer decides the default phase names and order.
     */
    public static String defaultSnapshot() throws Exception {
        Field mode = field("storeMode");
        boolean previous = mode.getBoolean(null);
        try {
            mode.setBoolean(null, false);
            JsonArray result = new JsonArray();
            for (int variant = 0; variant < 2; variant++) {
                RegionalCacheProbe.Cache cache =
                        cache("default-" + variant, variant == 0 ? 0 : 2, variant == 0 ? 0 : 3);
                cache.instrumented = variant == 0;
                cache.retained = 4096L + variant;
                cache.built.put(7L, new AtomicInteger(2));
                cache.built.put(11L, new AtomicInteger(1));
                for (int p = 0; p < cache.counts.length; p++) {
                    for (int c = 0; c < cache.counts[p].length; c++)
                        cache.counts[p][c].add((p + 1) * 100L + c);
                    cache.longest[p].set(1000L + p);
                    cache.filled[p] = p + 1;
                    cache.replaced[p] = p + 2;
                }
                result.add(cacheJson(cache, variant + 1, variant));
            }
            return result.toString();
        } finally {
            mode.setBoolean(null, previous);
        }
    }

    private static void flight(RegionalCacheProbe.Cache cache, long key) {
        Object flight = new Object();
        cache.started(key, flight);
        cache.waited(flight);
        // Synthetic offsets make disabled duration accounting fail instead of looking like zero.
        cache.starts.put(flight, cache.starts.get(flight) - 1000);
        for (long[] waiter : cache.waiters.get(flight)) waiter[1] -= 1000;
        cache.ended(key, flight);
    }

    private static int stress(int total) throws Exception {
        String[] passes = (String[]) field("PASSES").get(null);
        String[] kinds = (String[]) field("KINDS").get(null);
        int builds = counter("BUILDS"), repeats = counter("REBUILDS");
        int nanos = counter("BUILD_NANOS"), waits = counter("WAITS");
        int waitNanos = counter("WAIT_NANOS");
        RegionalCacheProbe.Cache networks = cache("drainage", 0, 0);
        RegionalCacheProbe.Cache plans = cache("plans", 0, 0);
        networks.coldKeys = ConcurrentHashMap.newKeySet();
        networks.timed = plans.timed = false;
        int[] perPass = new int[passes.length];
        for (int p = 0; p < passes.length; p++) {
            field("pass").setInt(null, p);
            int count = total / passes.length + (p < total % passes.length ? 1 : 0);
            require(count >= KEYS, "Stress fixture must visit every key in every phase");
            perPass[p] = count;
            for (int i = 0; i < count; i++) flight(networks, i % KEYS);
            flight(plans, 0);
            require(networks.count(p, builds) == count, "Lost memo flights");
            require(
                    networks.count(p, repeats) == count - (p == 0 ? KEYS : 0),
                    "Lost repeated flights");
            long totalWaits = 0, totalWaitNanos = 0, duration = networks.count(p, nanos);
            require(
                    duration >= count * 1000L
                            && networks.longest[p].get() >= 1000
                            && networks.longest[p].get() <= duration,
                    "Invalid retained flight durations");
            for (int k = 0; k < kinds.length; k++) {
                totalWaits += networks.count(p, waits + k);
                totalWaitNanos += networks.count(p, waitNanos + k);
                require(networks.count(p, waitNanos + k) >= 0, "Negative wait duration");
            }
            require(totalWaits == count, "Lost waits when tracing was disabled");
            require(
                    totalWaitNanos >= count * 1000L,
                    "Lost wait durations when tracing was disabled");
        }
        require(
                networks.built.size() == KEYS && networks.coldKeys.size() == KEYS,
                "Fixed unique key set changed");
        for (long key = 0; key < KEYS; key++)
            require(networks.coldKeys.contains(key), "Cold key was lost");
        require(
                networks.timeline.isEmpty() && plans.timeline.isEmpty(),
                "Retained per-flight rows");
        require(
                networks.starts.isEmpty() && networks.waiters.isEmpty(),
                "Retained completed flights");
        JsonObject detail = RegionalCacheProbe.flightDetails(plans, networks);
        require(
                detail.get("omittedDrainageMemoFlights").getAsLong() == total
                        && detail.get("omittedHydroMemoFlights").getAsLong() == passes.length,
                "Summary omitted incomplete totals");
        require(
                detail.get("detailMode").getAsString().equals("summary")
                        && detail.get("perFlightDetails").getAsString().equals("not collected"),
                "Missing explicit detail policy");
        require(
                detail.get("networkBuildAttemptCountSource")
                        .getAsString()
                        .equals("RegionalStore misses, followed by Drainage.build"),
                "Memo flights were labeled network builds");
        JsonObject summary = new JsonObject();
        summary.add("regionalFlightDetails", detail);
        JsonObject networkJson = cacheJson(networks, 0, 0);
        summary.add("drainage", networkJson);
        summary.add("plans", cacheJson(plans, 0, 0));
        JsonArray cold = new JsonArray();
        networks.coldKeys.stream().sorted().forEach(cold::add);
        summary.add("coldKeys", cold);
        JsonArray continents = RegionalCacheProbe.continents(null, null, null, true);
        require(continents.size() == 0, "Store report did not skip per-flight geometry");
        summary.add("continents", continents);
        JsonObject reportedPasses = networkJson.getAsJsonObject("passes");
        require(reportedPasses.size() == passes.length, "Dropped a store-mode phase");
        for (int p = 0; p < passes.length; p++) {
            JsonObject row = reportedPasses.getAsJsonObject(passes[p]);
            require(
                    row.get("builds").getAsLong() == perPass[p]
                            && row.get("rebuilds").getAsLong() == perPass[p] - (p == 0 ? KEYS : 0),
                    "Serialization lost flight counters");
            require(
                    row.get("buildSeconds").getAsDouble() == networks.count(p, nanos) / 1e9
                            && row.get("longestBuildMs").getAsDouble()
                                    == networks.longest[p].get() / 1e6,
                    "Serialization lost flight durations");
            for (int k = 0; k < kinds.length; k++)
                require(
                        row.get(kinds[k] + "Waits").getAsLong() == networks.count(p, waits + k)
                                && row.get(kinds[k] + "WaitSeconds").getAsDouble()
                                        == networks.count(p, waitNanos + k) / 1e9,
                        "Serialization lost wait counters");
        }
        int bytes = summary.toString().getBytes(StandardCharsets.UTF_8).length;
        require(bytes <= MAX_SUMMARY_BYTES, "Fixed-key summary grew with flight count");
        System.out.printf(
                "Synthetic memo flights=%d keys=%d timelineRows=0 summaryBytes=%d%n",
                total, KEYS, bytes);
        return bytes;
    }

    private static void defaultTrace() throws Exception {
        field("storeMode").setBoolean(null, false);
        field("pass").setInt(null, 0);
        RegionalCacheProbe.Cache cache = cache("default-trace", 0, 0);
        cache.timed = true;
        flight(cache, 3);
        flight(cache, 5);
        flight(cache, 3);
        require(cache.timeline.size() == 3, "Default raw traces changed");
        for (long[] event : cache.timeline)
            require(event.length == 3 && event[1] <= event[2], "Default trace layout changed");
        require(!defaultSnapshot().isEmpty(), "Default snapshot was empty");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("default-snapshot")) {
            System.out.println(defaultSnapshot());
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Use no args or default-snapshot");
        Field mode = field("storeMode"), pass = field("pass");
        boolean previousMode = mode.getBoolean(null);
        int previousPass = pass.getInt(null);
        try {
            mode.setBoolean(null, true);
            int small = stress(1000), large = stress(100000);
            require(large <= small + 4096, "Summary size scales with repeated flights");
            int lastPass = pass.getInt(null);
            defaultSnapshot();
            require(
                    mode.getBoolean(null) && pass.getInt(null) == lastPass,
                    "Default snapshot changed the probe globals");
            defaultTrace();
            System.out.println("Synthetic reporting counters, bounds and default traces passed");
        } finally {
            try {
                pass.setInt(null, previousPass);
            } finally {
                mode.setBoolean(null, previousMode);
            }
        }
    }
}
