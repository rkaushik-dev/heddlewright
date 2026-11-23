package io.github.rkaushikdev.heddlewright.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.heddlewright.util.DraftException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonArray;
import io.github.rkaushikdev.heddlewright.weave.Twill;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WifTest {

    private static final String TWILL_FILE = String.join(
            "\n",
            "[WIF]",
            "Version=1.1",
            "Source Program=heddlewright",
            "",
            "[CONTENTS]",
            "WEAVING=yes",
            "WARP=yes",
            "WEFT=yes",
            "THREADING=yes",
            "TIEUP=yes",
            "TREADLING=yes",
            "COLOR PALETTE=no",
            "",
            "[TEXT]",
            "Title=Four shaft twill",
            "Author=A Weaver",
            "",
            "[WEAVING]",
            "Shafts=4",
            "Treadles=4",
            "Rising Shed=yes",
            "",
            "[WARP]",
            "Threads=4",
            "",
            "[WEFT]",
            "Threads=4",
            "",
            "[THREADING]",
            "1=1",
            "2=2",
            "3=3",
            "4=4",
            "",
            "[TIEUP]",
            "1=1,2",
            "2=2,3",
            "3=3,4",
            "4=1,4",
            "",
            "[TREADLING]",
            "1=1",
            "2=2",
            "3=3",
            "4=4");

    @Nested
    class ReadWif {

        @Test
        void readsTheHeaderAndTheFourNumberedSections() {
            Wif.Read read = Wif.readWif(TWILL_FILE);
            assertThat(read.version()).isEqualTo(Wif.WIF_VERSION);
            assertThat(read.shafts()).isEqualTo(4);
            assertThat(read.treadles()).hasValue(4);
            assertThat(read.title()).isEqualTo("Four shaft twill");
            assertThat(read.threading()).containsExactly(1, 2, 3, 4);
            assertThat(read.tieup()).isEqualTo(List.of(List.of(1, 2), List.of(2, 3), List.of(3, 4), List.of(1, 4)));
        }

        @Test
        void readsTheShedDirection() {
            assertThat(Wif.readWif(TWILL_FILE).risingShed()).isTrue();
            assertThat(Wif.readWif(TWILL_FILE.replace("Rising Shed=yes", "Rising Shed=no"))
                            .risingShed())
                    .isFalse();
        }

        @Test
        void refusesAFileWithNoWifSection() {
            assertThatThrownBy(() -> Wif.readWif("[WEAVING]\nShafts=4\n")).hasMessageContaining("not a weaving file");
        }

        @Test
        void refusesAFileThatNeverSaysHowManyShafts() {
            assertThatThrownBy(() -> Wif.readWif("[WIF]\nVersion=1.1\n")).hasMessageContaining("how many shafts");
        }
    }

    @Nested
    class DraftFromWif {

        @Test
        void buildsADraftThatWeavesTheTwillTheFileDescribes() {
            Draft draft = Wif.draftFromWif(TWILL_FILE);
            assertThat(draft.shafts()).isEqualTo(4);
            assertThat(Drawdown.drawdownFromDraft(draft)).isEqualTo(Twill.twillWeave("2/2"));
        }

        @Test
        void takesTheTitleAsTheNameAndTheAuthorAsANote() {
            Draft draft = Wif.draftFromWif(TWILL_FILE);
            assertThat(draft.name()).isEqualTo("Four shaft twill");
            assertThat(draft.notes()).isEqualTo("after A Weaver");
        }

        @Test
        void letsTheCallerOverrideTheName() {
            assertThat(Wif.draftFromWif(TWILL_FILE, "mine").name()).isEqualTo("mine");
        }

        @Test
        void refusesAThreadingWithAGapInItNamingTheEnds() {
            String gappy = TWILL_FILE.replace("3=3\n", "");
            DraftException err = catchThrowableOfType(DraftException.class, () -> Wif.draftFromWif(gappy));
            assertThat(err).isNotNull();
            assertThat(((JsonArray) err.detail().get("ends")).items()).contains(Json.of(3));
        }

        @Test
        void refusesAFileWithNeitherATieupNorALiftplan() {
            String bare = TWILL_FILE.split("\\[TIEUP\\]")[0];
            assertThatThrownBy(() -> Wif.draftFromWif(bare)).hasMessageContaining("neither a tie-up nor a liftplan");
        }
    }

    @Nested
    class ADobbyFileWithALiftplan {

        private final String dobbyFile = String.join(
                "\n",
                "[WIF]",
                "Version=1.1",
                "[WEAVING]",
                "Shafts=4",
                "Rising Shed=yes",
                "[WARP]",
                "Threads=4",
                "[WEFT]",
                "Threads=4",
                "[THREADING]",
                "1=1",
                "2=2",
                "3=3",
                "4=4",
                "[LIFTPLAN]",
                "1=1,2",
                "2=2,3",
                "3=3,4",
                "4=1,4");

        @Test
        void givesEachDistinctLiftItsOwnTreadle() {
            Draft draft = Wif.draftFromWif(dobbyFile);
            assertThat(draft.treadles()).isEqualTo(4);
            assertThat(draft.tieup().get(0)).containsExactly(1, 2);
        }

        @Test
        void weavesTheSameClothAsTheTreadleFile() {
            assertThat(Drawdown.drawdownFromDraft(Wif.draftFromWif(dobbyFile)))
                    .isEqualTo(Drawdown.drawdownFromDraft(Wif.draftFromWif(TWILL_FILE)));
        }

        @Test
        void collapsesARepeatedLiftOntoOneTreadle() {
            String repeated = dobbyFile.replace("3=3,4", "3=1,2").replace("4=1,4", "4=2,3");
            assertThat(Wif.draftFromWif(repeated).treadles()).isEqualTo(2);
        }
    }

    @Nested
    class WifContents {

        @Test
        void listsOnlyTheSectionsTheFileSaysItCarries() {
            List<String> carried = Wif.wifContents(TWILL_FILE);
            assertThat(carried).contains("THREADING");
            assertThat(carried).doesNotContain("COLOR PALETTE");
        }

        @Test
        void isEmptyWhenThereIsNoContentsSection() {
            assertThat(Wif.wifContents("[WIF]\nVersion=1.1\n")).isEmpty();
        }
    }

    @Nested
    class DeclaresAndCarries {

        @Test
        void isTrueWhenTheFileBothPromisesAndHoldsASection() {
            assertThat(Wif.declaresAndCarries(TWILL_FILE, "THREADING")).isTrue();
        }

        @Test
        void isFalseWhenAPromisedSectionIsMissing() {
            String lying = TWILL_FILE.replace("COLOR PALETTE=no", "COLOR PALETTE=yes");
            assertThat(Wif.declaresAndCarries(lying, "COLOR PALETTE")).isFalse();
        }

        @Test
        void isFalseWhenASectionIsThereButNotDeclared() {
            String quiet = TWILL_FILE.replace("TIEUP=yes", "TIEUP=no");
            assertThat(Wif.declaresAndCarries(quiet, "TIEUP")).isFalse();
        }
    }

    @Nested
    class ReadColours {

        private final String file = String.join(
                "\n",
                "[WIF]",
                "Version=1.1",
                "[COLOR PALETTE]",
                "Entries=2",
                "Range=0,999",
                "[COLOR TABLE]",
                "1=999,0,0",
                "2=0,500,999",
                "[WARP]",
                "Threads=4",
                "Color=2",
                "[WARP COLORS]",
                "1=1",
                "[WEFT]",
                "Threads=2",
                "[WEFT COLORS]",
                "1=2",
                "2=1",
                "");

        @Test
        void scalesThePaletteFromTheRangeTheFileWasWrittenIn() {
            assertThat(Wif.readColours(file).palette()).containsExactly("#ff0000", "#0080ff");
        }

        @Test
        void fillsAThreadWithNoColourOfItsOwnFromTheDefault() {
            assertThat(Wif.readColours(file).warp()).containsExactly("#ff0000", "#0080ff", "#0080ff", "#0080ff");
            assertThat(Wif.readColours(file).weft()).containsExactly("#0080ff", "#ff0000");
        }

        @Test
        void readsNothingFromAFileWithNoColourTable() {
            assertThat(Wif.readColours("[WIF]\nVersion=1.1\n"))
                    .isEqualTo(new Wif.Colours(List.of(), List.of(), List.of()));
        }

        @Test
        void refusesAThreadPointingPastThePaletteOrAColourThatIsNotThreeNumbers() {
            assertThatThrownBy(() -> Wif.readColours(file.replace("2=1\n", "2=5\n")))
                    .isInstanceOf(DraftException.class)
                    .hasMessageContaining("weft thread 2");
            assertThatThrownBy(() -> Wif.readColours(file.replace("1=999,0,0", "1=999,0")))
                    .isInstanceOf(DraftException.class);
            assertThatThrownBy(() -> Wif.readColours(file.replace("Color=2\n", "")))
                    .isInstanceOf(DraftException.class)
                    .hasMessageContaining("warp thread 2");
        }
    }
}
