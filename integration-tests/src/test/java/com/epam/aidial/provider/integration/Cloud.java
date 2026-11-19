package com.epam.aidial.provider.integration;

/** The clouds under test, each with the command that renews its login. */
enum Cloud {
    AWS("aws sso login --profile <AWS_PROFILE>"),
    GCP("gcloud auth application-default login"),
    AZURE("az login");

    final String loginHint;

    Cloud(String loginHint) {
        this.loginHint = loginHint;
    }
}
