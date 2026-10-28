package cc.orthrus.riven.content;

import cc.orthrus.riven.worldgen.Rules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.util.NonNullList;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.oredict.OreDictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Riven's own blocks and items from riven-content.json. Each definition names a kind (its Java
 * implementation) and the values that kind reads; tools/content/build_content.py writes the
 * matching assets. Content never changes generated terrain.
 */
@Mod.EventBusSubscriber(modid = "riven")
public final class RivenContent {
    private static final Rules RULES = Rules.load("riven-content.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final List<Block> BLOCKS = new ArrayList<>();

    private RivenContent() {}

    /** Sets what grows a Riven sapling into its tree. */
    public static void treeGrower(TreeGrower grower) {
        BlockRivenSapling.grower = grower;
    }

    /** Every Riven block, in definition order (after registration). */
    public static List<Block> blocks() {
        return Collections.unmodifiableList(BLOCKS);
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        for (JsonElement e : RULES.json.getAsJsonArray("blocks")) {
            JsonObject j = e.getAsJsonObject();
            String id = j.get("id").getAsString(), kind = j.get("kind").getAsString();
            Block block;
            if (kind.equals("stone")) block = new Block(Material.ROCK);
            else if (kind.equals("pointed_dripstone"))
                block = new BlockPointedDripstone(j.getAsJsonObject("behaviour"));
            else throw new IllegalStateException("Unknown content kind " + kind + " for " + id);
            block.setRegistryName("riven", id);
            block.setTranslationKey("riven." + id);
            block.setHardness(j.get("hardness").getAsFloat());
            block.setResistance(j.get("resistance").getAsFloat());
            block.setHarvestLevel(j.get("tool").getAsString(), j.get("harvestLevel").getAsInt());
            block.setCreativeTab(tab(j.get("creativeTab").getAsString()));
            BLOCKS.add(block);
            event.getRegistry().register(block);
        }
        // Ores: one block per mineral over the host stones.
        JsonObject ores = RULES.json.getAsJsonObject("ores");
        int hosts = ores.getAsJsonArray("hosts").size();
        if (hosts > 16) throw new IllegalStateException("At most 16 ore hosts");
        if (net.minecraftforge.fml.common.Loader.isModLoaded("gregtech"))
            BlockRivenOre.gregtechOre = cc.orthrus.riven.content.GregTechOreDrops::ore;
        for (java.util.Map.Entry<String, JsonElement> e :
                ores.getAsJsonObject("minerals").entrySet()) {
            JsonObject m = e.getValue().getAsJsonObject();
            String gregtech =
                    m.get("gregtech").isJsonNull() ? null : m.get("gregtech").getAsString();
            BlockRivenOre ore =
                    new BlockRivenOre(
                            e.getKey(),
                            Integer.parseInt(m.get("color").getAsString().substring(1), 16),
                            gregtech,
                            hosts);
            ore.setRegistryName("riven", "ore_" + e.getKey());
            ore.setTranslationKey("riven.ore_" + e.getKey());
            ore.setHardness(ores.get("hardness").getAsFloat());
            ore.setResistance(ores.get("resistance").getAsFloat());
            ore.setHarvestLevel("pickaxe", ores.get("harvestLevel").getAsInt());
            ore.setCreativeTab(CreativeTabs.BUILDING_BLOCKS);
            BLOCKS.add(ore);
            event.getRegistry().register(ore);
        }
        // Unstable stone: one block per host rock.
        JsonObject unstable = RULES.json.getAsJsonObject("unstable");
        for (java.util.Map.Entry<String, JsonElement> e :
                unstable.getAsJsonObject("hosts").entrySet()) {
            BlockUnstableStone block =
                    new BlockUnstableStone(
                            unstable.getAsJsonObject("behaviour"), e.getValue().getAsJsonObject());
            block.setRegistryName("riven", "unstable_" + e.getKey());
            block.setTranslationKey("riven.unstable_" + e.getKey());
            block.setHardness(unstable.get("hardness").getAsFloat());
            block.setResistance(unstable.get("resistance").getAsFloat());
            block.setHarvestLevel("pickaxe", unstable.get("harvestLevel").getAsInt());
            block.setCreativeTab(CreativeTabs.BUILDING_BLOCKS);
            BLOCKS.add(block);
            event.getRegistry().register(block);
        }
        // Woods: each species' log, leaves, sapling, litter and planks.
        JsonObject woods = RULES.json.getAsJsonObject("woods");
        JsonObject log = woods.getAsJsonObject("log"),
                leaves = woods.getAsJsonObject("leaves"),
                litter = woods.getAsJsonObject("litter"),
                planks = woods.getAsJsonObject("planks");
        // Forge's older Gson has no JsonObject.keySet().
        for (java.util.Map.Entry<String, JsonElement> e :
                woods.getAsJsonObject("species").entrySet()) {
            String id = e.getKey();
            BlockRivenLog l = new BlockRivenLog(id);
            l.setHardness(log.get("hardness").getAsFloat());
            l.setResistance(log.get("resistance").getAsFloat());
            l.setHarvestLevel("axe", 0);
            register(event, l, "log_" + id, CreativeTabs.BUILDING_BLOCKS);
            BlockRivenLeaves f = new BlockRivenLeaves(id, leaves.get("saplingChance").getAsInt());
            f.setHardness(leaves.get("hardness").getAsFloat());
            f.setResistance(leaves.get("resistance").getAsFloat());
            f.setLightOpacity(1);
            register(event, f, "leaves_" + id, CreativeTabs.DECORATIONS);
            boolean palm =
                    e.getValue().getAsJsonObject().get("architecture").getAsString().equals("palm");
            BlockRivenSapling s = new BlockRivenSapling(id, palm);
            register(event, s, "sapling_" + id, CreativeTabs.DECORATIONS);
            f.sapling = s;
            BlockRivenDecal t = new BlockRivenDecal(id, BlockRivenDecal.ANY, true);
            t.setHardness(litter.get("hardness").getAsFloat());
            t.setResistance(litter.get("resistance").getAsFloat());
            register(event, t, "litter_" + id, CreativeTabs.DECORATIONS);
            BlockRivenPlanks p = new BlockRivenPlanks(id);
            p.setHardness(planks.get("hardness").getAsFloat());
            p.setResistance(planks.get("resistance").getAsFloat());
            p.setHarvestLevel("axe", 0);
            register(event, p, "planks_" + id, CreativeTabs.BUILDING_BLOCKS);
        }
        // Realm rocks: one block per rock, its main or alternate face by position.
        JsonObject rocks = RULES.json.getAsJsonObject("rocks");
        for (java.util.Map.Entry<String, JsonElement> e :
                rocks.getAsJsonObject("subjects").entrySet()) {
            JsonObject r = e.getValue().getAsJsonObject();
            Block block = new Block(Material.ROCK);
            block.setHardness(rocks.get("hardness").getAsFloat());
            block.setResistance(rocks.get("resistance").getAsFloat());
            block.setHarvestLevel("pickaxe", rocks.get("harvestLevel").getAsInt());
            if (r.has("light")) block.setLightLevel(r.get("light").getAsInt() / 15f);
            register(event, block, e.getKey(), CreativeTabs.BUILDING_BLOCKS);
        }
        // Ground decorations: one block per subject, its four forms by position.
        for (java.util.Map.Entry<String, JsonElement> e :
                RULES.json.getAsJsonObject("decorations").getAsJsonObject("subjects").entrySet()) {
            JsonObject d = e.getValue().getAsJsonObject();
            java.util.List<String> kinds = new ArrayList<>();
            for (JsonElement g : d.getAsJsonArray("ground")) kinds.add(g.getAsString());
            int ground = Grounds.mask(kinds);
            boolean replaceable = d.get("replaceable").getAsBoolean();
            String kind = d.get("kind").getAsString();
            Block block;
            if (kind.equals("plant")) block = new BlockRivenPlant(ground, replaceable);
            else if (kind.equals("tall")) block = new BlockRivenTallPlant(ground, replaceable);
            else if (kind.equals("decal"))
                block =
                        new BlockRivenDecal(
                                e.getKey(),
                                ground,
                                !d.has("flammable") || d.get("flammable").getAsBoolean());
            else throw new IllegalStateException("Unknown decoration kind " + kind);
            block.setHardness(0);
            register(event, block, kind + "_" + e.getKey(), CreativeTabs.DECORATIONS);
        }
    }

    private static void register(
            RegistryEvent.Register<Block> event, Block block, String id, CreativeTabs tab) {
        block.setRegistryName("riven", id);
        block.setTranslationKey("riven." + id);
        block.setCreativeTab(tab);
        BLOCKS.add(block);
        event.getRegistry().register(block);
    }

    /**
     * Wood recipes: a log crafts into four of its species' planks and smelts to charcoal. Ore
     * dictionary names and flammability follow vanilla wood, so recipes that take any planks or
     * logs take Riven's.
     */
    @SubscribeEvent
    public static void registerRecipes(RegistryEvent.Register<IRecipe> event) {
        java.util.Map<String, Block> planks = new java.util.HashMap<>();
        for (Block block : BLOCKS)
            if (block instanceof BlockRivenPlanks)
                planks.put(((BlockRivenPlanks) block).species, block);
        for (Block block : BLOCKS) {
            if (block instanceof BlockRivenLog) {
                String id = ((BlockRivenLog) block).species;
                Item item = Item.getItemFromBlock(block);
                event.getRegistry()
                        .register(
                                new ShapelessRecipes(
                                                "riven:planks",
                                                new ItemStack(planks.get(id), 4),
                                                NonNullList.from(
                                                        Ingredient.EMPTY,
                                                        Ingredient.fromItem(item)))
                                        .setRegistryName("riven", "planks_from_log_" + id));
                FurnaceRecipes.instance()
                        .addSmeltingRecipeForBlock(block, new ItemStack(Items.COAL, 1, 1), .15f);
                OreDictionary.registerOre("logWood", item);
                Blocks.FIRE.setFireInfo(block, 5, 5);
            } else if (block instanceof BlockRivenPlanks) {
                OreDictionary.registerOre("plankWood", block);
                Blocks.FIRE.setFireInfo(block, 5, 20);
            } else if (block instanceof BlockRivenLeaves) {
                OreDictionary.registerOre("treeLeaves", block);
                Blocks.FIRE.setFireInfo(block, 30, 60);
            } else if (block instanceof BlockRivenSapling) {
                OreDictionary.registerOre("treeSapling", block);
            } else if (block instanceof BlockRivenDecal) {
                if (((BlockRivenDecal) block).flammable) Blocks.FIRE.setFireInfo(block, 60, 100);
            } else if (block instanceof BlockRivenPlant || block instanceof BlockRivenTallPlant) {
                Blocks.FIRE.setFireInfo(block, 60, 100);
            }
        }
    }

    /** The index of a host stone in riven-content.json's ore hosts, or -1. */
    public static int hostIndex(String stone) {
        com.google.gson.JsonArray hosts =
                RULES.json.getAsJsonObject("ores").getAsJsonArray("hosts");
        for (int i = 0; i < hosts.size(); i++)
            if (hosts.get(i).getAsString().equals(stone)) return i;
        return -1;
    }

    /** The Riven ore block for a mineral, or null. */
    public static BlockRivenOre ore(String mineral) {
        for (Block b : BLOCKS)
            if (b instanceof BlockRivenOre && ((BlockRivenOre) b).mineral.equals(mineral))
                return (BlockRivenOre) b;
        return null;
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        for (Block block : BLOCKS) {
            ItemBlock item =
                    block instanceof BlockRivenOre
                            ? new ItemBlock(block) {
                                @Override
                                public int getMetadata(int damage) {
                                    return damage;
                                }
                            }
                            : new ItemBlock(block);
            if (block instanceof BlockRivenOre) item.setHasSubtypes(true);
            event.getRegistry().register(item.setRegistryName(block.getRegistryName()));
        }
    }

    private static CreativeTabs tab(String name) {
        switch (name) {
            case "building":
                return CreativeTabs.BUILDING_BLOCKS;
            case "decorations":
                return CreativeTabs.DECORATIONS;
            default:
                throw new IllegalStateException("Unknown creative tab " + name);
        }
    }
}
