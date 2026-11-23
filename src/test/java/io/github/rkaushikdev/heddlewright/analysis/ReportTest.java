package io.github.rkaushikdev.heddlewright.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.Fixtures;
import io.github.rkaushikdev.heddlewright.analysis.Interlacement.Firmness;
import io.github.rkaushikdev.heddlewright.analysis.Report.ClothReport;
import io.github.rkaushikdev.heddlewright.analysis.Report.Options;
import io.github.rkaushikdev.heddlewright.analysis.Report.Specification;
import io.github.rkaushikdev.heddlewright.draft.Draft;
import io.github.rkaushikdev.heddlewright.draft.Tieup;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReportTest {

    private static final Draft DRAFT = Fixtures.twill().ends(16).picks(16).draft();

    private static final Construction CONSTRUCTION = Construction.fromRecord(Fixtures.shirtingConstruction());

    private static final Options OPTIONS =
            Options.forCount(30).system("ne").sett(60, 50).construction(CONSTRUCTION);

    private static Draft sparse() {
        return Draft.create(DRAFT.toRecord().with("shafts", 8, "tieup", Tieup.twillTieup(8, 2), "treadles", 8));
    }

    @Nested
    class StructureSection {

        @Test
        void readsTheRepeatOutOfTheClothRatherThanTheDraft() {
            Report.Structure structure = Report.structureSection(DRAFT);
            assertThat(structure.picks()).isEqualTo(16);
            assertThat(structure.repeatPicks()).isEqualTo(4);
            assertThat(structure.repeatEnds()).isEqualTo(4);
            assertThat(structure.repeatShare()).isCloseTo(16.0 / 256, within(0.5e-6));
        }

        @Test
        void readsA22TwillAsBalancedAndMediumFirm() {
            assertThat(Report.structureSection(DRAFT).warpFace()).isEqualTo(0.5);
            assertThat(Report.structureSection(DRAFT).firmness()).isEqualTo(Firmness.MEDIUM);
        }
    }

    @Nested
    class FloatSection {

        @Test
        void measuresTheFloatsAndCountsWhatIsOverTheLimit() {
            Report.FloatSection floats = Report.floatSection(DRAFT);
            assertThat(floats.longestWarp()).isEqualTo(2);
            assertThat(floats.over()).isEqualTo(0);
        }

        @Test
        void readsTheBackOfABalancedTwillTheSameAsTheFace() {
            Report.FloatSection floats = Report.floatSection(DRAFT);
            assertThat(floats.longestBackWarp()).isEqualTo(floats.longestWarp());
            assertThat(floats.longestBackWeft()).isEqualTo(floats.longestWeft());
            assertThat(floats.overOnBack()).isEqualTo(0);
        }

        @Test
        void turnsTheLongFaceFloatsOfAnOpenTwillIntoBackFloatsTheOtherWay() {
            Report.FloatSection floats = Report.floatSection(sparse(), 1);
            assertThat(floats.longestBackWarp()).isEqualTo(floats.longestWeft());
            assertThat(floats.longestBackWeft()).isEqualTo(floats.longestWarp());
            assertThat(floats.overOnBack()).isGreaterThan(0);
            assertThat(Json.of(floats).get("overOnBack")).isEqualTo(Json.of(floats.overOnBack()));
        }

        @Test
        void countsMoreOverATighterLimit() {
            assertThat(Report.floatSection(DRAFT, 1).over()).isGreaterThan(0);
        }
    }

    @Nested
    class SettSection {

        @Test
        void worksTheSettOutFromTheClothAndTheYarn() {
            Report.SettSection sett = Report.settSection(DRAFT, OPTIONS);
            assertThat(sett.diameters()).isGreaterThan(0);
            assertThat(sett.maximumEndsPerInch()).isLessThan(sett.diameters());
            assertThat(sett.settFactor()).isCloseTo(4.0 / 6, within(0.5e-3));
        }

        @Test
        void leavesTheCoverOutWhenNoSettIsGiven() {
            Report.SettSection sett = Report.settSection(DRAFT, Options.forCount(30));
            assertThat(sett.cover()).isEmpty();
            assertThat(sett.jammed()).isEmpty();
            assertThat(sett.toJson().get("jammed")).isEqualTo(Json.NULL);
        }

        @Test
        void refusesAYarnCountOfNothing() {
            assertThatThrownBy(() -> Report.settSection(DRAFT, Options.forCount(0)))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class WeightSection {

        @Test
        void reportsTheWeightBothWaysRound() {
            Report.WeightSection weight = Report.weightSection(CONSTRUCTION);
            assertThat(weight.gsm()).isEqualTo(88);
            assertThat(weight.ouncesPerSquareYard()).isGreaterThan(0);
            assertThat(weight.warpShare()).isCloseTo(48.0 / 88, within(0.5e-4));
        }
    }

    @Nested
    class ClothReportOf {

        @Test
        void gathersEverySection() {
            ClothReport report = Report.clothReport(DRAFT, OPTIONS);
            assertThat(report.name()).isEqualTo("four shaft twill");
            assertThat(report.structure().picks()).isEqualTo(16);
            assertThat(report.floats().longestWarp()).isEqualTo(2);
            assertThat(report.weight().orElseThrow().gsm()).isEqualTo(88);
            assertThat(report.problems()).isEmpty();
        }

        @Test
        void leavesTheWeightOutWhenNoConstructionIsGiven() {
            ClothReport report = Report.clothReport(DRAFT, Options.forCount(30));
            assertThat(report.weight()).isEmpty();
            assertThat(report.toJson().get("weight")).isEqualTo(Json.NULL);
        }

        @Test
        void carriesTheDraftProblemsThrough() {
            assertThat(Report.clothReport(sparse(), Options.forCount(30)).problems())
                    .isNotEmpty();
        }
    }

    @Nested
    class RenderReport {

        @Test
        void printsEverySectionItHas() {
            String text = Report.renderReport(Report.clothReport(DRAFT, OPTIONS));
            assertThat(text).contains("four shaft twill", "Structure", "Floats", "Weight");
        }

        @Test
        void leavesTheWeightBlockOutWhenThereIsNone() {
            String text = Report.renderReport(Report.clothReport(DRAFT, Options.forCount(30)));
            assertThat(text).doesNotContain("Weight").contains("not given");
        }

        @Test
        void printsTheProblemsWhenThereAreAny() {
            assertThat(Report.renderReport(Report.clothReport(sparse(), Options.forCount(30))))
                    .contains("Problems");
        }
    }

    @Nested
    class MeetsSpecification {

        private final ClothReport report = Report.clothReport(DRAFT, OPTIONS);

        @Test
        void passesAClothInsideEveryLimit() {
            Specification limits =
                    Specification.NONE.maxFloat(4).minInterlacement(0.4).maxGsm(200);
            assertThat(Report.meetsSpecification(report, limits).meets()).isTrue();
        }

        @Test
        void namesTheLimitAClothBreaks() {
            assertThat(Report.meetsSpecification(report, Specification.NONE.maxFloat(1))
                            .findings()
                            .get(0)
                            .code())
                    .isEqualTo("float_too_long");
            assertThat(Report.meetsSpecification(report, Specification.NONE.maxGsm(50))
                            .findings()
                            .get(0)
                            .code())
                    .isEqualTo("too_heavy");
            assertThat(Report.meetsSpecification(report, Specification.NONE.minInterlacement(0.9))
                            .findings()
                            .get(0)
                            .code())
                    .isEqualTo("too_open");
        }

        @Test
        void holdsTheBackToItsOwnLimit() {
            ClothReport open = Report.clothReport(sparse(), Options.forCount(30));
            int back = Math.max(open.floats().longestBackWarp(), open.floats().longestBackWeft());
            Report.Verdict verdict = Report.meetsSpecification(open, Specification.NONE.maxBackFloat(back - 1));
            assertThat(verdict.meets()).isFalse();
            assertThat(verdict.findings()).containsExactly(new Report.Finding("back_float_too_long", back, back - 1));
            assertThat(Report.meetsSpecification(open, Specification.NONE.maxBackFloat(back))
                            .meets())
                    .isTrue();
        }

        @Test
        void listsEveryLimitBrokenInTheOrderTheLimitsRun() {
            Specification tight = Specification.NONE
                    .maxGsm(50)
                    .minInterlacement(0.9)
                    .maxBackFloat(1)
                    .maxFloat(1);
            assertThat(Report.meetsSpecification(report, tight).findings())
                    .extracting(Report.Finding::code)
                    .containsExactly("float_too_long", "back_float_too_long", "too_open", "too_heavy");
        }

        @Test
        void skipsTheWeightLimitWhenTheReportHasNoWeight() {
            ClothReport light = Report.clothReport(DRAFT, Options.forCount(30));
            assertThat(Report.meetsSpecification(light, Specification.NONE.maxGsm(1))
                            .meets())
                    .isTrue();
        }

        @Test
        void passesAFigureSittingExactlyOnItsLimit() {
            assertThat(Report.meetsSpecification(
                                    report, Specification.NONE.maxFloat(2).maxBackFloat(2))
                            .meets())
                    .isTrue();
            assertThat(Report.meetsSpecification(
                                    report,
                                    Specification.NONE.minInterlacement(
                                            report.structure().interlacement()))
                            .meets())
                    .isTrue();
        }

        @Test
        void checksNothingWhenNoLimitsAreGiven() {
            assertThat(Report.meetsSpecification(report)).isEqualTo(new Report.Verdict(true, java.util.List.of()));
        }
    }
}
