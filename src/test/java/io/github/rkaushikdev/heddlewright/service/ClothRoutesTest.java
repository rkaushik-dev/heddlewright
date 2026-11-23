package io.github.rkaushikdev.heddlewright.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.service.Router.Request;
import io.github.rkaushikdev.heddlewright.service.Router.Response;
import io.github.rkaushikdev.heddlewright.store.Repository;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonArray;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClothRoutesTest {

    private static final JsonObject PIECE = Json.obj("reference", "P1001", "yards", 100, "widthInches", 60);

    private Router router;
    private Repository repository;

    @BeforeEach
    void mount() {
        repository = Repository.createRepository();
        router = ClothRoutes.mountClothRoutes(Router.createRouter(), repository);
        Router.handle(router, Request.of("POST", "/pieces", PIECE));
    }

    private Response get(String path) {
        return Router.handle(router, Request.get(path));
    }

    private Response get(String path, Map<String, String> query) {
        return Router.handle(router, Request.get(path, query));
    }

    private Response post(String path, Object body) {
        return Router.handle(router, Request.of("POST", path, body));
    }

    private void book(Object... defects) {
        post("/pieces/P1001/defects", Json.obj("defects", List.of(defects)));
    }

    @Nested
    class PostPieces {

        @Test
        void opensAPieceAndAnswersWithIt() {
            Router fresh = ClothRoutes.mountClothRoutes(Router.createRouter(), Repository.createRepository());
            Response response = Router.handle(fresh, Request.of("POST", "/pieces", PIECE));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body().get("squareYards").asDouble()).isCloseTo((100.0 * 60) / 36, within(0.005));
        }

        @Test
        void refusesAPieceWithNoLength() {
            assertThat(post("/pieces", PIECE.with("reference", "P2", "yards", 0))
                            .status())
                    .isEqualTo(400);
        }

        @Test
        void refusesTheSameReferenceTwice() {
            assertThat(post("/pieces", PIECE).status()).isEqualTo(409);
        }
    }

    @Nested
    class GetPieces {

        @Test
        void listsWhatIsOpen() {
            Json listed = get("/pieces").body();
            assertThat(((JsonArray) listed).size()).isEqualTo(1);
            assertThat(listed.at(0).get("reference")).isEqualTo(Json.of("P1001"));
        }

        @Test
        void findsOneByReferenceAndRefusesOneThatIsNotThere() {
            assertThat(get("/pieces/P1001").status()).isEqualTo(200);
            assertThat(get("/pieces/P9999").status()).isEqualTo(404);
        }

        @Test
        void totalsTheRegister() {
            assertThat(get("/pieces/totals").body().get("yards")).isEqualTo(Json.of(100));
        }

        @Test
        void doesNotLetTheTotalsPathBeReadAsAPieceReference() {
            post("/pieces", PIECE.with("reference", "totals"));
            Json totals = get("/pieces/totals").body();
            assertThat(totals.get("pieces")).isEqualTo(Json.of(2));
            assertThat(totals.get("reference").isMissing()).isTrue();
        }
    }

    @Nested
    class PostPiecesReferenceDefects {

        private final JsonObject defects = Json.obj(
                "defects",
                List.of(
                        Json.obj("code", "weft_bar", "yard", 3, "inches", 12),
                        Json.obj("code", "slub", "yard", 20, "inches", 1)));

        @Test
        void booksASheetAndAnswersWithTheReading() {
            Response response = post("/pieces/P1001/defects", defects);
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body().get("defects")).isEqualTo(Json.of(2));
            assertThat(response.body().get("dominantOrigin")).isEqualTo(Json.of("weft"));
        }

        @Test
        void storesTheSheetAgainstThePiece() {
            post("/pieces/P1001/defects", defects);
            assertThat(ClothRoutes.defectsFor(repository, "P1001")).hasSize(2);
        }

        @Test
        void refusesASheetForAPieceThatIsNotThere() {
            assertThat(post("/pieces/P9999/defects", defects).status()).isEqualTo(404);
        }

        @Test
        void refusesAFaultCodeTheCatalogueDoesNotKnow() {
            JsonObject body = Json.obj("defects", List.of(Json.obj("code", "gremlins", "yard", 1, "inches", 1)));
            assertThat(post("/pieces/P1001/defects", body).status()).isEqualTo(404);
        }
    }

    @Nested
    class GetPiecesReferenceGrade {

        @Test
        void gradesACleanPieceAsAccepted() {
            Json grading = get("/pieces/P1001/grade").body();
            assertThat(grading.get("score").get("totalPoints")).isEqualTo(Json.of(0));
            assertThat(grading.get("grade").get("accepted")).isEqualTo(Json.of(true));
        }

        @Test
        void gradesAgainstTheDefectsBooked() {
            book(Json.obj("code", "weft_bar", "yard", 3, "inches", 12));
            assertThat(get("/pieces/P1001/grade").body().get("score").get("totalPoints"))
                    .isEqualTo(Json.of(4));
        }

        @Test
        void takesATighterAcceptanceFromTheQuery() {
            book(Json.obj("code", "weft_bar", "yard", 3, "inches", 12));
            Json strict = get("/pieces/P1001/grade", Map.of("acceptance", "1")).body();
            assertThat(strict.get("grade").get("accepted")).isEqualTo(Json.of(false));
        }

        @Test
        void gradesARunningFaultAtFourPointsForEveryYardItRuns() {
            book(Json.obj("code", "reed_mark", "yard", 10, "through", 19));
            Json score = get("/pieces/P1001/grade").body().get("score");
            assertThat(score.get("totalPoints")).isEqualTo(Json.of(40));
            assertThat(((JsonArray) score.get("perYard")).size()).isEqualTo(10);
        }

        @Test
        void gradesOnTheTenPointSystemWhenTheQueryNamesIt() {
            book(
                    Json.obj("code", "weft_bar", "yard", 3, "inches", 12),
                    Json.obj("code", "slub", "yard", 8, "inches", 4));
            Json grading =
                    get("/pieces/P1001/grade", Map.of("scheme", "ten_point")).body();
            assertThat(grading.get("score").get("totalPoints")).isEqualTo(Json.of(13));
            assertThat(grading.get("score").get("scheme")).isEqualTo(Json.of("ten_point"));
            assertThat(grading.get("grade").get("points")).isEqualTo(Json.of(0.13));
            assertThat(get("/pieces/P1001/grade", Map.of("scheme", "six_point")).status())
                    .isEqualTo(400);
        }

        @Test
        void refusesAnAcceptanceOfNothing() {
            assertThat(get("/pieces/P1001/grade", Map.of("acceptance", "0")).status())
                    .isEqualTo(400);
        }
    }

    @Nested
    class PostPiecesReferenceCut {

        @Test
        void plansCutsRoundTheDefectsThatWereBooked() {
            book(Json.obj("code", "hole", "yard", 50, "inches", 0.5));
            Json job = post("/pieces/P1001/cut", Json.obj("lengths", List.of(40, 40)))
                    .body();
            assertThat(((JsonArray) job.get("runs")).size()).isEqualTo(2);
            assertThat(job.get("summary").get("cutYards")).isEqualTo(Json.of(80));
        }

        @Test
        void keepsTheCutsOffTheWholeRunOfABookedReedMark() {
            book(Json.obj("code", "reed_mark", "yard", 30, "through", 60));
            Json job = post("/pieces/P1001/cut", Json.obj("lengths", List.of(35, 25)))
                    .body();
            assertThat(job.get("runs").at(1).get("from")).isEqualTo(Json.of(60.25));
            assertThat(job.get("summary").get("cutYards")).isEqualTo(Json.of(60));
            assertThat(((JsonArray) job.get("unmet")).size()).isEqualTo(0);
        }

        @Test
        void cutsTheWholePieceWhenNothingIsWrongWithIt() {
            Json job =
                    post("/pieces/P1001/cut", Json.obj("lengths", List.of(100))).body();
            assertThat(job.get("summary").get("yield")).isEqualTo(Json.of(1));
        }
    }

    @Nested
    class GetClothSummary {

        @Test
        void reportsTheTotalsAndTheFirstQualityShare() {
            Json summary = get("/cloth/summary").body();
            assertThat(summary.get("totals").get("pieces")).isEqualTo(Json.of(1));
            assertThat(summary.get("firstQualityShare")).isEqualTo(Json.of(1));
            assertThat(summary.get("reconciliation")).isEqualTo(Json.NULL);
        }

        @Test
        void reconcilesAgainstAWovenLengthWhenOneIsGiven() {
            Json summary = get("/cloth/summary", Map.of("woven", "100")).body();
            assertThat(summary.get("reconciliation").get("balanced")).isEqualTo(Json.of(true));
        }

        @Test
        void reportsAnUnbalancedRegister() {
            Json summary = get("/cloth/summary", Map.of("woven", "150")).body();
            assertThat(summary.get("reconciliation").get("difference")).isEqualTo(Json.of(-50));
        }
    }

    @Nested
    class TheStore {

        @Test
        void keepsPiecesInTheirOwnCollection() {
            assertThat(Repository.counts(repository)).isEqualTo(Json.obj("pieces", 1));
        }

        @Test
        void reportsNoDefectsForAPieceNobodyHasGraded() {
            assertThat(ClothRoutes.defectsFor(repository, "P1001")).isEmpty();
        }
    }
}
