package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.Rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Player effects from the subsurface climate: every interval, each survival or adventure player
 * underground in a Riven world takes every effect whose conditions hold at its feet. Riven ships
 * its effects (riven-player-effects.json) to config/riven/player-effects.json on first run and
 * reads that copy at server start and on /riven effects reload; a copy that fails to load leaves
 * the effects in force unchanged. Gameplay only; generated content never reads it.
 */
@SuppressWarnings("deprecation") // Keep the Gson API shared with the Forge fallback, as Rules does.
final class PlayerEffects {
    private PlayerEffects() {}

    private static final Logger LOG = LogManager.getLogger("riven");
    private static final String RESOURCE = "riven-player-effects.json";

    /** Whether the router runs (config/riven.cfg playerEffects). */
    static boolean enabled = true;

    /** One loaded set of effects, swapped whole on reload. */
    private static final class Config {
        final int interval;
        final List<Effect> effects;
        final String source;

        Config(JsonObject j, String source) {
            known(j, "schema", "revision", "about", "intervalTicks", "effects", "contract");
            if (!j.has("schema") || j.get("schema").getAsInt() != 1)
                throw new IllegalStateException("schema must be 1");
            interval = (int) Rules.number(j, "intervalTicks", 1, 1200);
            List<Effect> list = new ArrayList<>();
            for (JsonElement e : j.getAsJsonArray("effects"))
                list.add(new Effect(e.getAsJsonObject()));
            effects = Collections.unmodifiableList(list);
            this.source = source;
        }
    }

    /** The effects in force; null until first use, then the shipped defaults or the config copy. */
    private static volatile Config config;

    /** Built on first use, not at class load: preInit touches this class before potions settle. */
    private static Config current() {
        Config c = config;
        if (c == null)
            config = c = new Config(Rules.load(RESOURCE, 1).json, "Riven's shipped defaults");
        return c;
    }

    /** The config copy, once {@link #init} has run. */
    private static File file;

    /** A potion an effect gives. */
    private static final class Dose {
        final String id;
        final int amplifier;
        final Potion potion;

        Dose(String id, int amplifier) {
            this.id = id;
            this.amplifier = amplifier;
            potion = Potion.getPotionFromResourceLocation(id);
            if (potion == null) throw new IllegalStateException("no potion named " + id);
        }
    }

    /** One effect: its conditions and what it does. */
    static final class Effect {
        final String id;
        final double[] celsius, humidity, air;
        final Boolean frozen, steam;
        final Set<String> realms, depthBiomes;
        final List<Dose> unless = new ArrayList<>(), potions = new ArrayList<>();
        final double exhaustionPerSecond;

        Effect(JsonObject j) {
            known(j, "id", "when", "unless", "potions", "exhaustionPerSecond");
            id = j.get("id").getAsString();
            JsonObject when = j.has("when") ? j.getAsJsonObject("when") : new JsonObject();
            known(when, "celsius", "humidity", "air", "frozen", "steam", "realms", "depthBiomes");
            celsius = range(when, "celsius");
            humidity = range(when, "humidity");
            air = range(when, "air");
            frozen = when.has("frozen") ? when.get("frozen").getAsBoolean() : null;
            steam = when.has("steam") ? when.get("steam").getAsBoolean() : null;
            realms = names(when, "realms");
            depthBiomes = names(when, "depthBiomes");
            if (j.has("unless"))
                for (JsonElement e : j.getAsJsonArray("unless"))
                    unless.add(new Dose(e.getAsString(), 0));
            if (j.has("potions"))
                for (JsonElement e : j.getAsJsonArray("potions")) {
                    JsonObject p = e.getAsJsonObject();
                    known(p, "effect", "amplifier");
                    potions.add(
                            new Dose(
                                    p.get("effect").getAsString(),
                                    p.has("amplifier")
                                            ? (int) Rules.number(p, "amplifier", 0, 9)
                                            : 0));
                }
            exhaustionPerSecond =
                    j.has("exhaustionPerSecond")
                            ? Rules.number(j, "exhaustionPerSecond", 0, 10)
                            : 0;
        }

        boolean matches(RivenClimate.Here here) {
            return within(celsius, here.climate.celsius)
                    && within(humidity, here.climate.humidity)
                    && within(air, here.climate.air)
                    && (frozen == null || frozen == here.climate.frozen)
                    && (steam == null || steam == here.climate.steam)
                    && (realms == null || realms.contains(here.realm))
                    && (depthBiomes == null || depthBiomes.contains(here.depthBiome));
        }

        void apply(EntityPlayer player, int interval) {
            for (Dose d : unless) if (player.isPotionActive(d.potion)) return;
            // Ambient, without particles, held a little past the next check.
            for (Dose d : potions)
                player.addPotionEffect(
                        new PotionEffect(d.potion, interval + 30, d.amplifier, true, false));
            if (exhaustionPerSecond > 0)
                player.addExhaustion((float) (exhaustionPerSecond * interval / 20.0));
        }
    }

    /** A misspelt key would silently drop a condition, so every key must be known. */
    private static void known(JsonObject j, String... keys) {
        Set<String> allowed = new HashSet<>(Arrays.asList(keys));
        for (Map.Entry<String, JsonElement> e : j.entrySet())
            if (!allowed.contains(e.getKey()))
                throw new IllegalStateException("unknown key " + e.getKey());
    }

    private static double[] range(JsonObject j, String key) {
        if (!j.has(key)) return null;
        JsonArray a = j.getAsJsonArray(key);
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (!(lo <= hi)) throw new IllegalStateException(key + " must run from lowest to highest");
        return new double[] {lo, hi};
    }

    private static Set<String> names(JsonObject j, String key) {
        if (!j.has(key)) return null;
        Set<String> set = new HashSet<>();
        for (JsonElement e : j.getAsJsonArray(key)) set.add(e.getAsString());
        return set;
    }

    private static boolean within(double[] range, double v) {
        return range == null || (v >= range[0] && v <= range[1]);
    }

    /**
     * Ships the defaults to config/riven/player-effects.json on first run, then reads that copy.
     * Called at server start, once every mod's potions are registered.
     */
    static void init(File configDirectory) {
        file = new File(configDirectory, "riven" + File.separator + "player-effects.json");
        if (!file.isFile())
            try (InputStream in = PlayerEffects.class.getResourceAsStream("/" + RESOURCE)) {
                Files.createDirectories(file.getParentFile().toPath());
                Files.copy(in, file.toPath());
                LOG.info("Riven player effects: wrote the shipped defaults to {}", file);
            } catch (IOException e) {
                LOG.warn("Riven player effects: cannot write {} ({})", file, e.toString());
            }
        reload();
    }

    /**
     * Reads the config copy again; on any error keeps the effects in force. Logs the outcome and
     * returns it for the command's reply.
     */
    static String reload() {
        if (file == null || !file.isFile()) {
            String message = "Riven player effects: no config copy; " + summary();
            LOG.warn(message);
            return message;
        }
        try {
            JsonObject j =
                    new JsonParser()
                            .parse(
                                    new String(
                                            Files.readAllBytes(file.toPath()),
                                            StandardCharsets.UTF_8))
                            .getAsJsonObject();
            config = new Config(j, file.getPath());
            String message = "Riven player effects: " + summary();
            LOG.info(message);
            return message;
        } catch (IOException | RuntimeException e) {
            String message =
                    "Riven player effects: "
                            + file
                            + " is not valid ("
                            + (e.getMessage() != null ? e.getMessage() : e.toString())
                            + "); keeping "
                            + summary();
            LOG.error(message);
            return message;
        }
    }

    /** What is in force, for the log and /riven effects. */
    static String summary() {
        Config c = current();
        List<String> ids = new ArrayList<>();
        for (Effect e : c.effects) ids.add(e.id);
        return String.format(
                "%s, %d effects %s every %d ticks from %s",
                enabled ? "on" : "off (config/riven.cfg playerEffects)",
                ids.size(),
                ids,
                c.interval,
                c.source);
    }

    /** The effects whose conditions hold for a reading (for /riven). */
    static List<String> matching(RivenClimate.Here here) {
        List<String> ids = new ArrayList<>();
        for (Effect e : current().effects) if (e.matches(here)) ids.add(e.id);
        return ids;
    }

    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent event) {
        if (!enabled || event.phase != TickEvent.Phase.END || event.side != Side.SERVER) return;
        Config c = current();
        EntityPlayer player = event.player;
        if (player.ticksExisted % c.interval != 0 || player.isCreative() || player.isSpectator())
            return;
        RivenClimate.Here here = RivenClimate.at(player.world, new BlockPos(player));
        if (here == null) return;
        for (Effect e : c.effects) if (e.matches(here)) e.apply(player, c.interval);
    }
}
