package io.github.rkaushikdev.heddlewright.analysis;

import io.github.rkaushikdev.heddlewright.analysis.Interlacement.Firmness;
import io.github.rkaushikdev.heddlewright.draft.Draft;
import io.github.rkaushikdev.heddlewright.draft.Drawdown;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.Matrix;
import io.github.rkaushikdev.heddlewright.util.Problem;
import io.github.rkaushikdev.heddlewright.util.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The cloth report.
 *
 * <p>One draft in, one reading out, gathered from every part of the package that measures
 * something. It is the document a designer signs off, so nothing in it is stated that was not
 * derived: every figure here comes from the drawdown or from the construction, and the report says
 * which.
 */
public final class Report {

    /** What the report is worked out against: the float limit, the yarn, the sett and the construction. */
    public record Options(
            int floatLimit,
            double count,
            String system,
            OptionalDouble endsPerInch,
            OptionalDouble picksPerInch,
            Optional<Construction> construction) {

        /** A report for a yarn of this cotton count, with a float limit of three and nothing else. */
        public static Options forCount(double count) {
            return new Options(3, count, "ne", OptionalDouble.empty(), OptionalDouble.empty(), Optional.empty());
        }

        /** The same options with another float limit. */
        public Options floatLimit(int limit) {
            return new Options(limit, count, system, endsPerInch, picksPerInch, construction);
        }

        /** The same options with the yarn counted in another system. */
        public Options system(String name) {
            return new Options(floatLimit, count, name, endsPerInch, picksPerInch, construction);
        }

        /** The same options with the cloth's sett, which brings the cover factor in. */
        public Options sett(double ends, double picks) {
            return new Options(
                    floatLimit, count, system, OptionalDouble.of(ends), OptionalDouble.of(picks), construction);
        }

        /** The same options with a construction, which brings the weight in. */
        public Options construction(Construction value) {
            return new Options(floatLimit, count, system, endsPerInch, picksPerInch, Optional.of(value));
        }

        /** Read options from a plain record, as the service and the command line pass them. */
        public static Options fromRecord(Object input) {
            JsonObject record = Check.plainObject(input, "options");
            Json limit = record.get("floatLimit");
            Json system = record.get("system");
            Json construction = record.get("construction");
            return new Options(
                    limit.isNullish() ? 3 : Check.integer(limit, "floatLimit"),
                    Check.number(record.get("count").isNullish() ? Double.NaN : record.get("count"), "count"),
                    system.isNullish() ? "ne" : Check.nonEmptyString(system, "system"),
                    optional(record.get("endsPerInch"), "endsPerInch"),
                    optional(record.get("picksPerInch"), "picksPerInch"),
                    construction.isMissing() ? Optional.empty() : Optional.of(Construction.fromRecord(construction)));
        }

        private static OptionalDouble optional(Json value, String field) {
            return value.isNullish() ? OptionalDouble.empty() : OptionalDouble.of(Check.number(value, field));
        }
    }

    /** The structure of the cloth, read off the drawdown alone. */
    public record Structure(
            int picks,
            int ends,
            int repeatPicks,
            int repeatEnds,
            double repeatShare,
            double warpFace,
            double interlacement,
            Firmness firmness) {}

    /**
     * The floats, which is what decides whether the cloth will wear: the face first, then the back,
     * which is the side a lining or a seat rubs against.
     */
    public record FloatSection(
            int longestWarp,
            int longestWeft,
            double meanWarp,
            double meanWeft,
            int floatLimit,
            int over,
            int longestBackWarp,
            int longestBackWeft,
            int overOnBack) {}

    /** The sett and the cover, which need the yarn as well as the cloth. */
    public record SettSection(
            double diameters,
            double settFactor,
            double maximumEndsPerInch,
            double maximumPicksPerInch,
            double ashenhurstDivisor,
            Optional<Sett.Cover> cover)
            implements Json.Writable {

        /** Whether the cloth is jammed, which can only be said when there is a cover factor. */
        public Optional<Boolean> jammed() {
            return cover.map(Sett::isJammed);
        }

        /** Written with nulls for the cover and the jam when no sett was given. */
        @Override
        public Json toJson() {
            return Json.obj(
                    "diameters", diameters,
                    "settFactor", settFactor,
                    "maximumEndsPerInch", maximumEndsPerInch,
                    "maximumPicksPerInch", maximumPicksPerInch,
                    "ashenhurstDivisor", ashenhurstDivisor,
                    "cover", cover.<Object>map(found -> found).orElse(Json.NULL),
                    "jammed", jammed().<Object>map(found -> found).orElse(Json.NULL));
        }
    }

    /** The weight, which needs the construction rather than the drawdown. */
    public record WeightSection(
            double warpGsm, double weftGsm, double gsm, double ouncesPerSquareYard, double warpShare) {}

    /** The whole report. */
    public record ClothReport(
            String name,
            Structure structure,
            FloatSection floats,
            SettSection sett,
            Optional<WeightSection> weight,
            List<Problem> problems)
            implements Json.Writable {

        /** Copies the problems. */
        public ClothReport {
            problems = List.copyOf(problems);
        }

        /** Written with a null weight when no construction was given. */
        @Override
        public Json toJson() {
            return Json.obj(
                    "name", name,
                    "structure", structure,
                    "floats", floats,
                    "sett", sett,
                    "weight", weight.<Object>map(found -> found).orElse(Json.NULL),
                    "problems", problems);
        }
    }

    /** House limits a report is held to; any left out is not checked. */
    public record Specification(
            OptionalDouble maxFloat,
            OptionalDouble maxBackFloat,
            OptionalDouble minInterlacement,
            OptionalDouble maxGsm) {

        /** No limits at all. */
        public static final Specification NONE = new Specification(
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty());

        /** The same limits with a longest float on the face. */
        public Specification maxFloat(double limit) {
            return new Specification(OptionalDouble.of(limit), maxBackFloat, minInterlacement, maxGsm);
        }

        /** The same limits with a longest float on the back. */
        public Specification maxBackFloat(double limit) {
            return new Specification(maxFloat, OptionalDouble.of(limit), minInterlacement, maxGsm);
        }

        /** The same limits with a least interlacement. */
        public Specification minInterlacement(double limit) {
            return new Specification(maxFloat, maxBackFloat, OptionalDouble.of(limit), maxGsm);
        }

        /** The same limits with a heaviest weight. */
        public Specification maxGsm(double limit) {
            return new Specification(maxFloat, maxBackFloat, minInterlacement, OptionalDouble.of(limit));
        }
    }

    /** One limit as a report is checked against it: what it is called, its figure, and which way it cuts. */
    private record Limit(String code, OptionalDouble limit, OptionalDouble got, boolean ceiling) {
        Optional<Finding> broken() {
            if (limit.isEmpty() || got.isEmpty()) {
                return Optional.empty();
            }
            double value = got.getAsDouble();
            double bound = limit.getAsDouble();
            return (ceiling ? value > bound : value < bound)
                    ? Optional.of(new Finding(code, value, bound))
                    : Optional.empty();
        }
    }

    /** A limit a report breaks, with the figure and the limit. */
    public record Finding(String code, double got, double limit) {}

    /** Whether a report clears its limits, and the ones it does not. */
    public record Verdict(boolean meets, List<Finding> findings) {
        /** Copies the findings. */
        public Verdict {
            findings = List.copyOf(findings);
        }
    }

    private Report() {}

    /** The structure of the cloth, read off the drawdown alone. */
    public static Structure structureSection(Draft draft) {
        Matrix cloth = Drawdown.drawdownFromDraft(draft);
        Motif found = Motif.motif(cloth);
        return new Structure(
                cloth.rows(),
                cloth.cols(),
                found.picks(),
                found.ends(),
                Motif.compression(cloth).ratio(),
                Drawdown.warpFaceRatio(cloth),
                Interlacement.interlacementFactor(cloth),
                Interlacement.firmnessClass(cloth));
    }

    /** The floats against a limit of three threads. */
    public static FloatSection floatSection(Draft draft) {
        return floatSection(draft, 3);
    }

    /** The floats, which is what decides whether the cloth will wear. */
    public static FloatSection floatSection(Draft draft, int floatLimit) {
        Matrix cloth = Drawdown.drawdownFromDraft(draft);
        Floats.Longest longest = Floats.longestFloat(cloth);
        Floats.Longest back = Floats.longestFloat(cloth, Floats.Side.BACK);
        WarpWeft mean = Floats.meanFloatLength(cloth);
        return new FloatSection(
                longest.warp(),
                longest.weft(),
                mean.warp(),
                mean.weft(),
                floatLimit,
                Floats.floatsOver(cloth, floatLimit).size(),
                back.warp(),
                back.weft(),
                Floats.floatsOver(cloth, floatLimit, Floats.Side.BACK).size());
    }

    /** The sett and the cover, which need the yarn as well as the cloth. */
    public static SettSection settSection(Draft draft, Options options) {
        Check.positiveNumber(options.count(), "count");
        Matrix cloth = Drawdown.drawdownFromDraft(draft);
        Sett.Ashenhurst sett = Sett.ashenhurstSett(cloth, options.count(), options.system());
        Optional<Sett.Cover> cover = options.endsPerInch().isEmpty()
                ? Optional.empty()
                : Optional.of(Sett.coverFactor(
                        options.endsPerInch().getAsDouble(),
                        options.picksPerInch().orElse(Double.NaN),
                        options.count(),
                        options.count()));
        return new SettSection(
                sett.diameters(),
                Sett.settFactor(cloth).warp(),
                sett.warp(),
                sett.weft(),
                Interlacement.ashenhurstDivisor(cloth).warp(),
                cover);
    }

    /** The weight, which needs the construction rather than the drawdown. */
    public static WeightSection weightSection(Construction construction) {
        Weight.ClothWeight weight = Weight.clothWeightGsm(construction);
        return new WeightSection(
                weight.warp(),
                weight.weft(),
                weight.total(),
                Weight.gsmToOuncesPerSquareYard(weight.total()),
                weight.warpShare());
    }

    /** The whole report. */
    public static ClothReport clothReport(Draft draft, Options options) {
        Check.object(options, "options");
        return new ClothReport(
                draft.name(),
                structureSection(draft),
                floatSection(draft, options.floatLimit()),
                settSection(draft, options),
                options.construction().map(Report::weightSection),
                draft.inspect());
    }

    /** The report printed for a designer to sign. */
    public static String renderReport(ClothReport report) {
        List<String> blocks = new ArrayList<>();
        blocks.addAll(List.of(report.name(), "", "Structure", Table.renderPairs(report.structure())));
        blocks.addAll(List.of("", "Floats", Table.renderPairs(report.floats())));
        JsonObject sett = ((JsonObject) report.sett().toJson())
                .with(
                        "cover",
                        report.sett().cover().<Object>map(Sett.Cover::cloth).orElse("not given"));
        blocks.addAll(List.of("", "Sett", Table.renderPairs(sett)));
        report.weight().ifPresent(weight -> blocks.addAll(List.of("", "Weight", Table.renderPairs(weight))));
        if (!report.problems().isEmpty()) {
            blocks.addAll(List.of(
                    "",
                    "Problems",
                    Table.renderTable(
                            report.problems(), List.of(Table.Column.of("code"), Table.Column.of("message")))));
        }
        return String.join("\n", blocks);
    }

    /** Whether a report clears a set of house limits, the findings in the order the limits are listed. */
    public static Verdict meetsSpecification(ClothReport report, Specification limits) {
        FloatSection floats = report.floats();
        List<Limit> checks = List.of(
                new Limit(
                        "float_too_long",
                        limits.maxFloat(),
                        OptionalDouble.of(Math.max(floats.longestWarp(), floats.longestWeft())),
                        true),
                new Limit(
                        "back_float_too_long",
                        limits.maxBackFloat(),
                        OptionalDouble.of(Math.max(floats.longestBackWarp(), floats.longestBackWeft())),
                        true),
                new Limit(
                        "too_open",
                        limits.minInterlacement(),
                        OptionalDouble.of(report.structure().interlacement()),
                        false),
                new Limit(
                        "too_heavy",
                        limits.maxGsm(),
                        report.weight()
                                .map(weight -> OptionalDouble.of(weight.gsm()))
                                .orElse(OptionalDouble.empty()),
                        true));
        List<Finding> findings =
                checks.stream().flatMap(check -> check.broken().stream()).toList();
        return new Verdict(findings.isEmpty(), findings);
    }

    /** Whether a report clears no limits at all, which it always does. */
    public static Verdict meetsSpecification(ClothReport report) {
        return meetsSpecification(report, Specification.NONE);
    }
}
