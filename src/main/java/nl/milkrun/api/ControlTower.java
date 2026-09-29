package nl.milkrun.api;

import com.sun.net.httpserver.HttpServer;
import nl.milkrun.plan.Model.Params;
import nl.milkrun.plan.Model.Route;
import nl.milkrun.plan.Model.Solution;
import nl.milkrun.svc.Services.Decision;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Read-only control tower: plan, decision log and KPIs as JSON plus an HTML page. */
public final class ControlTower {
    private ControlTower() {}

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static String planJson(Solution s, Params p) {
        StringBuilder sb = new StringBuilder("[");
        for (Route r : s.routes) {
            if (r.stops.isEmpty()) continue;
            var e = r.eval(p);
            sb.append(String.format("{\"vehicle\":\"%s\",\"stops\":%d,\"totes\":%d,\"km\":%.1f,\"finishMin\":%d,\"orders\":[",
                    r.vehicle.id(), r.stops.size(), e.load(), e.km(), e.finishMin()));
            for (int i = 0; i < r.stops.size(); i++) sb.append(i == 0 ? "" : ",").append('"').append(r.stops.get(i).id()).append('"');
            sb.append("]},");
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        return sb.append(']').toString();
    }

    public static String decisionsJson(List<Decision> log) {
        StringBuilder sb = new StringBuilder("[");
        for (Decision d : log)
            sb.append(String.format("{\"order\":\"%s\",\"action\":\"%s\",\"detail\":\"%s\"},", d.orderId(), d.action(), d.detail().replace("\"", "'")));
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        return sb.append(']').toString();
    }

    public static String html(Solution s, Params p, List<Decision> log, String summary) {
        StringBuilder rows = new StringBuilder();
        for (Route r : s.routes) {
            if (r.stops.isEmpty()) continue;
            var e = r.eval(p);
            rows.append(String.format("<tr><td>%s</td><td class=n>%d</td><td class=n>%d / %d</td><td class=n>%.1f / %.0f</td><td class=n>%02d:%02d</td></tr>",
                    r.vehicle.id(), r.stops.size(), e.load(), r.vehicle.capacityTotes(), e.km(), r.vehicle.rangeKm(),
                    16 + e.finishMin() / 60, e.finishMin() % 60));
        }
        StringBuilder dec = new StringBuilder();
        for (Decision d : log)
            dec.append(String.format("<tr><td>%s</td><td>%s</td><td>%s</td></tr>", esc(d.orderId()), esc(d.action()), esc(d.detail())));
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Milkrun control tower</title><style>
            body{margin:0;font:15px/1.5 "Segoe UI",system-ui,sans-serif;color:#1f2a22;background:#f7faf7}
            main{max-width:960px;margin:0 auto;padding:40px 20px}h1{font-size:1.9rem;margin:0 0 4px}
            h2{font-size:1.15rem;margin:36px 0 8px}table{border-collapse:collapse;width:100%%;font-variant-numeric:tabular-nums}
            th,td{padding:7px 9px;border-bottom:1px solid #d9e3db;text-align:left}th{color:#5a6b5f;font-weight:600}.n{text-align:right}
            p{color:#56655a;max-width:68ch}div.w{overflow-x:auto}
            </style></head><body><main><h1>Tonight's vans</h1><p>%s</p>
            <div class=w><table><tr><th>Van</th><th class=n>Stops</th><th class=n>Totes</th><th class=n>km / range</th><th class=n>Back at hub</th></tr>%s</table></div>
            <h2>Every decision, with its reason</h2><div class=w><table><tr><th>Order</th><th>Action</th><th>Reason</th></tr>%s</table></div>
            </main></body></html>
            """.formatted(esc(summary), rows, dec);
    }

    public static HttpServer serve(int port, String html, String plan, String decisions, String kpis) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress(port), 0);
        route(srv, "/", "text/html", html);
        route(srv, "/api/plan", "application/json", plan);
        route(srv, "/api/decisions", "application/json", decisions);
        route(srv, "/api/kpis", "application/json", kpis);
        srv.start();
        return srv;
    }

    private static void route(HttpServer srv, String path, String type, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        srv.createContext(path, ex -> {
            ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
    }
}
