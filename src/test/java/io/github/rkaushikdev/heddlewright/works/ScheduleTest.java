package io.github.rkaushikdev.heddlewright.works;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.Fixtures;
import io.github.rkaushikdev.heddlewright.loom.Loom;
import io.github.rkaushikdev.heddlewright.loom.Registry;
import io.github.rkaushikdev.heddlewright.util.CapacityException;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.works.Schedule.Placement;
import io.github.rkaushikdev.heddlewright.works.Schedule.Plan;
import io.github.rkaushikdev.heddlewright.works.Schedule.Run;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ScheduleTest {

    private static final Loom NARROW = Registry.createLoom(Fixtures.narrowLoom());
    private static final Loom WIDE = Registry.createLoom(Fixtures.wideLoom());
    private static final List<Loom> LOOMS = List.of(WIDE, NARROW);

    private static final JsonObject SMALL_RECORD = Json.obj(
            "order",
            "SO-1",
            "style",
            "poplin",
            "metres",
            100,
            "picksPerCm",
            20,
            "requirements",
            Registry.styleRequirements(Json.obj("widthCm", 100, "shafts", 4)));

    private static final Run SMALL = Schedule.createRun(SMALL_RECORD);

    private static final Run BROAD = Schedule.createRun(Json.obj(
            "order",
            "SO-2",
            "style",
            "sheeting",
            "metres",
            100,
            "picksPerCm",
            20,
            "requirements",
            Registry.styleRequirements(Json.obj("widthCm", 250, "shafts", 4))));

    private static Run small(Object... fields) {
        return Schedule.createRun(SMALL_RECORD.with(fields));
    }

    private static Run huge(String order) {
        return small("order", order, "requirements", Registry.styleRequirements(Json.obj("widthCm", 400)));
    }

    @Nested
    class Candidates {

        @Test
        void listsTheCapableLoomsNarrowestFirst() {
            assertThat(Schedule.candidates(LOOMS, SMALL))
                    .extracting(Loom::reference)
                    .containsExactly("N1", "W1");
        }

        @Test
        void dropsTheLoomsThatCannotTakeTheStyle() {
            assertThat(Schedule.candidates(LOOMS, BROAD))
                    .extracting(Loom::reference)
                    .containsExactly("W1");
        }

        @Test
        void isEmptyWhenNothingOnTheFloorWillDo() {
            assertThat(Schedule.candidates(LOOMS, huge("SO-1"))).isEmpty();
        }
    }

    @Nested
    class RunHoursOn {

        @Test
        void isThePicksOverTheRunningSpeed() {
            assertThat(Schedule.runHoursOn(NARROW, SMALL, 1)).isCloseTo(200000.0 / (200 * 60), within(0.0005));
        }

        @Test
        void takesLongerAtALowerEfficiency() {
            assertThat(Schedule.runHoursOn(NARROW, SMALL, 0.5)).isGreaterThan(Schedule.runHoursOn(NARROW, SMALL, 1));
        }
    }

    @Nested
    class ScheduleAPlan {

        @Test
        void keepsANarrowStyleOffTheWideLoomWhileTheNarrowOneIsFree() {
            assertThat(Schedule.schedule(LOOMS, List.of(SMALL)).placed().get(0).loom())
                    .isEqualTo("N1");
        }

        @Test
        void putsABroadStyleOnTheOnlyLoomThatHoldsIt() {
            assertThat(Schedule.schedule(LOOMS, List.of(BROAD)).placed().get(0).loom())
                    .isEqualTo("W1");
        }

        @Test
        void runsTwoOrdersSideBySideOnTwoLooms() {
            Plan plan = Schedule.schedule(LOOMS, List.of(SMALL, BROAD));
            assertThat(new HashSet<>(plan.placed().stream().map(Placement::loom).toList()))
                    .hasSize(2);
            assertThat(plan.placed()).allMatch(entry -> entry.fromHour() == 0);
        }

        @Test
        void queuesASecondNarrowOrderBehindTheFirst() {
            Plan plan = Schedule.schedule(List.of(NARROW), List.of(SMALL, small("order", "SO-3")));
            assertThat(plan.placed().get(0).toHour())
                    .isEqualTo(plan.placed().get(1).fromHour());
            assertThat(plan.finishesAt()).isEqualTo(plan.placed().get(1).toHour());
        }

        @Test
        void takesTheHigherPriorityFirst() {
            Run urgent = small("order", "SO-9", "priority", 5);
            Plan plan = Schedule.schedule(List.of(NARROW), List.of(SMALL, urgent));
            assertThat(plan.placed().get(0).order()).isEqualTo("SO-9");
        }

        @Test
        void reportsARunNothingCanTakeRatherThanDroppingIt() {
            Plan plan = Schedule.schedule(LOOMS, List.of(huge("SO-8")));
            assertThat(plan.placed()).isEmpty();
            assertThat(plan.unplaced().get(0).order()).isEqualTo("SO-8");
        }

        @Test
        void startsWhereItIsTold() {
            assertThat(Schedule.schedule(LOOMS, List.of(SMALL), Json.obj("startHour", 12))
                            .placed()
                            .get(0)
                            .fromHour())
                    .isEqualTo(12);
        }

        @Test
        void needsAtLeastOneLoom() {
            assertThatThrownBy(() -> Schedule.schedule(List.of(), List.of(SMALL)))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class LoomLoad {

        @Test
        void reportsTheHoursAndTheShareOfTheSpanEachLoomCarries() {
            Plan plan = Schedule.schedule(List.of(NARROW), List.of(SMALL, small("order", "SO-3")));
            List<Schedule.Load> load = Schedule.loomLoad(plan);
            assertThat(load).hasSize(1);
            assertThat(load.get(0).utilisation()).isEqualTo(1);
        }

        @Test
        void readsAnIdleLoomOutOfTheLoadEntirely() {
            assertThat(Schedule.loomLoad(Schedule.schedule(LOOMS, List.of(SMALL))))
                    .extracting(Schedule.Load::loom)
                    .containsExactly("N1");
        }
    }

    @Nested
    class AssertFullyPlaced {

        @Test
        void passesAPlanWithNothingLeftOver() {
            Plan plan = Schedule.schedule(LOOMS, List.of(SMALL));
            assertThat(Schedule.assertFullyPlaced(plan)).isSameAs(plan);
        }

        @Test
        void refusesOneWithWorkThatHasNowhereToGo() {
            Plan plan = Schedule.schedule(LOOMS, List.of(huge("SO-1")));
            assertThatThrownBy(() -> Schedule.assertFullyPlaced(plan)).isInstanceOf(CapacityException.class);
        }
    }

    @Nested
    class FinishOfAndLateOrders {

        @Test
        void saysWhenAnOrderFinishesAndNullWhenItWasNeverPlaced() {
            Plan plan = Schedule.schedule(LOOMS, List.of(SMALL));
            assertThat(Schedule.finishOf(plan, "SO-1").getAsDouble()).isGreaterThan(0);
            assertThat(Schedule.finishOf(plan, "SO-9")).isEmpty();
        }

        @Test
        void findsTheOrdersThatWillMissTheirDateWorstFirst() {
            Plan plan = Schedule.schedule(List.of(NARROW), List.of(SMALL, small("order", "SO-3")));
            List<Schedule.Late> late = Schedule.lateOrders(plan, Json.obj("SO-1", 1, "SO-3", 100));
            assertThat(late).extracting(Schedule.Late::order).containsExactly("SO-1");
            assertThat(late.get(0).byHours()).isGreaterThan(0);
        }

        @Test
        void ignoresAnOrderWithNoDateAgainstIt() {
            assertThat(Schedule.lateOrders(Schedule.schedule(LOOMS, List.of(SMALL)), Json.obj()))
                    .isEmpty();
        }
    }

    @Nested
    class UnweavableRunsAndAssertRunComplete {

        @Test
        void namesTheRunsNoLoomCanTake() {
            assertThat(Schedule.unweavableRuns(LOOMS, List.of(SMALL, huge("SO-8"))))
                    .containsExactly("SO-8");
        }

        @Test
        void refusesARunWithEmptyRequirements() {
            Run bare = small("requirements", Json.obj());
            assertThatThrownBy(() -> Schedule.assertRunComplete(bare))
                    .hasMessageContaining("says nothing about what the loom");
            assertThat(Schedule.assertRunComplete(SMALL)).isSameAs(SMALL);
        }
    }

    @Nested
    class WarpChanges {

        private final Run sheeting = Schedule.createRun(Json.obj(
                "order",
                "SO-0",
                "style",
                "sheeting",
                "metres",
                50,
                "picksPerCm",
                20,
                "priority",
                1,
                "requirements",
                Registry.styleRequirements(Json.obj("widthCm", 250, "shafts", 4))));

        private final double weaving = Schedule.runHoursOn(NARROW, SMALL);

        private final double sheetingHours = Schedule.runHoursOn(WIDE, sheeting);

        private final List<Run> runs = List.of(
                sheeting, small("order", "SO-1"), small("order", "SO-2"), small("order", "SO-3", "style", "twill"));

        private Placement placementOf(Plan plan, String order) {
            return plan.placed().stream()
                    .filter(entry -> entry.order().equals(order))
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        void costsNothingWhenNoChangeHoursAreGiven() {
            Plan plan = Schedule.schedule(LOOMS, runs);
            assertThat(plan.placed()).allMatch(entry -> entry.changeHours() == 0);
            assertThat(placementOf(plan, "SO-2").loom()).isEqualTo("W1");
            assertThat(placementOf(plan, "SO-3").loom()).isEqualTo("N1");
        }

        @Test
        void keepsAStyleOnTheLoomAlreadyGaitedForIt() {
            Placement second = placementOf(Schedule.schedule(LOOMS, runs, Schedule.EFFICIENCY, 0, 30), "SO-2");
            assertThat(second.loom()).isEqualTo("N1");
            assertThat(second.changeHours()).isEqualTo(0);
            assertThat(second.fromHour()).isCloseTo(30 + weaving, within(0.001));
        }

        @Test
        void chargesTheChangeToTheLoomThatTakesANewStyle() {
            Plan plan = Schedule.schedule(LOOMS, runs, Schedule.EFFICIENCY, 0, 30);
            Placement twill = placementOf(plan, "SO-3");
            assertThat(twill.loom()).isEqualTo("W1");
            assertThat(twill.changeHours()).isEqualTo(30);
            assertThat(twill.fromHour()).isCloseTo(30 + sheetingHours, within(0.001));
            assertThat(twill.toHour() - twill.fromHour()).isCloseTo(30 + twill.hours(), within(0.001));
            assertThat(plan.finishesAt()).isCloseTo(twill.toHour(), within(0.001));
        }

        @Test
        void countsAWarpChangeAsBusyTime() {
            Plan plan = Schedule.schedule(LOOMS, runs, Schedule.EFFICIENCY, 0, 30);
            Schedule.Load wide = Schedule.loomLoad(plan).stream()
                    .filter(load -> load.loom().equals("W1"))
                    .findFirst()
                    .orElseThrow();
            assertThat(wide.hours())
                    .isCloseTo(
                            30 + sheetingHours + 30 + placementOf(plan, "SO-3").hours(), within(0.001));
        }

        @Test
        void readsTheChangeHoursFromTheRequestOptions() {
            assertThat(Schedule.schedule(LOOMS, runs, Json.obj("changeHours", 30)))
                    .isEqualTo(Schedule.schedule(LOOMS, runs, Schedule.EFFICIENCY, 0, 30));
            assertThatThrownBy(() -> Schedule.schedule(LOOMS, runs, Json.obj("changeHours", -1)))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }
}
