package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.NativeColumns;
import cc.orthrus.riven.worldgen.RivenBiomeTable;
import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.SeededNoise;
import cc.orthrus.riven.worldgen.TerrainBlock;

import com.google.gson.JsonObject;

import io.github.opencubicchunks.cubicchunks.api.world.ICube;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.World;
import net.minecraft.world.WorldEntitySpawner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A chunk column's first animals (riven-population.json animals), Riven's population. The plan is a
 * pure function of seed, definitions and the chunk; the cube holding a member's feet spawns it where
 * that cube and the cubes above and below it carry it. Population writes no block, so its result
 * does not depend on the order cubes populate in.
 */
final class FirstAnimals {
    private static final Rules RULES = Rules.load("riven-population.json", 1);
    static final String FINGERPRINT = RULES.fingerprint;

    /** The Forge data key holding an animal's origin: chunk x, chunk z, group and member. */
    static final String ORIGIN = "riven:origin";

    private final long seed;
    private final double groupChance;
    private final int maxGroups, step;
    private final ClimateBiomeProvider biomes;

    FirstAnimals(long worldSeed, ClimateBiomeProvider biomes) {
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        JsonObject animals = j.getAsJsonObject("animals");
        groupChance = Rules.number(animals, "groupChance", 0, 1);
        maxGroups = (int) Rules.number(animals, "maxGroups", 0, 16);
        step = (int) Rules.number(animals, "step", 1, 8);
        this.biomes = biomes;
    }

    /** One planned animal: its species, feet, facing, origin and its own seed. */
    static final class Animal {
        final RivenBiomeTable.Spawn kind;
        final int x, y, z, group;
        final float yaw;
        final long seed;
        final String origin;

        Animal(
                RivenBiomeTable.Spawn kind,
                int x,
                int y,
                int z,
                int group,
                float yaw,
                long seed,
                String origin) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.group = group;
            this.yaw = yaw;
            this.seed = seed;
            this.origin = origin;
        }
    }

    /**
     * Since the server started, on the server thread: cubes populated, planned members whose feet
     * stood in them, those that fit, those the world took and those it refused.
     */
    static final long[] COUNTS = new long[5];

    /** Members a group may have: group sizes above it are cut to it. */
    static final int MEMBERS = 8;

    /**
     * The animals planned for a chunk column, in group and member order, or only those whose feet
     * stand in the cube rising from {@code bottom} (any cube for Integer.MIN_VALUE); members over
     * water are left out. Each group's draws come first and do not depend on the biome, so the
     * chunk's column is built only once a member's feet fall in the cube asked for (one-column
     * surface answers settle that), and every member's draws are fixed.
     */
    List<Animal> plan(int chunkX, int chunkZ, int bottom) {
        List<Animal> out = new ArrayList<>();
        long west = chunkX * 16L, north = chunkZ * 16L;
        if (!ClimateBiomeProvider.inWorld(west, north)
                || !ClimateBiomeProvider.inWorld(west + 15, north + 15)) return out;
        Random random = new Random(SeededNoise.cell(seed, chunkX, chunkZ));
        List<int[]> groups = new ArrayList<>();
        List<long[]> members = new ArrayList<>();
        boolean inCube = bottom == Integer.MIN_VALUE;
        for (int group = 0; group < maxGroups && random.nextDouble() < groupChance; group++) {
            int kind = random.nextInt(1 << 30), size = random.nextInt(1 << 30);
            int lx = random.nextInt(16), lz = random.nextInt(16);
            groups.add(new int[] {kind, size});
            for (int member = 0; member < MEMBERS; member++) {
                lx = Math.max(0, Math.min(15, lx + random.nextInt(step) - random.nextInt(step)));
                lz = Math.max(0, Math.min(15, lz + random.nextInt(step) - random.nextInt(step)));
                // The first block above the column's highest non-air run: the member's feet.
                int feet = biomes.maps.columns.surface((int) west + lx, (int) north + lz);
                members.add(
                        new long[] {
                            lx,
                            lz,
                            feet,
                            Float.floatToIntBits(random.nextFloat() * 360),
                            random.nextLong()
                        });
                inCube |= feet >= bottom && feet < bottom + 16;
            }
        }
        if (!inCube) return out;
        NativeColumns.Column column = biomes.maps.columns.column(chunkX, chunkZ);
        List<RivenBiomeTable.Spawn> list =
                ((RivenBiomeTypes.RivenBiome)
                                biomes.biome(column, (8 << 4) | 8, (int) west + 8, (int) north + 8))
                        .definition()
                        .spawns("creature");
        int total = 0;
        for (RivenBiomeTable.Spawn s : list) total += s.weight;
        if (total == 0) return out;
        for (int group = 0; group < groups.size(); group++) {
            RivenBiomeTable.Spawn kind = list.get(list.size() - 1);
            for (int at = groups.get(group)[0] % total, i = 0; i < list.size(); i++)
                if ((at -= list.get(i).weight) < 0) {
                    kind = list.get(i);
                    break;
                }
            int size =
                    Math.min(
                            MEMBERS,
                            kind.minimum
                                    + groups.get(group)[1] % (kind.maximum - kind.minimum + 1));
            for (int member = 0; member < size; member++) {
                long[] m = members.get(group * MEMBERS + member);
                int x = (int) west + (int) m[0], z = (int) north + (int) m[1];
                int feet = (int) m[2];
                if (bottom != Integer.MIN_VALUE && (feet < bottom || feet >= bottom + 16)) continue;
                // A member over water stays out: the ground below its feet is the water's top.
                if (column.block((int) (m[1] << 4 | m[0]), feet - 1) == TerrainBlock.WATER)
                    continue;
                out.add(
                        new Animal(
                                kind,
                                x,
                                feet,
                                z,
                                group,
                                Float.intBitsToFloat((int) m[3]),
                                m[4],
                                chunkX + "," + chunkZ + "," + group + "," + member));
            }
        }
        return out;
    }

    /** Whether the world carries an animal with its feet at {@code feet}. */
    static boolean fits(World world, BlockPos feet) {
        return WorldEntitySpawner.canCreatureTypeSpawnAtLocation(
                        EntityLiving.SpawnPlacementType.ON_GROUND, world, feet)
                && !world.getBlockState(feet).getMaterial().blocksMovement()
                && !world.getBlockState(feet.up()).getMaterial().blocksMovement();
    }

    /** Spawns the planned animals whose feet stand in the cube that fit there. */
    void populate(World world, ICube cube) {
        Map<Integer, IEntityLivingData> herds = new HashMap<>();
        COUNTS[0]++;
        for (Animal animal : plan(cube.getX(), cube.getZ(), cube.getY() * 16)) {
            COUNTS[1]++;
            BlockPos feet = new BlockPos(animal.x, animal.y, animal.z);
            if (!fits(world, feet)) continue;
            COUNTS[2]++;
            EntityLiving entity;
            try {
                entity =
                        RivenBiomeTypes.mob(animal.kind.mob)
                                .getConstructor(World.class)
                                .newInstance(world);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create " + animal.kind.mob, e);
            }
            entity.setLocationAndAngles(animal.x + .5, animal.y, animal.z + .5, animal.yaw, 0f);
            // Every random choice the animal makes when it first spawns comes from its own draw:
            // its own generator and, for that call, the world's (sheep colour reads the world's).
            long resume = world.rand.nextLong();
            world.rand.setSeed(animal.seed);
            entity.getRNG().setSeed(SeededNoise.mix(animal.seed));
            herds.put(
                    animal.group,
                    entity.onInitialSpawn(
                            new DifficultyInstance(world.getDifficulty(), 0, 0, 0),
                            herds.get(animal.group)));
            world.rand.setSeed(resume);
            entity.getEntityData().setString(ORIGIN, animal.origin);
            COUNTS[world.spawnEntity(entity) ? 3 : 4]++;
        }
    }
}
