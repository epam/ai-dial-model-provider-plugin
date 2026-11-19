# Declarative HTTP test cases

Test cases that describe one HTTP exchange with a server, independently of its implementation language: the request
sent, the outgoing calls the server must make (and what each is answered with), and the assertions on the answer.
They are written as YAML with `!ref` references (a file that uses Jinja2 as a Jinja2 template of YAML, `*.yaml.j2`),
and compiled into plain YAML (`*.yaml`) that any runner can execute. The Java runner is
`provider/src/test/java/com/epam/aidial/provider/testing/http`.

```
http-tests/
  compile_test_cases.py        templates -> plain YAML (render, parse, resolve !ref, validate)
  requirements.txt             the compiler's Python dependencies, all pinned
  schema/test-case.schema.json JSON Schema of a compiled case: the contract with runners
  tests/                       the compiler's tests
  cases/<suite>/*.yaml[.j2]    test-case templates (.j2: Jinja2); files and directories starting with _ are libraries
  cases/**/base.yaml[.j2]      what the cases below it inherit: each case states only how it differs (Bases)
  cases/_lib/                  what several suites share
```

The service serves the Anthropic Messages API passthrough (`/anthropic/v1/messages`, `/count_tokens` below each
mount) and the OpenAI Responses API passthrough (below the openai mount). The suite's prefix (`openai-*`, `vertexai-*`,
`bedrock-*`) names the mount the case runs against. `OpenAiResponsesCasesTest` runs the cases of the Responses routes
(`ResponsesRoutes.BASES` and below), and `AnthropicCasesTest` runs every other case, as written. A new case for a
served route goes in a suite named for its mount.

| Suite | What it covers |
| --- | --- |
| `openai-responses` | the `/responses` routes (create, input_tokens, by id): endpoint selection and URLs, query and api-version, headers and extra data, body validation and forwarding, cache affinity, streams, relayed errors, sub-resources and route matching, the key and Azure AD token, the order of checks, Azure, Bedrock Mantle (not Runtime: its host cannot be a mock) |
| `openai-anthropic` | the Messages API through a provider named by `X-Upstream-Endpoint`: forwarding, cache affinity headers, streams, upstream errors, request validation, refused configuration, Azure AD failures |
| `vertexai-anthropic` | Vertex AI, Azure Foundry and the platform behind the vertexai mount: URLs, body shaping, auth, streams, upstream errors, the beta flags and server tools dropped per cloud, validation |
| `bedrock-anthropic` | Bedrock invoke-model (its event stream decoded to SSE, and cut half way), Mantle and the platform behind the bedrock mount: the same topics, SigV4, `_files/*.bin` event streams |
| `openai-removed`, `vertexai-removed`, `bedrock-removed` | a handful of unserved routes (chat completions, embeddings, tokenize, configuration, the model list): each answers `404 {"detail":"Not Found"}` and calls nothing |

Every other path answers `404`; the handful above checks it.

A suite keeps what its cases share in `base.yaml` files, `_lib/` (values several cases use whole) and `_files/` (the
files they send or receive, `$base64`), when it has any. The Anthropic suites share `cases/_lib/anthropic-stream.yaml`
(a stream of "Hello" and an error body) and `cases/_lib/anthropic-validation.j2` (the 400s the service answers for a
body, a macro each suite calls). `bedrock-anthropic/_files/stream-*.bin` are AWS event streams (a `chunk` message per
Messages API event, CRC-32 framed; the broken ones have a bad checksum, a cut message, a throttling exception or a
payload that is not base64).

What a case cannot express (timing, concurrency, connections cut mid-body, a client going away, values internal to the
service, such as the deployment table's cache thresholds or a PDF's page count, settings an adapter never has, an
upstream answer split at every byte) stays in the Java tests.

## Running

`./gradlew test` (`make test`) does everything: `:provider:httpTestsPython` installs `requirements.txt` into
`provider/build/http-tests/python` with the `python3` on PATH (`python` on Windows; `-Ppython=<interpreter>` picks
another, 3.10 or newer, with pip), `:provider:testHttpTestCaseCompiler` runs `tests/`, and
`:provider:compileHttpTestCases` compiles `cases/` into `provider/build/generated/http-tests/http-tests/`, which is on
the test classpath. Gradle reruns these only when their inputs change, and takes the compiled cases from the build
cache when it can. `make compile-http-tests` runs only the compilation. Each compiled case is a JUnit dynamic test named
after the case:

```bash
./gradlew :provider:test --tests '*CasesTest'
```

A failure names the case, its compiled file and document, and every expectation that failed, each with its path
(`response.json.choices[0].message.content: expected "Hello" but was "Hel"`, `mocks upstream#1.request.body: differs at
character 204 ...`).

By hand, with the dependencies installed (`pip install -r http-tests/requirements.txt`):

```bash
python http-tests/compile_test_cases.py --input http-tests/cases --output build/compiled
python -m unittest discover --start-directory http-tests/tests
```

The output directory is emptied first (only if it is empty or holds the compiler's `.compiled-test-cases` marker).
After a Python upgrade, `./gradlew :provider:httpTestsPython --rerun` reinstalls the dependencies' native wheels.

## A test case

One YAML document is one case. A compiled case (`schema/test-case.schema.json` has every member):

```yaml
name: text stream                  # unique in its suite; the JUnit display name
server:                            # what the server under test starts with for this case
  env: {GCP_PROJECT_ID: p, GOOGLE_VERTEX_BASE_URL: '${mocks.upstream.url}/vertex'}
  stubs: {google_access_token: t, now_seconds: 1000}   # test doubles of its own dependencies
options: {json_member_order: strict, mock_order: strict}
request:                           # sent to the server under test
  method: POST
  path: /openai/deployments/gemini-2.5-flash/chat/completions
  query: {api-version: '1'}        # optional
  headers: {Api-Key: dk}
  json: {messages: [{role: user, content: hi}], stream: true}   # or body (text) or body_base64
mocks:                             # the calls the server must make, each answered as declared
  - server: upstream               # a mock server's name; its base URL is ${mocks.upstream.url}
    name: generate                 # optional, for failure messages
    request:                       # what the call must be
      method: POST
      path: /vertex/v1beta1/projects/p/locations/us-central1/publishers/google/models/gemini-2.5-flash:streamGenerateContent
      query: {alt: sse}            # exactly these parameters; none when omitted
      headers: {authorization: Bearer t, x-goog-*: {$absent: true}}   # no x-goog-* header
      body: '{"contents": [...]}'  # exact text; or json (structural), body_base64, multipart
    response:                      # its answer: no header is added but framing
      status: 200
      headers: {Content-Type: text/event-stream}
      sse: {line_ending: "\r\n", events: [{json: {candidates: []}}]}   # or json, body, body_base64
expect:                            # the server's answer
  status: 200
  headers: {x-dial-cache-expire-at: '1300', x-dial-*: {$absent: true}}   # that X-DIAL-* header, no other
  sse:
    events:
      - json: {choices: [{index: 0, delta: {content: Hel}}], id: {$type: string}}
      - data: '[DONE]'
```

### The server

`server` is what makes a case self-contained: the settings and stubs the server under test must run with. A runner
hands it to the server's test adapter before sending the request (the Java runner's `HttpTestRunner.Target`), which
maps `env` onto the server's configuration and `stubs` onto its test doubles; their meaning is the adapter's.
`${mocks.<name>.url}` anywhere in a case is the base URL of that mock server (`http://127.0.0.1:<port>`), substituted
when the case runs; `$${` writes a literal `${`. It is the only runtime substitution.

### Mocks

A runner starts one mock server per name the case uses. Each call to a mock server takes a declared mock of that
server:

- `options.mock_order: strict` (the default): the n-th call is the n-th mock declared for that server, whatever it
  is; a difference fails the case, and the call still gets that mock's response.
- `mock_order: any`: a call takes the first unused mock whose `request` it matches.

A call beyond the declared ones, or one no mock matches, is answered 500 and fails the case; so does a mock never
called (a call still in flight when the answer ends is waited for, up to 2 s). Order is per server: calls to different
servers may interleave.

`request` of a mock: `method` and `path` (the raw path) are compared exactly; `query` as decoded parameters (a list for
a repeated one); `headers` as below; the body as `body` (exact UTF-8 text, or a matcher), `body_base64`, `json`
(structural, below) or `multipart` (the parts in order, each `name` and any of `filename`, `content_type` (`""` when
the part has none), `body`, `body_base64`, `json`).

`response`: `status`, `headers` (sent as given), and the body: `body` (text), `body_base64`, `json` (serialized by the
runner: the server under test must not depend on its formatting) or `sse` (each event's `comment`, `event`, `id`,
`retry`, then `data` or `json` as `data:` lines, each line ended by `line_ending` (`\n` by default) and the event by
an empty line).

### Headers

`headers` maps names (case-insensitive) to a string (the values joined by `, `) or a matcher; `{$absent: true}` asserts
a header is not there. A name with `*` (any run of characters) is a pattern, which takes only `{$absent: true}`: no
header it matches may be there but those `headers` names exactly. Headers not named and matching no pattern are not
checked.

```yaml
headers:
  authorization: Bearer t
  x-vertex-ai-llm-request-type: shared
  x-vertex-ai-*: {$absent: true}   # that x-vertex-ai-* header, no other
```

### The answer

`expect.status`; `expect.headers` as above; and the body, as one of:

- `body`: the exact text, or a matcher (`{$regex: ...}`);
- `body_base64`: the exact bytes;
- `json`: the body parsed, matched structurally;
- `sse`: the body read as server-sent events (below).

### JSON matching

An expected value matches an actual one exactly: objects have the same members (`options.json_member_order: strict`
also requires the expected order), arrays the same elements in order, numbers the same value and the same kind
(`1` is not `1.0`), strings, booleans and null are equal. An object whose members all start with `$` is a matcher:

| matcher | matches |
| ------- | ------- |
| `{$type: string}` | a value of that type: `string`, `number` (integers too), `integer`, `boolean`, `null`, `object`, `array`, or a list of them |
| `{$regex: '^chatcmpl-'}` | a string with a match (a search: anchor it for a full match) |
| `{$partial: {a: 1}}` | an object with at least these members (others ignored, order not checked) |
| `{$absent: true}` | no value: as a member, the member is missing |
| `{$unordered: [1, 2]}` | an array with these elements in any order |
| `{$json: {a: 1}}` | a string holding JSON that matches (tool-call arguments, for example) |
| `{$literal: {$ref: x}}` | exactly this value, `$` members included |
| `{$one_of: [a, b]}` | a value that any of the alternatives matches (each a value or a matcher) |

Several matchers in one object must all hold. An unknown `$` member fails the case; a runner may register more (the Java
runner's `HttpTestRunner.operator`).

### Server-sent events

The body is parsed as the HTML standard's event stream: lines end at CRLF, LF or CR, comments are skipped, a blank line
dispatches an event, and transport chunks do not matter. `sse` holds:

- `events`: every event, in order (or as a multiset with `ordered: false`). Each is matched on what it names: `event`
  (`message` unless the stream sets it), `id` (the last id seen), `retry`, and `data` (the text, lines joined by LF) or
  `json` (the data parsed; data that is not JSON fails).
- `termination`: `complete` (the default: the body ended normally, on an event boundary), `incomplete_event` (it ended
  normally after a partial event, which is discarded) or `truncated` (the connection was cut before the body ended).
- `aggregate`: checks over the whole stream, each `{type, path, equals}` where `path` is dotted (numbers index arrays)
  and is read in every event whose data is JSON:
  - `concat`: the strings at `path` concatenated (`choices.0.delta.content`: the streamed text);
  - `tool_calls`: OpenAI-style streamed tool calls at `path`, merged by `index` (the first `id`, `type` and
    `function.name`, the `function.arguments` fragments concatenated), as an array ordered by index;
  - `last_value`: the last non-null value at `path` (`usage`);
  - `last_event`: `equals` is an event expectation for the last event (`{data: '[DONE]'}`);
  - `count`: the number of events.

  A runner may register more (the Java runner's `HttpTestRunner.aggregate`).

## Templates

A file named `*.yaml.j2` (or `*.yml.j2`) is rendered by Jinja2 before it is read as YAML, with `StrictUndefined` (an
undefined name fails the compilation), `trim_blocks` and `lstrip_blocks`. Includes and imports name files relative to
`--input` (`{% import "_lib/vertex.j2" as v %}`). Insert values with `tojson`: its output is a valid YAML scalar for any
value (it keeps member order and stays ASCII):

```yaml
{% set models = ["gemini-2.5-flash", "gemini-2.5-pro"] %}
{% for model in models %}
---
name: {{ ("chat - " ~ model) | tojson }}
request: {method: POST, path: {{ ("/openai/deployments/" ~ model ~ "/chat/completions") | tojson }}, json: {messages: []}}
expect: {status: 200, json: {model: {{ model | tojson }}}}
{% endfor %}
```

Use Jinja2 for anything parameterized: variables, loops, conditionals, and macros (`_lib/vertex.j2` builds request
paths, and the PDFs the fake DIAL Core serves). Only a `.j2` file is rendered: a plain `*.yaml` holding `{{`, `{%` or
`{#` fails the compilation, so the name says which files to read as templates. Its compiled file drops the `.j2`
(`chat/types.yaml.j2` compiles to `chat/types.yaml`); a template and a plain file of one name fail.

### `!ref`

`!ref <target>` is replaced by a copy of another YAML value, after rendering. The target is one of

- `<file>:<path>`: a value in another file (`_lib/vertex.yaml:bearer`), split at the first `:`;
- `<file>`, ending with `.yaml`, `.yml`, `.yaml.j2` or `.yml.j2`: that file's whole document;
- `<path>` (or `:<path>`): a value in the same document, from its root (`definitions.model`).

A file is relative to the referencing file's directory, or to `--input` when it starts with `/`, and may not leave
`--input`; a `.j2` one is rendered by Jinja2 too, and it must hold exactly one YAML document.

A path is keys separated by `.`; on a list, a key is an index from 0 (`answers.0.status`). Keys containing `.` cannot
be referenced. References resolve recursively, each against its own file and document, and may point through other
references; values keep their types. A missing file, key or index, a reference that leaves `--input`, and a cycle
(reported with the chain of references) fail the compilation.

```yaml
definitions:                        # dropped from the compiled case
  model: gemini-2.5-flash
name: refs
server: !ref _lib/server.yaml:default
request:
  method: POST
  path: /chat
  json: {model: !ref definitions.model}
mocks:
  - !ref _lib/mocks.yaml:generate   # a whole mock
expect: {status: 200, json: !ref /vertexai-gemini/_lib/answers.yaml:ok}
```

`!ref` does not parameterize: a value that varies belongs in a Jinja2 variable or macro. In a case or a `base.yaml`,
a value written as `!ref` is a whole value: it replaces what the case inherits there, never merges into it (Bases).

### `$base64`

`$base64('<file>')` in a string is replaced by the file's bytes in base64 (RFC 4648, padded); `$base64url('<file>')`
uses the URL-safe alphabet. The file is named as by `!ref` (relative to the file the string is written in, or to
`--input` with a leading `/`, never outside it), with single or double quotes, and the call may sit anywhere in a
string, inside JSON text too. `$$base64(` writes the text `$base64(`. Keep such files in a library directory
(`_files/`): they are compared byte for byte, so `.gitattributes` marks them binary.

```yaml
request:
  json:
    messages: [{role: user, content: [{type: file, file: {file_data: $base64('_files/blank-3-pages.pdf')}}]}]
mocks:
  - server: upstream
    request:
      json: {contents: [{parts: [{inlineData: {data: $base64url('_files/blank-3-pages.pdf')}}]}]}
```

### Bases

A `base.yaml` (or `base.yaml.j2`, not both) holds what every case below its directory inherits, so that a case states
only what it is about: how it differs from that default exchange. It is one mapping, a partial case (rendered by Jinja2
if a `.j2`, its `!ref` and `$base64` resolved against it, its `definitions` dropped). A case is the base of `--input`
(if any), with each one down to the case's directory merged onto it, and the case merged onto that:

```yaml
# chat/base.yaml.j2: "hi" sent, one upstream call answered "ok", the answer that makes
request: {method: POST, path: /openai/deployments/gemini-2.5-flash/chat/completions, json: {messages: [{role: user, content: hi}]}}
mocks:
- server: upstream
  request: {method: POST, path: {{ v.vertex("gemini-2.5-flash", "generateContent") }}, json: {contents: ...}}
  response: {status: 200, json: {candidates: ...}}
expect: {status: 200, json: {choices: ..., model: gemini-2.5-flash}}
```
```yaml
# chat/resolution.yaml.j2: the deployment's name reaches the upstream path and the answer's model
name: model with a space
request:
  path: /openai/deployments/a%20b/chat/completions
  headers: {X-Upstream-Extra-Data: '{"compatible_model_id":"gemini-2.5-flash"}'}
mocks:
- server: upstream
  request: {path: {{ v.vertex("a%20b", "generateContent") }}}
expect: {json: {model: a b}}
```

Merging a value onto the inherited one:

- a mapping merges member by member: a member not written is inherited, a new one is added after the inherited ones
  (`request.headers` above adds a header to the inherited ones);
- a list merges element by element, and has the length written: `mocks: []` makes no calls, `mocks: [{server:
  upstream, request: {path: /x}}]` changes the first call's path, a second element adds a call; `{}` keeps an
  element as inherited;
- anything else replaces: a scalar, a matcher object (`{$type: string}`), and a value written as `!ref`;
- `!replace <value>` replaces, without merging (`json: !replace {error: {message: bad}}`);
- `!delete` removes the inherited member (`usage: !delete`: the answer must have no `usage`); in a flow mapping, follow
  it with a space (`{usage: !delete }`); deleting what is not inherited fails the compilation;
- a body replaces an inherited body of another form: a request, a mock's request or response, `expect` and a
  multipart part hold one of `body`, `body_base64`, `json`, `sse` and `multipart`, so a case's `sse` drops the
  inherited `json`.

With `options.json_member_order: strict`, a member added to an inherited object must come after its inherited members
in the actual JSON too; write the object with `!replace` when it does not.

### What the compiler checks and writes

1. Every `*.yaml`/`*.yml` (and `*.yaml.j2`/`*.yml.j2`) under `--input` whose path has no component starting with `_`
   or `.`, other than `base.yaml` and `base.yaml.j2`, is a template.
2. It is rendered if a `.j2` (a plain file with Jinja2 syntax fails) and parsed with the safe loader (no Python tags);
   each non-empty document is a case.
3. `!ref` and `$base64` are resolved, the top-level `definitions` member dropped, and the case merged onto its
   `base.yaml` files.
4. Each case must stay in the JSON data model (string keys; no dates, binary or non-finite numbers: quote them) and
   match `schema/test-case.schema.json`; case names must be unique within a suite (a top-level directory
   of `--input`).
5. Each template's cases are written as plain YAML to the same relative path under `--output`, without `.j2`, in a
   deterministic form (the same templates always give the same bytes).
