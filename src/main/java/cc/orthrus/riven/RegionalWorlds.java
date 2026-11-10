package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.RegionalIdentity;
import cc.orthrus.riven.worldgen.RegionalStore;
import cc.orthrus.riven.worldgen.WorldScale;

import io.github.opencubicchunks.cubicchunks.api.worldgen.WorldBuildPool;
import io.github.opencubicchunks.cubicchunks.api.worldgen.WorldDerivedDataDirectory;

import net.minecraft.init.Biomes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

/**
 * Host lifecycle adapter. Factory setup and its selected abandonment stay inline on the server
 * thread; terminal unload may also arrive from the JVM's shutdown hook.
 */
final class RegionalWorlds {
    private static final RegionalOwnership OWNERS = new RegionalOwnership();
    private static final Object LIFECYCLE = new Object();
    private static MinecraftServer activeServer;
    private static Thread activeThread;

    private RegionalWorlds() {}

    static void start(MinecraftServer server) {
        factoryThread(server);
        synchronized (LIFECYCLE) {
            OWNERS.start(server);
            activeServer = server;
            activeThread = Thread.currentThread();
        }
    }

    static void stopped() {
        List<RegionalStore> detached;
        synchronized (LIFECYCLE) {
            MinecraftServer server = activeServer;
            // This event has no server argument. A late old thread cannot close a newer epoch.
            if (server == null || activeThread != Thread.currentThread()) return;
            activeServer = null;
            activeThread = null;
            detached = OWNERS.stop(server);
        }
        RegionalCleanup.closeAll(detached, RegionalStore::close);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void unloaded(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (!world.isRemote && world instanceof WorldServer)
            RegionalCleanup.closeAll(OWNERS.unload(world), RegionalStore::close);
    }

    static BiomeProvider biomes(World world) {
        // Remote clients consume authoritative chunk biome arrays, never a guessed seed.
        if (world.isRemote) return new BiomeProviderSingle(Biomes.PLAINS);
        if (!(world instanceof WorldServer serverWorld))
            return new ClimateBiomeProvider(
                    world.getSeed(),
                    WorldScale.parse(world.getWorldInfo().getGeneratorOptions()),
                    null,
                    null);

        MinecraftServer server = serverWorld.getMinecraftServer();
        factoryThread(server);
        RegionalOwnership.Token token;
        synchronized (LIFECYCLE) {
            if (activeServer != server)
                throw new IllegalStateException("Riven server lifecycle is not active");
            token = OWNERS.claim(server, world);
        }
        boolean requestReturned = false;
        RegionalStore unowned = null;
        try {
            ForkJoinPool pool = WorldBuildPool.request(world);
            requestReturned = true;
            long seed = world.getSeed();
            WorldScale scale = WorldScale.parse(world.getWorldInfo().getGeneratorOptions());
            Path directory = WorldDerivedDataDirectory.request(world);
            RegionalStore store = null;
            if (directory != null) {
                RegionalStore candidate =
                        new RegionalStore(directory, RegionalIdentity.capture(seed, scale));
                unowned = candidate;
                RegionalOwnership.Attachment attachment = OWNERS.attach(token, candidate);
                unowned = attachment.unowned();
                if (!attachment.accepted())
                    throw new IllegalStateException(
                            "Riven world closed during regional store setup");
                store = candidate;
            }
            ClimateBiomeProvider provider = new ClimateBiomeProvider(seed, scale, pool, store);
            if (!OWNERS.complete(token))
                throw new IllegalStateException(
                        "Riven world closed during biome provider construction");
            return provider;
        } catch (RuntimeException | Error failure) {
            RegionalOwnership.FailureCleanup cleanup = OWNERS.fail(token, requestReturned);
            RegionalCleanup.failed(
                    failure,
                    unowned,
                    cleanup,
                    RegionalStore::close,
                    () -> {
                        factoryThread(server);
                        WorldBuildPool.abandon(world);
                    });
            throw failure;
        }
    }

    private static void factoryThread(MinecraftServer server) {
        if (server == null || !server.isCallingFromMinecraftThread())
            throw new IllegalStateException(
                    "Riven world construction must run on the server thread");
    }
}
