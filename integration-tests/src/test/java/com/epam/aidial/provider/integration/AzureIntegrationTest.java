package com.epam.aidial.provider.integration;

/** Claude on Azure AI Foundry and GPT on Azure OpenAI, by API key and by the default Azure credential. */
class AzureIntegrationTest extends CloudIntegrationTest {

    AzureIntegrationTest() {
        super(Cloud.AZURE);
    }
}
