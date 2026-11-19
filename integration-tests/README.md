# Integration tests against the clouds

Live tests of the service against AWS Bedrock, Google Vertex AI and Azure, with real credentials and real models. They
are not part of `make test` or `make lint` (`make lint` still compiles and lints them: Checkstyle, PMD, Spotless).

```sh
make integration-tests-smoke   # who am I on each cloud, and does each model answer? a table with the reasons
make integration-tests         # the tests; ARGS="--tests '*AwsIntegrationTest'" runs one cloud
```

Run the smoke report first. It checks the login of each cloud with its command line tool (`aws sts`, `gcloud`, `az`),
puts one question to every model of the catalog through the service, and names the problem of each one that does not
answer: not logged in (`401`), no permission or a firewall (`403`), a model that is not there for the account, region or
project (`404`, `400`), a quota (`429`). It exits with 1 when anything is not OK.

## What is tested

Every model of `Catalog`, through the API of the cloud, as DIAL Core would call it:

| Cloud | API | Models | Ways |
| ----- | --- | ------ | ---- |
| AWS | Anthropic Messages | Claude | invoke-model (`legacy`) and `mantle` clients, the AWS profile |
| AWS | OpenAI Responses | GPT | Bedrock Mantle, the AWS profile |
| GCP | Anthropic Messages | Claude | Vertex AI (global endpoint), Application Default Credentials |
| Azure | Anthropic Messages | Claude | AI Foundry, by API key and by `DefaultAzureCredential` |
| Azure | OpenAI Responses | GPT | Azure OpenAI, by API key and by `DefaultAzureCredential` |

Vertex AI has no Responses API in the service. On a developer machine `DefaultAzureCredential` falls back to the
credentials of `az login`.

Two tests for each, in `CloudIntegrationTest`, run by one class per cloud:

1. `answersSimpleQuestion`: "What is 2+3?", the answer contains `5`.
2. `callsTool`: "What's the weather?" with a `get_weather` tool, the model calls it. The newer models refuse a forced
   tool choice, and ask "which city?" of a tool that takes one, so the tool takes no argument and the request says to
   call tools at once.

The service runs in the test JVM (`Service`), on the same code and settings as the standalone process, so the cloud SDKs
find their credentials the way they do there. The tests send plain HTTP to it.

## Setup

| What | Where |
| ---- | ----- |
| AWS profile and region, GCP project | `.env` in the repository root (git-ignored, see `.env.example`); the environment wins |
| Azure resource and API key | `integration-tests/azure.local.json` (git-ignored, see `azure.example.json`) |
| Logins | `aws sso login --profile <AWS_PROFILE>`, `gcloud auth application-default login`, `az login` |

The Azure resource must accept calls from your machine: a resource with a network firewall answers `403 Access denied
due to Virtual Network/Firewall rules` to every key and token alike (the smoke report shows it).

## Adding a model

Add its id to the list of its cloud in `Catalog`. Nothing else: the suites and the smoke report read the catalog.
