package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Factory for packaged, fingerprinted generation definitions, listed in {@link Definitions}. No
 * runtime file access.
 */
public final class Rules {
    public final String resource;
    public final JsonObject json;
    public final String fingerprint;

    private Rules(String resource, JsonObject json, String fingerprint) {
        this.resource = resource;
        this.json = json;
        this.fingerprint = fingerprint;
    }

    /** The definition's revision field, or null where it has none. */
    public String revision() {
        return json.has("revision") ? json.get("revision").getAsString() : null;
    }

    @SuppressWarnings("deprecation") // Keep the Gson API shared with the Forge fallback.
    public static Rules load(String resource, int schema) {
        if (!Definitions.contains(resource))
            throw new IllegalStateException("Not a packaged definition (Definitions): " + resource);
        try (InputStream stream = Rules.class.getResourceAsStream("/" + resource)) {
            if (stream == null) throw new IllegalStateException("Missing " + resource);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
            byte[] data = bytes.toByteArray();
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
                hash.append(String.format("%02x", b & 255));
            JsonObject json =
                    new JsonParser()
                            .parse(new String(data, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (json.get("schema").getAsInt() != schema)
                throw new IllegalStateException("Schema: " + resource);
            return new Rules(resource, json, hash.toString());
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load " + resource, error);
        }
    }

    public static double number(JsonObject json, String key, double min, double max) {
        double value = json.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalStateException("Range: " + key);
        return value;
    }

    public static long seed(JsonObject json, String key) {
        return Long.parseUnsignedLong(json.get(key).getAsString().replace("0x", ""), 16);
    }

    public static double smooth(double value) {
        double t = Math.max(0, Math.min(1, value));
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    static boolean inCoordinateDomain(double x, double z) {
        return Double.isFinite(x)
                && Double.isFinite(z)
                && Math.abs(x) <= 30000000
                && Math.abs(z) <= 30000000;
    }

    static void coordinates(double x, double z) {
        if (!inCoordinateDomain(x, z))
            throw new IllegalArgumentException("Finite Minecraft coordinates required");
    }

    public static int choose(double[] weights, double unit) {
        double total = 0;
        for (double weight : weights) total += weight;
        double draw = unit * total;
        for (int i = 0; i < weights.length; i++) {
            draw -= weights[i];
            if (draw < 0) return i;
        }
        return -1;
    }
}
