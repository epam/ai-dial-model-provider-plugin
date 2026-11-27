package com.epam.aidial.provider.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * {@code make integration-tests-smoke}: the availability, authorization and permissions report. It checks the login of
 * each cloud, then puts one question to every model of the {@link Catalog} through the service, and says for each why
 * it did not answer (not logged in, no permission, model not available, throttled), so that a problem with a
 * credential is seen at once, not as a wall of failed tests. The exit status is 1 when anything is not OK.
 */
public final class Smoke {

    private static final String QUESTION = "What is 2+3? Answer with the number only.";

    /** What a model's answer says about the access to it. */
    enum Verdict {
        OK,
        WRONG_ANSWER("the model answered, but not 5"),
        UNAUTHENTICATED("the credentials were refused or could not be obtained"),
        FORBIDDEN(
                "no permission to call the model, or a network firewall that does not know this machine (VPN, IP allowlist)"),
        NOT_FOUND("the model or the deployment does not exist for this account, region or project"),
        BAD_REQUEST("the upstream refused the request: often a model not enabled for this account"),
        THROTTLED("rate limit or quota"),
        ERROR("the upstream or the service failed"),
        UNREACHABLE("no answer from the service"),
        CONFIG("a setting or a local file is missing");

        final String meaning;

        Verdict() {
            this("");
        }

        Verdict(String meaning) {
            this.meaning = meaning;
        }

        static Verdict of(Reply reply) {
            return switch (reply.status()) {
                case 200 -> OK;
                case 400 -> BAD_REQUEST;
                case 401 -> UNAUTHENTICATED;
                case 403 -> FORBIDDEN;
                case 404 -> NOT_FOUND;
                case 429 -> THROTTLED;
                case 0 -> UNREACHABLE;
                default -> ERROR;
            };
        }
    }

    private record Row(Cloud cloud, String api, String via, String model, Verdict verdict, String detail) {}

    private Smoke() {}

    public static void main(String[] args) {
        System.out.println("Logins");
        List<Preflight.Login> logins = Preflight.check();
        logins.forEach(l -> System.out.printf("  %-6s %-4s %s%n", l.cloud(), l.ok() ? "OK" : "FAIL", l.detail()));

        System.out.println("%nModels (one question each, through the service)".formatted());
        List<Row> rows = probe();
        rows.forEach(r -> System.out.printf(
                "  %-6s %-9s %-32s %-38s %-14s %s%n", r.cloud(), r.api(), r.via(), r.model(), r.verdict(), r.detail()));

        report(logins, rows);
        System.exit(
                rows.stream().allMatch(r -> r.verdict() == Verdict.OK)
                                && logins.stream().allMatch(Preflight.Login::ok)
                        ? 0
                        : 1);
    }

    private static List<Row> probe() {
        List<Row> rows = new ArrayList<>();
        List<Deployment> deployments = new ArrayList<>();
        for (Cloud cloud : Cloud.values()) {
            try {
                deployments.addAll(Catalog.of(cloud));
            } catch (RuntimeException e) {
                rows.add(new Row(cloud, "-", "-", "-", Verdict.CONFIG, e.getMessage()));
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<Row>> asked = deployments.stream()
                    .map(d -> CompletableFuture.supplyAsync(() -> ask(d), pool))
                    .toList();
            asked.forEach(f -> rows.add(f.join()));
        } finally {
            pool.shutdown();
        }
        return rows;
    }

    private static Row ask(Deployment d) {
        Reply reply = d.tryAsk(QUESTION, null);
        Verdict verdict = Verdict.of(reply);
        String detail = "";
        if (verdict == Verdict.OK) {
            Answer answer = d.api().read(reply.json());
            verdict = answer.text().contains("5") ? Verdict.OK : Verdict.WRONG_ANSWER;
            detail = answer.text().strip();
        } else {
            detail = "HTTP %d %s".formatted(reply.status(), reply.reason());
        }
        return new Row(d.cloud(), d.api().name().toLowerCase(Locale.ROOT), d.via(), d.model(), verdict, detail);
    }

    private static void report(List<Preflight.Login> logins, List<Row> rows) {
        long ok = rows.stream().filter(r -> r.verdict() == Verdict.OK).count();
        System.out.printf("%n%d of %d models answered.%n", ok, rows.size());
        for (Verdict verdict : Verdict.values()) {
            List<Row> failed = rows.stream()
                    .filter(r -> r.verdict() == verdict && verdict != Verdict.OK)
                    .toList();
            if (!failed.isEmpty()) {
                String clouds =
                        failed.stream().map(r -> r.cloud().name()).distinct().collect(Collectors.joining(", "));
                System.out.printf("  %s x%d (%s): %s%n", verdict, failed.size(), clouds, verdict.meaning);
                failed.stream().map(Row::detail).distinct().limit(3).forEach(d -> System.out.println("      " + d));
            }
        }
        logins.stream()
                .filter(l -> !l.ok())
                .forEach(l -> System.out.printf("  login of %s failed: run `%s`%n", l.cloud(), l.cloud().loginHint));
    }
}
