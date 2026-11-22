package cc.orthrus.riven;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import org.apache.logging.log4j.LogManager;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sends each joining player the client-side part of the world's definitions: its fingerprint and
 * the riven: biome colours DH reads on the client. Generation stays on
 * the server; clients need no mapping files.
 */
final class RivenNetwork {
    private static SimpleNetworkWrapper channel;

    private RivenNetwork() {}

    static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("riven");
        channel.registerMessage(Handler.class, Definitions.class, 0, Side.CLIENT);
        MinecraftForge.EVENT_BUS.register(RivenNetwork.class);
    }

    @SubscribeEvent
    public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        Mappings.Snapshot snapshot = RivenWorldType.snapshot(DimensionManager.getWorld(0));
        if (snapshot == null) return;
        channel.sendTo(
                new Definitions(snapshot.fingerprint, snapshot.colours),
                (EntityPlayerMP) event.player);
    }

    @SubscribeEvent
    public static void left(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        // Back to every biome's own colours for the next server or world.
        FMLCommonHandler.instance()
                .getWorldThread(event.getHandler())
                .addScheduledTask(() -> RivenBiomes.applyColours(Collections.emptyMap()));
    }

    /** The world's fingerprint and biome colour overrides. */
    public static final class Definitions implements IMessage {
        String fingerprint = "";
        Map<String, int[]> colours = new TreeMap<>();

        public Definitions() {}

        Definitions(String fingerprint, Map<String, int[]> colours) {
            this.fingerprint = fingerprint;
            this.colours = new TreeMap<>(colours);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            fingerprint = ByteBufUtils.readUTF8String(buf);
            int n = buf.readInt();
            for (int i = 0; i < n; i++)
                colours.put(
                        ByteBufUtils.readUTF8String(buf), new int[] {buf.readInt(), buf.readInt()});
        }

        @Override
        public void toBytes(ByteBuf buf) {
            ByteBufUtils.writeUTF8String(buf, fingerprint);
            buf.writeInt(colours.size());
            for (Map.Entry<String, int[]> e : colours.entrySet()) {
                ByteBufUtils.writeUTF8String(buf, e.getKey());
                buf.writeInt(e.getValue()[0]);
                buf.writeInt(e.getValue()[1]);
            }
        }
    }

    public static final class Handler implements IMessageHandler<Definitions, IMessage> {
        @Override
        public IMessage onMessage(Definitions message, MessageContext ctx) {
            FMLCommonHandler.instance()
                    .getWorldThread(ctx.netHandler)
                    .addScheduledTask(
                            () -> {
                                RivenBiomes.applyColours(message.colours);
                                LogManager.getLogger("riven")
                                        .info(
                                                "Riven definitions {} received ({} biome colour overrides)",
                                                message.fingerprint,
                                                message.colours.size());
                            });
            return null;
        }
    }
}
