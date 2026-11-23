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
| `stream` | `stream`; the answer is a stream (see below) |

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

### Tools

| Messages API | Responses API |
| --- | --- |
| a custom tool (type `custom`, or none) | a `function` tool: `name`, `description` (if not empty), `parameters` the `input_schema` less its root `$schema` (OpenAI rejects it; `{"type":"object","properties":{}}` without a schema), `strict` (always sent, `false` unless the tool says so) |
| a `web_search_*` tool | the built-in `web_search`, with `filters.allowed_domains` (if not empty) and an `approximate` `user_location` (the fields not given are `null`). `blocked_domains` is dropped, with a warning: **the search is not restricted** |
| `max_uses` of a web search | `max_tool_calls`, when it is the only web search; dropped, with a warning, when there are several |
| any other tool | forwarded as sent, for the deployment to accept or refuse |
| `tool_choice` `auto`, `any`, `none` | `auto`, `required`, `none` |
| `tool_choice` `tool` | `{"type":"function","name"}`; for the web search tool `{"type":"allowed_tools","mode":"required","tools":[{"type":"web_search"}]}` |
| `tool_choice` of another type | not sent |
| `disable_parallel_tool_use` in `tool_choice` | `parallel_tool_calls` (its opposite); not sent when `tool_choice` does not say |
| `output_config.format` of type `json_schema` with a schema | `text.format`: `{"type":"json_schema","name":"response","schema","strict":true}`; any other format is dropped, with a warning |

**Tool names.** A name that is not `^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$` is replaced by an alias, in `tools`, in `tool_choice`
and in the replayed `tool_use` blocks, and the answer says the name again. The alias is the name with each character
that is not a letter, a digit, `_` or `-` as `_` (a `t_` before it when it does not start with a letter or `_`), cut to
55 characters, `_` and the first 8 hex digits of the name's SHA-256: `get weather` is `get_weather_dce3870e`. It lives
for one request.

### Thinking

| Messages API | Responses API |
| --- | --- |
| `thinking` of type `disabled` | `reasoning.effort: none`, whatever effort is asked |
| the effort of the last visible `system` turn (`output_config.effort`), else `output_config.effort` | `reasoning.effort`, if it is an effort the Responses API has (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`); any other (`ultra`) is a `400`: "Cannot convert Anthropic Messages API reasoning effort ultra to an OpenAI reasoning effort" |
| `thinking` of another type (`enabled`, `adaptive`), unless its `display` is `omitted` or the effort is `none` | `reasoning.summary: auto`: unlike Anthropic, the Responses API returns no reasoning text unless asked for a summary. `budget_tokens` has no equivalent and is ignored |
| neither | no `reasoning` at all: the deployment's default stays |

A system turn that was cleared (`clear_at`) does not count.

The answer's reasoning item is a `thinking` block: its summary and content texts joined by a blank line, and its
encrypted content as the `signature` (see below). With a text but no encrypted content the `signature` is empty; with
encrypted content but no text it is a `redacted_thinking` block whose `data` is the signature; with neither it is
nothing.

**The signature.** The Responses API keeps no state here (`store: false`), so the model can only think on from its earlier
reasoning if the client gives it back, encrypted. The translator puts the encrypted reasoning item in the `signature` of
the `thinking` block (or the `data` of the `redacted_thinking` block) as JSON:

```json
{"dial_translator_reasoning_wrapper":{"version":2,"id":"rs_1","encrypted_content":"gAAAA..."}}
```

A client that sends the block back unchanged in the next turn gets it turned into a `reasoning` item, with the thinking
text as its summary, in front of the items that follow it.

#### Thinking blocks that are dropped

**A `thinking` or `redacted_thinking` block whose signature is not a wrapper like the one above is dropped from the
request, its text with it.** Nothing says so to the client or in the log above the debug level. The model does not
get to see that reasoning, and no error says it was lost. It is the translator's behaviour on purpose, and it can be
surprising:

- *The signature is another provider's.* A conversation that was answered by Claude, then by a model behind this
  translator, has `thinking` blocks signed by Anthropic (`"signature": "EqQBCkYIAxgCIkD..."`). A signature is only
  worth something to the provider that made it, and the Responses API takes no reasoning that its own model did not
  encrypt. The blocks are dropped.
- *The signature is gone.* A client that rewrites or summarizes its history may keep a `thinking` block's text and
  lose its `signature` (`""`), or change it. The block is dropped. A `redacted_thinking` block has only a signature,
  so it is dropped whole.
- *The wrapper is of another version.* Only `"version": 2` is replayed.

An example. The request:

```json
{"messages": [
  {"role": "user", "content": "hi"},
  {"role": "assistant", "content": [
    {"type": "thinking", "thinking": "Claude thought this", "signature": "EqQBCkYIAxgCIkD"},
    {"type": "thinking", "thinking": "Our own", "signature": "{\"dial_translator_reasoning_wrapper\":{\"version\":2,\"id\":\"rs_1\",\"encrypted_content\":\"ENC\"}}"},
    {"type": "redacted_thinking", "data": "EqQBCkYIAxgCIkD"},
    {"type": "text", "text": "Hello"}
  ]}
]}
```

is sent to the Responses API with the text and **only the block that this translator signed**:

```json
{"input": [
  {"type": "message", "role": "user", "content": [{"type": "input_text", "text": "hi"}]},
  {"type": "reasoning", "id": "rs_1", "encrypted_content": "ENC", "summary": [{"type": "summary_text", "text": "Our own"}]},
  {"type": "message", "role": "assistant", "content": "Hello"}
]}
```

"Claude thought this" and the redacted block are not in it. Without the block that was signed here, the same request
sends the assistant text alone:

```json
{"input": [
  {"type": "message", "role": "user", "content": [{"type": "input_text", "text": "hi"}]},
  {"type": "message", "role": "assistant", "content": "Hello"}
]}
```

What to do about it: to keep a model's reasoning across turns, send its `thinking` and `redacted_thinking` blocks back
exactly as they came, `signature` and `data` included. Reasoning cannot be carried from one provider's model to
another's.

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
- A `function_call` is a `tool_use` (its `arguments` as the `input`: `{}` when they are no JSON object) and makes the
  `stop_reason` `tool_use`; the tool's own name comes back (see above).
- A URL cited by an output text, the first time it is cited in the answer, is a `web_search_result` of a `web_search_tool_result`,
  which follows the text, with the `server_tool_use` it answers (`input.query` is empty: the search is not known).
- `stop_reason`: `max_tokens` when the response is incomplete because of `max_output_tokens`, `refusal` when it is
  because of `content_filter`; otherwise `tool_use` if it holds a function call, else `refusal` if it holds a refusal,
  else `end_turn`. A tool call that was cut by the output budget is `max_tokens`.
- `usage` counts as Anthropic does: `input_tokens` leaves out what the cache read and wrote, which the Responses API's
  `input_tokens` includes. `cache_read_input_tokens` is `input_tokens_details.cached_tokens` (at most the input tokens),
  `cache_creation_input_tokens` is `cache_write_tokens` (at most what the reads left), `output_tokens_details.thinking_tokens`
  is `reasoning_tokens`, and `server_tool_use` counts the `web_search_call` items (`web_search_requests` a search,
  `web_fetch_requests` the opening of a page), when there are any. A missing counter is 0; with no `usage` at all the
  message says `input_tokens` and `output_tokens` of 0 and nothing else.
- An output item or content part this page does not list is dropped, with a warning in the log; so is an unknown value
  of an enumeration. Only what the translator reads is checked: a field it never looks at may be of any type.

## Streaming

With `stream: true` the answer is a Messages API event stream (`Content-Type: text/event-stream`), each event written as
`event: <type>` and one line of compact JSON as `data:`.

- `message_start` comes with the response's `response.created` (the message `id` is the response's), or, without one,
  before the first block. Its `usage` is zeros; the final one is in `message_delta`.
- One content block is open at a time; opening a block closes the one before. A text is a block of its own per content
  part of an output item (`output_index`, `content_index`), a refusal's text too. The deltas are `text_delta`.
- `response.completed` and `response.incomplete` end the message: an empty `text` block if nothing was sent, the
  open block's `content_block_stop`, `message_delta` (the `stop_reason` and the `usage` of the final response, see
  above) and `message_stop`. Events after the end are ignored.
- A comment line of the upstream (`: ping`, which is how its heartbeat looks) is a `ping` event, wherever it falls.
- The other events of the Responses API (`response.in_progress`, `response.content_part.added`, `response.output_text.done`,
  ...) say nothing the message needs and are dropped; so is `[DONE]`. An event of a type outside the API's `response.`
  namespace is named once in a warning.

**What goes wrong in a stream** goes in an `error` event, the response being `200` already, and the stream ends:

| What | `error.type`, message |
| --- | --- |
| a `response.failed` event, or an `error` event | by the error code, as for a response that failed: `rate_limit_error`, `timeout_error`, `invalid_request_error`, `api_error`, with the upstream's message |
| the stream ends without `response.completed`, `response.incomplete` or a failure | `api_error`, "The Responses API stream ended before the response completed" |
| an event that is not JSON, or has no `type`, or has a field the translator reads of the wrong type | `api_error`, "Internal server error"; the detail is logged |
| the connection to the upstream breaks | `api_error`, "Internal server error"; the detail is logged |

An upstream that answers a stream request with an error status gets the same error answer as for a request that is not
streamed (a JSON body, not a stream). A client that goes away ends the upstream call.

## Failures

Everything the client sees is in the Anthropic API's error body, `{"type":"error","error":{"type","message"}}`.

- The upstream answers with a status of 400 or more: the same status. The message is its `error.message`, else its
  text, else "The Responses API handler answered <status>". Its headers go on, `Retry-After` included. `error.type`
  follows the status: 400 `invalid_request_error`, 401 `authentication_error`, 402 `billing_error`, 403
  `permission_error`, 404 `not_found_error`, 409 `conflict_error`, 413 `request_too_large`, 429 `rate_limit_error`, 504
  `timeout_error`, 503 `overloaded_error`, any other 4xx `invalid_request_error`, anything else `api_error`.
- The response says `status: failed`: its error message, with a status by the error code: `rate_limit_exceeded` 429,
  `vector_store_timeout` 504, the codes that blame the request (`invalid_prompt`, `context_length_exceeded`, the
  `invalid_image...` ones and so on) 400, any other code or none 500.
- The upstream's answer is not a Responses API response (not JSON, no `output`, a token count that is no number): a
  `500` with the message "Internal server error"; the detail is logged, never sent. An item or content part with no
  `type` is such an answer.
- `count_tokens` is not served for a Responses API upstream: `404`, "Endpoint not supported".
