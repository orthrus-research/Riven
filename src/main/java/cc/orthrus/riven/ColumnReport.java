package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.Basin;
import cc.orthrus.riven.worldgen.ClimateMap;
import cc.orthrus.riven.worldgen.CoastShape;
import cc.orthrus.riven.worldgen.Hydro;
import cc.orthrus.riven.worldgen.LakeShape;
import cc.orthrus.riven.worldgen.PlateMap;
import cc.orthrus.riven.worldgen.RiverShape;
import cc.orthrus.riven.worldgen.TerrainMap;

import io.github.opencubicchunks.cubicchunks.core.server.ICubicPlayerList;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import java.util.Locale;

/**
 * The default /riven report: every observation at one column, from climate and hydrology to the
 * generated blocks. Read-only.
 */
final class ColumnReport {
    private ColumnReport() {}

    static void send(MinecraftServer server, ICommandSender sender, World world, int x, int z) {
        ClimateBiomeProvider provider = (ClimateBiomeProvider) world.getBiomeProvider();
        sender.sendMessage(
                new TextComponentString(
                        "View distances: server horizontal="
                                + server.getPlayerList().getViewDistance()
                                + " vertical="
                                + ((ICubicPlayerList) server.getPlayerList())
                                        .getVerticalViewDistance()));
        ClimateMap.Sample climate = new ClimateMap.Sample();
        provider.maps.climate.sample(x, z, climate);
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Climate forcing %s/%s: background=%.3f correction=%+.3f factor=%+.3f landWeight=%.3f obstruction=%.2f contrast=%.2f blocks datumY=%d reads=%d/%d (structure/elevation)",
                                provider.maps.climateForcing.policy,
                                climate.forcing.status(),
                                climate.backgroundMoisture,
                                climate.moistureCorrection,
                                climate.forcing.moistureFactor(),
                                climate.forcing.landWeight(),
                                climate.forcing.obstructionAboveDatum(),
                                climate.forcing.meanContrastBlocks(),
                                provider.maps.climateStructure.datumY,
                                climate.forcing.structureQueries(),
                                climate.forcing.elevationQueries())));
        PlateMap.Sample plate = provider.maps.plates.sample(x, z);
        TerrainMap.Sample terrain = new TerrainMap.Sample();
        provider.maps.terrain.sample(x, z, terrain);
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Hydro body=%s id=%s waterY=%d groundwaterPotential=%.2f bankWeight=%.3f",
                                terrain.water.kind,
                                Long.toUnsignedString(terrain.water.id, 16),
                                terrain.waterY(),
                                terrain.groundwaterHead,
                                terrain.water.weight)));
        Hydro hydro = provider.maps.terrain.hydro;
        Hydro.Plan owner =
                hydro.plan(Math.floorDiv(x, hydro.cellBlocks), Math.floorDiv(z, hydro.cellBlocks));
        describeRivers(sender, owner.rivers, terrain.water.riverShape);
        if (terrain.water.coastShape != null) describeCoast(sender, terrain.water.coastShape);
        if (owner.present() && terrain.water.id == owner.id) describeLake(sender, owner.lakeShape);
        for (int i = 0; i < owner.basins(); i++) {
            Basin basin = owner.basin(i);
            if (terrain.water.id == basin.id) {
                describeLake(sender, basin.lakeShape);
                if (terrain.water.coastShape == null) describeCoast(sender, basin.coastShape);
            }
        }
        sender.sendMessage(
                new TextComponentString(
                        "Aquifer interval="
                                + terrain.water.aquiferMin
                                + ".."
                                + terrain.water.aquiferMax
                                + " id="
                                + Long.toUnsignedString(terrain.water.aquiferId, 16)));
        int waterY = provider.context.terrainHeights.waterTopY;
        BlockPos surface = new BlockPos(x, terrain.blockY(), z);
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Riven (%d, %d): %.1f C %s | moisture=%.3f %s | region=%s | biome=%s expected=%s | surface=%s | plate=%s | boundary=%.0f blocks %s",
                                x,
                                z,
                                climate.celsius,
                                climate.temperatureBand,
                                climate.moisture,
                                climate.moistureBand,
                                climate.region,
                                world.getBiome(surface).getRegistryName(),
                                provider.biome(terrain, x, z).getRegistryName()
                                        + " look="
                                        + BiomeProvision.lookName(provider.look(terrain, x, z)),
                                world.getBlockState(surface).getBlock().getRegistryName(),
                                Long.toUnsignedString(plate.plateId, 16),
                                plate.boundaryDistance,
                                plate.boundary)));
        // Realms report their biome by height: the biome where the sender stands, not at the
        // surface.
        BlockPos here = sender.getPosition();
        sender.sendMessage(
                new TextComponentString(
                        "Here (Y "
                                + here.getY()
                                + "): biome="
                                + world.getBiome(here).getRegistryName()));
        RivenClimate.Here below = RivenClimate.at(world, here);
        if (below != null)
            sender.sendMessage(
                    new TextComponentString(
                            String.format(
                                    Locale.ROOT,
                                    "Climate here: %.1f C humidity=%.2f air=%.2f depth=%.0f%s%s | realm=%s%s | effects=%s",
                                    below.climate.celsius,
                                    below.climate.humidity,
                                    below.climate.air,
                                    below.climate.depth,
                                    below.climate.frozen ? " frozen" : "",
                                    below.climate.steam ? " steam" : "",
                                    below.realm,
                                    below.depthBiome == null
                                            ? ""
                                            : " depthBiome=" + below.depthBiome,
                                    PlayerEffects.matching(below))));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Ocean marine=%s coast=%s coordinate=%.2f landWeight=%.3f | bed=%d seaLevel=%d highestWater=%d | aboveBed=%s waterTop=%s aboveWater=%s skyAtWater=%d/%d/%d",
                                terrain.ocean.marine,
                                terrain.ocean.form,
                                terrain.ocean.coastCoordinate,
                                terrain.shore.landWeight,
                                terrain.blockY(),
                                world.getSeaLevel(),
                                provider.context.terrainHeights.waterTopY,
                                world.getBlockState(surface.up()),
                                world.getBlockState(new BlockPos(x, waterY, z)),
                                world.getBlockState(new BlockPos(x, waterY + 1, z)),
                                world.getLightFor(EnumSkyBlock.SKY, new BlockPos(x, waterY, z)),
                                world.getLightFor(EnumSkyBlock.SKY, new BlockPos(x, waterY - 1, z)),
                                world.getLightFor(
                                        EnumSkyBlock.SKY, new BlockPos(x, waterY - 2, z)))));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Continents=%s owner=%s islands=%s ownership=%s supplied=%s protectedScaffold=%s | size=%.2f axis=%.2f rough=%.2f interior=%.2f shelter=%.2f cape=%.2f",
                                terrain.ocean.family,
                                Long.toUnsignedString(terrain.ocean.owner, 16),
                                terrain.ocean.archipelago,
                                terrain.ocean.reason,
                                terrain.ocean.supplied,
                                terrain.ocean.protectedWater,
                                terrain.ocean.continentSize,
                                terrain.ocean.elongation,
                                terrain.ocean.roughness,
                                terrain.ocean.interior,
                                terrain.ocean.shelter,
                                terrain.ocean.cape)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Terrain family=%s character=%s weights=%.3f/%.3f/%.3f rugged=%.3f | edgeOpportunity=%.3f cliff=%.3f waterfallSite=%.3f (no water supply implied)",
                                terrain.land.level,
                                terrain.land.character,
                                terrain.land.low,
                                terrain.land.middle,
                                terrain.land.high,
                                terrain.land.rugged,
                                terrain.land.edge,
                                terrain.shore.cliff,
                                terrain.land.waterfallSite)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Shore=%s distanceEstimate=%.2f normal=%.3f/%.3f shelter=%.3f grade=%.3f | beach=%.3f plain=%.3f rock=%.3f marshOpportunity=%.3f | broadHeight=%.2f",
                                terrain.shore.kind,
                                terrain.shore.distance,
                                terrain.shore.normalX,
                                terrain.shore.normalZ,
                                terrain.shore.shelter,
                                terrain.shore.grade,
                                terrain.shore.beach,
                                terrain.shore.plain,
                                terrain.shore.rock,
                                terrain.shore.marsh,
                                terrain.shore.height)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Sediment=%s reason=%s id=%s | parent=%.2f top=%.2f weight=%.3f emergent=%s | candidates=%d boxColumns=%d active=%d masked=%d earlierProbes=%d islandColumns=%d",
                                terrain.sediment.family,
                                terrain.sediment.reason,
                                Long.toUnsignedString(terrain.sediment.id, 16),
                                terrain.sediment.parentHeight,
                                terrain.sediment.height,
                                terrain.sediment.weight,
                                terrain.emergentSediment,
                                terrain.sediment.candidates,
                                terrain.sediment.boxColumns,
                                terrain.sediment.activeColumns,
                                terrain.sediment.maskedColumns,
                                terrain.sediment.probeColumns,
                                terrain.sediment.islandColumns)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Rocky=%s reason=%s id=%s arch=%s | parent=%.2f top=%.2f weight=%.3f emergent=%s | candidates=%d boxColumns=%d bodyColumns=%d certificateVoxels=%d volumeCuts=%d",
                                terrain.rocky.family,
                                terrain.rocky.reason,
                                Long.toUnsignedString(terrain.rocky.id, 16),
                                terrain.rocky.arch,
                                terrain.rocky.parentHeight,
                                terrain.rocky.height,
                                terrain.rocky.weight,
                                terrain.emergentRock,
                                terrain.rocky.candidates,
                                terrain.rocky.boxColumns,
                                terrain.rocky.bodyColumns,
                                terrain.rocky.voxels,
                                terrain.rocky.cutCount)));
        if (terrain.ocean.marine) return;
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Lowlands=%s weights=%.3f/%.3f/%.3f/%.3f | applied=%+.3f weight=%.3f candidates=%d shoreClearance=%.2f waterClearance=%.2f",
                                terrain.lowland.reason,
                                terrain.lowland.weights[0],
                                terrain.lowland.weights[1],
                                terrain.lowland.weights[2],
                                terrain.lowland.weights[3],
                                terrain.lowland.delta,
                                terrain.lowland.weight,
                                terrain.lowland.candidateVisits,
                                terrain.lowland.shoreClearance,
                                terrain.lowland.waterClearance)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Terrain top=%d base=%.2f erosion=%+.2f | %s/%s blend=%.3f uplift=%.3f | above=%s below=%s",
                                terrain.blockY(),
                                terrain.baseHeight,
                                terrain.erosion.displacement,
                                terrain.firstLandform,
                                terrain.secondLandform,
                                terrain.blend,
                                terrain.uplift,
                                world.getBlockState(surface.up()).getBlock().getRegistryName(),
                                world.getBlockState(surface.down()).getBlock().getRegistryName())));
        if (!terrain.ocean.marine)
            sender.sendMessage(
                    new TextComponentString(
                            String.format(
                                    Locale.ROOT,
                                    "Region=%s | inland mean=%.2f amplitude=%.2f wide/fine=%.3f/%.3f",
                                    terrain.baseTerrain().region().dominant(),
                                    terrain.baseTerrain().meanHeight(),
                                    terrain.baseTerrain().amplitude(),
                                    terrain.baseTerrain().region().wideShare(),
                                    terrain.baseTerrain().region().fineShare())));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Formation=%s permutation=%s | shape=%+.2f roughening=%+.2f smoothing=%+.2f strength=%.3f | bounded change=%+.2f",
                                terrain.passes.dominant,
                                Long.toUnsignedString(terrain.passes.permutation, 16),
                                terrain.passes.formation + terrain.passes.formationFine,
                                terrain.passes.roughening,
                                terrain.passes.smoothing,
                                terrain.passes.smoothingStrength,
                                terrain.passes.appliedDisplacement)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Volcanic=%s reason=%s id=%s anchor=(%.1f,%.1f) | delta=%+.3f weight=%.3f candidates=%d contributors=%d retainedCertificateColumns=%d stencilColumns=%d | minPotential=%.3f maxColumnSlope=%.3f",
                                terrain.volcanic.dominant,
                                terrain.volcanic.reason,
                                Long.toUnsignedString(terrain.volcanic.id, 16),
                                terrain.volcanic.anchorX,
                                terrain.volcanic.anchorZ,
                                terrain.volcanic.delta,
                                terrain.volcanic.weight,
                                terrain.volcanic.candidates,
                                terrain.volcanic.contributors,
                                terrain.volcanic.certificateColumns,
                                terrain.volcanic.stencilColumns,
                                terrain.volcanic.minPotential,
                                terrain.volcanic.maxColumnSlope)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "ColdRegions=%s reason=%s bits=%d id=%s anchor=(%.1f,%.1f) | delta=%+.3f weight=%.3f candidates=%d contributors=%d retainedCertificateColumns=%d diagnosticColumns=%d | cold=%.3f earlierRelief256=%.3f maxBroadSlope=%.3f",
                                terrain.cold.dominant,
                                terrain.cold.reason,
                                terrain.cold.rejectionBits,
                                Long.toUnsignedString(terrain.cold.id, 16),
                                terrain.cold.anchorX,
                                terrain.cold.anchorZ,
                                terrain.cold.delta,
                                terrain.cold.weight,
                                terrain.cold.candidates,
                                terrain.cold.contributors,
                                terrain.cold.certificateColumns,
                                terrain.cold.diagnosticColumns,
                                terrain.cold.cold,
                                terrain.cold.relief,
                                terrain.cold.slope)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Plateaus=%s reason=%s id=%s anchor=(%.1f,%.1f) | candidates=%d contributors=%d delta=%+.3f retainedCertificateColumns=%d",
                                terrain.plateaus.dominant,
                                terrain.plateaus.reason,
                                Long.toUnsignedString(terrain.plateaus.id, 16),
                                terrain.plateaus.anchorX,
                                terrain.plateaus.anchorZ,
                                terrain.plateaus.candidates,
                                terrain.plateaus.contributors,
                                terrain.plateaus.delta,
                                terrain.plateaus.certificateColumns)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Mountains=%s reason=%s id=%s anchor=(%.1f,%.1f) | candidates=%d contributors=%d delta=%+.3f ridge=%.3f pass=%.3f",
                                terrain.mountains.dominant,
                                terrain.mountains.reason,
                                Long.toUnsignedString(terrain.mountains.id, 16),
                                terrain.mountains.anchorX,
                                terrain.mountains.anchorZ,
                                terrain.mountains.candidates,
                                terrain.mountains.contributors,
                                terrain.mountains.delta,
                                terrain.mountains.ridge,
                                terrain.mountains.pass)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Hills=%s reason=%s id=%s anchor=(%.1f,%.1f) | candidates=%d contributors=%d delta=%+.3f retainedCertificateColumns=%d",
                                terrain.hills.dominant,
                                terrain.hills.reason,
                                Long.toUnsignedString(terrain.hills.id, 16),
                                terrain.hills.anchorX,
                                terrain.hills.anchorZ,
                                terrain.hills.candidates,
                                terrain.hills.contributors,
                                terrain.hills.delta,
                                terrain.hills.certificateColumns)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Landmark=%s variant=%d id=%s anchor=(%.1f,%.1f) climate=%s | overlaps=%d raw=%+.2f applied=%+.2f",
                                terrain.landmarks.dominant,
                                terrain.landmarks.variant,
                                Long.toUnsignedString(terrain.landmarks.featureId, 16),
                                terrain.landmarks.anchorX,
                                terrain.landmarks.anchorZ,
                                terrain.landmarks.anchorRegion,
                                terrain.landmarks.overlaps,
                                terrain.landmarks.rawDisplacement,
                                terrain.landmarks.appliedDisplacement)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Carver=%s id=%s anchor=(%.1f,%.1f) climate=%s | overlaps=%d rawCut=%.2f appliedCut=%.2f",
                                terrain.carvers.dominant,
                                Long.toUnsignedString(terrain.carvers.featureId, 16),
                                terrain.carvers.anchorX,
                                terrain.carvers.anchorZ,
                                terrain.carvers.anchorRegion,
                                terrain.carvers.overlaps,
                                terrain.carvers.rawCut,
                                terrain.carvers.appliedCut)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "GeneralCuts=%s reason=%s id=%s anchor=(%.1f,%.1f) | cut=%.3f weight=%.3f candidates=%d contributors=%d relief256=%.3f saddleFit=%.3f | retainedSupport=%d protected=%d corridor=%d probes=%d",
                                terrain.cuts.dominant,
                                terrain.cuts.reason,
                                Long.toUnsignedString(terrain.cuts.id, 16),
                                terrain.cuts.anchorX,
                                terrain.cuts.anchorZ,
                                terrain.cuts.cut,
                                terrain.cuts.weight,
                                terrain.cuts.candidates,
                                terrain.cuts.contributors,
                                terrain.cuts.relief,
                                terrain.cuts.saddleFit,
                                terrain.cuts.supportColumns,
                                terrain.cuts.protectedColumns,
                                terrain.cuts.corridorColumns,
                                terrain.cuts.probeColumns)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Drylands=%s reason=%s shares=%.3f/%.3f/%.3f/%.3f | delta=%+.3f sand=%.3f pavement=%.3f yardangCut=%.3f candidates=%d contributors=%d | fit=%.3f aridity=%.3f shoreClearance=%.2f waterClearance=%.2f",
                                terrain.drylands.dominant,
                                terrain.drylands.reason,
                                terrain.drylands.weights[0],
                                terrain.drylands.weights[1],
                                terrain.drylands.weights[2],
                                terrain.drylands.weights[3],
                                terrain.drylands.delta,
                                terrain.drylands.sandCover,
                                terrain.drylands.pavement,
                                terrain.drylands.yardangCut,
                                terrain.drylands.candidates,
                                terrain.drylands.contributors,
                                terrain.drylands.fit,
                                terrain.drylands.aridity,
                                terrain.drylands.shoreClearance,
                                terrain.drylands.waterClearance)));
        sender.sendMessage(
                new TextComponentString(
                        "Dryland family admission="
                                + java.util.Arrays.toString(terrain.drylands.reasons)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Cover=%s stoneFromY=%d soilDepth=%d | expectedSurface=%s substrate=%s",
                                provider.maps.surfaces.reason(terrain),
                                provider.maps.surfaces.stoneAboveY,
                                provider.maps.surfaces.soilDepth,
                                TerrainColumn.blockAt(
                                                terrain.blockY(),
                                                terrain,
                                                provider.maps.surfaces.bareStone(terrain),
                                                provider.maps.surfaces,
                                                provider.context.terrainHeights)
                                        .getBlock()
                                        .getRegistryName(),
                                world.getBlockState(surface.down(provider.maps.surfaces.soilDepth))
                                        .getBlock()
                                        .getRegistryName())));
    }

    private static void describeCoast(ICommandSender sender, CoastShape.Decision decision) {
        if (decision == null) return;
        CoastShape shape = decision.shape;
        if (shape == null) {
            sender.sendMessage(
                    new TextComponentString(
                            "CoastShape="
                                    + decision.family
                                    + "/"
                                    + decision.reason
                                    + " certificateColumns="
                                    + decision.probes
                                    + " receiverColumns="
                                    + decision.receiverColumns));
            return;
        }
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "CoastShape=%s id=%s length=%.2f support=%.2f foundation=%.2f | shelter=%.3f sedimentOpportunity=%.3f receiverHalfWidth=%.2f certificateColumns=%d receiverColumns=%d",
                                decision.family,
                                Long.toUnsignedString(shape.geometry.id, 16),
                                shape.length,
                                shape.geometry.supportRadius,
                                shape.foundation,
                                shape.shelter,
                                shape.sediment,
                                shape.receiverHalfWidth,
                                decision.probes,
                                decision.receiverColumns)));
    }

    private static void describeRivers(
            ICommandSender sender, RiverShape rivers, RiverShape.Decision local) {
        if (rivers == null) return;
        sender.sendMessage(
                new TextComponentString(
                        "RiverShapes accepted="
                                + rivers.accepted
                                + "/"
                                + rivers.candidates()
                                + " ownerCertificateColumns="
                                + rivers.certificateColumns));
        if (local != null) {
            RiverShape.Reach shape = local.shape;
            sender.sendMessage(
                    new TextComponentString(
                            String.format(
                                    Locale.ROOT,
                                    "RiverShape=%s edge=%d route=%s width=%.3f depth=%.1f length=%.2f heads=%.3f..%.3f branchLength=%.2f | segments=%d certificateColumns=%d",
                                    local.family,
                                    local.edge,
                                    Long.toUnsignedString(shape.geometry.routeId, 16),
                                    shape.width,
                                    shape.depth,
                                    shape.length,
                                    shape.startWater,
                                    shape.endWater,
                                    shape.branchLength,
                                    shape.geometry.segments,
                                    local.probes)));
        } else {
            StringBuilder reasons = new StringBuilder("River selections:");
            for (int i = 0; i < rivers.candidates(); i++) {
                RiverShape.Decision d = rivers.decision(i);
                reasons.append(' ')
                        .append(d.edge)
                        .append(':')
                        .append(d.family)
                        .append('/')
                        .append(d.reason);
            }
            sender.sendMessage(new TextComponentString(reasons.toString()));
        }
    }

    private static void describeLake(ICommandSender sender, LakeShape.Decision decision) {
        if (decision == null) return;
        LakeShape shape = decision.shape;
        if (shape == null) {
            sender.sendMessage(
                    new TextComponentString(
                            "LakeShape="
                                    + decision.reason
                                    + " certificateColumns="
                                    + decision.probes));
            return;
        }
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "LakeShape=%s profile=%s width=%.2f depth=%.0f islandRadius=%.3f outletWidth=%.2f | support=%.2f certificateColumns=%d",
                                shape.geometry.family,
                                shape.profile,
                                shape.geometry.t.width(),
                                shape.geometry.t.depth(),
                                shape.geometry.t.islandRadius(),
                                shape.geometry.t.outletWidth(),
                                shape.geometry.supportRadius,
                                decision.probes)));
    }
}
