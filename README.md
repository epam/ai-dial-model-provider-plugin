<h1 align="center">
  DIAL Model Provider Plugin
</h1>
<p align="center">
  <p align="center">
  <a href="https://dialx.ai/">
    <img src="https://dialx.ai/logo/dialx_logo.svg" alt="About DIALX">
  </a>
</p>
<h4 align="center">
  <a href="https://discord.gg/ukzj9U9tEe">
    <img src="https://img.shields.io/static/v1?label=DIALX%20Community%20on&message=Discord&color=blue&logo=Discord&style=flat-square" alt="Discord">
  </a>
</h4>

- [Overview](#overview)
- [Connect to DIAL Core](#connect-to-dial-core)
  - [1. Add the plugin to DIAL Core's image](#1-add-the-plugin-to-dial-cores-image)
  - [2. Declare a plugin entry](#2-declare-a-plugin-entry)
  - [3. Point models at the entry](#3-point-models-at-the-entry)
- [Upstreams](#upstreams)
- [Messages API for a Responses model](#messages-api-for-a-responses-model)
- [Settings](#settings)
- [Standalone service](#standalone-service)
- [Limitations](#limitations)
- [Development](#development)
- [License](#license)

---

## Overview

A DIAL Core plugin that forwards model requests to the LLM APIs of the clouds. It runs inside DIAL Core, with no extra
HTTP hop.

| API                                                                 | DIAL Core interface | Upstreams                                                       |
| ------------------------------------------------------------------- | ------------------- | --------------------------------------------------------------- |
| Anthropic Messages (`/v1/messages`, `/count_tokens`)                | `anthropicMessages` | Anthropic API, Azure AI Foundry, Vertex AI, Bedrock             |
| OpenAI Responses (create, retrieve, delete, cancel, `input_tokens`) | `openaiResponses`   | OpenAI, Azure OpenAI, Bedrock Mantle and Runtime, Alibaba Cloud |

Requests go upstream as they are. Only the model name in the URL, the provider's version field, the credentials and
unsupported beta flags change. The one exception: a model that only speaks the OpenAI Responses API can also be called
with the Anthropic Messages API, which the plugin
[translates](#messages-api-for-a-responses-model).

> [!WARNING]
> Preview. Not yet proven in production. Settings, packaging and DIAL Core's plugin API may change between releases.
> A plugin jar is built for one DIAL Core version.

## Connect to DIAL Core

Needs a DIAL Core version with the
[plugin API](https://github.com/epam/ai-dial-core/blob/development/docs/dynamic-settings/plugins.md).

### 1. Add the plugin to DIAL Core's image

The plugin is published as an image with the jar only:
[`epam/ai-dial-model-provider-plugin`](https://hub.docker.com/r/epam/ai-dial-model-provider-plugin).
Copy it into a plugin directory of DIAL Core's image, `/app/plugins/<group>/<plugin>/`:

```dockerfile
FROM epam/ai-dial-model-provider-plugin:<version> AS model-provider-plugin

FROM epam/ai-dial-core:<DIAL Core version>
COPY --from=model-provider-plugin / /app/plugins/custom/model-provider/
```

On Kubernetes 1.35+ you can mount the image as a volume instead
([dial-core Helm chart](https://github.com/epam/ai-dial-helm/blob/main/charts/dial-core/README.md)):

```yaml
core:
  extraVolumes:
    - name: plugin-model-provider
      image:
        reference: epam/ai-dial-model-provider-plugin:<version>
        pullPolicy: IfNotPresent
  extraVolumeMounts:
    - name: plugin-model-provider
      mountPath: /app/plugins/custom/model-provider
      readOnly: true
```

- Replace the jar on upgrade. Two copies of it stop DIAL Core.
- Never put a jar directly in `/app/plugins` or `/app/plugins/custom`, and never mount over `/app/plugins`.

### 2. Declare a plugin entry

One entry per adapter you use:

```json
{
  "plugins": {
    "model-provider-bedrock": {
      "type": "model-provider",
      "settings": { "adapter": "bedrock", "HTTP_MAX_CONNECTIONS": "2000" }
    },
    "model-provider-openai": {
      "type": "model-provider",
      "settings": { "adapter": "openai" }
    }
  }
}
```

- `adapter` is `openai`, `vertexai` or `bedrock`. It decides how the upstream is chosen
  (see [Upstreams](#upstreams)).
- An entry can instead be a translator, `"translator": "openaiResponses"`, in place of `adapter` (an entry has one of
  the two): see [Messages API for a Responses model](#messages-api-for-a-responses-model).
- Other settings are the [variables](#settings), by name. Values can be strings, numbers or booleans. `null` unsets a
  variable.
- Variables not set here come from DIAL Core's environment. **Keep credentials there**: DIAL Core returns settings
  as written through its API.
- Settings apply from the next request, no restart. A new jar needs a restart.

### 3. Point models at the entry

Set the interface's `base_url` to `dial-plugin://<entry name>`. The model's `upstreams` stay as they are:

```json
{
  "models": {
    "claude-sonnet-bedrock": {
      "type": "chat",
      "displayName": "Claude Sonnet (Bedrock)",
      "overrideName": "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
      "interfaces": {
        "anthropicMessages": {
          "base_url": "dial-plugin://model-provider-bedrock"
        }
      }
    },
    "gpt-5": {
      "type": "chat",
      "interfaces": {
        "openaiResponses": { "base_url": "dial-plugin://model-provider-openai" }
      },
      "upstreams": [
        {
          "endpoint": "https://contoso.openai.azure.com/openai/v1/responses",
          "key": "AZURE_OPENAI_KEY"
        }
      ]
    }
  }
}
```

- A plugin URL works only in the platform config, as an interface's `base_url` or a model's `baseUrl`.
- A missing entry answers `502`.
- To roll back, put the HTTP base URL back.

## Upstreams

The upstream stays in the model's `upstreams`: `endpoint`, `key` and `extraData`.
DIAL Core passes them to the plugin. The adapter decides which upstreams it accepts.

Anthropic Messages:

| Upstream         | `openai`                                      | `vertexai`                                                                  | `bedrock`                                                                               |
| ---------------- | --------------------------------------------- | --------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| Anthropic API    | any endpoint up to `/v1/messages`             | key set                                                                     | key set                                                                                 |
| Azure AI Foundry | Azure host with a base ending in `/anthropic` | endpoint ending in `/anthropic/v1/messages`                                 | no                                                                                      |
| Vertex AI        | no                                            | no key: Google credentials, region and project from `extraData` or settings | no                                                                                      |
| Bedrock          | no                                            | no                                                                          | no key: invoke-model or Mantle, region and AWS credentials from `extraData` or settings |

OpenAI Responses, `openai` adapter only. The endpoint must end in `/responses`:

- OpenAI and other v1 endpoints
- Azure OpenAI, with its `api-version`
- Bedrock Mantle and Runtime
- Alibaba Cloud (`extraData` `vendor`)

Without a key, the plugin uses an Azure AD token (Azure) or AWS credentials (Bedrock).

Vertex AI example: use the `vertexai` adapter, put the model name in `overrideName`, and set the region and project
in `GCP_DEFAULT_REGION` and `GCP_PROJECT_ID` (or in `extraData`: `{"region": …, "project": …}`).

## Messages API for a Responses model

A model whose upstream speaks only the OpenAI Responses API (Azure OpenAI, Bedrock Mantle, ...) can be called with the
Anthropic Messages API, so that a client such as Claude Code works with it. The plugin translates the request, the
answer and the stream. It is always on and there is nothing to set: the `openai` adapter looks at the endpoint of the
upstream of each Messages request, and an endpoint ending in `/responses` is translated (one with `/v1/messages` in it
is passed through, as always). Point the model's `anthropicMessages` interface at the `openai` entry, with the
`upstreams` of the Responses model:

```json
{
  "models": {
    "gpt-5": {
      "type": "chat",
      "interfaces": {
        "anthropicMessages": { "base_url": "dial-plugin://model-provider-openai" },
        "openaiResponses": { "base_url": "dial-plugin://model-provider-openai" }
      },
      "upstreams": [
        { "endpoint": "https://contoso.openai.azure.com/openai/v1/responses", "key": "AZURE_OPENAI_KEY" }
      ]
    }
  }
}
```

Another way is DIAL Core's *translator* contract, where the model's Messages interface is a translator and the Responses
call goes to DIAL Core itself (the model is then the deployment `out`). Declare the entry
`"translator": "openaiResponses"` (no `adapter`) and set the interface to a translator with
`baseUrl: dial-plugin://<entry name>`. The standalone service serves the same at `POST /openaiResponses/anthropic/v1/messages`,
calling `<DIAL_URL>openai/v1/responses` with the caller's key.

What is translated, what is dropped (thinking blocks of another model's signature, `cache_control`, `blocked_domains`
and more), and every failure are in [docs/messages-to-responses-translation.md](docs/messages-to-responses-translation.md).

## Settings

Set them in the plugin entry or in DIAL Core's environment. The cloud SDKs also read their own variables from there:
the AWS default chain (`AWS_PROFILE`, web identity, instance credentials), Azure's `DefaultAzureCredential` and
Google's application default credentials.

| Variable                                                                                 | Adapter           | Default                                        | Meaning                                                                                             |
| ---------------------------------------------------------------------------------------- | ----------------- | ---------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `HTTP_MAX_CONNECTIONS`                                                                   | openai, vertexai  | `1000`, `250`                                  | Upstream calls in flight per adapter                                                                |
| `HTTP_POOL_TIMEOUT`                                                                      | openai, vertexai  | `10`                                           | Seconds a call waits for a free slot, then `503`. `0`: no limit                                     |
| `ANTHROPIC_MAX_CONNECTIONS`, `ANTHROPIC_POOL_TIMEOUT`                                    | bedrock           | `1000`, `10`                                   | The same                                                                                            |
| `REQUEST_TIMEOUT_SECONDS`                                                                | bedrock           | `600`                                          | Wait for response headers and for each body read                                                    |
| `SSE_HEARTBEAT_INTERVAL`                                                                 | openai            | `0` (off)                                      | Seconds of silence in a Responses stream before a `: ping`                                          |
| `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`                                                  | all               | unset                                          | Proxy for upstream calls                                                                            |
| `DIAL_URL`                                                                               | all               | unset                                          | DIAL Core URL for the user info of AWS session tags                                                 |
| `AZURE_OPEN_AI_SCOPE`                                                                    | openai            | `https://cognitiveservices.azure.com/.default` | Scope of the Azure AD token                                                                         |
| `AWS_BEDROCK_BASE_URL`, `AWS_BEARER_TOKEN_BEDROCK`                                       | openai            | unset                                          | Bedrock Mantle base URL and bearer token, for Responses                                             |
| `VLLM_DEPLOYMENTS`, `QWEN3_ASR_VLLM_DEPLOYMENTS`                                         | openai            | unset                                          | Comma-separated models that need an `X-Upstream-Key`, else `401`                                    |
| `ANTHROPIC_BASE_URL`                                                                     | vertexai, bedrock | `https://api.anthropic.com`                    | Anthropic API used with a key                                                                       |
| `GCP_DEFAULT_REGION`, `GCP_PROJECT_ID`                                                   | vertexai          | unset                                          | Region and project when `extraData` has none                                                        |
| `ANTHROPIC_VERTEX_BASE_URL`                                                              | vertexai          | per region                                     | Vertex AI base URL                                                                                  |
| `GOOGLE_APPLICATION_CREDENTIALS`                                                         | vertexai          | unset                                          | Application default credentials, or an AWS workload identity file                                   |
| `GOOGLE_CLOUD_QUOTA_PROJECT`                                                             | vertexai          | unset                                          | Quota project of the credentials                                                                    |
| `AWS_DEFAULT_REGION`                                                                     | bedrock           | unset                                          | Region when `extraData` has none                                                                    |
| `AWS_CLAUDE_DEFAULT_CLIENT`                                                              | bedrock           | `legacy`                                       | `legacy` (invoke-model) or `mantle`                                                                 |
| `ANTHROPIC_BEDROCK_BASE_URL`, `ANTHROPIC_BEDROCK_MANTLE_BASE_URL`                        | bedrock           | per region                                     | invoke-model and Mantle base URLs                                                                   |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, `AWS_ASSUME_ROLE_ARN` | bedrock           | unset                                          | AWS credentials when `extraData` has none. Otherwise the SDK's default chain                        |
| `AWS_ENDPOINT_URL_STS`, `AWS_ENDPOINT_URL`                                               | bedrock           | unset                                          | STS endpoint for assuming roles                                                                     |
| `AWS_SESSION_TAGS`                                                                       | bedrock           | unset                                          | JSON object: tag key to `Bedrock.modelId` or `UserInfo.<path>`. Tags the session of an assumed role |

## Standalone service

The same code can also run as a separate service next to DIAL Core. It is optional.

```sh
docker build -t ai-dial-model-provider .
docker run --rm -p 5000:5000 --env-file .env ai-dial-model-provider
```

Set the interface's `base_url` to `http://<host>:5000/compat/<adapter>`. Plugin and service answer the same requests
the same way, so a model moves between them by changing the `base_url` only:

| Plugin                                 | Service                                     |
| -------------------------------------- | ------------------------------------------- |
| `dial-plugin://model-provider-bedrock` | `http://model-provider:5000/compat/bedrock` |

Paths served. Everything else answers `404 {"detail":"Not Found"}`:

| Path                                                                        | Served                                       |
| --------------------------------------------------------------------------- | -------------------------------------------- |
| `POST /compat/{openai,vertexai,bedrock}/anthropic/v1/messages`              | Messages API, streamed or whole              |
| `POST /compat/{openai,vertexai,bedrock}/anthropic/v1/messages/count_tokens` | Token counts (`404` on Bedrock invoke-model) |
| `POST /compat/openai/openai/v1/responses`                                   | Responses API: create, streamed or whole     |
| `POST /openaiResponses/anthropic/v1/messages`                               | Messages API over DIAL Core's Responses API  |
| `POST …/responses/input_tokens`                                             | Input token count                            |
| `GET`, `DELETE …/responses/{id}`, `POST …/responses/{id}/cancel`            | Retrieve, delete, cancel                     |
| `GET /health`                                                               | Health check                                 |

Service-only variables:

| Variable               | Default | Meaning                                                                                                                                                                                             |
| ---------------------- | ------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PORT`                 | `5000`  | Listening port                                                                                                                                                                                      |
| `LOG_LEVEL`            | `INFO`  | Log level                                                                                                                                                                                           |
| `OTEL_TRACES_EXPORTER` | unset   | Tracing is off unless set (`otlp`, `console`). Other `OTEL_*` variables go to the OpenTelemetry SDK. Traces only. The plugin has no such setting: its upstream calls are spans of DIAL Core's trace |
| `USE_SYSTEM_CA_CERTS`  | unset   | Import `/certificates/*.crt` at start                                                                                                                                                               |
| `JAVA_OPTS`            | unset   | JVM flags, for example `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`                                                                                                                        |

## Limitations

- Only the Messages and Responses APIs. Other routes (chat completions, embeddings and so on) answer `404`.
- Responses requests are proxied as they are. DIAL file URLs are not downloaded or inlined.
- A missing or wrong upstream setting (endpoint, key) answers `502`.
- Upstream calls are not retried. Redirects are not followed.
- The Messages API for a Responses model: `count_tokens` is `404`; `cache_control` is ignored (no `prompt_cache_key` is
  sent: only DIAL Core's cache affinity headers are carried over); `top_k`, `stop_sequences` and a few more parameters
  are dropped; a web search's `blocked_domains` is dropped, so the search is not restricted; a `thinking` block that
  this plugin did not sign (another model's, or without its signature) is dropped with its text. See the
  [translation docs](docs/messages-to-responses-translation.md).

## Development

Needs JDK 25 and Python 3.10+ (for the HTTP test cases in [`http-tests`](http-tests/README.md)).

The `plugin` module compiles against DIAL Core's plugin API from GitHub Packages, which asks for a token even for a
public package. Set it before any build:

```sh
export GPR_USERNAME=<github user>
export GPR_PASSWORD=<token with the read:packages scope>
```

```sh
make lint test           # Checkstyle, PMD, formatting check, tests
make format              # format the sources
make install_git_hooks   # pre-push formatting check
make build-plugin-jar    # plugin/build/plugins/aidial-model-provider-plugin-<version>.jar
make bump-ai-dial-plugin-api PACKAGE_VERSION=0.51.0   # build against another plugin API version
make serve               # run the standalone service
docker build -f plugin/Dockerfile --secret id=GPR_USERNAME --secret id=GPR_PASSWORD -t model-provider-plugin .
```

Tests against live clouds: [`integration-tests`](integration-tests/README.md).

CI runs `make lint test`, then builds the plugin image and publishes it to Docker Hub.

## License

Copyright (C) 2026 EPAM Systems

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
