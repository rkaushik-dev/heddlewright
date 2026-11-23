package io.github.rkaushikdev.heddlewright.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.heddlewright.Fixtures;
import io.github.rkaushikdev.heddlewright.loom.Loom;
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

class FloorRoutesTest {

    private static final JsonObject NARROW = Fixtures.narrowLoom();
    private static final JsonObject WIDE = Fixtures.wideLoom(180);
    private static final JsonObject ORDER = Fixtures.poplinOrder();
    private static final JsonObject RUN = Json.obj(
            "order",
            "SO-1",
            "style",
            "poplin",
            "metres",
            400,
            "picksPerCm",
            20,
            "requirements",
            Json.obj("widthCm", 100, "shafts", 4, "hooks", 0, "weftColours", 1));

    private Router router;
    private Repository repository;

    @BeforeEach
    void mount() {
        repository = Repository.createRepository();
        router = FloorRoutes.mountFloorRoutes(Router.createRouter(), repository);
        post("/looms", NARROW);
        post("/looms", WIDE);
    }

    private Response get(String path) {
        return Router.handle(router, Request.get(path));
    }

    private Response post(String path, Object body) {
        return Router.handle(router, Request.of("POST", path, body));
    }

    private static List<Json> items(Json value) {
        return ((JsonArray) value).items();
    }

    @Nested
    class Looms {

        @Test
        void listsWhatIsRegistered() {
            assertThat(items(get("/looms").body()))
                    .extracting(loom -> loom.get("reference").asText())
                    .containsExactly("N1", "W1");
        }

        @Test
        void keepsTheSummaryPathOffTheReferenceRoute() {
            Json dobby = items(get("/looms/summary").body()).stream()
                    .filter(entry -> entry.get("shedding").equals(Json.of("dobby")))
                    .findFirst()
                    .orElseThrow();
            assertThat(((JsonArray) dobby.get("references")).size()).isEqualTo(2);
        }

        @Test
        void findsOneLoomAndRefusesOneThatIsNotThere() {
            assertThat(get("/looms/N1").status()).isEqualTo(200);
            assertThat(get("/looms/N9").status()).isEqualTo(404);
        }

        @Test
        void refusesALoomWithNoShaftsOnAShaftMachine() {
            JsonObject body = Json.obj("reference", "X", "shedding", "dobby", "reedWidthCm", 100, "picksMinute", 100);
            assertThat(post("/looms", body).status()).isEqualTo(400);
        }

        @Test
        void rebuildsTheStoredRecordsIntoLoomRecords() {
            // A record has no setters, so a rebuilt loom cannot be changed behind the store.
            assertThat(FloorRoutes.loomRecords(repository)).hasSize(2).allMatch(loom -> loom.getClass()
                    .isRecord());
        }
    }

    @Nested
    class PostLoomsCapable {

        @Test
        void namesTheLoomsThatCanTakeAStyleNarrowWorkOffTheWideLoomLast() {
            assertThat(post("/looms/capable", Json.obj("widthCm", 100, "shafts", 4))
                            .body())
                    .isEqualTo(Json.arr("N1", "W1"));
        }

        @Test
        void isEmptyWhenNothingFits() {
            assertThat(post("/looms/capable", Json.obj("widthCm", 400)).body()).isEqualTo(Json.arr());
        }
    }

    @Nested
    class OrdersRoutes {

        @BeforeEach
        void open() {
            post("/orders", ORDER);
        }

        @Test
        void opensAnOrderInTheQuotedState() {
            assertThat(get("/orders").body().at(0).get("state")).isEqualTo(Json.of("quoted"));
        }

        @Test
        void movesOneOnAndStoresWhereItGotTo() {
            Response moved = post("/orders/SO-1/advance", Json.obj("to", "weaving"));
            assertThat(moved.body().get("state")).isEqualTo(Json.of("weaving"));
            assertThat(get("/orders").body().at(0).get("state")).isEqualTo(Json.of("weaving"));
        }

        @Test
        void refusesToMoveAnOrderBackwards() {
            post("/orders/SO-1/advance", Json.obj("to", "weaving"));
            assertThat(post("/orders/SO-1/advance", Json.obj("to", "quoted")).status())
                    .isEqualTo(409);
        }

        @Test
        void filtersToTheOpenOnes() {
            post("/orders/SO-1/advance", Json.obj("to", "despatched"));
            assertThat(Router.handle(router, Request.get("/orders", Map.of("open", "yes")))
                            .body())
                    .isEqualTo(Json.arr());
        }

        @Test
        void totalsTheBookByState() {
            assertThat(get("/orders/book").body().get("openMetres")).isEqualTo(Json.of(400));
        }
    }

    @Nested
    class PostPlan {

        @Test
        void worksThePlanOutFromTheLoomsAsTheyStand() {
            Json plan = post("/plan", Json.obj("runs", List.of(RUN))).body();
            assertThat(plan.get("placed").at(0).get("loom")).isEqualTo(Json.of("N1"));
            assertThat(plan.get("load").at(0).get("utilisation")).isEqualTo(Json.of(1));
        }

        @Test
        void reportsWorkNoLoomCanTake() {
            JsonObject wide = RUN.with(
                    "order",
                    "SO-9",
                    "requirements",
                    Json.obj("widthCm", 400, "shafts", 0, "hooks", 0, "weftColours", 1));
            Json plan = post("/plan", Json.obj("runs", List.of(wide))).body();
            assertThat(plan.get("unplaced").at(0).get("order")).isEqualTo(Json.of("SO-9"));
        }

        @Test
        void namesTheOrdersThatWillBeLateWhenDatesAreGiven() {
            Json plan = post("/plan", Json.obj("runs", List.of(RUN), "wantedByHour", Json.obj("SO-1", 1)))
                    .body();
            assertThat(plan.get("late").at(0).get("order")).isEqualTo(Json.of("SO-1"));
        }

        @Test
        void refusesARunWithNoLength() {
            assertThat(post("/plan", Json.obj("runs", List.of(RUN.with("metres", 0))))
                            .status())
                    .isEqualTo(400);
        }
    }

    @Nested
    class Outturn {

        private final List<JsonObject> entries =
                List.of(Json.obj("order", "SO-1", "loom", "N1", "metres", 120, "hours", 8, "stops", 12));

        @Test
        void booksWhatWasMadeAndReportsTheRates() {
            Response response = post("/outturn", Json.obj("entries", entries));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body().get("rates").at(0).get("metresPerHour")).isEqualTo(Json.of(15));
            assertThat(FloorRoutes.outturnBook(repository)).hasSize(1);
        }

        @Test
        void setsTheOutturnBesideThePlan() {
            post("/outturn", Json.obj("entries", entries));
            Json rows = post(
                            "/outturn/against-plan",
                            Json.obj("runs", List.of(RUN), "orderedMetres", Json.obj("SO-1", 400)))
                    .body();
            assertThat(rows.at(0).get("metres")).isEqualTo(Json.of(120));
            assertThat(rows.at(0).get("share")).isEqualTo(Json.of(0.3));
        }

        @Test
        void reportsNoOutturnBeforeAnythingIsBooked() {
            assertThat(FloorRoutes.outturnBook(repository)).isEmpty();
        }
    }

    @Nested
    class PostLoomsReferenceBoxes {

        private Response boxes(String loom, Object... pattern) {
            return post("/looms/" + loom + "/boxes", Json.obj("pattern", List.of(pattern)));
        }

        @Test
        void allotsTheLoomsBoxesForTravelWhenTheBodyAsks() {
            Response response = post(
                    "/looms/N1/boxes",
                    Json.obj(
                            "pattern", List.of("ground", "ground", "blue", "blue", "gold", "gold"), "allot", "travel"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().get("allot")).isEqualTo(Json.of("travel"));
            assertThat(response.body().get("travel")).isEqualTo(Json.of(4));
        }

        @Test
        void gaitsAWeftOrderUpOnTheLoomThatIsRegistered() {
            Response response = boxes("N1", "ground", "ground", "blue", "blue");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().get("chain")).isEqualTo(Json.arr(1, 1, 2, 2));
            assertThat(response.body().get("changes")).isEqualTo(Json.of(2));
        }

        @Test
        void worksTheLoomSpeedDownByWhatTheChangingCosts() {
            Response response = boxes("N1", "ground", "ground", "blue", "blue");
            assertThat(response.body().get("picksMinute").asDouble())
                    .isLessThan(NARROW.get("picksMinute").asDouble());
        }

        @Test
        void refusesAPatternTheLoomIsNotBoxedToPick() {
            Response response = boxes("N1", "ground", "blue", "blue");
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.body().get("code")).isEqualTo(Json.of("capacity_error"));
        }

        @Test
        void refusesALoomThatIsNotOnTheFloor() {
            assertThat(boxes("N9", "a", "a").status()).isEqualTo(404);
        }

        @Test
        void keepsTheBoxesPathOffTheReferenceRoute() {
            assertThat(get("/looms/N1").status()).isEqualTo(200);
            assertThat(FloorRoutes.loomRecords(repository))
                    .extracting(Loom::reference)
                    .contains("N1");
        }
    }
}
