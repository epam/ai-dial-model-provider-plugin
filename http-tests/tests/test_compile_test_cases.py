"""The compiler and the !ref resolver: python -m unittest discover -s http-tests/tests"""

import json
import pathlib
import sys
import tempfile
import textwrap
import unittest

import yaml

HTTP_TESTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HTTP_TESTS))

from compile_test_cases import DEFAULT_SCHEMA, CompileError, compile_tree  # noqa: E402

SCHEMA = json.loads(DEFAULT_SCHEMA.read_text(encoding="utf-8"))
CASE = """
name: {name}
request: {{method: GET, path: /}}
expect: {{status: 200}}
"""


class CompilerTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = pathlib.Path(tmp.name) / "cases"
        self.out = pathlib.Path(tmp.name) / "out"
        self.root.mkdir()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text).lstrip("\n"), encoding="utf-8")

    def compile(self, schema=SCHEMA):
        compile_tree(self.root, self.out, schema)
        return {p.relative_to(self.out).as_posix(): list(yaml.safe_load_all(p.read_text(encoding="utf-8")))
                for p in sorted(self.out.rglob("*.yaml"))}

    def fails(self, *fragments):
        with self.assertRaises(CompileError) as e:
            compile_tree(self.root, self.out, SCHEMA)
        for fragment in fragments:
            self.assertIn(fragment, str(e.exception))
        return str(e.exception)

    # ---- Jinja2 ----

    def test_multiple_documents_from_a_loop(self):
        self.write("chat.yaml.j2", """
            {% set models = ["a", "b"] %}
            {% for model in models %}
            ---
            name: {{ ("chat " ~ model) | tojson }}
            request: {method: POST, path: /chat, json: {model: {{ model | tojson }}, n: 2}}
            expect: {status: 200, json: {model: {{ model | tojson }}}}
            {% endfor %}
            """)
        cases = self.compile()["chat.yaml"]
        self.assertEqual(["chat a", "chat b"], [c["name"] for c in cases])
        self.assertEqual({"model": "b", "n": 2}, cases[1]["request"]["json"])

    def test_undefined_variable_fails(self):
        self.write("a.yaml.j2", "name: {{ nope }}\nrequest: {method: GET, path: /}\nexpect: {status: 200}\n")
        self.fails("a.yaml.j2:1", "undefined Jinja2 variable", "'nope' is undefined")

    def test_jinja_syntax_error_names_the_line(self):
        self.write("a.yaml.j2", "name: x\n{% if %}\n")
        self.fails("a.yaml.j2:2", "Jinja2 syntax error")

    def test_only_a_j2_file_is_rendered(self):
        for text in ("name: {{ x }}\n", "{% set x = 1 %}\n", "{# a comment #}\n"):
            with self.subTest(text=text):
                self.write("s/a.yaml", "# a case\n" + text)
                self.fails("s/a.yaml:2: Jinja2 syntax in a plain YAML file: name it a.yaml.j2")
        self.write("s/a.yaml", CASE.format(name="plain"))
        self.write("s/b.yaml.j2", "{% set n = 'templated' %}" + CASE.format(name="{{ n }}"))
        self.assertEqual({"s/a.yaml": ["plain"], "s/b.yaml": ["templated"]},
                         {f: [c["name"] for c in cases] for f, cases in self.compile().items()})

    def test_a_template_and_a_plain_file_of_one_name_fail(self):
        self.write("a.yaml", CASE.format(name="x"))
        self.write("a.yaml.j2", CASE.format(name="y"))
        self.fails("a.yaml.j2 and a.yaml compile to one file: keep one")

    def test_imports_includes_and_macros(self):
        self.write("_lib/macros.j2", """
            {% macro header(value) %}{{ {"X-Test": value} | tojson }}{% endmacro %}
            {% set token = "t-1" %}
            """)
        self.write("_lib/expect.yaml.j2", "expect: {status: 204}\n")
        self.write("a.yaml.j2", """
            {% import "_lib/macros.j2" as m %}
            name: imported
            request: {method: GET, path: /, headers: {{ m.header(m.token) }}}
            {% include "_lib/expect.yaml.j2" %}
            """)
        case = self.compile()["a.yaml"][0]
        self.assertEqual({"X-Test": "t-1"}, case["request"]["headers"])
        self.assertEqual({"status": 204}, case["expect"])

    def test_tojson_keeps_member_order_and_escapes(self):
        self.write("a.yaml.j2", """
            {% set body = {"z": 1, "a": "quote \\" <tag> 'single' é"} %}
            name: order
            request: {method: POST, path: /, body: {{ body | tojson | tojson }}}
            expect: {status: 200}
            """)
        body = self.compile()["a.yaml"][0]["request"]["body"]
        self.assertEqual({"z": 1, "a": "quote \" <tag> 'single' é"}, json.loads(body))
        self.assertTrue(body.startswith('{"z"'))

    # ---- YAML ----

    def test_invalid_yaml_fails(self):
        self.write("a.yaml", "name: [unclosed\n")
        self.fails("a.yaml: invalid YAML after rendering", "rendered line")

    def test_no_unsafe_tags(self):
        self.write("a.yaml", "name: !!python/object/apply:os.system ['true']\n")
        self.fails("a.yaml: invalid YAML after rendering")

    def test_values_outside_json_fail(self):
        self.write("a.yaml", CASE.format(name="dated") + "description: 2024-01-01\n")
        self.fails("description: a YAML date is not a JSON value")

    def test_schema_violation_fails(self):
        self.write("a.yaml", "name: x\nrequest: {method: GET, path: /}\nexpect: {status: 200, bogus: 1}\n")
        self.fails("a.yaml document 1: does not match the test-case schema", "expect", "bogus")

    def test_duplicate_names_fail(self):
        self.write("a.yaml", CASE.format(name="same"))
        self.write("b.yaml", CASE.format(name="same"))
        self.fails("b.yaml: test case name 'same' is used twice (also in a.yaml)")

    def test_names_are_unique_within_a_suite(self):
        self.write("one/a.yaml", CASE.format(name="same"))
        self.write("two/a.yaml", CASE.format(name="same"))
        self.compile()
        self.write("two/deeper/b.yaml", CASE.format(name="same"))
        self.fails("two/deeper/b.yaml: test case name 'same' is used twice (also in two/a.yaml)")

    def test_libraries_and_hidden_files_are_not_cases(self):
        self.write("_lib/x.yaml", "not: a case\n")
        self.write(".hidden/y.yaml", "not: a case\n")
        self.write("suite/a.yaml", CASE.format(name="only"))
        self.assertEqual(["suite/a.yaml"], list(self.compile()))

    # ---- !ref ----

    def test_refs_to_files_paths_and_same_document(self):
        self.write("suite/_lib/common.yaml", """
            headers: {Api-Key: k}
            answers:
              - {status: 200, json: {n: 1, ok: true, ratio: 0.5, none: null}}
            status: 201
            """)
        self.write("suite/a.yaml", """
            definitions:
              path: /x
              method: !ref definitions.suite_method
              suite_method: POST
            name: refs
            request:
              method: !ref definitions.method
              path: !ref definitions.path
              headers: !ref _lib/common.yaml:headers
            mocks:
              - server: upstream
                request: {method: GET, path: /up}
                response: !ref /suite/_lib/common.yaml:answers.0
            expect: {status: !ref _lib/common.yaml:status}
            """)
        self.write("suite/b.yaml", "name: whole\nrequest: {method: GET, path: /}\nexpect: !ref _lib/expect.yaml\n")
        self.write("suite/_lib/expect.yaml", "status: 204\nheaders: {x-a: b}\n")
        out = self.compile()
        a = out["suite/a.yaml"][0]
        self.assertNotIn("definitions", a)
        self.assertEqual({"method": "POST", "path": "/x", "headers": {"Api-Key": "k"}}, a["request"])
        self.assertEqual({"status": 200, "json": {"n": 1, "ok": True, "ratio": 0.5, "none": None}},
                         a["mocks"][0]["response"])
        self.assertEqual(201, a["expect"]["status"])
        self.assertEqual({"status": 204, "headers": {"x-a": "b"}}, out["suite/b.yaml"][0]["expect"])
        self.assertNotIn("!ref", (self.out / "suite/a.yaml").read_text())

    def test_nested_refs_resolve_against_their_own_file(self):
        self.write("_lib/a.yaml", "value: !ref sub/b.yaml:inner\n")
        self.write("_lib/sub/b.yaml", "inner: !ref c.yaml\n")
        self.write("_lib/sub/c.yaml", "[1, 2]\n")
        self.write("t.yaml", "name: nested\nrequest: {method: GET, path: /}\nexpect: {status: 200, json: !ref _lib/a.yaml:value}\n")
        self.assertEqual([1, 2], self.compile()["t.yaml"][0]["expect"]["json"])

    def test_resolved_copies_are_independent(self):
        self.write("_lib/v.yaml", "obj: {a: 1}\n")
        self.write("t.yaml", "name: copies\nrequest: {method: GET, path: /}\n"
                             "expect: {status: 200, json: [!ref _lib/v.yaml:obj, !ref _lib/v.yaml:obj]}\n")
        json_value = self.compile()["t.yaml"][0]["expect"]["json"]
        self.assertEqual([{"a": 1}, {"a": 1}], json_value)

    def test_missing_file(self):
        self.write("t.yaml", CASE.format(name="x") + "description: !ref _lib/none.yaml\n")
        self.fails("t.yaml:4:14", "!ref '_lib/none.yaml': no file _lib/none.yaml")

    def test_missing_key(self):
        self.write("_lib/v.yaml", "a: {b: 1}\n")
        self.write("t.yaml", CASE.format(name="x") + "description: !ref _lib/v.yaml:a.c\n")
        self.fails("no key 'c' in a (keys: b)")

    def test_bad_index_and_scalar_path(self):
        self.write("_lib/v.yaml", "list: [1]\nscalar: 2\n")
        self.write("t.yaml", CASE.format(name="x") + "description: !ref _lib/v.yaml:list.3\n")
        self.fails("list is a list of 1, '3' is not an index in it")
        self.write("t.yaml", CASE.format(name="x") + "description: !ref _lib/v.yaml:scalar.x\n")
        self.fails("scalar is a scalar, it has no 'x'")

    def test_path_that_looks_like_a_file(self):
        self.write("t.yaml", CASE.format(name="x") + "description: !ref fixtures/thing\n")
        self.fails("no key 'fixtures/thing'", "a reference to a file names a .yaml or .yml file")
        self.write("t.yaml", CASE.format(name="x") + "description: !ref _lib/v.yaml.a\n")
        self.fails("no key '_lib/v'", "a file and a path in it are written <file>.yaml:<path>")

    def test_a_leading_colon_names_the_same_document(self):
        self.write("t.yaml", CASE.format(name="x") + "definitions: {k: v}\nserver: {env: {X: !ref ':definitions.k'}}\n")
        self.assertEqual({"X": "v"}, self.compile()["t.yaml"][0]["server"]["env"])

    def test_ref_outside_the_root(self):
        (self.root.parent / "secret.yaml").write_text("x: 1\n")
        self.write("t.yaml", CASE.format(name="x") + "description: !ref ../secret.yaml:x\n")
        self.fails("points outside")

    def test_referenced_file_with_two_documents(self):
        self.write("_lib/two.yaml", "a: 1\n---\na: 2\n")
        self.write("t.yaml", CASE.format(name="x") + "description: !ref _lib/two.yaml:a\n")
        self.fails("has 2 YAML documents, a referenced file needs exactly one")

    def test_circular_refs_across_files(self):
        self.write("_lib/a.yaml", "x: !ref b.yaml:y\n")
        self.write("_lib/b.yaml", "y: !ref a.yaml:x\n")
        self.write("t.yaml", CASE.format(name="x") + "description: !ref _lib/a.yaml:x\n")
        message = self.fails("circular !ref", "_lib/a.yaml:1:4 !ref b.yaml:y", "_lib/b.yaml:1:4 !ref a.yaml:x")
        self.assertIn("t.yaml:4:14", message)

    def test_circular_refs_in_one_document(self):
        self.write("t.yaml", CASE.format(name="x") + "definitions: {a: !ref definitions.b, b: !ref definitions.a}\n"
                                                     "description: !ref definitions.a\n")
        self.fails("circular !ref")

    def test_self_reference_through_a_path(self):
        self.write("t.yaml", CASE.format(name="x") + "definitions: {a: {b: !ref definitions.a}}\n"
                                                     "description: !ref definitions.a.b\n")
        self.fails("circular !ref")

    def test_ref_needs_a_scalar(self):
        self.write("t.yaml", CASE.format(name="x") + "description: !ref [a]\n")
        self.fails("!ref takes a non-empty scalar target")

    # ---- base.yaml ----

    def test_cases_merge_onto_their_bases(self):
        self.write("base.yaml", """
            options: {mock_order: any}
            request: {method: GET, path: /, headers: {a: outer, b: outer}}
            server: {env: {E: outer, F: outer}}
            expect: {status: 200, json: {x: 1, y: [1, 2], z: {$type: string}}}
            """)
        self.write("sub/base.yaml", """
            request: {headers: {a: inner}}
            server: {env: {F: !delete }}
            """)
        self.write("top.yaml", "name: top\n")
        self.write("sub/t.yaml", """
            ---
            name: plain
            ---
            name: own
            options: {json_member_order: strict}
            request: {headers: {c: case, b: !delete }}
            expect: {json: {y: [3], z: zz, w: 2}}
            """)
        out = self.compile()
        self.assertEqual(["sub/t.yaml", "top.yaml"], list(out))  # a base.yaml is not a template
        top, (plain, own) = out["top.yaml"][0], out["sub/t.yaml"]
        self.assertEqual({"a": "outer", "b": "outer"}, top["request"]["headers"])
        self.assertEqual({"a": "inner", "b": "outer"}, plain["request"]["headers"])  # inner over outer
        self.assertEqual({"env": {"E": "outer"}}, plain["server"])
        self.assertEqual({"mock_order": "any", "json_member_order": "strict"}, own["options"])
        self.assertEqual({"a": "inner", "c": "case"}, own["request"]["headers"])
        # A list takes the case's length, a matcher is replaced, a new member goes after the inherited ones.
        self.assertEqual([("x", 1), ("y", [3]), ("z", "zz"), ("w", 2)], list(own["expect"]["json"].items()))
        self.assertNotIn("&", (self.out / "sub/t.yaml").read_text())  # copies, not YAML aliases

    def test_lists_merge_element_by_element(self):
        self.write("base.yaml", """
            request: {method: GET, path: /}
            mocks: [{server: up, request: {method: GET, path: /a}, response: {status: 200, json: {k: v}}}]
            expect: {status: 200}
            """)
        self.write("t.yaml", """
            ---
            name: none
            mocks: []
            ---
            name: two
            mocks:
            - request: {path: /b}
            - {server: dial, request: {method: GET, path: /c}, response: {status: 204}}
            """)
        none, two = self.compile()["t.yaml"]
        self.assertEqual([], none["mocks"])
        self.assertEqual([{"server": "up", "request": {"method": "GET", "path": "/b"},
                           "response": {"status": 200, "json": {"k": "v"}}},
                          {"server": "dial", "request": {"method": "GET", "path": "/c"}, "response": {"status": 204}}],
                         two["mocks"])

    def test_replace_refs_and_body_forms(self):
        self.write("base.yaml", """
            request: {method: POST, path: /, json: {m: 1}}
            expect: {status: 200, json: {a: 1, b: 2}}
            """)
        self.write("_lib/x.yaml", "answer: {c: 3}\n")
        self.write("t.yaml", """
            ---
            name: replaced
            expect: {json: !replace {c: 3, d: !ref _lib/x.yaml:answer}}
            ---
            name: referenced
            expect: {json: !ref _lib/x.yaml:answer}
            ---
            name: other forms
            request: {body: raw}
            expect: {body: text}
            ---
            name: no body
            request:
              json: !delete
            """)
        replaced, referenced, forms, no_body = self.compile()["t.yaml"]
        self.assertEqual({"c": 3, "d": {"c": 3}}, replaced["expect"]["json"])
        self.assertEqual({"c": 3}, referenced["expect"]["json"])  # a !ref value is never merged
        self.assertEqual({"method": "POST", "path": "/", "body": "raw"}, forms["request"])
        self.assertEqual({"status": 200, "body": "text"}, forms["expect"])
        self.assertEqual({"method": "POST", "path": "/"}, no_body["request"])

    def test_bases_resolve_refs_and_functions_against_their_own_file(self):
        self.write("s/_lib/server.yaml", "default: {env: {K: v}}\n")
        (self.root / "s/_lib/b.bin").write_bytes(b"\x00\x01")
        self.write("s/base.yaml", """
            definitions: {status: 201}
            server: !ref _lib/server.yaml:default
            request: {method: POST, path: /, body_base64: $base64('_lib/b.bin')}
            expect: {status: !ref definitions.status}
            """)
        self.write("s/t.yaml", "name: x\n")
        case = self.compile()["s/t.yaml"][0]
        self.assertEqual({"env": {"K": "v"}}, case["server"])
        self.assertEqual("AAE=", case["request"]["body_base64"])
        self.assertEqual({"status": 201}, case["expect"])
        self.assertNotIn("definitions", case)

    def test_base_errors(self):
        for base, case, fragments in [
            (None, CASE.format(name="x") + "options:\n  mock_order: !delete\n",
             ["t.yaml document 1: options.mock_order: !delete, but nothing is inherited there"]),
            (None, CASE.format(name="x") + "options: {mock_order: !delete any}\n", ["!delete takes no value"]),
            ("a: 1\n---\nb: 2\n", CASE.format(name="x"), ["base.yaml: a base.yaml holds one mapping"]),
            ("- a\n", CASE.format(name="x"), ["base.yaml: a base.yaml holds one mapping"]),
            ("options: {x: !delete }\n", CASE.format(name="x"), ["base.yaml: options.x: !delete, but nothing"]),
            ("servr: 1\n", CASE.format(name="x"), ["t.yaml document 1 (on base.yaml): does not match", "servr"]),
        ]:
            with self.subTest(base=base, case=case):
                (self.root / "base.yaml").unlink(missing_ok=True)
                if base is not None:
                    self.write("base.yaml", base)
                self.write("t.yaml", case)
                self.fails(*fragments)

    def test_a_base_may_be_a_template(self):
        self.write("s/base.yaml.j2", "{% set s = 201 %}\nexpect: {status: {{ s }}}\n")
        self.write("s/t.yaml", "name: t\nrequest: {method: GET, path: /}\nmocks: []\n")
        self.write("s/u.yaml", "name: u\nrequest: {method: GET, path: /}\nexpect: !ref base.yaml.j2:expect\n")
        self.assertEqual({"s/t.yaml": [201], "s/u.yaml": [201]},
                         {f: [c["expect"]["status"] for c in cases] for f, cases in self.compile().items()})
        self.write("s/base.yaml", "options: {}\n")
        self.fails("s: both base.yaml and base.yaml.j2: keep one")

    # ---- $base64 ----

    def test_base64_functions(self):
        (self.root / "sub/_files").mkdir(parents=True)
        (self.root / "sub/_files/b.bin").write_bytes(b"\xfb\xff\x00")
        self.write("sub/_lib/x.yaml", "v: $base64('../_files/b.bin')\n")
        self.write("sub/t.yaml", """
            name: x
            request:
              method: POST
              path: /
              json:
                std: $base64('_files/b.bin')
                url: $base64url("/sub/_files/b.bin")
                inside: '{"data": "$base64url("_files/b.bin")", "n": 1}'
                escaped: $$base64('_files/b.bin')
                via_ref: !ref _lib/x.yaml:v
            expect: {status: 200}
            """)
        body = self.compile()["sub/t.yaml"][0]["request"]["json"]
        self.assertEqual({"std": "+/8A", "url": "-_8A", "inside": '{"data": "-_8A", "n": 1}',
                          "escaped": "$base64('_files/b.bin')", "via_ref": "+/8A"}, body)

    def test_base64_errors(self):
        (self.root / "b.bin").write_bytes(b"x")
        for call, fragment in [
            ("$base64('missing.bin')", "t.yaml: $base64('missing.bin'): no file missing.bin"),
            ("$base64('../b.bin')", "points outside"),
            ("$base64(b.bin)", "t.yaml: malformed call '$base64(b.bin)'"),
        ]:
            with self.subTest(call):
                self.write("t.yaml", CASE.format(name="x") + f"server: {{env: {{F: {call}}}}}\n")
                self.fails(fragment)

    # ---- output ----

    def test_output_is_deterministic_and_replaced(self):
        self.write("a.yaml", CASE.format(name="one"))
        self.write("b.yaml", CASE.format(name="two"))
        self.compile()
        first = (self.out / "a.yaml").read_bytes()
        (self.root / "b.yaml").unlink()
        self.assertEqual(["a.yaml"], list(self.compile()))
        self.assertEqual(first, (self.out / "a.yaml").read_bytes())

    def test_foreign_output_directory_is_left_alone(self):
        self.write("a.yaml", CASE.format(name="one"))
        self.out.mkdir()
        (self.out / "keep.txt").write_text("mine")
        self.fails("is not empty and was not written by this script")
        self.assertTrue((self.out / "keep.txt").exists())


if __name__ == "__main__":
    unittest.main()
