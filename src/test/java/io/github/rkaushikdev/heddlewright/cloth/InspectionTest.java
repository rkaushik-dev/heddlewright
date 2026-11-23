package io.github.rkaushikdev.heddlewright.cloth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.heddlewright.cloth.FourPoint.Kind;
import io.github.rkaushikdev.heddlewright.cloth.Inspection.Defect;
import io.github.rkaushikdev.heddlewright.cloth.Inspection.Fault;
import io.github.rkaushikdev.heddlewright.cloth.Inspection.Origin;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.NotFoundException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InspectionTest {

    private static final List<Defect> SHEET = Inspection.recordSheet(List.of(
            Json.obj("code", "weft_bar", "yard", 3, "inches", 12),
            Json.obj("code", "weft_bar", "yard", 9, "inches", 8),
            Json.obj("code", "reed_mark", "yard", 12, "inches", 4),
            Json.obj("code", "slub", "yard", 20, "inches", 1),
            Json.obj("code", "hole", "yard", 40, "inches", 0.5)));

    @Nested
    class ListFaults {

        @Test
        void carriesACatalogueInCodeOrder() {
            List<String> codes =
                    Inspection.listFaults().stream().map(Fault::code).toList();
            assertThat(codes).isSorted();
            assertThat(codes).contains("reed_mark");
        }

        @Test
        void givesEveryFaultAnOriginTheCatalogueRecognises() {
            for (Fault fault : Inspection.listFaults()) {
                assertThat(Inspection.FAULT_ORIGINS).contains(fault.origin());
            }
        }

        @Test
        void handsBackFrozenRecords() {
            // A record has no setters, so nothing can change a catalogue entry.
            assertThat(Inspection.listFaults().get(0).getClass().isRecord()).isTrue();
        }
    }

    @Nested
    class FaultByCode {

        @Test
        void findsAFaultAndReportsWhereItComesFrom() {
            Fault fault = Inspection.faultByCode("reed_mark");
            assertThat(fault.origin()).isEqualTo(Origin.LOOM);
            assertThat(fault.kind()).isEqualTo(Kind.LENGTH);
        }

        @Test
        void saysPlainlyWhenThereIsNoSuchFault() {
            assertThatThrownBy(() -> Inspection.faultByCode("gremlins")).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class FaultsByOrigin {

        @Test
        void gathersTheFaultsAWarpIsToBlameFor() {
            List<String> codes =
                    Inspection.faultsByOrigin("warp").stream().map(Fault::code).toList();
            assertThat(codes).contains("missing_end");
            assertThat(codes).contains("tight_end");
            assertThat(codes).doesNotContain("weft_bar");
        }

        @Test
        void refusesAnOriginItDoesNotKnow() {
            assertThatThrownBy(() -> Inspection.faultsByOrigin("gremlins"))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class RecordDefect {

        @Test
        void fillsTheOriginAndKindInFromTheCatalogue() {
            Defect record = Inspection.recordDefect(Json.obj("code", "weft_bar", "yard", 4, "inches", 12));
            assertThat(record.origin()).isEqualTo(Origin.WEFT);
            assertThat(record.kind()).isEqualTo(Kind.LENGTH);
            assertThat(record.points()).isEqualTo(4);
        }

        @Test
        void scoresAHoleOnTheHoleRuleNotTheLengthRule() {
            assertThat(Inspection.recordDefect(Json.obj("code", "hole", "yard", 1, "inches", 0.5))
                            .points())
                    .isEqualTo(2);
        }

        @Test
        void keepsANoteWhenTheGraderWritesOne() {
            assertThat(Inspection.recordDefect(Json.obj("code", "slub", "yard", 1, "inches", 1, "note", "left edge"))
                            .note())
                    .isEqualTo("left edge");
        }

        @Test
        void refusesADefectWithNoLengthOrAnUnknownCode() {
            assertThatThrownBy(() -> Inspection.recordDefect(Json.obj("code", "slub", "yard", 1, "inches", 0)))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Inspection.recordDefect(Json.obj("code", "nope", "yard", 1, "inches", 1)))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ByFault {

        @Test
        void ranksFaultsByThePointsTheyCost() {
            assertThat(Inspection.byFault(SHEET).get(0))
                    .isEqualTo(Json.obj("code", "weft_bar", "count", 2, "points", 7));
        }

        @Test
        void countsEveryDefectExactlyOnce() {
            int total = Inspection.byFault(SHEET).stream()
                    .mapToInt(entry -> entry.get("count").asInt())
                    .sum();
            assertThat(total).isEqualTo(SHEET.size());
        }
    }

    @Nested
    class ByOrigin {

        @Test
        void gathersTheLoomFaultsTogether() {
            JsonObject loom = Inspection.byOrigin(SHEET).stream()
                    .filter(entry -> entry.get("origin").equals(Json.of("loom")))
                    .findFirst()
                    .orElseThrow();
            assertThat(loom.get("count").asInt()).isEqualTo(2);
            assertThat(loom.get("points").asDouble()).isEqualTo(2 + 2);
        }

        @Test
        void isEmptyForAnEmptySheet() {
            assertThat(Inspection.byOrigin(List.of())).isEmpty();
        }
    }

    @Nested
    class DominantOrigin {

        @Test
        void namesWhereMostOfTheTroubleComesFrom() {
            assertThat(Inspection.dominantOrigin(SHEET)).contains(Origin.WEFT);
        }

        @Test
        void isNullWhenNothingIsBooked() {
            assertThat(Inspection.dominantOrigin(List.of())).isEmpty();
        }
    }

    @Nested
    class DefectDensity {

        @Test
        void countsDefectsPerHundredSquareYards() {
            assertThat(Inspection.defectDensity(SHEET, 60, 100)).isEqualTo(3);
        }

        @Test
        void isNothingForACleanRoll() {
            assertThat(Inspection.defectDensity(List.of(), 60, 100)).isEqualTo(0);
        }
    }

    @Nested
    class InspectionSheet {

        @Test
        void gathersTheWholeReadingIntoOneRecord() {
            Inspection.Sheet sheet = Inspection.inspectionSheet(SHEET, 60, 100);
            assertThat(sheet.defects()).isEqualTo(5);
            assertThat(sheet.points())
                    .isEqualTo(SHEET.stream().mapToInt(Defect::points).sum());
            assertThat(sheet.dominantOrigin()).contains(Origin.WEFT);
            assertThat(sheet.worstDefect().orElseThrow().yard()).isEqualTo(3);
        }

        @Test
        void reportsNoWorstDefectOnACleanRoll() {
            Inspection.Sheet sheet = Inspection.inspectionSheet(List.of(), 60, 100);
            assertThat(sheet.worstDefect()).isEmpty();
            assertThat(Json.of(sheet).get("worstDefect")).isEqualTo(Json.NULL);
            assertThat(sheet.points()).isEqualTo(0);
        }
    }

    @Nested
    class RunningFaults {

        @Test
        void booksAReedMarkThatRunsThroughSeveralYards() {
            Defect mark = Inspection.recordDefect(Json.obj("code", "reed_mark", "yard", 10, "through", 12.5));
            assertThat(mark.through()).hasValue(12.5);
            assertThat(mark.inches()).isEqualTo(90);
            assertThat(mark.points()).isEqualTo(16);
        }

        @Test
        void keepsALengthTheGraderMeasured() {
            Defect mark =
                    Inspection.recordDefect(Json.obj("code", "weft_bar", "yard", 10, "through", 11, "inches", 30));
            assertThat(mark.inches()).isEqualTo(30);
            assertThat(mark.points()).isEqualTo(8);
        }

        @Test
        void leavesAnOrdinaryFaultWithNoRun() {
            Defect slub = Inspection.recordDefect(Json.obj("code", "slub", "yard", 4, "inches", 2));
            assertThat(slub.through()).isEmpty();
            assertThat(Json.of(slub).get("through").isMissing()).isTrue();
        }

        @Test
        void refusesARunThatEndsBeforeItStartsOrAHoleThatRuns() {
            assertThatThrownBy(() -> Inspection.recordDefect(Json.obj("code", "reed_mark", "yard", 10, "through", 9)))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Inspection.recordDefect(Json.obj("code", "hole", "yard", 1, "through", 2)))
                    .isInstanceOf(InvalidArgumentException.class)
                    .hasMessageContaining("hole cannot run");
        }

        @Test
        void putsTheRunAtTheTopOfTheSheet() {
            List<Defect> sheet = Inspection.recordSheet(List.of(
                    Json.obj("code", "slub", "yard", 1, "inches", 2),
                    Json.obj("code", "reed_mark", "yard", 20, "through", 23)));
            Inspection.Sheet printed = Inspection.inspectionSheet(sheet, 60, 100);
            assertThat(printed.points()).isEqualTo(1 + 16);
            assertThat(printed.worstDefect())
                    .hasValueSatisfying(worst -> assertThat(worst.code()).isEqualTo("reed_mark"));
            assertThat(printed.dominantOrigin()).contains(Inspection.Origin.LOOM);
        }
    }
}
