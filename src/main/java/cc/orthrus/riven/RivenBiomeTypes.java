package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.RivenBiomeTable;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.EntityCaveSpider;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntityHusk;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.monster.EntityStray;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.monster.EntityZombieVillager;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeBeach;
import net.minecraft.world.biome.BiomeDesert;
import net.minecraft.world.biome.BiomeForest;
import net.minecraft.world.biome.BiomeHell;
import net.minecraft.world.biome.BiomeHills;
import net.minecraft.world.biome.BiomeJungle;
import net.minecraft.world.biome.BiomeMesa;
import net.minecraft.world.biome.BiomeOcean;
import net.minecraft.world.biome.BiomePlains;
import net.minecraft.world.biome.BiomeRiver;
import net.minecraft.world.biome.BiomeSavanna;
import net.minecraft.world.biome.BiomeSnow;
import net.minecraft.world.biome.BiomeStoneBeach;
import net.minecraft.world.biome.BiomeSwamp;
import net.minecraft.world.biome.BiomeTaiga;
import net.minecraft.world.biome.BiomeVoid;
import net.minecraft.world.chunk.ChunkPrimer;

import java.util.Random;

/**
 * The classes of Riven's biomes. Each extends the vanilla class of its kind, so shader packs and
 * other mods that classify biomes by class read the intended category; none decorates or reworks
 * surfaces (Riven places every block), and each takes colour overrides from the world's snapshot.
 */
final class RivenBiomeTypes {
    private RivenBiomeTypes() {}

    /** A Riven biome: its definition and the snapshot's colour overrides (-1 keeps the default). */
    interface RivenBiome {
        RivenBiomeTable.Definition definition();

        Colours colours();
    }

    /** Snapshot colour overrides, applied on server and client alike. */
    static final class Colours {
        volatile int grass = -1, foliage = -1;

        int grass(int fallback) {
            return grass >= 0 ? grass : fallback;
        }

        int foliage(int fallback) {
            return foliage >= 0 ? foliage : fallback;
        }
    }

    static Biome create(RivenBiomeTable.Definition d) {
        Biome.BiomeProperties p = new Biome.BiomeProperties(d.name);
        p.setTemperature(d.temperature).setRainfall(d.rainfall);
        if (d.precipitation.equals("none")) p.setRainDisabled();
        if (d.precipitation.equals("snow")) p.setSnowEnabled();
        switch (d.parent) {
            case "snow":
                return new Snow(d, p);
            case "taiga":
                return new Taiga(d, p);
            case "plains":
                return new Plains(d, p);
            case "forest":
                return new Forest(d, p);
            case "savanna":
                return new Savanna(d, p);
            case "jungle":
                return new Jungle(d, p);
            case "desert":
                return new Desert(d, p);
            case "hills":
                return new Hills(d, p);
            case "mesa":
                return new Mesa(d, p);
            case "beach":
                return new Beach(d, p);
            case "stone_beach":
                return new StoneBeach(d, p);
            case "swamp":
                return new Swamp(d, p);
            case "ocean":
                return new Ocean(d, p);
            case "river":
                return new River(d, p);
            case "void":
                return new Underground(d, p);
            case "hell":
                return new Hell(d, p);
            default:
                throw new IllegalStateException("No biome class for parent " + d.parent);
        }
    }

    static final class Snow extends BiomeSnow implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Snow(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(false, p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Taiga extends BiomeTaiga implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Taiga(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(BiomeTaiga.Type.NORMAL, p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Plains extends BiomePlains implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Plains(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(false, p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Forest extends BiomeForest implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Forest(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(BiomeForest.Type.NORMAL, p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Savanna extends BiomeSavanna implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Savanna(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Jungle extends BiomeJungle implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Jungle(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(false, p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Desert extends BiomeDesert implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Desert(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Hills extends BiomeHills implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Hills(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(BiomeHills.Type.NORMAL, p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Mesa extends BiomeMesa implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Mesa(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(false, false, p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Beach extends BiomeBeach implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Beach(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class StoneBeach extends BiomeStoneBeach implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        StoneBeach(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Swamp extends BiomeSwamp implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Swamp(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class Ocean extends BiomeOcean implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Ocean(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    static final class River extends BiomeRiver implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        River(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(p);
            this.d = d;
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}

        @Override
        public int getGrassColorAtPos(BlockPos p) {
            return c.grass(super.getGrassColorAtPos(p));
        }

        @Override
        public int getFoliageColorAtPos(BlockPos p) {
            return c.foliage(super.getFoliageColorAtPos(p));
        }
    }

    /**
     * An underground biome (a depth biome or a deep realm): the void's class, so shader packs give
     * it no surface category, spawning only the vanilla mobs its definition lists.
     */
    static final class Underground extends BiomeVoid implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Underground(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(p);
            this.d = d;
            // BiomeVoid starts with no spawns.
            for (RivenBiomeTable.Spawn s : d.monsters)
                spawnableMonsterList.add(
                        new SpawnListEntry(mob(s.mob), s.weight, s.minimum, s.maximum));
            for (RivenBiomeTable.Spawn s : d.ambient)
                spawnableCaveCreatureList.add(
                        new SpawnListEntry(mob(s.mob), s.weight, s.minimum, s.maximum));
        }

        private static Class<? extends EntityLiving> mob(String name) {
            switch (name) {
                case "zombie":
                    return EntityZombie.class;
                case "zombie_villager":
                    return EntityZombieVillager.class;
                case "husk":
                    return EntityHusk.class;
                case "skeleton":
                    return EntitySkeleton.class;
                case "stray":
                    return EntityStray.class;
                case "spider":
                    return EntitySpider.class;
                case "cave_spider":
                    return EntityCaveSpider.class;
                case "creeper":
                    return EntityCreeper.class;
                case "slime":
                    return EntitySlime.class;
                case "enderman":
                    return EntityEnderman.class;
                case "witch":
                    return EntityWitch.class;
                case "bat":
                    return EntityBat.class;
                default:
                    throw new IllegalStateException("No vanilla mob named " + name);
            }
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}
    }

    /** A Nether-themed realm biome: the Nether's class and type, none of its mobs. */
    static final class Hell extends BiomeHell implements RivenBiome {
        private final RivenBiomeTable.Definition d;
        private final Colours c = new Colours();

        Hell(RivenBiomeTable.Definition d, BiomeProperties p) {
            super(p);
            this.d = d;
            // BiomeHell lists ghasts, pigmen, magma cubes and endermen; Riven's realms start empty.
            spawnableMonsterList.clear();
            spawnableCreatureList.clear();
            spawnableWaterCreatureList.clear();
            spawnableCaveCreatureList.clear();
        }

        @Override
        public RivenBiomeTable.Definition definition() {
            return d;
        }

        @Override
        public Colours colours() {
            return c;
        }

        @Override
        public void decorate(World w, Random r, BlockPos p) {}

        @Override
        public void genTerrainBlocks(World w, Random r, ChunkPrimer c, int x, int z, double n) {}
    }
}
