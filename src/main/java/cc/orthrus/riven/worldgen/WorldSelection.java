package cc.orthrus.riven.worldgen;

/** The measured choices needed to reconstruct a world's kept outline and climate draw. */
record WorldSelection(
        int landAttempt,
        double landShare,
        int climateAttempt,
        int climateDraws,
        int climateFewest) {
    static final String KIND = "world-selection";

    static final RegionalStore.Codec<WorldSelection> CODEC =
            new RegionalStore.Codec<>() {
                @Override
                public int version() {
                    return 1;
                }

                @Override
                public void write(RegionalData.Writer out, WorldSelection value) {
                    out.integer(value.landAttempt);
                    out.doubleValue(value.landShare);
                    out.integer(value.climateAttempt);
                    out.integer(value.climateDraws);
                    out.integer(value.climateFewest);
                }

                @Override
                public WorldSelection read(RegionalData.Reader in) throws RegionalData.Malformed {
                    int landAttempt = in.integer();
                    double landShare = in.doubleValue();
                    int climateAttempt = in.integer();
                    int climateDraws = in.integer();
                    int climateFewest = in.integer();
                    try {
                        return new WorldSelection(
                                landAttempt,
                                landShare,
                                climateAttempt,
                                climateDraws,
                                climateFewest);
                    } catch (IllegalArgumentException invalid) {
                        throw new RegionalData.Malformed("Invalid world selection");
                    }
                }
            };

    WorldSelection {
        if (landAttempt < 0
                || landAttempt >= LandShare.ATTEMPTS
                || !Double.isFinite(landShare)
                || Double.doubleToRawLongBits(landShare) < 0
                || landShare > 1
                || !ClimateCoverage.validSelection(climateAttempt, climateDraws, climateFewest))
            throw new IllegalArgumentException("Invalid world selection");
    }
}
