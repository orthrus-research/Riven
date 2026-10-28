package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.RealmMap;
import cc.orthrus.riven.worldgen.SubsurfaceClimate;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Locale;

/**
 * The subsurface climate (riven-subsurface-climate.json) at a block of a Riven world, for player
 * effects and commands now and for machines later. Server side: clients never hold the seed.
 */
public final class RivenClimate {
    private RivenClimate() {}

    /** A located reading: the climate, and where the block lies in the descent. */
    public static final class Here {
        public final SubsurfaceClimate.Reading climate = new SubsurfaceClimate.Reading();

        /** The crust realm (skin, layered, basement, root) or the realm below the crust floor. */
        public String realm;

        /** The crust depth biome the block reports, or null below the crust floor. */
        public String depthBiome;
    }

    /** The last sampled column, reused while a caller stays in it (one per thread). */
    private static final class Column {
        int x, z;
        Object maps;
        final TerrainMap.Sample sample = new TerrainMap.Sample();
    }

    private static final ThreadLocal<Column> LAST = ThreadLocal.withInitial(Column::new);

    /**
     * The climate at pos, or null where pos is not underground in a Riven cubic world: above the
     * crust's surface band (where cubes report the column's own biome), or outside the world.
     */
    public static Here at(World world, BlockPos pos) {
        if (!(world.getBiomeProvider() instanceof ClimateBiomeProvider)) return null;
        RealmMap realms = RivenWorldType.realms(world);
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (realms == null || !ClimateBiomeProvider.inWorld(x, z)) return null;
        ClimateBiomeProvider provider = (ClimateBiomeProvider) world.getBiomeProvider();
        Column column = LAST.get();
        // Keyed by the world's maps too, so a second world in one session never reads the first.
        if (column.maps != provider.maps || column.x != x || column.z != z) {
            provider.maps.terrain.sample(x, z, column.sample);
            column.x = x;
            column.z = z;
            column.maps = provider.maps;
        }
        TerrainMap.Sample s = column.sample;
        Here here = new Here();
        if (y < TerrainHeightReference.DEFAULT.minimumY) {
            double[] tops = new double[RealmMap.realms().size()];
            realms.tops(x, z, tops);
            int realm = RealmMap.realm(y, tops);
            if (realm < 0) return null;
            SubsurfaceClimate.at(y, s, realm, here.climate);
            here.realm = RealmMap.realms().get(realm).id;
            return here;
        }
        if (s.blockY() - y < RealmMap.surfaceBand()) return null;
        SubsurfaceClimate.at(y, s, -1, here.climate);
        here.realm = RealmMap.crust(y, s.geology.formation(y), s).name().toLowerCase(Locale.ROOT);
        here.depthBiome = RealmMap.cell(y, s).biome;
        return here;
    }
}
