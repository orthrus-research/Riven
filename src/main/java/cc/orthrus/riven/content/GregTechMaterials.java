package cc.orthrus.riven.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import gregtech.api.GregTechAPI;
import gregtech.api.unification.material.Material;
import gregtech.api.unification.material.event.MaterialEvent;
import gregtech.api.unification.material.event.MaterialRegistryEvent;
import gregtech.api.unification.material.info.MaterialIconSet;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GTCEu glue, registered only when GTCEu is present: Riven's own GTCEu materials. Riven creates
 * the riven material registry and builds there each mineral whose material id is riven:
 * (riven-content.json ores.minerals, material), with dust and ore properties, so GTCEu makes its
 * ores, dusts and processing for them. GTCEu posts both events in its preInit, which can run before
 * Riven's, so Riven registers this class when it is constructed.
 */
public final class GregTechMaterials {
    private GregTechMaterials() {}

    @SubscribeEvent
    public static void createRegistry(MaterialRegistryEvent event) {
        GregTechAPI.materialManager.createRegistry("riven");
    }

    @SubscribeEvent
    public static void registerMaterials(MaterialEvent event) {
        for (Map.Entry<String, JsonElement> e :
                RivenContent.RULES
                        .json
                        .getAsJsonObject("ores")
                        .getAsJsonObject("minerals")
                        .entrySet()) {
            JsonObject m = e.getValue().getAsJsonObject();
            String id = m.get("gregtech").getAsString();
            if (!id.startsWith("riven:")) continue;
            JsonObject material = m.getAsJsonObject("material");
            List<Object> components = new ArrayList<>();
            for (Map.Entry<String, JsonElement> c :
                    material.getAsJsonObject("components").entrySet()) {
                Material element = GregTechAPI.materialManager.getMaterial(c.getKey());
                if (element == null)
                    throw new IllegalStateException(
                            "Unknown GTCEu material " + c.getKey() + " in " + id);
                components.add(element);
                components.add(c.getValue().getAsInt());
            }
            new Material.Builder(material.get("id").getAsInt(), new ResourceLocation(id))
                    .dust()
                    .ore()
                    .color(Integer.parseInt(m.get("color").getAsString().substring(1), 16))
                    .iconSet(
                            m.get("shine").getAsString().equals("shiny")
                                    ? MaterialIconSet.SHINY
                                    : MaterialIconSet.DULL)
                    .components(components.toArray())
                    .build();
        }
    }
}
