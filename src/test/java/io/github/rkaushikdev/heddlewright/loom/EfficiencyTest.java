package io.github.rkaushikdev.heddlewright.loom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EfficiencyTest {

    private static final List<JsonObject> SHIFT = List.of(
            Json.obj("cause", "warp break", "count", 12, "minutesEach", 1.5, "waitEach", 0.5),
            Json.obj("cause", "weft break", "count", 20, "minutesEach", 0.5, "waitEach", 0.2),
            Json.obj("cause", "beam change", "count", 1, "minutesEach", 25, "waitEach", 5));

    @Nested
    class StopCauses {

        @Test
        void namesTheCausesAShedBooksTimeAgainst() {
            assertThat(Efficiency.STOP_CAUSES).contains("warp break");
            assertThat(Efficiency.STOP_CAUSES).contains("no weaver");
            assertThat(new HashSet<>(Efficiency.STOP_CAUSES)).hasSameSizeAs(Efficiency.STOP_CAUSES);
        }
    }

    @Nested
    class NormaliseStop {

        @Test
        void fillsInTheCountsARecordLeavesOut() {
            assertThat(Efficiency.normaliseStop(Json.obj("cause", "mechanical")))
                    .isEqualTo(new Efficiency.Stop("mechanical", 1, 0, 0, 0, Efficiency.StopClass.MECHANICAL));
        }

        @Test
        void addsTheWaitToTheMendForTheTimeLost() {
            assertThat(Efficiency.normaliseStop(
                                    Json.obj("cause", "warp break", "count", 2, "minutesEach", 1, "waitEach", 3))
                            .minutesLost())
                    .isEqualTo(8);
        }

        @Test
        void refusesARecordWithNoCauseAndANegativeCount() {
            assertThatThrownBy(() -> Efficiency.normaliseStop(Json.obj())).isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Efficiency.normaliseStop(Json.obj("cause", "x", "count", -1)))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class LostMinutes {

        @Test
        void splitsMendingFromWaiting() {
            Efficiency.Lost lost = Efficiency.lostMinutes(SHIFT);
            assertThat(lost.mending()).isEqualTo(12 * 1.5 + 20 * 0.5 + 25);
            assertThat(lost.waiting()).isEqualTo(12 * 0.5 + 20 * 0.2 + 5);
            assertThat(lost.total()).isEqualTo(lost.mending() + lost.waiting());
        }

        @Test
        void isNothingForAShiftWithNoStops() {
            assertThat(Efficiency.lostMinutes(List.of())).isEqualTo(new Efficiency.Lost(0, 0, 0));
        }
    }

    @Nested
    class EfficiencyOverAShift {

        @Test
        void isTheRunningShareOfTheShift() {
            assertThat(Efficiency.efficiency(480, SHIFT))
                    .isCloseTo((480 - Efficiency.lostMinutes(SHIFT).total()) / 480, within(0.00005));
        }

        @Test
        void isOneWhenNothingStops() {
            assertThat(Efficiency.efficiency(480, List.of())).isEqualTo(1);
        }

        @Test
        void refusesStopsAddingUpToMoreThanTheShift() {
            assertThatThrownBy(() ->
                            Efficiency.efficiency(60, List.of(Json.obj("cause", "mechanical", "minutesEach", 120))))
                    .hasMessageContaining("more than the shift");
        }
    }

    @Nested
    class LossByCause {

        @Test
        void ranksTheCausesByTimeLost() {
            List<JsonObject> ranked = Efficiency.lossByCause(SHIFT);
            assertThat(ranked.get(0).get("cause")).isEqualTo(Json.of("beam change"));
            assertThat(ranked.get(0).get("minutesLost").asDouble()).isEqualTo(30);
        }

        @Test
        void addsUpRepeatedRecordsForTheSameCause() {
            List<JsonObject> ranked = Efficiency.lossByCause(List.of(
                    Json.obj("cause", "warp break", "count", 2, "minutesEach", 1),
                    Json.obj("cause", "warp break", "count", 3, "minutesEach", 1)));
            assertThat(ranked).hasSize(1);
            assertThat(ranked.get(0).get("count").asDouble()).isEqualTo(5);
            assertThat(ranked.get(0).get("minutesLost").asDouble()).isEqualTo(5);
        }

        @Test
        void separatesAFrequentCheapCauseFromARareExpensiveOne() {
            List<JsonObject> ranked = Efficiency.lossByCause(SHIFT);
            JsonObject weft = ranked.stream()
                    .filter(entry -> entry.get("cause").equals(Json.of("weft break")))
                    .findFirst()
                    .orElseThrow();
            assertThat(weft.get("count").asDouble()).isEqualTo(20);
            assertThat(weft.get("minutesLost").asDouble())
                    .isLessThan(ranked.get(0).get("minutesLost").asDouble());
        }
    }

    @Nested
    class BreakRate {

        @Test
        void isQuotedPerHundredThousandPicks() {
            assertThat(Efficiency.breakRate(12, 240000)).isEqualTo(5);
        }

        @Test
        void takesADifferentBase() {
            assertThat(Efficiency.breakRate(12, 240000, 1000000)).isEqualTo(50);
        }

        @Test
        void isNothingWhenNothingBroke() {
            assertThat(Efficiency.breakRate(0, 240000)).isEqualTo(0);
        }
    }

    @Nested
    class EfficiencyFromStopRate {

        @Test
        void takesTheStopTimeOutOfTheHour() {
            assertThat(Efficiency.efficiencyFromStopRate(6, 1)).isEqualTo(0.9);
            assertThat(Efficiency.efficiencyFromStopRate(0, 1)).isEqualTo(1);
        }

        @Test
        void bottomsOutAtNothingRatherThanGoingNegative() {
            assertThat(Efficiency.efficiencyFromStopRate(100, 1)).isEqualTo(0);
        }
    }

    @Nested
    class StopSensitivity {

        @Test
        void saysWhatOneStopAnHourIsWorth() {
            assertThat(Efficiency.stopSensitivity(1.2)).isEqualTo(0.02);
        }

        @Test
        void agreesWithTheStopRateReading() {
            double base = Efficiency.efficiencyFromStopRate(5, 1.2);
            double worse = Efficiency.efficiencyFromStopRate(6, 1.2);
            assertThat(base - worse).isCloseTo(Efficiency.stopSensitivity(1.2), within(0.00005));
        }
    }

    @Nested
    class ShiftReport {

        @Test
        void gathersTheWholeShiftIntoOneRecord() {
            Efficiency.ShiftReport report = Efficiency.shiftReport(480, SHIFT, OptionalDouble.of(200));
            assertThat(report.efficiencyPercent()).isCloseTo(report.efficiency() * 100, within(0.005));
            assertThat(report.byCause().get(0).get("cause")).isEqualTo(Json.of("beam change"));
            assertThat(report.picksWoven()).isEqualTo(OptionalLong.of(Math.round(480 * report.efficiency() * 200)));
        }

        @Test
        void leavesThePickCountOutWhenNoSpeedIsGiven() {
            assertThat(Efficiency.shiftReport(480, SHIFT, OptionalDouble.empty())
                            .picksWoven())
                    .isEmpty();
        }
    }

    @Nested
    class StopClasses {

        @Test
        void classesEveryCauseTheBookKnows() {
            assertThat(Efficiency.classOf("warp break")).isEqualTo(Efficiency.StopClass.WARP);
            assertThat(Efficiency.classOf("shuttle change")).isEqualTo(Efficiency.StopClass.WEFT);
            assertThat(Efficiency.classOf("beam change")).isEqualTo(Efficiency.StopClass.ORGANISATION);
            assertThat(Efficiency.STOP_CAUSES)
                    .allMatch(cause -> Efficiency.classOf(cause) != Efficiency.StopClass.OTHER);
        }

        @Test
        void booksACauseItDoesNotKnowAsOther() {
            assertThat(Efficiency.classOf("lightning")).isEqualTo(Efficiency.StopClass.OTHER);
            assertThat(Efficiency.normaliseStop(Json.obj("cause", "lightning")).stopClass())
                    .isEqualTo(Efficiency.StopClass.OTHER);
            assertThatThrownBy(() -> Efficiency.classOf("")).isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void addsTheLossesUpByClassWorstFirst() {
            List<JsonObject> ranked = Efficiency.lossByClass(List.of(
                    Json.obj("cause", "weft break", "count", 10, "minutesEach", 1),
                    Json.obj("cause", "shuttle change", "count", 4, "minutesEach", 2),
                    Json.obj("cause", "mechanical", "count", 1, "minutesEach", 15)));
            assertThat(ranked)
                    .extracting(row -> row.get("class"))
                    .containsExactly(Json.of("weft"), Json.of("mechanical"));
            assertThat(ranked.get(0).get("minutesLost")).isEqualTo(Json.of(18));
            assertThat(ranked.get(0).get("count")).isEqualTo(Json.of(14));
        }

        @Test
        void putsTheClassesInTheShiftReport() {
            Efficiency.ShiftReport report = Efficiency.shiftReport(480, SHIFT, OptionalDouble.empty());
            assertThat(report.byClass())
                    .extracting(row -> row.get("class"))
                    .containsExactly(Json.of("organisation"), Json.of("warp"), Json.of("weft"));
        }

        @Test
        void splitsTheLostMinutesTheSameWayItAlwaysHas() {
            Efficiency.Lost lost = Efficiency.lostMinutes(SHIFT);
            assertThat(lost.mending()).isEqualTo(12 * 1.5 + 20 * 0.5 + 25);
            assertThat(lost.waiting()).isEqualTo(12 * 0.5 + 20 * 0.2 + 5);
            assertThat(lost.total()).isEqualTo(lost.mending() + lost.waiting());
        }
    }

    @Nested
    class AgainstCounter {

        @Test
        void findsTheMinutesTheStopBookDoesNotAccountFor() {
            Efficiency.Counter counter = Efficiency.againstCounter(480, SHIFT, 480 * 200 * 0.8, 200);
            double book = Efficiency.efficiency(480, SHIFT);
            assertThat(counter.counterEfficiency()).isEqualTo(0.8);
            assertThat(counter.bookEfficiency()).isEqualTo(book);
            assertThat(counter.unbookedMinutes()).isCloseTo((book - 0.8) * 480, within(0.01));
            assertThat(counter.unbookedMinutes()).isGreaterThan(0);
        }

        @Test
        void readsABookThatClaimsTooMuchAsNegative() {
            Efficiency.Counter counter = Efficiency.againstCounter(480, SHIFT, 480 * 200, 200);
            assertThat(counter.counterEfficiency()).isEqualTo(1);
            assertThat(counter.unbookedMinutes()).isLessThan(0);
        }

        @Test
        void refusesACounterThatRanFasterThanTheLoom() {
            assertThatThrownBy(() -> Efficiency.againstCounter(480, SHIFT, 480 * 200 + 1, 200))
                    .isInstanceOf(InvalidArgumentException.class)
                    .hasMessageContaining("more picks");
            assertThatThrownBy(() -> Efficiency.againstCounter(480, SHIFT, -1, 200))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }
}
