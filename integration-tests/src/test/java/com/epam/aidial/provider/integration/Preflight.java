package com.epam.aidial.provider.integration;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Who the command line tools of each cloud say you are, before any model is asked: a login that expired shows here, in
 * one line with its fix, not as a 401 of every model. The service takes its credentials from the same sources.
 */
final class Preflight {

    /** One cloud's login: whether it works, who it is, and what renews it. */
    record Login(Cloud cloud, boolean ok, String detail) {}

    private Preflight() {}

    /** The login of every cloud, checked side by side. */
    static List<Login> check() {
        List<CompletableFuture<Login>> checks = List.of(
                CompletableFuture.supplyAsync(Preflight::aws),
                CompletableFuture.supplyAsync(Preflight::gcp),
                CompletableFuture.supplyAsync(Preflight::azure));
        return checks.stream().map(CompletableFuture::join).toList();
    }

    private static Login aws() {
        String profile = Env.all().get("AWS_PROFILE");
        List<String> command = new java.util.ArrayList<>(
                List.of("aws", "sts", "get-caller-identity", "--query", "Arn", "--output", "text"));
        if (profile != null) {
            command.addAll(List.of("--profile", profile));
        }
        return login(Cloud.AWS, run(command), out -> "profile " + profile + ": " + out);
    }

    private static Login gcp() {
        // The token itself is not shown: the exit status says whether Application Default Credentials work.
        Result adc = run(List.of("gcloud", "auth", "application-default", "print-access-token"));
        Result account = run(List.of("gcloud", "auth", "list", "--filter=status:ACTIVE", "--format=value(account)"));
        return login(Cloud.GCP, adc, out -> "application default credentials; gcloud account " + account.output());
    }

    private static Login azure() {
        Result who = run(List.of("az", "account", "show", "--query", "user.name", "--output", "tsv"));
        if (!who.ok()) {
            return login(Cloud.AZURE, who, out -> out);
        }
        // What DefaultAzureCredential falls back to on this machine: a token of the az CLI for Azure OpenAI.
        Result token = run(List.of(
                "az",
                "account",
                "get-access-token",
                "--resource",
                "https://cognitiveservices.azure.com",
                "--query",
                "expiresOn",
                "--output",
                "tsv"));
        return login(Cloud.AZURE, token, out -> "az CLI as " + who.output() + ", token until " + out);
    }

    private static Login login(Cloud cloud, Result result, java.util.function.Function<String, String> detail) {
        if (result.ok()) {
            return new Login(cloud, true, detail.apply(result.output()));
        }
        return new Login(cloud, false, result.firstLine() + " -> " + cloud.loginHint);
    }

    private record Result(int exit, String output) {

        boolean ok() {
            return exit == 0;
        }

        String firstLine() {
            return output.lines().findFirst().orElse("no output (is the tool installed?)");
        }
    }

    private static Result run(List<String> command) {
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, command.get(0) + " timed out");
            }
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            return new Result(-1, command.get(0) + " cannot be run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        }
    }
}
