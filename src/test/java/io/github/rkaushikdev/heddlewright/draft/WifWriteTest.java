package io.github.rkaushikdev.heddlewright.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.heddlewright.Fixtures;
import io.github.rkaushikdev.heddlewright.util.DraftException;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Matrix;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WifWriteTest {

    private static final Draft DRAFT = Fixtures.twillDraft();

    @Nested
    class WriteWif {

        @Test
        void writesAFileTheReaderTakesBackToTheSameCloth() {
            Draft back = Wif.draftFromWif(WifWrite.writeWif(DRAFT));
            assertThat(Drawdown.drawdownFromDraft(back).format())
                    .isEqualTo(Drawdown.drawdownFromDraft(DRAFT).format());
            assertThat(back.id()).isEqualTo(DRAFT.id());
        }

        @Test
        void keepsTheNameAndTheCounts() {
            Wif.Read read = Wif.readWif(WifWrite.writeWif(DRAFT));
            assertThat(read.title()).isEqualTo("four shaft twill");
            assertThat(read.shafts()).isEqualTo(4);
            assertThat(read.warpThreads()).isEqualTo(8);
            assertThat(read.weftThreads()).isEqualTo(8);
        }

        @Test
        void writesAnAuthorWhenOneIsGiven() {
            String text = WifWrite.writeWif(DRAFT, WifWrite.Options.DEFAULT.author("A Weaver"));
            assertThat(Wif.readWif(text).author()).isEqualTo("A Weaver");
        }

        @Test
        void recordsASinkingShedAsSuch() {
            Draft sinking = Draft.create(DRAFT.toRecord().with("shed", "sinking"));
            assertThat(Wif.readWif(WifWrite.writeWif(sinking)).risingShed()).isFalse();
        }

        @Test
        void declaresEverySectionItActuallyWrites() {
            String text = WifWrite.writeWif(DRAFT);
            for (String name : List.of("TEXT", "WEAVING", "WARP", "WEFT", "THREADING", "TIEUP", "TREADLING")) {
                assertThat(Wif.declaresAndCarries(text, name)).isTrue();
            }
        }

        @Test
        void endsWithANewlineAndHasNoBlankLineInsideASection() {
            String text = WifWrite.writeWif(DRAFT);
            assertThat(text).endsWith("\n");
            assertThat(text).doesNotContain("\n\n\n");
        }

        @Test
        void refusesSomethingThatIsNotADraft() {
            assertThatThrownBy(() -> WifWrite.writeWif(null)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class WriteDobbyWif {

        private final Matrix liftplan = Liftplan.liftplanFromTreadling(DRAFT.tieup(), DRAFT.treadling(), 4, 4);

        @Test
        void writesALiftplanInsteadOfATieupAndTreadling() {
            Map<String, Map<String, String>> sections = Ini.parseIni(WifWrite.writeDobbyWif(DRAFT, liftplan));
            assertThat(sections).containsKey("LIFTPLAN");
            assertThat(sections).doesNotContainKey("TIEUP");
            assertThat(sections).doesNotContainKey("TREADLING");
        }

        @Test
        void declaresTheLiftplanInTheContents() {
            assertThat(Wif.declaresAndCarries(WifWrite.writeDobbyWif(DRAFT, liftplan), "LIFTPLAN"))
                    .isTrue();
        }

        @Test
        void readsBackToTheSameCloth() {
            Draft back = Wif.draftFromWif(WifWrite.writeDobbyWif(DRAFT, liftplan));
            assertThat(Drawdown.drawdownFromDraft(back).format())
                    .isEqualTo(Drawdown.drawdownFromDraft(DRAFT).format());
        }

        @Test
        void writesOneLinePerPick() {
            Map<String, Map<String, String>> sections = Ini.parseIni(WifWrite.writeDobbyWif(DRAFT, liftplan));
            assertThat(sections.get("LIFTPLAN")).hasSize(8);
            assertThat(sections.get("LIFTPLAN").get("1")).isEqualTo("1,2");
        }
    }

    @Nested
    class ContentsFor {

        @Test
        void namesTheSectionsADraftWillFill() {
            assertThat(WifWrite.contentsFor(DRAFT))
                    .containsExactly("TEXT", "WEAVING", "WARP", "WEFT", "THREADING", "TIEUP", "TREADLING");
        }

        @Test
        void refusesSomethingThatIsNotADraft() {
            assertThatThrownBy(() -> WifWrite.contentsFor(null)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class Colours {

        private final List<String> warp =
                List.of("#1a2b3c", "#1a2b3c", "#ffffff", "#ffffff", "#1a2b3c", "#1a2b3c", "#ffffff", "#ffffff");

        private final List<String> weft =
                List.of("#FF0000", "#ff0000", "#ff0000", "#ff0000", "#ffffff", "#ffffff", "#ffffff", "#ffffff");

        @Test
        void writesAPaletteAndAnIndexForEveryThread() {
            Map<String, Map<String, String>> sections =
                    Ini.parseIni(WifWrite.writeWif(DRAFT, WifWrite.Options.DEFAULT.colours(warp, weft)));
            assertThat(sections.get("COLOR PALETTE"))
                    .containsEntry("ENTRIES", "3")
                    .containsEntry("RANGE", "0,255");
            assertThat(sections.get("COLOR TABLE"))
                    .containsEntry("1", "26,43,60")
                    .containsEntry("3", "255,0,0");
            assertThat(sections.get("WARP COLORS")).containsEntry("3", "2").hasSize(8);
            assertThat(sections.get("WEFT COLORS")).containsEntry("1", "3").containsEntry("5", "2");
        }

        @Test
        void declaresTheColourSectionsItWrites() {
            String text = WifWrite.writeWif(DRAFT, WifWrite.Options.DEFAULT.colours(warp, weft));
            for (String name : List.of("COLOR PALETTE", "COLOR TABLE", "WARP COLORS", "WEFT COLORS")) {
                assertThat(Wif.declaresAndCarries(text, name)).isTrue();
            }
            assertThat(Wif.declaresAndCarries(WifWrite.writeWif(DRAFT), "COLOR TABLE"))
                    .isFalse();
        }

        @Test
        void readsBackTheColoursItWrote() {
            Wif.Colours back = Wif.readColours(WifWrite.writeWif(DRAFT, WifWrite.Options.DEFAULT.colours(warp, weft)));
            assertThat(back.warp()).isEqualTo(warp);
            assertThat(back.weft().get(0)).isEqualTo("#ff0000");
            assertThat(back.palette()).containsExactly("#1a2b3c", "#ffffff", "#ff0000");
        }

        @Test
        void carriesTheColoursIntoADobbyFileToo() {
            Matrix liftplan = Liftplan.liftplanFromTreadling(DRAFT.tieup(), DRAFT.treadling(), 4, 4);
            String text = WifWrite.writeDobbyWif(DRAFT, liftplan, WifWrite.Options.DEFAULT.colours(warp, weft));
            assertThat(Wif.readColours(text).warp()).isEqualTo(warp);
            assertThat(Wif.declaresAndCarries(text, "LIFTPLAN")).isTrue();
            assertThat(Ini.parseIni(text)).doesNotContainKey("TIEUP");
        }

        @Test
        void refusesAColourListThatMissesAThreadOrAColourItCannotRead() {
            assertThatThrownBy(
                            () -> WifWrite.writeWif(DRAFT, WifWrite.Options.DEFAULT.colours(warp.subList(0, 7), weft)))
                    .isInstanceOf(InvalidArgumentException.class);
            List<String> bad = new java.util.ArrayList<>(warp);
            bad.set(2, "white");
            assertThatThrownBy(() -> WifWrite.writeWif(DRAFT, WifWrite.Options.DEFAULT.colours(bad, weft)))
                    .isInstanceOf(DraftException.class)
                    .hasMessageContaining("#rrggbb");
        }

        @Test
        void takesBlankNotesAsNoNotes() {
            Draft noted = Draft.create(DRAFT.toRecord().with("notes", "from the pattern book"));
            String text = WifWrite.writeWif(noted, WifWrite.Options.DEFAULT.notes("   "));
            assertThat(Ini.parseIni(text).get("TEXT")).containsEntry("NOTES", "from the pattern book");
        }
    }
}
