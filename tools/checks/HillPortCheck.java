package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.hills.Hills;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Controlled earlier-query certificate protocol; no production occurrence or continuous proof. */
public final class HillPortCheck {
    private static final long SEED = 8675309;
    private static final double PARENT = 128;
    private static final List<String> ROWS = new ArrayList<>();

    private enum Mode {
        CLEAR,
        INTENT_MARINE,
        WRONG_INTENT,
        SUPPORT_MARINE,
        SUPPORT_SHORE,
        SUPPORT_WATER,
        INVALID_PARENT,
        HEADROOM
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static final class Probe
            implements IntentSampler, MajorFeatureParent, OwnerEdgeWaterClearance {
        final TerrainHeightReference heights;
        final double reach;
        final Mode mode;
        int intents, parents, waters;
        double anchorX, anchorZ, lastX, lastZ;

        Probe(TerrainHeightReference heights, double reach, Mode mode) {
            this.heights = heights;
            this.reach = reach;
            this.mode = mode;
        }

        void clearCounts() {
            intents = parents = waters = 0;
        }

        @Override
        public void sample(double x, double z, TerrainMap.BaseSample out) {
            require(parents == 0 && waters == 0, "Broad intent must precede certification");
            if (intents == 0) {
                anchorX = x;
                anchorZ = z;
            } else {
                require(intents <= 4, "Unexpected extra broad-intent query");
                double expectedX = anchorX + (intents == 1 ? reach : intents == 2 ? -reach : 0);
                double expectedZ = anchorZ + (intents == 3 ? reach : intents == 4 ? -reach : 0);
                require(
                        bits(x).equals(bits(expectedX)) && bits(z).equals(bits(expectedZ)),
                        "Cardinal adjacency order/coordinates changed");
            }
            out.baseHeight = PARENT;
            out.broadDx = 1;
            out.broadDz = 0;
            out.ocean.marine = mode == Mode.INTENT_MARINE;
            out.land.low = 0;
            out.land.middle = mode == Mode.WRONG_INTENT || intents > 0 ? 0 : 1;
            out.land.high = mode == Mode.WRONG_INTENT || intents == 0 ? 0 : 1;
            intents++;
        }

        @Override
        public void sample(double x, double z, TerrainMap.Sample out) {
            require(intents == 5 && parents == waters, "Parent/owner-edge query order changed");
            if (parents == 0) {
                require(
                        bits(x).equals(bits(anchorX)) && bits(z).equals(bits(anchorZ)),
                        "Certificate must start at the original fractional anchor");
            } else {
                require(
                        x == Math.floor(x) && z == Math.floor(z),
                        "Support certificate queries must remain integer columns");
            }
            parents++;
            lastX = x;
            lastZ = z;
            out.height = mode == Mode.INVALID_PARENT ? Double.NaN : PARENT;
            out.minimumY = heights.lowestSurface;
            out.maximumY = mode == Mode.HEADROOM ? PARENT : heights.highestSurface;
            out.ocean.marine = mode == Mode.SUPPORT_MARINE;
            out.shore.kind = mode == Mode.SUPPORT_SHORE ? Shore.Kind.BEACH : Shore.Kind.INLAND;
            out.shore.landWeight = 1;
            out.shore.distance = Double.POSITIVE_INFINITY;
            out.water.clear();
        }

        @Override
        public double surfaceClearance(double x, double z) {
            require(
                    parents == waters + 1
                            && bits(x).equals(bits(lastX))
                            && bits(z).equals(bits(lastZ)),
                    "Owner-edge clearance must follow the same parent column");
            waters++;
            return mode == Mode.SUPPORT_WATER ? 0 : 1000000;
        }
    }

    private static HillTerrain create(Sites sites, Probe probe) {
        int intents = probe.intents, parents = probe.parents, waters = probe.waters;
        HillTerrain terrain = new HillTerrain(SEED, probe.heights, sites, probe, probe, probe);
        require(
                probe.intents == intents && probe.parents == parents && probe.waters == waters,
                "Constructor queried dependencies");
        return terrain;
    }

    private static String facts(HillTerrain.Result out) {
        return bits(out.height)
                + "|"
                + bits(out.delta)
                + "|"
                + bits(out.weight)
                + "|"
                + bits(out.anchorX)
                + "|"
                + bits(out.anchorZ)
                + "|"
                + out.candidates
                + "|"
                + out.contributors
                + "|"
                + out.certificateColumns;
    }

    private static void row(
            String role,
            int cx,
            int cz,
            String reason,
            Object family,
            long id,
            Probe probe,
            String detail) {
        ROWS.add(
                role
                        + ","
                        + cx
                        + ","
                        + cz
                        + ","
                        + reason
                        + ","
                        + family
                        + ","
                        + Long.toHexString(id)
                        + ","
                        + probe.intents
                        + ","
                        + probe.parents
                        + ","
                        + probe.waters
                        + ","
                        + detail);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Expected a new output CSV path");
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        Sites sites = new GenerationMaps(SEED).terrain.types.sites;
        Probe probe = new Probe(heights, sites.maximumRadius, Mode.CLEAR);
        HillTerrain terrain = create(sites, probe);
        ROWS.add("role,cellX,cellZ,reason,family,id,intentCalls,parentCalls,waterCalls,detail");
        int requests = 0, selectedX = 0, selectedZ = 0;
        HillTerrain.Decision selected = null;
        search:
        for (int cz = 0; cz <= 128 && requests < 8; cz++)
            for (int cx = 0; cx <= 128 && requests < 8; cx++) {
                long key = sites.key(cx, cz);
                if (!sites.admits(cx, cz, key, Sites.Lane.HILLS)) continue;
                requests++;
                probe.clearCounts();
                HillTerrain.Decision decision = terrain.plan(cx, cz);
                require(
                        probe.intents == 5
                                && probe.parents == decision.certificateColumns
                                && probe.waters == probe.parents,
                        "Controlled plan query counts changed");
                row(
                        "PLAN",
                        cx,
                        cz,
                        decision.reason,
                        decision.family,
                        0,
                        probe,
                        "synthetic-flat-128");
                if (decision.site == null) continue;
                require(
                        decision.reason.equals("ACCEPTED") && decision.certificateColumns > 1,
                        "Controlled site must have a complete parent certificate");
                require(
                        probe.anchorX != Math.floor(probe.anchorX)
                                || probe.anchorZ != Math.floor(probe.anchorZ),
                        "Controlled anchor must exercise earlier fractional sampling");
                selected = decision;
                selectedX = cx;
                selectedZ = cz;
                break search;
            }
        require(
                selected != null,
                "No controlled accepted site within eight admitted-owner requests");
        Hills.Site site = selected.site;
        int intents = probe.intents, parents = probe.parents, waters = probe.waters;
        require(
                terrain.plan(selectedX, selectedZ) == selected
                        && probe.intents == intents
                        && probe.parents == parents
                        && probe.waters == waters,
                "Immediate cached plan queried dependencies");
        HillTerrain.Result out = new HillTerrain.Result();
        for (boolean fractional : new boolean[] {false, true}) {
            double x = fractional ? site.x + .25 : Math.floor(site.x);
            double z = fractional ? site.z + .5 : Math.floor(site.z);
            terrain.apply(x, z, PARENT, out);
            double expectedWeight = site.weight(x, z);
            double expectedDelta = site.delta(x, z) / Math.max(1, expectedWeight);
            require(
                    out.candidates == 1
                            && out.contributors == 1
                            && out.id == site.key
                            && out.dominant == site.family
                            && bits(out.weight).equals(bits(expectedWeight))
                            && bits(out.delta).equals(bits(expectedDelta))
                            && bits(out.height).equals(bits(PARENT + out.delta))
                            && out.delta != 0,
                    "Controlled integer/fractional composition changed");
            require(
                    probe.intents == intents && probe.parents == parents && probe.waters == waters,
                    "Cached application queried earlier dependencies");
            row(
                    fractional ? "FRACTIONAL" : "INTEGER",
                    selectedX,
                    selectedZ,
                    out.reason,
                    out.dominant,
                    out.id,
                    probe,
                    bits(x) + "|" + bits(z) + "|" + facts(out));
        }
        Mode[] modes = {
            Mode.INTENT_MARINE,
            Mode.WRONG_INTENT,
            Mode.SUPPORT_MARINE,
            Mode.SUPPORT_SHORE,
            Mode.SUPPORT_WATER,
            Mode.INVALID_PARENT,
            Mode.HEADROOM
        };
        String[] reasons = {
            "MARINE",
            "WRONG_INTENT",
            "MARINE_SUPPORT",
            "PROTECTED_SHORE_SUPPORT",
            "RESERVED_WATER_SUPPORT",
            "INVALID_PARENT",
            "HEADROOM_SUPPORT"
        };
        for (int i = 0; i < modes.length; i++) {
            Probe deniedProbe = new Probe(heights, sites.maximumRadius, modes[i]);
            HillTerrain deniedTerrain = create(sites, deniedProbe);
            HillTerrain.Decision denied = deniedTerrain.plan(selectedX, selectedZ);
            int expectedIntents = modes[i] == Mode.INTENT_MARINE ? 1 : 5;
            int expectedParents = i < 2 ? 0 : 1;
            int expectedWaters = i < 4 ? 0 : 1;
            require(
                    denied.site == null
                            && denied.reason.equals(reasons[i])
                            && denied.certificateColumns == expectedParents
                            && deniedProbe.intents == expectedIntents
                            && deniedProbe.parents == expectedParents
                            && deniedProbe.waters == expectedWaters,
                    "Controlled rejection/query precedence changed: " + modes[i]);
            out.height = out.delta = out.weight = out.anchorX = out.anchorZ = 99;
            out.id = 99;
            out.dominant = site.family;
            out.candidates = out.contributors = out.certificateColumns = 99;
            out.reason = "STALE";
            deniedTerrain.apply(Math.floor(site.x), Math.floor(site.z), PARENT, out);
            require(
                    out.height == PARENT
                            && out.delta == 0
                            && out.weight == 0
                            && out.anchorX == 0
                            && out.anchorZ == 0
                            && out.id == 0
                            && out.dominant == null
                            && out.candidates == 1
                            && out.contributors == 0
                            && out.certificateColumns == expectedParents
                            && out.reason.equals(reasons[i])
                            && deniedProbe.intents == expectedIntents
                            && deniedProbe.parents == expectedParents
                            && deniedProbe.waters == expectedWaters,
                    "Rejected cached application failed to reset public fields: " + modes[i]);
            row(
                    "REJECT_" + modes[i],
                    selectedX,
                    selectedZ,
                    out.reason,
                    out.dominant,
                    out.id,
                    deniedProbe,
                    facts(out));
        }
        Files.write(
                Paths.get(args[0]),
                ROWS,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(
                "PASS synthetic Hill query/certificate protocol; admitted requests="
                        + requests
                        + "; accepted family="
                        + site.family
                        + "; no production occurrence qualification.");
    }
}
