package cc.orthrus.riven.worldgen;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/** The complete, frozen geometry inputs shared by one world's derived regional entries. */
public final class RegionalIdentity {
    private final byte[] manifest;
    final String namespace;

    private RegionalIdentity(long seed, WorldScale world, List<Rules> definitions) {
        RegionalData.Writer out = new RegionalData.Writer();
        out.integer(TerrainMap.VERSION);
        out.longValue(seed);
        out.longValue(Double.doubleToRawLongBits(world.layout));
        out.integer(definitions.size());
        for (Rules definition : definitions) {
            out.bytes(definition.resource.getBytes(StandardCharsets.UTF_8));
            out.raw(HexFormat.of().parseHex(definition.fingerprint));
        }
        manifest = out.finish();
        namespace = Rules.sha256(manifest);
    }

    /**
     * Eagerly hashes the whole packaged catalogue, including rules not yet used by a lazy map.
     * Geometry does not read the world's resolved block-role mappings or biome colours.
     */
    public static RegionalIdentity capture(long seed, WorldScale world) {
        return new RegionalIdentity(seed, world, Definitions.loadAll());
    }

    void write(RegionalData.Writer out) {
        out.bytes(manifest);
    }

    void verify(RegionalData.Reader in) throws RegionalData.Malformed {
        in.expect(manifest);
    }
}
