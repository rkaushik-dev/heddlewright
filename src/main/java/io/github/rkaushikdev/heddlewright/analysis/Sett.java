package io.github.rkaushikdev.heddlewright.analysis;

import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Matrix;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import io.github.rkaushikdev.heddlewright.yarn.Count;
import java.util.List;
import java.util.Locale;

/**
 * Sett: how many threads to the inch a cloth can carry.
 *
 * <p>Ashenhurst's rule starts from the number of yarn diameters that fit in an inch and takes off an
 * allowance for the crossings, because every crossing lifts one thread over another and uses up
 * room. The more a weave interlaces, the more room it needs, so plain weave sets the most openly and
 * a satin the closest for the same yarn:
 *
 * <pre>sett = threads in repeat / (threads in repeat + intersections) x diameters</pre>
 *
 * <p>Brierley's rule reads the same weave another way, from its average float. A square plain cloth
 * sets at half the diameters an inch holds, and any other weave sets at that figure times its average
 * float raised to a power that belongs to its family: 0.42 for a twill, 0.39 for a matt and 0.35 for a
 * satin. It is the rule a mill uses once the floats grow long, where Ashenhurst's sets too openly.
 */
public final class Sett {

    /** Ashenhurst's sett: the diameters an inch holds, and the ends and picks the weave allows. */
    public record Ashenhurst(double diameters, double warp, double weft) {}

    /** Peirce's cover factor for each direction and for the cloth. */
    public record Cover(double warp, double weft, double cloth) {}

    /** A weave family as Brierley's rule knows it, with the power its average float is raised to. */
    public enum Family {
        /** Twills, on 0.42. */
        TWILL(0.42),
        /** Matts, the hopsacks and baskets, on 0.39. */
        MATT(0.39),
        /** Satins and sateens, on 0.35. */
        SATIN(0.35);

        private final double exponent;

        Family(double exponent) {
            this.exponent = exponent;
        }

        /** The power the average float is raised to. */
        public double exponent() {
            return exponent;
        }

        /** Read a family by the word a designer writes, refusing anything else. */
        public static Family parse(Object text) {
            return Check.member(text, "family", values());
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Brierley's sett for a square cloth: the average float, the plain sett it starts from, and the sett. */
    public record Brierley(double averageFloat, double plainSett, double sett) {}

    /** Ashenhurst's diameter constant for cotton, which is also the cover factor at which threads touch. */
    public static final double ASHENHURST_CONSTANT = 28;

    private Sett() {}

    /** The most threads of a cotton count yarn that fit in an inch lying side by side. */
    public static double maximumSett(double count) {
        return Count.threadsPerInch(count);
    }

    /** The most threads of a yarn that fit in an inch lying side by side. */
    public static double maximumSett(double count, String system) {
        return Count.threadsPerInch(count, system);
    }

    /** The same on a diameter constant for another fibre. */
    public static double maximumSett(double count, String system, double constant) {
        return Count.threadsPerInch(count, system, constant);
    }

    /** Ashenhurst's sett for a cloth in a cotton count yarn, read off its own drawdown. */
    public static Ashenhurst ashenhurstSett(Matrix drawdown, double count) {
        return ashenhurstSett(drawdown, count, "ne", ASHENHURST_CONSTANT);
    }

    /** Ashenhurst's sett for a cloth in a yarn counted in any system. */
    public static Ashenhurst ashenhurstSett(Matrix drawdown, double count, String system) {
        return ashenhurstSett(drawdown, count, system, ASHENHURST_CONSTANT);
    }

    /** Ashenhurst's sett on a diameter constant for another fibre. */
    public static Ashenhurst ashenhurstSett(Matrix drawdown, double count, String system, double constant) {
        double diameters = Count.threadsPerInch(count, system, constant);
        WarpWeft factor = rawSettFactor(drawdown);
        return new Ashenhurst(
                diameters,
                Numeric.roundTo(factor.warp() * diameters, 2),
                Numeric.roundTo(factor.weft() * diameters, 2));
    }

    /**
     * The share of the maximum a weave may be set at, which is the part of the rule that depends only
     * on the interlacement and not on the yarn.
     */
    public static WarpWeft settFactor(Matrix drawdown) {
        WarpWeft factor = rawSettFactor(drawdown);
        return new WarpWeft(Numeric.roundTo(factor.warp(), 4), Numeric.roundTo(factor.weft(), 4));
    }

    /** The threads of a repeat over the threads and the crossings, each way, unrounded. */
    private static WarpWeft rawSettFactor(Matrix drawdown) {
        int rows = drawdown.rows();
        int cols = drawdown.cols();
        double warpIntersections = Numeric.mean(Interlacement.intersectionsPerEnd(drawdown));
        double weftIntersections = Numeric.mean(Interlacement.intersectionsPerPick(drawdown));
        return new WarpWeft(cols / (cols + weftIntersections), rows / (rows + warpIntersections));
    }

    /**
     * Brierley's maximum sett for a square cloth in a cotton count yarn: half the diameters an inch
     * holds, times the weave's average float raised to its family's power. The average float is the
     * threads across the repeat over the crossings a pick makes in it.
     */
    public static Brierley brierleySett(Matrix drawdown, double count, Family family) {
        return brierleySett(drawdown, count, "ne", family);
    }

    /** Brierley's maximum sett for a square cloth in a yarn counted in any system. */
    public static Brierley brierleySett(Matrix drawdown, double count, String system, Family family) {
        Check.required(family, "family");
        double crossings = Numeric.mean(
                drawdown.allRows().stream().map(Sett::crossingsRoundTheRepeat).toList());
        double averageFloat = crossings == 0 ? drawdown.cols() : drawdown.cols() / crossings;
        double plain = Count.threadsPerInch(count, system) / 2;
        return new Brierley(
                Numeric.roundTo(averageFloat, 4),
                Numeric.roundTo(plain, 2),
                Numeric.roundTo(plain * Math.pow(averageFloat, family.exponent()), 2));
    }

    /** How many times a pick changes face going once round the repeat, the join included. */
    private static int crossingsRoundTheRepeat(List<Integer> pick) {
        int crossings = 0;
        for (int end = 0; end < pick.size(); end += 1) {
            crossings += pick.get(end).equals(pick.get((end + 1) % pick.size())) ? 0 : 1;
        }
        return crossings;
    }

    /** A working sett at two thirds of the theoretical one, the usual start for a cloth meant to drape. */
    public static double workingSett(double theoretical) {
        return workingSett(theoretical, 0.67);
    }

    /** A working sett, which a designer takes as a share of the theoretical figure. */
    public static double workingSett(double theoretical, double share) {
        Check.positiveNumber(theoretical, "theoretical");
        Check.positiveNumber(share, "share");
        return Numeric.roundTo(theoretical * share, 1);
    }

    /**
     * Peirce's cloth cover factor. Each direction is threads per inch over the square root of the
     * cotton count, and the cloth figure takes off the overlap where warp and weft cover the same
     * ground.
     */
    public static Cover coverFactor(double endsPerInch, double picksPerInch, double warpCount, double weftCount) {
        Check.positiveNumber(endsPerInch, "endsPerInch");
        Check.positiveNumber(picksPerInch, "picksPerInch");
        Check.positiveNumber(warpCount, "warpCount");
        Check.positiveNumber(weftCount, "weftCount");
        double warp = endsPerInch / Math.sqrt(warpCount);
        double weft = picksPerInch / Math.sqrt(weftCount);
        return new Cover(
                Numeric.roundTo(warp, 2),
                Numeric.roundTo(weft, 2),
                Numeric.roundTo(warp + weft - (warp * weft) / ASHENHURST_CONSTANT, 2));
    }

    /** A cover factor at or above 28 in one direction means the cloth is jammed. */
    public static boolean isJammed(Cover cover) {
        return cover.warp() >= ASHENHURST_CONSTANT || cover.weft() >= ASHENHURST_CONSTANT;
    }

    /** How many ends a cloth width takes at a given sett. */
    public static long endsForWidth(double widthInches, double endsPerInch) {
        Check.positiveNumber(widthInches, "widthInches");
        Check.positiveNumber(endsPerInch, "endsPerInch");
        return Math.round(widthInches * endsPerInch);
    }

    /** The width a given end count occupies at a sett, in inches. */
    public static double widthForEnds(int ends, double endsPerInch) {
        Check.positiveInteger(ends, "ends");
        Check.positiveNumber(endsPerInch, "endsPerInch");
        return Numeric.roundTo(ends / endsPerInch, 3);
    }
}
