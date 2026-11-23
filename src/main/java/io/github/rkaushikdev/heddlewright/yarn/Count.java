package io.github.rkaushikdev.heddlewright.yarn;

import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import io.github.rkaushikdev.heddlewright.util.Text;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Yarn count systems.
 *
 * <p>A direct system states mass for a fixed length, so a bigger number is a thicker yarn: tex is
 * grams per thousand metres, denier grams per nine thousand. An indirect system states length for a
 * fixed mass, so a bigger number is a finer yarn: cotton counts hanks of 840 yards to the pound.
 *
 * <p>Every constant here is worked out from the pound and the yard rather than copied from a table,
 * so the familiar 590.5 for cotton falls out rather than being asserted. A system is declared by what
 * its unit is: so much mass on so much length for a direct count, so much length in so much mass for
 * an indirect one. Jute is spun to pounds per spyndle of 14,400 yards, American woollen to runs of
 * 1,600 yards and cuts of 300 yards a pound, and Galashiels woollen to cuts of 300 yards in 24 ounces.
 */
public final class Count {

    /** Whether a count states mass for a length or length for a mass. */
    public enum Kind {
        /** Mass for a fixed length: a bigger number is a thicker yarn. */
        DIRECT,
        /** Length for a fixed mass: a bigger number is a finer yarn. */
        INDIRECT;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One count system: how it reads against tex, and what its unit means. */
    public sealed interface CountSystem permits Direct, Indirect {
        /** Whether the system is direct or indirect. */
        Kind kind();

        /** What one unit of the system means, for a listing. */
        String label();

        /** A count in this system, in tex. */
        double toTex(double value);

        /** A tex count, in this system. */
        double fromTex(double tex);
    }

    /** A direct system: tex is the count times {@code factor}. */
    public record Direct(double factor, String label) implements CountSystem {
        @Override
        public Kind kind() {
            return Kind.DIRECT;
        }

        @Override
        public double toTex(double value) {
            return value * factor;
        }

        @Override
        public double fromTex(double tex) {
            return tex / factor;
        }
    }

    /** An indirect system: tex is {@code constant} over the count. */
    public record Indirect(double constant, String label) implements CountSystem {
        @Override
        public Kind kind() {
            return Kind.INDIRECT;
        }

        @Override
        public double toTex(double value) {
            return constant / value;
        }

        @Override
        public double fromTex(double tex) {
            return constant / tex;
        }
    }

    private static final double GRAMS_PER_POUND = 453.59237;
    private static final double GRAMS_PER_OUNCE = GRAMS_PER_POUND / 16;
    private static final double METRES_PER_YARD = 0.9144;

    /** The systems a mill quotes in, by name. */
    public static final Map<String, CountSystem> COUNT_SYSTEMS = systems();

    private Count() {}

    /** A direct system whose unit is {@code grams} on {@code metres}. */
    private static Direct massOnLength(double grams, double metres, String label) {
        return new Direct((grams * 1000) / metres, label);
    }

    /** An indirect system whose unit is {@code metres} in {@code grams}. */
    private static Indirect lengthInMass(double metres, double grams, String label) {
        return new Indirect((grams * 1000) / metres, label);
    }

    /** An indirect system counting hanks of {@code yards} to the pound. */
    private static Indirect hanksPerPound(double yards, String label) {
        return lengthInMass(yards * METRES_PER_YARD, GRAMS_PER_POUND, label);
    }

    private static Map<String, CountSystem> systems() {
        Map<String, CountSystem> map = new LinkedHashMap<>();
        map.put("tex", massOnLength(1, 1000, "grams per 1000 m"));
        map.put("dtex", massOnLength(1, 10000, "grams per 10000 m"));
        map.put("denier", massOnLength(1, 9000, "grams per 9000 m"));
        map.put("nm", lengthInMass(1, 1, "metres per gram"));
        map.put("ne", hanksPerPound(840, "cotton hanks per pound"));
        map.put("new", hanksPerPound(560, "worsted hanks per pound"));
        map.put("nel", hanksPerPound(300, "linen leas per pound"));
        map.put("nw", hanksPerPound(256, "woollen skeins per pound"));
        map.put("jute", massOnLength(GRAMS_PER_POUND, 14400 * METRES_PER_YARD, "pounds per spyndle of 14400 yards"));
        map.put("run", hanksPerPound(1600, "american woollen runs per pound"));
        map.put("cut", hanksPerPound(300, "american woollen cuts per pound"));
        map.put(
                "galashiels",
                lengthInMass(300 * METRES_PER_YARD, 24 * GRAMS_PER_OUNCE, "galashiels cuts per 24 ounces"));
        return Collections.unmodifiableMap(map);
    }

    /** Look a system up by name, case-insensitively. */
    public static CountSystem countSystem(String name) {
        Check.nonEmptyString(name, "system");
        CountSystem system = COUNT_SYSTEMS.get(name.toLowerCase(Locale.ROOT));
        if (system == null) {
            throw new InvalidArgumentException(
                    "unknown count system \"" + name + "\"",
                    Json.obj("system", name, "known", List.copyOf(COUNT_SYSTEMS.keySet())));
        }
        return system;
    }

    /** Convert a count in any system to tex. */
    public static double toTex(double value, String system) {
        Check.positiveNumber(value, "value");
        return countSystem(system).toTex(value);
    }

    /** Convert tex to any system. */
    public static double fromTex(double tex, String system) {
        Check.positiveNumber(tex, "tex");
        return countSystem(system).fromTex(tex);
    }

    /** Convert straight between two systems, to four places. */
    public static double convertCount(double value, String from, String to) {
        return convertCount(value, from, to, 4);
    }

    /** Convert straight between two systems, to the places given. */
    public static double convertCount(double value, String from, String to, int places) {
        return Numeric.roundTo(fromTex(toTex(value, from), to), places);
    }

    /** True when a bigger number in this system means a finer yarn. */
    public static boolean isIndirect(String system) {
        return countSystem(system).kind() == Kind.INDIRECT;
    }

    /**
     * The resultant count of a folded yarn. Direct counts add; indirect counts add as reciprocals,
     * so two ends of 40s cotton folded together read as 20s.
     */
    public static double foldedCount(List<? extends Number> counts, String system) {
        Check.array(counts, "counts", 1);
        CountSystem spec = countSystem(system);
        for (int i = 0; i < counts.size(); i += 1) {
            Check.positiveNumber(counts.get(i), "counts[" + i + "]");
        }
        if (spec.kind() == Kind.DIRECT) {
            return Numeric.roundTo(Numeric.sum(counts), 4);
        }
        double reciprocal = Numeric.sumBy(counts, count -> 1 / count.doubleValue(), "counts");
        return Numeric.roundTo(1 / reciprocal, 4);
    }

    /** The same fold written as a plied notation, {@code 2/40s} for two ends of forties. */
    public static String formatFold(List<? extends Number> counts, String system) {
        Check.array(counts, "counts", 1);
        countSystem(system);
        String name = system.toLowerCase(Locale.ROOT);
        if (new HashSet<>(counts.stream().map(Number::doubleValue).toList()).size() == 1) {
            return counts.size() + "/" + Text.string(counts.get(0)) + ("ne".equals(name) ? "s" : " " + name);
        }
        return counts.stream().map(Text::string).collect(Collectors.joining(" + "));
    }

    /** The diameter of a cotton count yarn, on Ashenhurst's constant of 28. */
    public static double yarnDiameterInches(double count) {
        return yarnDiameterInches(count, "ne", 28);
    }

    /** The diameter of a yarn counted in any system, on Ashenhurst's constant of 28. */
    public static double yarnDiameterInches(double count, String system) {
        return yarnDiameterInches(count, system, 28);
    }

    /**
     * Ashenhurst's yarn diameter in inches: a cotton yarn of count Ne measures one over twenty eight
     * times the square root of the count. It is the figure every maximum-sett rule is built on.
     */
    public static double yarnDiameterInches(double count, String system, double constant) {
        double ne = fromTex(toTex(count, system), "ne");
        Check.positiveNumber(constant, "constant");
        return 1 / (constant * Math.sqrt(ne));
    }

    /** How many threads of a cotton count yarn lie side by side in an inch, touching. */
    public static double threadsPerInch(double count) {
        return threadsPerInch(count, "ne", 28);
    }

    /** How many threads of a yarn lie side by side in an inch, touching. */
    public static double threadsPerInch(double count, String system) {
        return threadsPerInch(count, system, 28);
    }

    /** The same, on a diameter constant for another fibre. */
    public static double threadsPerInch(double count, String system, double constant) {
        return Numeric.roundTo(1 / yarnDiameterInches(count, system, constant), 2);
    }

    /** The length of one gram of a yarn, in metres, whatever system it is stated in. */
    public static double metresPerGram(double count, String system) {
        return Numeric.roundTo(1000 / toTex(count, system), 4);
    }
}
