package io.github.rkaushikdev.heddlewright.yarn;

import io.github.rkaushikdev.heddlewright.util.Bounds;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Twist.
 *
 * <p>Twist is what holds a spun yarn together, and how much of it a yarn needs depends on how thick
 * the yarn is: a coarse yarn needs fewer turns an inch than a fine one to be equally firm. The trade
 * handles that with a twist multiplier, which is turns per inch over the square root of the cotton
 * count, and quotes yarns by the multiplier rather than by the turns.
 *
 * <p>Direction matters as much as amount. A warp and a weft twisted the same way lie together and
 * the cloth reads flat; twisted opposite ways they stand apart and the twill line shows.
 *
 * <p>Twisting also shortens a yarn. The turns wind the fibres round the axis, so a metre of strand
 * comes out of the twister as rather less than a metre of yarn, and that yarn is heavier per metre
 * than the strands that went in. A folded yarn is costed and set on its resultant count, and a
 * resultant worked from the singles alone reads a few per cent too fine.
 */
public final class Twist {

    /** The way a yarn is twisted. */
    public enum Direction {
        /** Left handed. */
        S,
        /** Right handed. */
        Z;

        /** The two directions, S first. */
        public static final List<Direction> DIRECTIONS = List.of(values());

        /** Read a direction by its letter, naming the field in any complaint. */
        public static Direction parse(Object text, String field) {
            return Check.member(text, field, values());
        }
    }

    /** How a yarn's twist reads against the multiplier its purpose wants. */
    public record TwistClass(double multiplier, double wanted, double ratio, String reading) {}

    /** How far either side of the wanted multiplier a yarn still reads as normally twisted. */
    public static final double CLASS_TOLERANCE = 0.15;

    private static final Map<String, Double> MULTIPLIERS = multipliers();

    private Twist() {}

    private static Map<String, Double> multipliers() {
        Map<String, Double> table = new LinkedHashMap<>();
        table.put("weft", 3.0);
        table.put("warp", 4.0);
        table.put("hosiery", 2.5);
        table.put("voile", 5.5);
        return java.util.Collections.unmodifiableMap(table);
    }

    /** The twist multiplier a cotton count yarn is running at. */
    public static double twistMultiplier(double turnsPerInch, double count) {
        return twistMultiplier(turnsPerInch, count, "ne");
    }

    /** The twist multiplier a yarn is running at. */
    public static double twistMultiplier(double turnsPerInch, double count, String system) {
        Check.positiveNumber(turnsPerInch, "turnsPerInch");
        return Numeric.roundTo(turnsPerInch / Math.sqrt(in(count, system, "ne")), 4);
    }

    /** A count in one system read in another, unrounded, which the multipliers are worked on. */
    private static double in(double count, String system, String wanted) {
        return Count.fromTex(Count.toTex(count, system), wanted);
    }

    /** The turns an inch a wanted multiplier asks for on a cotton count yarn. */
    public static double turnsForMultiplier(double multiplier, double count) {
        return turnsForMultiplier(multiplier, count, "ne");
    }

    /** The turns an inch a wanted multiplier asks for. */
    public static double turnsForMultiplier(double multiplier, double count, String system) {
        Check.positiveNumber(multiplier, "multiplier");
        return Numeric.roundTo(multiplier * Math.sqrt(in(count, system, "ne")), 2);
    }

    /** The same in metric: turns a metre over the square root of the metric count. */
    public static double metricTwistFactor(double turnsPerMetre, double count, String system) {
        Check.positiveNumber(turnsPerMetre, "turnsPerMetre");
        return Numeric.roundTo(turnsPerMetre / Math.sqrt(in(count, system, "nm")), 4);
    }

    /** Turns an inch to turns a metre. */
    public static double turnsPerMetre(double turnsPerInch) {
        Check.positiveNumber(turnsPerInch, "turnsPerInch");
        return Numeric.roundTo(turnsPerInch / 0.0254, 2);
    }

    /** Turns a metre to turns an inch. */
    public static double turnsPerInch(double metreTurns) {
        Check.positiveNumber(metreTurns, "turnsPerMetre");
        return Numeric.roundTo(metreTurns * 0.0254, 4);
    }

    /** The usual working multiplier for what the yarn is for. */
    public static double multiplierFor(String purpose) {
        Double found = MULTIPLIERS.get(purpose);
        if (found == null) {
            throw new InvalidArgumentException(
                    "no working multiplier for \"" + purpose + "\"",
                    Json.obj("purpose", purpose, "known", List.copyOf(MULTIPLIERS.keySet())));
        }
        return found;
    }

    /** Whether a cotton count warp yarn is soft, normal or hard twisted. */
    public static TwistClass twistClass(double turnsPerInch, double count) {
        return twistClass(turnsPerInch, count, "ne", "warp");
    }

    /** Whether a yarn is soft, normal or hard twisted for what it is meant to do. */
    public static TwistClass twistClass(double turnsPerInch, double count, String system, String purpose) {
        double running = twistMultiplier(turnsPerInch, count, system);
        double wanted = multiplierFor(purpose);
        double ratio = Numeric.roundTo(running / wanted, 3);
        String reading = ratio < 1 - CLASS_TOLERANCE ? "soft" : ratio > 1 + CLASS_TOLERANCE ? "hard" : "normal";
        return new TwistClass(running, wanted, ratio, reading);
    }

    /**
     * The resultant direction of a folded yarn. Folding twist runs against the single twist, so a Z
     * spun single folded with S twist gives an S resultant.
     */
    public static Direction foldedDirection(Direction singleDirection) {
        Check.required(singleDirection, "singleDirection");
        return singleDirection == Direction.Z ? Direction.S : Direction.Z;
    }

    /** The folding twist at seven tenths of the single twist. */
    public static double foldingTurns(double singleTurnsPerInch) {
        return foldingTurns(singleTurnsPerInch, 0.7);
    }

    /**
     * How much twist a folded yarn should carry, as a share of the single twist. Too little and the
     * fold unwinds; too much and the yarn snarls.
     */
    public static double foldingTurns(double singleTurnsPerInch, double share) {
        Check.positiveNumber(singleTurnsPerInch, "singleTurnsPerInch");
        Check.number(share, "share", Bounds.between(0.1, 1.5));
        return Numeric.roundTo(singleTurnsPerInch * share, 2);
    }

    /**
     * The contraction twisting took out of a yarn, as a percentage of the length before: a metre of
     * strand that comes off as 97 centimetres of yarn has contracted 3 per cent.
     */
    public static double twistContraction(double untwistedLength, double twistedLength) {
        Check.positiveNumber(untwistedLength, "untwistedLength");
        Check.positiveNumber(twistedLength, "twistedLength");
        if (twistedLength > untwistedLength) {
            throw new InvalidArgumentException(
                    "a twisted yarn cannot come out longer than the strand that went in",
                    Json.obj("untwistedLength", untwistedLength, "twistedLength", twistedLength));
        }
        return Numeric.roundTo(((untwistedLength - twistedLength) / untwistedLength) * 100, 3);
    }

    /**
     * The resultant count of a folded yarn once the folding twist has contracted it. The singles are
     * folded as {@link Count#foldedCount} folds them, and the contraction makes the result heavier: a
     * direct count rises by one over one less the contraction, and an indirect count falls by it.
     */
    public static double foldedCount(List<? extends Number> counts, String system, double contractionPercent) {
        Check.number(contractionPercent, "contractionPercent", Bounds.between(0, 50));
        double folded = Count.foldedCount(counts, system);
        double kept = 1 - contractionPercent / 100;
        return Numeric.roundTo(Count.isIndirect(system) ? folded * kept : folded / kept, 4);
    }

    /** Whether the twist of a warp and a weft will show a Z twill line or hide it. */
    public static String twillContrast(Direction warpDirection, Direction weftDirection) {
        return twillContrast(warpDirection, weftDirection, Direction.Z);
    }

    /**
     * Whether a warp and a weft twisted this way will show the twill line or hide it. Opposite twist
     * stands the two systems apart and the line shows.
     */
    public static String twillContrast(Direction warpDirection, Direction weftDirection, Direction twillHand) {
        Check.required(warpDirection, "warpDirection");
        Check.required(weftDirection, "weftDirection");
        Check.required(twillHand, "twillHand");
        if (warpDirection != weftDirection) {
            return "pronounced";
        }
        return warpDirection == twillHand ? "flattened" : "pronounced";
    }
}
