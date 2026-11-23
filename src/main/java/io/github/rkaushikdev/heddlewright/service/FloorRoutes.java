package io.github.rkaushikdev.heddlewright.service;

import io.github.rkaushikdev.heddlewright.loom.BoxPlan;
import io.github.rkaushikdev.heddlewright.loom.Loom;
import io.github.rkaushikdev.heddlewright.loom.Registry;
import io.github.rkaushikdev.heddlewright.service.Router.Response;
import io.github.rkaushikdev.heddlewright.store.Repository;
import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonArray;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.works.Orders;
import io.github.rkaushikdev.heddlewright.works.Production;
import io.github.rkaushikdev.heddlewright.works.Schedule;
import java.util.List;

/**
 * The shop floor side of the service.
 *
 * <p>The plan is never stored. It is worked out from the looms and the open orders whenever it is
 * asked for, which is the only way to be sure it reflects the orders as they stand rather than as
 * they stood when somebody last pressed a button.
 */
public final class FloorRoutes {

    /** The collection looms are stored in. */
    public static final String LOOMS = "looms";

    /** The collection orders are stored in. */
    public static final String ORDERS = "orders";

    /** The collection the outturn book is stored in. */
    public static final String OUTTURN = "outturn";

    private FloorRoutes() {}

    /** Add the floor routes to a router. */
    public static Router mountFloorRoutes(Router router, Repository repository) {
        Router.route(router, "GET", "/looms", call -> Repository.list(repository, LOOMS));

        Router.route(router, "GET", "/looms/summary", call -> Registry.byShedding(loomRecords(repository)));

        Router.route(
                router,
                "GET",
                "/looms/:reference",
                call -> Repository.get(repository, LOOMS, call.params().get("reference")));

        Router.route(router, "POST", "/looms", call -> {
            Check.plainObject(call.body(), "body");
            Loom loom = Registry.createLoom(call.body());
            Repository.insert(repository, LOOMS, loom.reference(), loom);
            return Response.of(201, loom);
        });

        Router.route(router, "POST", "/looms/capable", call -> {
            Check.plainObject(call.body(), "body");
            return Registry.loomsFor(loomRecords(repository), Registry.styleRequirements(call.body())).stream()
                    .map(Loom::reference)
                    .toList();
        });

        Router.route(router, "POST", "/looms/:reference/boxes", call -> {
            JsonObject body = Check.plainObject(call.body(), "body");
            Loom loom = Registry.loomByReference(
                    loomRecords(repository), call.params().get("reference"));
            return BoxPlan.boxMotionPlan(Json.obj(
                    "pattern", body.get("pattern"),
                    "boxes", loom.shuttleBoxes(),
                    "boxedAt", loom.boxedAt(),
                    "picksMinute", loom.picksMinute(),
                    "allot", body.get("allot").isNullish() ? Json.NULL : body.get("allot")));
        });

        Router.route(router, "GET", "/orders", call -> {
            List<Orders.Order> orders = orderRecords(repository);
            return "yes".equals(call.query().get("open")) ? Orders.openOrders(orders) : orders;
        });

        Router.route(router, "GET", "/orders/book", call -> Orders.orderBook(orderRecords(repository)));

        Router.route(router, "POST", "/orders", call -> {
            Check.plainObject(call.body(), "body");
            Orders.Order order = Orders.createOrder(call.body());
            Repository.insert(repository, ORDERS, order.reference(), order);
            return Response.of(201, order);
        });

        Router.route(router, "POST", "/orders/:reference/advance", call -> {
            Json stored = Repository.get(repository, ORDERS, call.params().get("reference"));
            Json to = call.body() instanceof JsonObject body ? body.get("to") : Json.MISSING;
            Orders.Order moved = Orders.advanceOrder(Orders.createOrder(stored), to);
            Repository.put(repository, ORDERS, moved.reference(), moved);
            return moved;
        });

        Router.route(router, "POST", "/plan", call -> {
            JsonObject body = Check.plainObject(call.body(), "body");
            Schedule.Plan plan = plan(repository, body);
            Json wanted = body.get("wantedByHour");
            return ((JsonObject) Json.of(plan))
                    .with(
                            "load",
                            Schedule.loomLoad(plan),
                            "late",
                            wanted.isMissing() ? List.of() : Schedule.lateOrders(plan, wanted));
        });

        Router.route(router, "POST", "/outturn", call -> {
            JsonObject body = Check.plainObject(call.body(), "body");
            Json given = body.get("entries");
            List<Production.Outturn> entries =
                    Production.recordBook(Check.array(given.isNullish() ? Json.arr() : given, "entries"));
            Repository.put(repository, OUTTURN, "book", Json.obj("entries", entries));
            return Response.of(201, Json.obj("entries", entries.size(), "rates", Production.loomRates(entries)));
        });

        Router.route(router, "POST", "/outturn/against-plan", call -> {
            JsonObject body = Check.plainObject(call.body(), "body");
            Schedule.Plan plan = plan(repository, body);
            Json ordered = body.get("orderedMetres");
            return Production.againstPlan(
                    plan, Production.recordBook(outturnBook(repository)), ordered.isNullish() ? Json.obj() : ordered);
        });

        return router;
    }

    private static Schedule.Plan plan(Repository repository, JsonObject body) {
        Json given = body.get("runs");
        List<Schedule.Run> runs = Check.array(given.isNullish() ? Json.arr() : given, "runs").stream()
                .map(Schedule::createRun)
                .toList();
        Json options = body.get("options");
        return Schedule.schedule(loomRecords(repository), runs, options.isNullish() ? Json.obj() : options);
    }

    /** The stored looms, rebuilt into loom records. */
    public static List<Loom> loomRecords(Repository repository) {
        return Repository.list(repository, LOOMS).stream()
                .map(Registry::createLoom)
                .toList();
    }

    /** The stored orders, rebuilt into order records. */
    public static List<Orders.Order> orderRecords(Repository repository) {
        return Repository.list(repository, ORDERS).stream()
                .map(Orders::createOrder)
                .toList();
    }

    /** The outturn book as it was stored, or an empty one. */
    public static List<Json> outturnBook(Repository repository) {
        return Repository.find(repository, OUTTURN, "book")
                .map(stored -> stored.get("entries") instanceof JsonArray book ? book.items() : List.<Json>of())
                .orElse(List.of());
    }
}
