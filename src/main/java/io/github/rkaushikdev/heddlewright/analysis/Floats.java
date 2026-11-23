package io.github.rkaushikdev.heddlewright.analysis;

import io.github.rkaushikdev.heddlewright.util.Bounds;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.Matrix;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import io.github.rkaushikdev.heddlewright.util.Runs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Float measurement.
 *
 * <p>A warp float is a run of raised cells down one end; a weft float is a run of lowered cells
 * across one pick. Both are measured round the repeat, because the cloth does not stop at the edge
 * of the diagram, and both are what decide whether a cloth will snag, how it will drape and how
 * closely it can be set.
 *
 * <p>The drawdown is the face of the cloth. The back is its complement: an end that is lowered on the
 * face lies on the back, so the long weft floats of a warp satin are the warp floats of its back. A
 * cloth seen from both sides, or one whose back rubs against a lining, is read from the side asked.
 */
public final class Floats {

    /** A warp float: the 1-based end, the pick it starts on, its length, and whether it wraps. */
    public record WarpFloat(int end, int start, int length, boolean wraps) {}

    /** A weft float: the 1-based pick, the end it starts on, its length, and whether it wraps. */
    public record WeftFloat(int pick, int start, int length, boolean wraps) {}

    /** A float past a limit, tagged with the direction it runs in. */
    public record Over(String direction, int thread, int start, int length, boolean wraps) {}

    /** How many floats of each length run in each direction. */
    public record Histogram(Map<Integer, Integer> warp, Map<Integer, Integer> weft) {}

    /**
     * The longest float either way, and which thread carries it. A direction with no float at all
     * reads zero, and names no thread.
     */
    public record Longest(int warp, int weft, int longest, OptionalInt onEnd, OptionalInt onPick)
            implements Json.Writable {

        /** Written with a null thread where a direction never floats, as a report prints it. */
        @Override
        public Json toJson() {
            return Json.obj(
                    "warp", warp,
                    "weft", weft,
                    "longest", longest,
                    "onEnd", onEnd.isPresent() ? onEnd.getAsInt() : Json.NULL,
                    "onPick", onPick.isPresent() ? onPick.getAsInt() : Json.NULL);
        }
    }

    /** Which side of the cloth is read. */
    public enum Side {
        /** The face, which is the drawdown as drawn. */
        FACE,
        /** The back, where every lowered cell is a raised one. */
        BACK
    }

    /** One run of floating cells along a thread, 0-based. */
    private record Run(int thread, int start, int length, boolean wraps) {}

    private Floats() {}

    /** The runs of {@code floating} along each thread, measured round the repeat. */
    private static List<Run> runs(List<List<Integer>> threads, int floating) {
        List<Run> out = new ArrayList<>();
        for (int thread = 0; thread < threads.size(); thread += 1) {
            for (Runs.Run<Integer> run : Runs.cyclicRuns(threads.get(thread))) {
                if (run.value() == floating) {
                    out.add(new Run(thread, run.start(), run.length(), run.wraps()));
                }
            }
        }
        return out;
    }

    /** The cell value a warp float is made of on a side; a weft float is made of the other. */
    private static int raised(Side side) {
        return side == Side.FACE ? 1 : 0;
    }

    /** Every warp float on the face of the repeat. */
    public static List<WarpFloat> warpFloats(Matrix drawdown) {
        return warpFloats(drawdown, Side.FACE);
    }

    /** Every warp float on the side of the repeat given. */
    public static List<WarpFloat> warpFloats(Matrix drawdown, Side side) {
        return runs(drawdown.columns(), raised(side)).stream()
                .map(run -> new WarpFloat(run.thread() + 1, run.start() + 1, run.length(), run.wraps()))
                .toList();
    }

    /** Every weft float on the face of the repeat. */
    public static List<WeftFloat> weftFloats(Matrix drawdown) {
        return weftFloats(drawdown, Side.FACE);
    }

    /** Every weft float on the side of the repeat given. */
    public static List<WeftFloat> weftFloats(Matrix drawdown, Side side) {
        return runs(drawdown.allRows(), 1 - raised(side)).stream()
                .map(run -> new WeftFloat(run.thread() + 1, run.start() + 1, run.length(), run.wraps()))
                .toList();
    }

    /** The longest float either way on the face, and which thread carries it. */
    public static Longest longestFloat(Matrix drawdown) {
        return longestFloat(drawdown, Side.FACE);
    }

    /** The longest float either way on the side given, the first thread to carry it if tied. */
    public static Longest longestFloat(Matrix drawdown, Side side) {
        Optional<WarpFloat> warp = warpFloats(drawdown, side).stream()
                .reduce((best, found) -> found.length() > best.length() ? found : best);
        Optional<WeftFloat> weft = weftFloats(drawdown, side).stream()
                .reduce((best, found) -> found.length() > best.length() ? found : best);
        int warpLength = warp.map(WarpFloat::length).orElse(0);
        int weftLength = weft.map(WeftFloat::length).orElse(0);
        return new Longest(
                warpLength,
                weftLength,
                Math.max(warpLength, weftLength),
                warp.map(found -> OptionalInt.of(found.end())).orElse(OptionalInt.empty()),
                weft.map(found -> OptionalInt.of(found.pick())).orElse(OptionalInt.empty()));
    }

    /** Floats on the face longer than the limit, both ways, ready to print in a report. */
    public static List<Over> floatsOver(Matrix drawdown, int limit) {
        return floatsOver(drawdown, limit, Side.FACE);
    }

    /** Floats on the side given longer than the limit, warp first, ready to print in a report. */
    public static List<Over> floatsOver(Matrix drawdown, int limit, Side side) {
        Check.positiveInteger(limit, "limit");
        List<Over> out = new ArrayList<>();
        warpFloats(drawdown, side).stream()
                .filter(found -> found.length() > limit)
                .forEach(found -> out.add(new Over("warp", found.end(), found.start(), found.length(), found.wraps())));
        weftFloats(drawdown, side).stream()
                .filter(found -> found.length() > limit)
                .forEach(
                        found -> out.add(new Over("weft", found.pick(), found.start(), found.length(), found.wraps())));
        return List.copyOf(out);
    }

    /**
     * The longer float of the two sides, which is the one that snags whichever way the cloth is
     * made up.
     */
    public static int longestEitherSide(Matrix drawdown) {
        return Math.max(
                longestFloat(drawdown, Side.FACE).longest(),
                longestFloat(drawdown, Side.BACK).longest());
    }

    /** A count of floats by length, for each direction. */
    public static Histogram floatHistogram(Matrix drawdown) {
        Map<Integer, Integer> warp = new TreeMap<>();
        Map<Integer, Integer> weft = new TreeMap<>();
        warpFloats(drawdown).forEach(found -> warp.merge(found.length(), 1, Integer::sum));
        weftFloats(drawdown).forEach(found -> weft.merge(found.length(), 1, Integer::sum));
        return new Histogram(java.util.Collections.unmodifiableMap(warp), java.util.Collections.unmodifiableMap(weft));
    }

    /** The mean float length in each direction, which reads as cloth firmness. */
    public static WarpWeft meanFloatLength(Matrix drawdown) {
        return new WarpWeft(
                Numeric.meanBy(warpFloats(drawdown), WarpFloat::length, "floats"),
                Numeric.meanBy(weftFloats(drawdown), WeftFloat::length, "floats"));
    }

    /** The float limit for a sett, taking a tenth of an inch as the longest a float may span. */
    public static int floatLimitForSett(int threadsPerInch) {
        return floatLimitForSett(threadsPerInch, 0.1);
    }

    /**
     * The float limit a cloth can carry before it snags, taken from the sett: the usual shed rule is
     * that a float should not span more than about a tenth of an inch, so the limit falls as the
     * cloth is set closer.
     */
    public static int floatLimitForSett(int threadsPerInch, double spanInches) {
        Check.positiveInteger(threadsPerInch, "threadsPerInch");
        return Math.max(1, (int) Math.floor(threadsPerInch * spanInches));
    }

    /** True when nothing in the cloth floats further than the limit. */
    public static boolean withinFloatLimit(Matrix drawdown, int limit) {
        Check.integer(limit, "limit", Bounds.atLeast(1));
        return longestFloat(drawdown).longest() <= limit;
    }

    /** The longest float down a single named end, 1-based. */
    public static int floatOnEnd(Matrix drawdown, int end) {
        Check.positiveInteger(end, "end");
        return Runs.longestRun(drawdown.column(end - 1), 1);
    }

    /** The longest float across a single named pick, 1-based. */
    public static int floatOnPick(Matrix drawdown, int pick) {
        Check.positiveInteger(pick, "pick");
        return Runs.longestRun(drawdown.row(pick - 1), 0);
    }
}
