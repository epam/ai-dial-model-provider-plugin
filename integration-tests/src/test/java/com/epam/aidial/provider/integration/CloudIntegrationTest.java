package com.epam.aidial.provider.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What every model of a cloud must do through the service. A cloud's test class names the cloud and nothing else, so
 * that a model or a way to reach it is added to the {@link Catalog} alone.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class CloudIntegrationTest {

    private final Cloud cloud;

    CloudIntegrationTest(Cloud cloud) {
        this.cloud = cloud;
    }

    List<Deployment> deployments() {
        return Catalog.of(cloud);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deployments")
    void answersSimpleQuestion(Deployment model) {
        Answer answer = model.ask("What is 2+3? Answer with the number only.");

        assertTrue(answer.text().contains("5"), () -> model + " answered: " + answer.text());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deployments")
    void callsTool(Deployment model) {
        Answer answer = model.ask("What's the weather?", Tool.GET_WEATHER);

        assertEquals(List.of("get_weather"), answer.toolCalls(), model + " answered: " + answer.text());
    }
}
