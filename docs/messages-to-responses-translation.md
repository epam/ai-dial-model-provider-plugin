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
| a `user` message | an `input` message of the parts its blocks become (below), if it has any |
| an `assistant` message | its texts joined by a line feed in one `assistant` message; its `tool_use` blocks are function calls (below) |
| a `system` turn | a `system` message of its text |
| `max_tokens` | `max_output_tokens`; 1 to 15 is raised to 16 (the API refuses less), `0` stays `0` |
| `temperature`, `top_p` | the same |
| `metadata.user_id` | `user`; dropped, not truncated, when longer than 64 characters |
| `service_tier` | `auto` is `auto`, `standard_only` is `default`, any other is dropped |
| `stream` | `stream` (streaming is not translated yet: such a request is answered `501`) |

### Content blocks

| Block | Part of the message |
| --- | --- |
| `text` (not empty) | `input_text` |
| `image`, `base64` | `input_image`, `image_url` a data URL (`image/png` when the media type is missing), `detail: auto` |
| `image`, `url` | `input_image` with the URL |
| `document`, `base64` | `input_file`, `filename` the `title` (`document.pdf` without one), `file_data` a data URL |
| `document`, `url` | `input_file` with `file_url` |
| `document`, `text` | `input_text` of its data |
| `document`, `content` | the parts of its text and image blocks |
| `search_result` | `input_text` of `<title>`, `<source>`, a blank line and its texts joined by a line feed |
| `compaction` | `input_text` of its `content`, if it has any |
| any other | dropped |

An `image` or `document` whose source is a file of the Anthropic Files API is a `400`: "Files uploaded via Anthropic
Files API aren't supported.".

### Turns, in order

- **A user turn** gives its `tool_result` blocks first (each a `function_call_output`), then its `mid_conv_system`
  text (a `system` message), then one `user` message of the rest. The outputs answer the calls of the previous turn,
  and the system text prefaces the user text.
- **An assistant turn** gives its `mid_conv_system` text first, then its blocks in order. Adjacent `text` and
  `compaction` blocks are one message, which ends at every `tool_use`; a `tool_use` is a `function_call` with the
  `input` as its `arguments` (`{}` without one).
- **A tool result** of only text is a string output (the texts joined by a line feed, prefixed with `Error: ` when
  `is_error`). With an image or document it is the list of parts (a first part `Error:` when `is_error`). A
  `tool_reference` is dropped.
- **A system turn** that says `clear_at: next_user_message` is dropped once a user message follows it, and stays while
  none does. A system turn, and a `mid_conv_system` block, hold a string or text blocks (and nested `mid_conv_system`
  blocks); an empty one is nothing.

Everything dropped is named in one warning in the log.

Always sent: `store: false` and `include: ["reasoning.encrypted_content"]`. Not sent, with one warning in the log:
`top_k`, `stop_sequences`, `compaction`, `mcp_servers`, `container`, `inference_geo`, `context_management`.
`cache_control` is ignored.

A request that cannot be translated is a `400` in the Anthropic API's error body: a body that is not a JSON object, a
field of the wrong type, an unknown role.

## The answer

The message is sparse: `id`, `type`, `role`, `model`, `content`, `stop_reason`, `usage`.

- `model` is the deployment's name, `X-DIAL-DEPLOYMENT-ID`, whatever model the request names. DIAL Core always sends
  the header; without it the request is a `500`.
- `content` is a `text` block per output text, and per refusal (its text); an answer with none is one empty `text` block.
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
