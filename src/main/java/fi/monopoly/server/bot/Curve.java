package fi.monopoly.server.bot;

/**
 * A response curve mapping a raw input in any range to an output in [0,1].
 *
 * <p>This is the "response curve" component of Dave Mark's Infinite Axis Utility System.
 * Each {@link Consideration} normalizes its raw input through one of these curves before
 * combining with other considerations.</p>
 *
 * <p>All {@code eval} methods clamp their output to [0,1] unless otherwise noted.</p>
 */
public record Curve(Type type, double[] params) {

    public enum Type {
        /**
         * {@code y = clamp(slope × x + intercept)}.
         * params: [slope, intercept]
         */
        LINEAR,

        /**
         * {@code y = clamp(slope × |x|^exponent × sign(x) + intercept)}.
         * params: [exponent, slope, intercept]
         */
        POLYNOMIAL,

        /**
         * Logistic S-curve: {@code y = 1 / (1 + e^(-steepness × (x − midpoint)))}.
         * params: [midpoint, steepness]
         */
        LOGISTIC,

        /**
         * Binary step: {@code y = (x >= threshold) ? aboveValue : belowValue}.
         * params: [threshold, belowValue, aboveValue]
         */
        STEP
    }

    /**
     * Evaluates this curve at {@code x} and returns a value in [0,1].
     */
    public double eval(double x) {
        return switch (type) {
            case LINEAR -> {
                double slope = params[0], intercept = params[1];
                yield clamp(slope * x + intercept);
            }
            case POLYNOMIAL -> {
                double exponent = params[0], slope = params[1], intercept = params[2];
                yield clamp(slope * Math.pow(Math.abs(x), exponent) * Math.signum(x) + intercept);
            }
            case LOGISTIC -> {
                double midpoint = params[0], steepness = params[1];
                yield 1.0 / (1.0 + Math.exp(-steepness * (x - midpoint)));
            }
            case STEP -> {
                double threshold = params[0], below = params[1], above = params[2];
                yield x >= threshold ? above : below;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Factories for common shapes
    // -------------------------------------------------------------------------

    /** Linear: y = slope × x + intercept, clamped to [0,1]. */
    public static Curve linear(double slope, double intercept) {
        return new Curve(Type.LINEAR, new double[]{slope, intercept});
    }

    /** Logistic S-curve centred at {@code midpoint} with given steepness. */
    public static Curve logistic(double midpoint, double steepness) {
        return new Curve(Type.LOGISTIC, new double[]{midpoint, steepness});
    }

    /** Step: returns {@code below} when x < threshold, {@code above} otherwise. */
    public static Curve step(double threshold, double below, double above) {
        return new Curve(Type.STEP, new double[]{threshold, below, above});
    }

    /** Polynomial: y = slope × x^exponent + intercept. */
    public static Curve polynomial(double exponent, double slope, double intercept) {
        return new Curve(Type.POLYNOMIAL, new double[]{exponent, slope, intercept});
    }

    // -------------------------------------------------------------------------
    // Common named curves
    // -------------------------------------------------------------------------

    /** Hard veto: 0 if x < threshold, 1 otherwise. */
    public static Curve veto(double threshold) {
        return step(threshold, 0.0, 1.0);
    }

    /** Identity map [0,1] → [0,1]. */
    public static Curve identity() {
        return linear(1.0, 0.0);
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
