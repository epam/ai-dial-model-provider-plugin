package com.epam.aidial.provider.credentials;

import static com.epam.aidial.provider.testing.MockUpstream.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.aidial.provider.core.Config;
import com.epam.aidial.provider.testing.MockUpstream;
import com.epam.aidial.provider.upstream.CredentialsUnavailableException;
import com.epam.aidial.provider.upstream.CredentialsUnavailableException.Source;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.services.sts.model.Tag;

class AwsCredentialsTest {

    private static final Future<Void> NEVER = Promise.<Void>promise().future();
    private static final AwsCredentials.CredentialSet ROLE =
            new AwsCredentials.CredentialSet("", new Secret(""), new Secret(""), "arn:aws:iam::1:role/r");

    private Vertx vertx;
    private MockUpstream sts;
    private volatile long lifeSeconds = 3600;
    private final List<String> profileReaders = new ArrayList<>();
    private volatile String profileFileContent = "[profile dev]\nregion=us-east-1\n";

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        sts = new MockUpstream(vertx)
                .on(
                        "/",
                        r -> vertx.setTimer(
                                50,
                                t -> r.response()
                                        .putHeader("Content-Type", "text/xml")
                                        .end(
                                                "<AssumeRoleResponse><AssumeRoleResult><Credentials><AccessKeyId>AKIDSTS</AccessKeyId><SecretAccessKey>s</SecretAccessKey>"
                                                        + "<SessionToken>t</SessionToken><Expiration>"
                                                        + DateTimeFormatter.ISO_INSTANT.format(Instant.now()
                                                                .plusSeconds(lifeSeconds)
                                                                .truncatedTo(ChronoUnit.SECONDS))
                                                        + "</Expiration></Credentials></AssumeRoleResult></AssumeRoleResponse>")));
    }

    @AfterEach
    void stop() throws Exception {
        await(vertx.close());
    }

    private AwsCredentials credentials() {
        return new AwsCredentials(
                vertx,
                Config.from(Map.of("AWS_ENDPOINT_URL_STS", sts.url())),
                () -> StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDCHAIN", "s")));
    }

    private static AwsCredentials.Session session(String project) {
        return new AwsCredentials.Session(
                "Project_" + project,
                List.of(Tag.builder().key("project").value(project).build()));
    }

    private SigV4.Credentials retrieve(AwsCredentials c, String client, AwsCredentials.Session s) throws Exception {
        return await(c.provider(ROLE, "us-east-1", client, s, AwsCredentials.BEDROCK_ROLE_WINDOW)
                .credentials(NEVER));
    }

    @Test
    void assumedRolesPerSet() throws Exception {
        AwsCredentials c = credentials();
        int[][] steps = {{0, 1}, {0, 1}, {1, 2}, {0, 2}, {2, 3}}; // {set, STS calls after}
        String[][] sets = {{"legacy", "a"}, {"legacy", "b"}, {"mantle", "a"}};
        for (int[] step : steps) {
            assertEquals(
                    "AKIDSTS",
                    retrieve(c, sets[step[0]][0], session(sets[step[0]][1])).accessKeyId());
            assertEquals(step[1], sts.seen().size());
        }
        List<Future<SigV4.Credentials>> concurrent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            concurrent.add(c.provider(ROLE, "us-east-1", "legacy", session("c"), AwsCredentials.BEDROCK_ROLE_WINDOW)
                    .credentials(NEVER));
        }
        for (Future<SigV4.Credentials> f : concurrent) {
            await(f);
        }
        assertEquals(4, sts.seen().size());
        lifeSeconds = 50;
        retrieve(c, "legacy", session("d"));
        retrieve(c, "legacy", session("d"));
        assertEquals(6, sts.seen().size());
    }

    @Test
    void lastUsed512SetsKept() throws Exception {
        AwsCredentials c = credentials();
        AwsCredentials.Provider first = keys(c, 0);
        for (int i = 1; i < 512; i++) {
            keys(c, i);
        }
        assertSame(first, keys(c, 0), "512 sets kept");
        keys(c, 512);
        assertSame(first, keys(c, 0), "the least recently used dropped, not the first made");
        for (int i = 513; i < 1025; i++) { // 512 sets used after it
            keys(c, i);
        }
        assertNotSame(first, keys(c, 0));
    }

    private static AwsCredentials.Provider keys(AwsCredentials c, int i) throws Exception {
        return c.provider(
                new AwsCredentials.CredentialSet("AKID" + i, new Secret("s"), new Secret(""), ""),
                "us-east-1",
                "legacy",
                AwsCredentials.Session.NONE,
                AwsCredentials.BEDROCK_ROLE_WINDOW);
    }

    private AwsCredentials withProfile() {
        return new AwsCredentials(
                vertx,
                Config.from(Map.of("AWS_ENDPOINT_URL_STS", sts.url(), "AWS_PROFILE", "dev")),
                () -> StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDCHAIN", "s")),
                () -> {
                    synchronized (profileReaders) {
                        profileReaders.add(Thread.currentThread().getName());
                    }
                    return ProfileFile.builder()
                            .content(new ByteArrayInputStream(profileFileContent.getBytes(StandardCharsets.UTF_8)))
                            .type(ProfileFile.Type.CONFIGURATION)
                            .build();
                });
    }

    private void assertFilesReadOnWorkers() {
        synchronized (profileReaders) {
            assertFalse(profileReaders.isEmpty(), "the files were read");
            profileReaders.forEach(thread -> assertTrue(thread.contains("worker"), "the files are read on " + thread));
        }
    }

    @Test
    void profileReadOnWorker() throws Exception {
        AwsCredentials c = withProfile();
        await(c.checkProfile(Source.AWS_ROLE, NEVER));
        assertFilesReadOnWorkers();
        assertEquals(
                "AKIDSTS", retrieve(c, "legacy", AwsCredentials.Session.NONE).accessKeyId());
    }

    @Test
    void missingProfileFailsTheCredentialsWithoutAnStsCall() throws Exception {
        profileFileContent = "[profile other]\nregion=us-east-1\n";
        AwsCredentials c = withProfile();
        for (Future<?> check : List.of(
                c.checkProfile(Source.AWS_ROLE, NEVER),
                c.provider(ROLE, "us-east-1", "legacy", AwsCredentials.Session.NONE, AwsCredentials.BEDROCK_ROLE_WINDOW)
                        .credentials(NEVER))) {
            var failure =
                    assertThrows(ExecutionException.class, () -> await(check)).getCause();
            var unavailable = assertInstanceOf(CredentialsUnavailableException.class, failure);
            assertEquals(Source.AWS_ROLE, unavailable.source());
            assertEquals("failed to get shared config profile, dev", unavailable.getMessage());
        }
        assertEquals(0, sts.seen().size());
        assertFilesReadOnWorkers();
    }

    @Test
    void missingProfileIsLookedUpAgainOnlyAfterTheWindow() throws Exception {
        profileFileContent = "[profile other]\nregion=us-east-1\n";
        AwsCredentials c = withProfile();
        for (int i = 0; i < 3; i++) {
            assertThrows(ExecutionException.class, () -> await(c.checkProfile(Source.AWS_CHAIN, NEVER)));
        }
        assertEquals(1, profileReaders.size(), "the window keeps the next requests from reading the files");
    }
}
