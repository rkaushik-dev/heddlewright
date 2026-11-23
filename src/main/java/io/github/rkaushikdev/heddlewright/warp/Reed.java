package io.github.rkaushikdev.heddlewright.warp;

import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.Limits;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import io.github.rkaushikdev.heddlewright.util.Problem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The reed and the denting plan.
 *
 * <p>A reed is a comb of dents, worked here in dents per inch, and the warp is sleyed through it so
 * many ends to a dent. It sets the width in the loom and spaces the warp across it, and a denting that
 * is uneven or over-full shows in the cloth as a reed mark long after the loom has forgotten it.
 *
 * <p>Reed makers do not all count per inch. A Stockport count is the dents in two inches, and a
 * metric count the dents in ten centimetres, so a 24s Stockport reed and a 47 metric reed are both
 * near enough twelve dents an inch. A rack is read in the count it was stamped with.
 *
 * <p>The selvedges are sleyed heavier than the ground, commonly four ends to a dent against the
 * ground's two, so the edge carries the temple's pull without opening up. A selvedge denting puts
 * its own dents at each side of the ground rather than averaging them into it.
 */
public final class Reed {

    /** Reeds a shed is likely to have on the rack, in dents per inch. */
    public static final List<Integer> STOCK_REEDS = List.of(5, 6, 8, 10, 12, 14, 15, 16, 18, 20, 24, 30);

    /** The rack reed closest to a wanted count, and how far off it is. */
    public record Nearest(int reed, double off) {}

    /** One way to reach a sett: a reed and so many ends in each dent. */
    public record Option(int reed, int endsPerDent) {}

    /** How a reed's count is stamped: dents in an inch, in two inches, or in ten centimetres. */
    public enum ReedCount {
        /** Dents per inch. */
        PER_INCH(1),
        /** Stockport: dents in two inches. */
        STOCKPORT(2),
        /** Metric: dents in ten centimetres. */
        METRIC(10 / 2.54);

        private final double inches;

        ReedCount(double inches) {
            this.inches = inches;
        }

        /** A reed stamped in this count, in dents per inch. */
        public double dentsPerInch(double count) {
            return Numeric.roundTo(Check.positiveNumber(count, "count") / inches, 4);
        }

        /** A reed of so many dents per inch, in this count. */
        public double count(double dentsPerInch) {
            return Numeric.roundTo(Check.positiveNumber(dentsPerInch, "dentsPerInch") * inches, 2);
        }
    }

    private Reed() {}

    /** The reed a sett needs at two ends a dent. */
    public static double reedFor(double endsPerInch) {
        return reedFor(endsPerInch, 2);
    }

    /** The reed a sett needs, given how many ends go in each dent. */
    public static double reedFor(double endsPerInch, int endsPerDent) {
        Check.positiveNumber(endsPerInch, "endsPerInch");
        Check.positiveInteger(endsPerDent, "endsPerDent");
        return Numeric.roundTo(endsPerInch / endsPerDent, 4);
    }

    /** The stock reed closest to a wanted count. */
    public static Nearest nearestReed(double dentsPerInch) {
        return nearestReed(dentsPerInch, STOCK_REEDS);
    }

    /** The reed on a given rack closest to a wanted count, and how far off it is, the first on a tie. */
    public static Nearest nearestReed(double dentsPerInch, List<Integer> reeds) {
        Check.positiveNumber(dentsPerInch, "dentsPerInch");
        Check.array(reeds, "reeds", 1);
        int best = reeds.stream()
                .min(Comparator.comparingDouble((Integer reed) -> Math.abs(reed - dentsPerInch)))
                .orElseThrow();
        return new Nearest(best, Numeric.roundTo(best - dentsPerInch, 4));
    }

    /**
     * The reed on a rack stamped in another count closest to a wanted number of dents per inch. The
     * reed is named by its stamp, and how far off it is is said in dents per inch.
     */
    public static Nearest nearestReed(double dentsPerInch, List<Integer> reeds, ReedCount stamped) {
        Check.positiveNumber(dentsPerInch, "dentsPerInch");
        Check.array(reeds, "reeds", 1);
        Check.required(stamped, "stamped");
        int best = reeds.stream()
                .min(Comparator.comparingDouble((Integer reed) -> Math.abs(stamped.dentsPerInch(reed) - dentsPerInch)))
                .orElseThrow();
        return new Nearest(best, Numeric.roundTo(stamped.dentsPerInch(best) - dentsPerInch, 4));
    }

    /** Every stock reed that reaches a sett at up to four ends a dent. */
    public static List<Option> reedOptions(double endsPerInch) {
        return reedOptions(endsPerInch, STOCK_REEDS, 4);
    }

    /** Every stock reed that reaches a sett at up to the given ends a dent. */
    public static List<Option> reedOptions(double endsPerInch, int maxEndsPerDent) {
        return reedOptions(endsPerInch, STOCK_REEDS, maxEndsPerDent);
    }

    /**
     * Every reed on a rack that reaches a sett with a whole number of ends per dent, which is what a
     * weaver actually wants to choose between.
     */
    public static List<Option> reedOptions(double endsPerInch, List<Integer> reeds, int maxEndsPerDent) {
        Check.positiveNumber(endsPerInch, "endsPerInch");
        Check.positiveInteger(maxEndsPerDent, "maxEndsPerDent");
        double wanted = Numeric.roundTo(endsPerInch, 6);
        return reeds.stream()
                .flatMap(reed -> IntStream.rangeClosed(1, maxEndsPerDent)
                        .filter(perDent -> Numeric.roundTo((double) reed * perDent, 6) == wanted)
                        .mapToObj(perDent -> new Option(reed, perDent)))
                .toList();
    }

    /** Spread the ends two to a dent. */
    public static List<Integer> dentingPlan(int ends) {
        return dentingPlan(ends, List.of(2));
    }

    /**
     * Spread {@code ends} across dents following a repeating pattern, so a sett of two and a half to
     * the dent is written as {@code [3, 2]} rather than as a fraction.
     */
    public static List<Integer> dentingPlan(int ends, List<?> pattern) {
        Check.positiveInteger(ends, "ends");
        return fill(ends, sizes(pattern, "pattern"));
    }

    /**
     * A denting with selvedges: {@code selvedgeEnds} at each side sleyed {@code selvedgePerDent} to a
     * dent, and the ground between them sleyed on its own pattern. The ground is the ends left once
     * both selvedges are taken off.
     */
    public static List<Integer> selvedgeDenting(int ends, List<?> pattern, int selvedgeEnds, int selvedgePerDent) {
        Check.positiveInteger(ends, "ends");
        Check.positiveInteger(selvedgeEnds, "selvedgeEnds");
        Check.positiveInteger(selvedgePerDent, "selvedgePerDent");
        Limits.assertFits(
                selvedgeEnds * 2 + 1,
                ends,
                "denting",
                "the selvedges leave no ground to dent",
                Json.obj("ends", ends, "selvedgeEnds", selvedgeEnds));
        List<Integer> selvedge = fill(selvedgeEnds, List.of(selvedgePerDent));
        List<Integer> out = new ArrayList<>(selvedge);
        out.addAll(fill(ends - selvedgeEnds * 2, sizes(pattern, "pattern")));
        out.addAll(selvedge.reversed());
        return List.copyOf(out);
    }

    private static List<Integer> sizes(List<?> pattern, String field) {
        List<Object> groups = Check.array(pattern, field, 1);
        return IntStream.range(0, groups.size())
                .mapToObj(i -> Check.positiveInteger(groups.get(i), field + "[" + i + "]"))
                .toList();
    }

    /** So many ends dented on a repeating pattern, the last dent taking whatever is left. */
    private static List<Integer> fill(int ends, List<Integer> sizes) {
        List<Integer> dents = new ArrayList<>();
        for (int placed = 0, step = 0; placed < ends; step += 1) {
            int take = Math.min(sizes.get(step % sizes.size()), ends - placed);
            dents.add(take);
            placed += take;
        }
        return List.copyOf(dents);
    }

    /** The average ends per dent a repeating pattern gives. */
    public static double averageEndsPerDent(List<Integer> pattern) {
        Check.array(pattern, "pattern", 1);
        return Numeric.roundTo(Numeric.sum(pattern) / pattern.size(), 4);
    }

    /** The width in the reed, in inches, for so many dents at a given reed count. */
    public static double reedWidth(int dents, double dentsPerInch) {
        Check.positiveInteger(dents, "dents");
        Check.positiveNumber(dentsPerInch, "dentsPerInch");
        return Numeric.roundTo(dents / dentsPerInch, 3);
    }

    /** Which dent each 1-based end falls in, two ends to a dent. */
    public static List<Integer> sleyOrder(int ends) {
        return sleyOrder(ends, List.of(2));
    }

    /** The full sley: which dent each 1-based end falls in. */
    public static List<Integer> sleyOrder(int ends, List<?> pattern) {
        return sley(dentingPlan(ends, pattern));
    }

    /** The sley of any denting, selvedges included: which dent each 1-based end falls in. */
    public static List<Integer> sley(List<Integer> dents) {
        Check.array(dents, "dents", 1);
        return IntStream.range(0, dents.size())
                .flatMap(index -> IntStream.range(0, dents.get(index)).map(end -> index + 1))
                .boxed()
                .toList();
    }

    /** Warn about a denting that will show, at a crowding limit of four ends a dent. */
    public static List<Problem> dentingWarnings(List<Integer> dents) {
        return dentingWarnings(dents, 4);
    }

    /**
     * Warn about a denting that will show. Too many ends in a dent crowds them into a stripe, and a
     * plan whose last dent is short leaves a thin edge.
     */
    public static List<Problem> dentingWarnings(List<Integer> dents, int maxEndsPerDent) {
        Check.array(dents, "dents", 1);
        List<Problem> warnings = new ArrayList<>();
        List<Integer> crowded = IntStream.range(0, dents.size())
                .filter(index -> dents.get(index) > maxEndsPerDent)
                .mapToObj(index -> index + 1)
                .toList();
        if (!crowded.isEmpty()) {
            warnings.add(new Problem(
                    "crowded_dent",
                    crowded.size() + " dents carry more than " + maxEndsPerDent + " ends",
                    Json.obj("dents", crowded, "maxEndsPerDent", maxEndsPerDent)));
        }
        int first = dents.get(0);
        int last = dents.get(dents.size() - 1);
        if (dents.size() > 1 && last < first) {
            warnings.add(new Problem(
                    "short_last_dent",
                    "the last dent is not full, so the selvedge will pull in",
                    Json.obj("first", first, "last", last)));
        }
        return List.copyOf(warnings);
    }

    /** Refuse a denting the reed cannot physically hold. */
    public static List<Integer> assertDentingFits(List<Integer> dents, int dentsAvailable) {
        Check.array(dents, "dents", 1);
        Check.positiveInteger(dentsAvailable, "dentsAvailable");
        Limits.assertFits(
                dents.size(),
                dentsAvailable,
                "denting",
                "the denting needs more dents than the reed has",
                Json.obj("needed", dents.size(), "dentsAvailable", dentsAvailable));
        return dents;
    }
}
