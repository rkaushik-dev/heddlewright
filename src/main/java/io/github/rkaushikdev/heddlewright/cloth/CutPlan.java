package io.github.rkaushikdev.heddlewright.cloth;

import io.github.rkaushikdev.heddlewright.util.Bounds;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.Numeric;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cutting a roll around its faults.
 *
 * <p>A garment cutter wants unbroken lengths. A roll with a fault every few yards is worth much less
 * than its length suggests, because a fault in the middle of a length spoils the whole length.
 * Planning the cuts means finding the clear runs first and only then deciding what to take out of
 * them.
 *
 * <p>A fault is a point on the roll, or for a fault that runs down the piece a stretch from the
 * {@code yard} it starts at to the yard it runs {@code through}. The margin is kept either side of the
 * whole stretch, since a cut anywhere inside a reed mark still lands on the reed mark.
 *
 * <p>Each wanted length is cut from the tightest run that still holds it, so the long runs stay
 * whole for as long as possible and what is left over comes off as a sellable short length rather
 * than a handful of fents. Offcuts are sorted the way the warehouse sorts them: short lengths of ten
 * yards or more, fents down to a yard, and rags below that.
 */
public final class CutPlan {

    /** The usual margin either side of a fault, in yards. */
    public static final double CLEARANCE = 0.25;

    /** The shortest offcut still sold by the yard as a short length. */
    public static final double SHORT_LENGTH_YARDS = 10;

    /** The shortest offcut still sold as a fent; anything shorter is a rag. */
    public static final double FENT_YARDS = 1;

    /** A stretch of a roll: where it starts and ends, and how long it is. */
    public record Span(double from, double to, double yards) {}

    /** A cutting plan: the lengths cut, the ones that would not fit, and what is left of each run. */
    public record Plan(List<Span> cuts, List<Double> unmet, List<Span> offcuts) {
        /** Copies the lists. */
        public Plan {
            cuts = List.copyOf(cuts);
            unmet = List.copyOf(unmet);
            offcuts = List.copyOf(offcuts);
        }
    }

    /** The offcuts sorted for the warehouse, each list in roll order. */
    public record Offcuts(List<Span> shortLengths, List<Span> fents, List<Span> rags) {
        /** Copies the lists. */
        public Offcuts {
            shortLengths = List.copyOf(shortLengths);
            fents = List.copyOf(fents);
            rags = List.copyOf(rags);
        }
    }

    /** What a plan yields against its roll. */
    public record Summary(
            double rollYards, double cutYards, double offcutYards, double wasteYards, double yield, int unmet) {}

    /** The whole job: the clear runs, the plan, its summary, and its offcuts sorted. */
    public record Job(
            List<Span> runs, List<Span> cuts, List<Double> unmet, List<Span> offcuts, Summary summary, Offcuts sorted) {
        /** Copies the lists. */
        public Job {
            runs = List.copyOf(runs);
            cuts = List.copyOf(cuts);
            unmet = List.copyOf(unmet);
            offcuts = List.copyOf(offcuts);
        }
    }

    private static final class Open {
        private double from;
        private double to;

        Open(double from, double to) {
            this.from = from;
            this.to = to;
        }
    }

    private CutPlan() {}

    /** The clear runs with a quarter yard either side of each fault. */
    public static List<Span> clearRuns(List<?> defectYards, double rollYards) {
        return clearRuns(defectYards, rollYards, CLEARANCE);
    }

    /**
     * The stretches of a roll clear of faults, each with a margin either side of the fault so a cut
     * never lands on one. A fault is a yard, or a record with the {@code yard} it starts at and an
     * optional {@code through}.
     */
    public static List<Span> clearRuns(List<?> defectYards, double rollYards, double clearance) {
        List<Object> faults = Check.array(defectYards, "defectYards");
        Check.positiveNumber(rollYards, "rollYards");
        Check.number(clearance, "clearance", Bounds.atLeast(0));
        List<Open> blocked = new ArrayList<>(faults.size());
        for (int index = 0; index < faults.size(); index += 1) {
            Open fault = faultSpan(faults.get(index), "defectYards[" + index + "]");
            blocked.add(new Open(Math.max(0, fault.from - clearance), Math.min(rollYards, fault.to + clearance)));
        }
        blocked.sort(Comparator.comparingDouble((Open span) -> span.from));

        List<Open> merged = new ArrayList<>();
        for (Open span : blocked) {
            Open last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && span.from <= last.to) {
                last.to = Math.max(last.to, span.to);
                continue;
            }
            merged.add(span);
        }

        List<Span> runs = new ArrayList<>();
        double at = 0;
        for (Open span : merged) {
            if (span.from > at) {
                runs.add(span(Numeric.roundTo(at, 3), Numeric.roundTo(span.from, 3)));
            }
            at = Math.max(at, span.to);
        }
        if (at < rollYards) {
            runs.add(span(Numeric.roundTo(at, 3), Numeric.roundTo(rollYards, 3)));
        }
        return List.copyOf(runs);
    }

    /** Where a fault lies: a yard on its own, or a running fault from its yard through to its end. */
    private static Open faultSpan(Object fault, String field) {
        if (!(fault instanceof JsonObject) && !(fault instanceof Map<?, ?>)) {
            double at = Check.number(fault, field, Bounds.atLeast(0));
            return new Open(at, at);
        }
        JsonObject record = Check.plainObject(fault, field);
        double from = Check.number(record.get("yard"), field + ".yard", Bounds.atLeast(0));
        Json through = record.get("through");
        double to = through.isNullish() ? from : Check.number(through, field + ".through", Bounds.atLeast(from));
        return new Open(from, to);
    }

    private static Span span(double from, double to) {
        return new Span(from, to, Numeric.roundTo(to - from, 3));
    }

    /**
     * Fit wanted lengths into the clear runs, longest first, cutting each from the run with the least
     * left that still holds it, the earlier run on a tie.
     */
    public static Plan planCuts(List<Span> runs, List<?> wantedLengths) {
        Check.array(runs, "runs");
        List<Double> wanted = new ArrayList<>(Check.array(wantedLengths, "wantedLengths").stream()
                .map(length -> Check.positiveNumber(length, "wanted length"))
                .toList());
        wanted.sort(Comparator.reverseOrder());
        List<Open> left =
                runs.stream().map(run -> new Open(run.from(), run.to())).toList();
        List<Span> cuts = new ArrayList<>();
        List<Double> unmet = new ArrayList<>();
        for (double length : wanted) {
            Optional<Open> tightest = left.stream()
                    .filter(run -> Numeric.roundTo(run.to - run.from, 3) >= length)
                    .min(Comparator.comparingDouble((Open run) -> run.to - run.from));
            if (tightest.isEmpty()) {
                unmet.add(length);
                continue;
            }
            Open run = tightest.get();
            cuts.add(new Span(Numeric.roundTo(run.from, 3), Numeric.roundTo(run.from + length, 3), length));
            run.from = Numeric.roundTo(run.from + length, 3);
        }
        cuts.sort(Comparator.comparingDouble(Span::from));
        List<Span> offcuts = left.stream()
                .filter(run -> Numeric.roundTo(run.to - run.from, 3) > 0)
                .map(run -> span(run.from, run.to))
                .toList();
        return new Plan(cuts, unmet, offcuts);
    }

    /** The offcuts of a plan sorted into short lengths, fents and rags. */
    public static Offcuts sortOffcuts(Plan plan) {
        List<Span> offcuts = plan.offcuts();
        return new Offcuts(
                offcuts.stream()
                        .filter(cut -> cut.yards() >= SHORT_LENGTH_YARDS)
                        .toList(),
                offcuts.stream()
                        .filter(cut -> cut.yards() >= FENT_YARDS && cut.yards() < SHORT_LENGTH_YARDS)
                        .toList(),
                offcuts.stream().filter(cut -> cut.yards() < FENT_YARDS).toList());
    }

    /** What a plan actually yields, against the roll it came out of. */
    public static Summary cutPlanSummary(Plan plan, double rollYards) {
        Check.positiveNumber(rollYards, "rollYards");
        double cut = Numeric.roundTo(
                Numeric.sum(plan.cuts().stream().map(Span::yards).toList()), 3);
        double offcut = Numeric.roundTo(
                Numeric.sum(plan.offcuts().stream().map(Span::yards).toList()), 3);
        return new Summary(
                rollYards,
                cut,
                offcut,
                Numeric.roundTo(rollYards - cut - offcut, 3),
                Numeric.roundTo(cut / rollYards, 4),
                plan.unmet().size());
    }

    /** The longest single length the roll can give, clear of faults. */
    public static double bestSingleLength(List<Span> runs) {
        Check.array(runs, "runs");
        double best = 0;
        for (Span run : runs) {
            best = Math.max(best, run.yards());
        }
        return best;
    }

    /** How many lengths of one size the roll gives. */
    public static int lengthsAvailable(List<Span> runs, double length) {
        Check.positiveNumber(length, "length");
        return (int) Numeric.sumBy(runs, (Span run) -> Math.floor(run.yards() / length), "runs");
    }

    /** The whole job with the usual clearance. */
    public static Job cutRoll(double rollYards, List<?> defectYards, List<?> wantedLengths) {
        return cutRoll(rollYards, defectYards, wantedLengths, CLEARANCE);
    }

    /**
     * The whole job in one call: find the clear runs, fit the order into them and report what came
     * out.
     */
    public static Job cutRoll(double rollYards, List<?> defectYards, List<?> wantedLengths, double clearance) {
        List<Span> runs = clearRuns(defectYards, rollYards, clearance);
        Plan plan = planCuts(runs, wantedLengths);
        return new Job(
                runs, plan.cuts(), plan.unmet(), plan.offcuts(), cutPlanSummary(plan, rollYards), sortOffcuts(plan));
    }

    /**
     * The whole job from a request record: {@code rollYards}, {@code defectYards},
     * {@code wantedLengths} and an optional {@code clearance}, checked as the typed form checks them.
     */
    public static Job cutRoll(Object request) {
        JsonObject record = Check.plainObject(request, "request");
        Json clearance = record.get("clearance");
        double margin = clearance.isMissing() ? CLEARANCE : Check.number(clearance, "clearance", Bounds.atLeast(0));
        Object defects = record.get("defectYards");
        Object lengths = record.get("wantedLengths");
        List<Span> runs = clearRuns(
                Check.array(defects, "defectYards"),
                Check.positiveNumber(record.get("rollYards"), "rollYards"),
                margin);
        Plan plan = planCuts(runs, Check.array(lengths, "wantedLengths"));
        return new Job(
                runs,
                plan.cuts(),
                plan.unmet(),
                plan.offcuts(),
                cutPlanSummary(plan, record.get("rollYards").asDouble()),
                sortOffcuts(plan));
    }
}
