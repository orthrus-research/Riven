package cc.orthrus.riven;

import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

import org.apache.logging.log4j.LogManager;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Optional native captures for an owned inspection launch; never changes world state. A single
 * capture waits for the first settle; a tour (riven.tour) captures once after every teleport, when
 * the player has held still for the settle time, so a launcher can step through viewpoints.
 *
 * <p>Where Quadrature draws distant terrain, a capture also waits past the hold until Quadrature
 * calls its picture settled (DistantHook.settledPasses: every distant tile that can arrive stored
 * and faded in, near terrain caught up, the cube source settled) for riven.settlePasses world passes
 * in a row (40; 0 waits for the hold alone). A view not settled riven.settleLimitTicks after the
 * hold (6000, five minutes) is not captured: the client logs "Riven view unsettled" with what it
 * waited on and quits, so the session fails at that view.
 */
@Mod.EventBusSubscriber(modid = "riven", value = Side.CLIENT)
public final class RivenClient {
    private static final boolean CAPTURE = Boolean.getBoolean("riven.capture");
    private static final boolean TOUR = Boolean.getBoolean("riven.tour");
    private static final int CAPTURE_TICKS =
            Math.max(100, Integer.getInteger("riven.captureTicks", 400));
    private static final int LIGHT_DUMP = Integer.getInteger("riven.lightDump", 0);
    private static final int SETTLE_PASSES = Integer.getInteger("riven.settlePasses", 40);
    private static final int SETTLE_LIMIT_TICKS =
            Math.max(1, Integer.getInteger("riven.settleLimitTicks", 6000));

    /** Quadrature's settled passes and what it waits on, or null where none is drawn. */
    private static MethodHandle settledPasses, unsettled;

    private static boolean distantLooked, unsettledSaid;
    private static boolean captured, due;
    private static int still, views, frames, settleFrames;
    private static long settleStart;
    private static double lastX = Double.NaN, lastY, lastZ, shotX = Double.NaN, shotY, shotZ;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!CAPTURE || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        double x = mc.player.posX, y = mc.player.posY, z = mc.player.posZ;
        boolean moved =
                Double.isNaN(lastX)
                        || Math.abs(x - lastX) + Math.abs(y - lastY) + Math.abs(z - lastZ) > .05;
        still = moved ? 0 : still + 1;
        // Frame rate is averaged from a quarter of the settle onward, after the view has loaded in.
        if (still == CAPTURE_TICKS / 4) {
            settleStart = System.nanoTime();
            settleFrames = frames;
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        if (TOUR) {
            // A new viewpoint is a position away from the last shot (sideways or straight down),
            // held still for the settle.
            boolean fresh =
                    Double.isNaN(shotX)
                            || Math.abs(x - shotX) + Math.abs(z - shotZ) > 8
                            || Math.abs(y - shotY) > 8;
            if (fresh && still >= CAPTURE_TICKS && settled(mc, still - CAPTURE_TICKS)) {
                shotX = x;
                shotY = y;
                shotZ = z;
                due = true;
            }
        } else if (!captured
                && mc.player.ticksExisted >= CAPTURE_TICKS
                && settled(mc, mc.player.ticksExisted - CAPTURE_TICKS)) {
            captured = true;
            due = true;
        }
    }

    /**
     * Whether the next view may be captured, the hold having passed {@code waited} ticks ago: at
     * once without Quadrature's distant terrain, otherwise once its picture has been settled for the
     * passes asked. Past the limit the view is given up and the client quits.
     */
    private static boolean settled(Minecraft mc, int waited) {
        if (SETTLE_PASSES <= 0 || unsettledSaid || !lookUpDistant()) return !unsettledSaid;
        long passes;
        String waitingOn;
        try {
            passes = (long) settledPasses.invokeExact();
            waitingOn = passes >= SETTLE_PASSES ? "" : (String) unsettled.invokeExact();
        } catch (Throwable failure) {
            throw new IllegalStateException(
                    "Quadrature's settled passes could not be read", failure);
        }
        if (passes >= SETTLE_PASSES) {
            LogManager.getLogger("Riven")
                    .info(
                            "Riven view settled: view {} after {} ticks past the hold ({} passes)",
                            String.format("%02d", views),
                            waited,
                            passes);
            return true;
        }
        if (waited >= SETTLE_LIMIT_TICKS) {
            unsettledSaid = true;
            LogManager.getLogger("Riven")
                    .error(
                            "Riven view unsettled: view {} not settled {} ticks past the hold: {}",
                            String.format("%02d", views),
                            waited,
                            waitingOn);
            mc.shutdown();
        }
        return false;
    }

    /** Finds Quadrature's distant terrain once; false where it is absent or not requested. */
    private static boolean lookUpDistant() {
        if (!distantLooked) {
            distantLooked = true;
            try {
                Class<?> hook = Class.forName("cc.orthrus.quadrature.distant.DistantHook");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                boolean requested =
                        (boolean)
                                lookup.findStatic(
                                                hook,
                                                "requested",
                                                MethodType.methodType(boolean.class))
                                        .invokeExact();
                if (requested) {
                    MethodHandle passes =
                            lookup.findStatic(
                                    hook, "settledPasses", MethodType.methodType(long.class));
                    unsettled =
                            lookup.findStatic(
                                    hook, "unsettled", MethodType.methodType(String.class));
                    settledPasses = passes;
                }
            } catch (ClassNotFoundException absent) {
                // No Quadrature: the hold alone.
            } catch (NoSuchMethodException | IllegalAccessException older) {
                LogManager.getLogger("Riven")
                        .warn(
                                "Quadrature draws distant terrain but says nothing of its settling;"
                                        + " views wait for the hold alone");
            } catch (Throwable failure) {
                throw new IllegalStateException(
                        "Quadrature's distant terrain could not be asked", failure);
            }
        }
        return settledPasses != null;
    }

    @SubscribeEvent
    public static void render(RenderWorldLastEvent event) {
        frames++;
        if (!due) return;
        due = false;
        Minecraft mc = Minecraft.getMinecraft();
        double settleSeconds = (System.nanoTime() - settleStart) / 1e9;
        LogManager.getLogger("Riven")
                .info(
                        "Inspection view distances: client horizontal={} vertical={}",
                        mc.gameSettings.renderDistanceChunks,
                        CubicChunksConfig.verticalCubeLoadDistance);
        // The F3 counters for this frame: sections, entities
        // rendered/total, the client's loaded cubes/columns, and heap in use.
        Runtime runtime = Runtime.getRuntime();
        LogManager.getLogger("Riven")
                .info(
                        "Riven view stats: fps={} avgFps={} {} | {} | {} | heap={}MB",
                        Minecraft.getDebugFPS(),
                        Math.round((frames - settleFrames) / settleSeconds),
                        mc.renderGlobal.getDebugInfoRenders(),
                        mc.renderGlobal.getDebugInfoEntities(),
                        mc.world.getProviderName(),
                        (runtime.totalMemory() - runtime.freeMemory()) >> 20);
        String name =
                (TOUR ? String.format("riven-view-%02d-", views++) : "riven-terrain-")
                        + System.currentTimeMillis()
                        + ".png";
        String result =
                ScreenShotHelper.saveScreenshot(
                                mc.gameDir,
                                name,
                                mc.displayWidth,
                                mc.displayHeight,
                                mc.getFramebuffer())
                        .getUnformattedText();
        LogManager.getLogger("Riven").info("Initial terrain inspection capture: {}", result);
        // Retained heap after the shot, so views compare without garbage-collection noise.
        System.gc();
        LogManager.getLogger("Riven")
                .info(
                        "Riven view heap after GC: {}MB",
                        (runtime.totalMemory() - runtime.freeMemory()) >> 20);
        if (TOUR) LogManager.getLogger("Riven").info("Riven view captured: {}", name);
        if (LIGHT_DUMP > 0) dumpLight(mc, name.replace(".png", "-light.txt"));
    }

    /**
     * The client's own sky and block light around the player at a capture (riven.lightDump=R: a box
     * R blocks out each way), to compare with the server's saved light:
     * "x y z sky block" for each position that is not an opaque cube, and "h x z height" for each
     * column's client height.
     */
    private static void dumpLight(Minecraft mc, String name) {
        BlockPos at = new BlockPos(mc.player);
        int r = LIGHT_DUMP;
        // Beside the screenshots, not among them: a tour files every new screenshot as a view.
        File out = new File(new File(mc.gameDir, "riven-light"), name);
        out.getParentFile().mkdirs();
        try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
            w.printf("# client light at %d %d %d, r=%d%n", at.getX(), at.getY(), at.getZ(), r);
            for (int x = at.getX() - r; x <= at.getX() + r; x++)
                for (int z = at.getZ() - r; z <= at.getZ() + r; z++) {
                    w.printf("h %d %d %d%n", x, z, mc.world.getHeight(x, z));
                    for (int y = at.getY() - r; y <= at.getY() + r; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (mc.world.getBlockState(p).isOpaqueCube()) continue;
                        w.printf(
                                "%d %d %d %d %d%n",
                                x,
                                y,
                                z,
                                mc.world.getLightFor(EnumSkyBlock.SKY, p),
                                mc.world.getLightFor(EnumSkyBlock.BLOCK, p));
                    }
                }
        } catch (IOException e) {
            LogManager.getLogger("Riven").warn("Light dump failed: {}", e.toString());
        }
        LogManager.getLogger("Riven").info("Riven light dump: {}", out);
    }
}
