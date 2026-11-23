# Messages API over a Responses API upstream

A model whose upstream speaks only the OpenAI Responses API can be called with the Anthropic Messages API. The
`openai` adapter looks at the upstream (`X-Upstream-Endpoint`) of each `POST /anthropic/v1/messages`:

| Endpoint | The route |
| --- | --- |
| has `/v1/messages` in it | passes the request through, as it always has |
| ends in `/responses` | translates the request for the Responses API, calls the upstream, and translates the answer back |
| neither | refuses it (`502`, "Invalid upstream endpoint format") |

The translation is a port of the Python translator of the DIAL SDK. Nothing is stored between requests, so the
Responses request always has `store: false`.

> This page grows with the translation. It describes what is translated *so far*; what is not listed here is not
> translated yet.

## The request

| Messages API | Responses API |
| --- | --- |
| `model` | `model`, if given (the model of the *answer* is the deployment's, see below) |
| `system` (a string, or text blocks joined by a blank line) | `instructions`; nothing if empty |
| a `user` message of text | an `input` message with `input_text` parts |
| an `assistant` message of text | an `input` message with the texts joined by a line feed |
| `max_tokens` | `max_output_tokens`; 1 to 15 is raised to 16 (the API refuses less), `0` stays `0` |
| `temperature`, `top_p` | the same |
| `metadata.user_id` | `user`; dropped, not truncated, when longer than 64 characters |
| `service_tier` | `auto` is `auto`, `standard_only` is `default`, any other is dropped |
| `stream` | `stream` (streaming is not translated yet: such a request is answered `501`) |

Always sent: `store: false` and `include: ["reasoning.encrypted_content"]`. Not sent, with one warning in the log:
`top_k`, `stop_sequences`, `compaction`, `mcp_servers`, `container`, `inference_geo`, `context_management`.
`cache_control` is ignored.

A request that cannot be translated is a `400` in the Anthropic API's error body: a body that is not a JSON object, a
field of the wrong type, an unknown role.

## The answer

The message is sparse: `id`, `type`, `role`, `model`, `content`, `stop_reason`, `usage`.

- `model` is the deployment's name, `X-DIAL-DEPLOYMENT-ID`, whatever model the request names. DIAL Core always sends
  the header; without it the request is a `500`.
- `content` is a `text` block per output text; an answer with none is one empty `text` block.
- `usage` is `input_tokens` and `output_tokens`; zeros without a `usage`.
- An output item or content part this page does not list is dropped, with a warning in the log.

## Failures

Everything the client sees is in the Anthropic API's error body, `{"type":"error","error":{"type","message"}}`.

- The upstream answers with a status of 400 or more: the same status. The message is its `error.message`, else its
  text, else "The Responses API handler answered <status>". Its headers go on, `Retry-After` included. `error.type`
  follows the status: 400 `invalid_request_error`, 401 `authentication_error`, 402 `billing_error`, 403
  `permission_error`, 404 `not_found_error`, 409 `conflict_error`, 413 `request_too_large`, 429 `rate_limit_error`, 504
  `timeout_error`, 503 `overloaded_error`, any other 4xx `invalid_request_error`, anything else `api_error`.
- The response says `status: failed`: a `500` with its error message.
- The upstream's answer is not a Responses API response (not JSON, no `output`, a token count that is no number): a
  `500` with the message "Internal server error"; the detail is logged, never sent.
- `count_tokens` is not served for a Responses API upstream: `404`, "Endpoint not supported".
