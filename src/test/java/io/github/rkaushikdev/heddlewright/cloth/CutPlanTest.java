package io.github.rkaushikdev.heddlewright.cloth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.cloth.CutPlan.Span;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CutPlanTest {

    @Nested
    class ClearRuns {

        @Test
        void isTheWholeRollWhenNothingIsFaulty() {
            assertThat(CutPlan.clearRuns(List.of(), 100)).containsExactly(new Span(0, 100, 100));
        }

        @Test
        void splitsTheRollEitherSideOfAFaultWithClearance() {
            assertThat(CutPlan.clearRuns(List.of(50), 100))
                    .containsExactly(new Span(0, 49.75, 49.75), new Span(50.25, 100, 49.75));
        }

        @Test
        void mergesFaultsThatFallWithinAClearanceOfEachOther() {
            List<Span> runs = CutPlan.clearRuns(List.of(50, 50.2), 100);
            assertThat(runs).hasSize(2);
            assertThat(runs.get(1).from()).isEqualTo(50.45);
        }

        @Test
        void sortsTheFaultsBeforeWorking() {
            assertThat(CutPlan.clearRuns(List.of(70, 30), 100)).isEqualTo(CutPlan.clearRuns(List.of(30, 70), 100));
        }

        @Test
        void dropsARunWhenAFaultSitsAtTheVeryStart() {
            List<Span> runs = CutPlan.clearRuns(List.of(0), 100);
            assertThat(runs).hasSize(1);
            assertThat(runs.get(0).from()).isEqualTo(0.25);
        }

        @Test
        void takesAWiderClearance() {
            assertThat(CutPlan.clearRuns(List.of(50), 100, 1).get(0).to()).isEqualTo(49);
        }

        @Test
        void keepsTheClearanceRoundTheWholeRunOfARunningFault() {
            assertThat(CutPlan.clearRuns(List.of(Json.obj("yard", 40, "through", 45)), 100))
                    .containsExactly(new Span(0, 39.75, 39.75), new Span(45.25, 100, 54.75));
        }

        @Test
        void readsAFaultRecordWithNoRunAsAPoint() {
            assertThat(CutPlan.clearRuns(List.of(Json.obj("yard", 50)), 100))
                    .isEqualTo(CutPlan.clearRuns(List.of(50), 100));
        }

        @Test
        void swallowsAPointFaultThatFallsInsideARun() {
            List<Span> runs = CutPlan.clearRuns(List.of(42, Json.obj("yard", 40, "through", 45), 80), 100);
            assertThat(runs).extracting(Span::from).containsExactly(0.0, 45.25, 80.25);
            assertThat(runs).extracting(Span::to).containsExactly(39.75, 79.75, 100.0);
        }

        @Test
        void sortsRunningFaultsByWhereTheyStart() {
            List<Span> runs = CutPlan.clearRuns(
                    List.of(Json.obj("yard", 70, "through", 72), Json.obj("yard", 10, "through", 30)), 100);
            assertThat(runs).extracting(Span::yards).containsExactly(9.75, 39.5, 27.75);
        }

        @Test
        void refusesARunThatEndsBeforeItStartsOrAFaultWithNoYard() {
            assertThatThrownBy(() -> CutPlan.clearRuns(List.of(Json.obj("yard", 40, "through", 30)), 100))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> CutPlan.clearRuns(List.of(Json.obj("through", 30)), 100))
                    .isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void refusesAFaultAtANegativePosition() {
            assertThatThrownBy(() -> CutPlan.clearRuns(List.of(-1), 100)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class PlanCuts {

        private final List<Span> runs = CutPlan.clearRuns(List.of(50), 100);

        @Test
        void fitsTheLongestWantedLengthFirst() {
            CutPlan.Plan plan = CutPlan.planCuts(runs, List.of(10, 40));
            assertThat(plan.cuts()).extracting(Span::yards).containsExactly(40.0, 10.0);
            assertThat(plan.unmet()).isEmpty();
        }

        @Test
        void reportsALengthNoRunCanHold() {
            CutPlan.Plan plan = CutPlan.planCuts(runs, List.of(60));
            assertThat(plan.cuts()).isEmpty();
            assertThat(plan.unmet()).containsExactly(60.0);
        }

        @Test
        void leavesWhatIsLeftOfEachRunAsAnOffcut() {
            CutPlan.Plan plan = CutPlan.planCuts(runs, List.of(40));
            double offcutYards =
                    plan.offcuts().stream().mapToDouble(Span::yards).sum();
            assertThat(offcutYards).isCloseTo(49.75 - 40 + 49.75, within(0.0005));
        }

        @Test
        void neverCutsPastTheEndOfARun() {
            CutPlan.Plan plan = CutPlan.planCuts(runs, List.of(40, 9, 40, 9));
            for (Span cut : plan.cuts()) {
                assertThat(runs.stream().anyMatch(run -> cut.from() >= run.from() && cut.to() <= run.to()))
                        .isTrue();
            }
        }

        @Test
        void refusesAWantedLengthOfNothing() {
            assertThatThrownBy(() -> CutPlan.planCuts(runs, List.of(0))).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class CutPlanSummary {

        @Test
        void addsTheCutsTheOffcutsAndTheWasteBackToTheRoll() {
            CutPlan.Plan plan = CutPlan.planCuts(CutPlan.clearRuns(List.of(50), 100), List.of(40, 40));
            CutPlan.Summary summary = CutPlan.cutPlanSummary(plan, 100);
            assertThat(summary.cutYards()).isEqualTo(80);
            assertThat(summary.cutYards() + summary.offcutYards() + summary.wasteYards())
                    .isCloseTo(100, within(0.0005));
        }

        @Test
        void readsTheYieldAsTheShareActuallyCut() {
            CutPlan.Plan plan = CutPlan.planCuts(CutPlan.clearRuns(List.of(), 100), List.of(50));
            assertThat(CutPlan.cutPlanSummary(plan, 100).yield()).isEqualTo(0.5);
        }

        @Test
        void countsTheLengthsItCouldNotMeet() {
            CutPlan.Plan plan = CutPlan.planCuts(CutPlan.clearRuns(List.of(50), 100), List.of(60, 60));
            assertThat(CutPlan.cutPlanSummary(plan, 100).unmet()).isEqualTo(2);
        }
    }

    @Nested
    class BestSingleLength {

        @Test
        void isTheLongestClearRun() {
            assertThat(CutPlan.bestSingleLength(CutPlan.clearRuns(List.of(20), 100)))
                    .isEqualTo(79.75);
            assertThat(CutPlan.bestSingleLength(CutPlan.clearRuns(List.of(), 100)))
                    .isEqualTo(100);
        }

        @Test
        void isNothingWhenThereAreNoRunsAtAll() {
            assertThat(CutPlan.bestSingleLength(List.of())).isEqualTo(0);
        }
    }

    @Nested
    class LengthsAvailable {

        @Test
        void countsHowManyOfOneSizeTheRollGives() {
            assertThat(CutPlan.lengthsAvailable(CutPlan.clearRuns(List.of(), 100), 30))
                    .isEqualTo(3);
            assertThat(CutPlan.lengthsAvailable(CutPlan.clearRuns(List.of(50), 100), 30))
                    .isEqualTo(2);
        }

        @Test
        void isNothingWhenTheLengthDoesNotFitAnywhere() {
            assertThat(CutPlan.lengthsAvailable(CutPlan.clearRuns(List.of(50), 100), 60))
                    .isEqualTo(0);
        }
    }

    @Nested
    class CutRoll {

        @Test
        void doesTheWholeJobInOneCall() {
            CutPlan.Job job = CutPlan.cutRoll(
                    Json.obj("rollYards", 100, "defectYards", List.of(50), "wantedLengths", List.of(40, 40)));
            assertThat(job.runs()).hasSize(2);
            assertThat(job.cuts()).hasSize(2);
            assertThat(job.summary().yield()).isEqualTo(0.8);
        }

        @Test
        void aRollWithAFaultInTheMiddleYieldsLessThanACleanOne() {
            CutPlan.Job clean = CutPlan.cutRoll(100, List.of(), List.of(60));
            CutPlan.Job faulty = CutPlan.cutRoll(100, List.of(50), List.of(60));
            assertThat(clean.summary().yield()).isEqualTo(0.6);
            assertThat(faulty.summary().yield()).isEqualTo(0);
            assertThat(faulty.unmet()).containsExactly(60.0);
        }

        @Test
        void losesTheWholeRunOfARunningFaultFromTheOrder() {
            CutPlan.Job job = CutPlan.cutRoll(Json.obj(
                    "rollYards",
                    100,
                    "defectYards",
                    List.of(Json.obj("yard", 20, "through", 50)),
                    "wantedLengths",
                    List.of(45, 25)));
            assertThat(job.cuts()).extracting(Span::from).containsExactly(50.25);
            assertThat(job.unmet()).containsExactly(25.0);
            assertThat(job.summary().wasteYards()).isEqualTo(30.5);
        }
    }
}
