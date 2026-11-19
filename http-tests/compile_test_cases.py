#!/usr/bin/env python3
"""Compiles declarative HTTP test-case templates into plain YAML test cases (see README.md).

    python compile_test_cases.py --input cases --output build/compiled [--schema schema/test-case.schema.json]

Every `*.yaml`/`*.yml` file under --input whose path has no component starting with `_` or `.` (and that is not a
`base.yaml`) is a test-case template, and so is every such `*.yaml.j2`/`*.yml.j2`: only a `.j2` file is rendered by
Jinja2 (StrictUndefined; includes and imports resolve against --input), and a plain one with Jinja2 syntax is refused.
A template is parsed as YAML (safe loader, one test case per document), its `!ref` tags and `$base64(...)` calls are
resolved, `definitions` dropped, and each case is merged onto the `base.yaml` (or `base.yaml.j2`) files of the
directories above it (the outermost first; `!replace` and `!delete` steer the merge) and checked against the JSON data
model and the schema. Each template's cases are written to the same relative path under --output, without `.j2`;
--output is emptied first (only if it is empty or was written by this script).
"""

from __future__ import annotations

import argparse
import base64
import copy
import json
import math
import pathlib
import re
import sys
import traceback
from dataclasses import dataclass

import jinja2
import jsonschema
import yaml

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_SCHEMA = HERE / "schema" / "test-case.schema.json"
MARKER = ".compiled-test-cases"
# A YAML file, plain or a Jinja2 template of one. A reference without ":" to such a file is to its whole document; any
# other is a path in the same document.
YAML_FILE = re.compile(r"\.ya?ml(\.j2)?$")
TEMPLATE = ".j2"
# What only a template may hold: a Jinja2 expression, statement or comment.
JINJA = re.compile(r"\{[{%#]")
# $base64('file') and $base64url('file') in a string: the file's bytes in base64 (RFC 4648 section 4 or 5, padded);
# $$base64(...) is the literal text $base64(...).
FUNCTION = re.compile(r"""(\$?)\$(base64url|base64)\((?:'([^']*)'|"([^"]*)")\)""")
MALFORMED = re.compile(r"(?<!\$)\$base64(?:url)?\(")
ENCODERS = {"base64": base64.b64encode, "base64url": base64.urlsafe_b64encode}
BASES = ("base.yaml", "base.yaml" + TEMPLATE)
# The members that hold the body of a request, a mock's request or response, an expectation and a multipart part: one
# at a time, so a body of one form replaces an inherited body of another.
BODY_FORMS = frozenset({"body", "body_base64", "json", "sse", "multipart"})
BODY_LEVELS = {("request",), ("mocks", "*", "request"), ("mocks", "*", "response"), ("expect",),
               ("mocks", "*", "request", "multipart", "*")}


class CompileError(Exception):
    pass


@dataclass(frozen=True)
class Ref:
    """An unresolved `!ref <target>` and where it was written."""

    target: str
    file: pathlib.Path
    line: int
    column: int

    def where(self, root: pathlib.Path) -> str:
        return f"{rel(self.file, root)}:{self.line}:{self.column}"


@dataclass(frozen=True)
class Replace:
    """A value that replaces the inherited one whole instead of merging into it: `!replace <value>`, or a !ref."""

    value: object


class Delete:
    """`!delete`: the inherited member is removed."""


def rel(path: pathlib.Path, root: pathlib.Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return str(path)


def template_line(error: Exception, root: pathlib.Path) -> str | None:
    """`file:line` of the innermost template frame of a rendering error (Jinja2 rewrites tracebacks to templates)."""
    found = None
    for frame in traceback.extract_tb(error.__traceback__):
        path = pathlib.Path(frame.filename)
        if path.suffix in (".yaml", ".yml", ".j2", ".jinja") and path.exists():
            found = f"{rel(path.resolve(), root)}:{frame.lineno}"
    return found


def loader_for(file: pathlib.Path) -> type[yaml.SafeLoader]:
    """A safe loader that reads `!ref` scalars as Ref markers of this file, `!replace` and `!delete` as theirs."""

    class Loader(yaml.SafeLoader):
        pass

    def construct_ref(loader: yaml.SafeLoader, node: yaml.Node) -> Ref:
        target = str(loader.construct_scalar(node)).strip() if isinstance(node, yaml.ScalarNode) else ""
        if not target:
            raise yaml.constructor.ConstructorError(None, None, "!ref takes a non-empty scalar target", node.start_mark)
        return Ref(target, file, node.start_mark.line + 1, node.start_mark.column + 1)

    def construct_delete(loader: yaml.SafeLoader, node: yaml.Node) -> Delete:
        if not isinstance(node, yaml.ScalarNode) or node.value:
            raise yaml.constructor.ConstructorError(None, None, "!delete takes no value", node.start_mark)
        return Delete()

    def construct_replace(loader: yaml.SafeLoader, node: yaml.Node) -> Replace:
        # The same node untagged, so that it is read as it would be without !replace.
        if isinstance(node, yaml.MappingNode):
            plain = yaml.MappingNode("tag:yaml.org,2002:map", node.value, node.start_mark, node.end_mark, node.flow_style)
        elif isinstance(node, yaml.SequenceNode):
            plain = yaml.SequenceNode("tag:yaml.org,2002:seq", node.value, node.start_mark, node.end_mark,
                                      node.flow_style)
        else:
            tag = loader.resolve(yaml.ScalarNode, node.value, (node.style is None, False))
            plain = yaml.ScalarNode(tag, node.value, node.start_mark, node.end_mark, node.style)
        return Replace(loader.construct_object(plain, deep=True))

    Loader.add_constructor("!ref", construct_ref)
    Loader.add_constructor("!delete", construct_delete)
    Loader.add_constructor("!replace", construct_replace)
    return Loader


def show(path: tuple) -> str:
    return "".join(f"[{k}]" if isinstance(k, int) else f".{k}" for k in path).lstrip(".") or "<case>"


def is_matcher(value: dict) -> bool:
    return bool(value) and all(isinstance(k, str) and k.startswith("$") for k in value)


def plain(value, path: tuple = ()):
    """A value that replaces what it is merged onto: its markers unwrapped; a !delete in it has nothing to remove."""
    if isinstance(value, Replace):
        return plain(value.value, path)
    if isinstance(value, Delete):
        raise CompileError(f"{show(path)}: !delete, but nothing is inherited there")
    if isinstance(value, dict):
        return {k: plain(v, path + (k,)) for k, v in value.items()}
    if isinstance(value, list):
        return [plain(v, path + (i,)) for i, v in enumerate(value)]
    return value


def merge(base, patch, path: tuple = ()):
    """`patch` (a case or a base.yaml) applied to what it inherits (README "Bases"): mappings merge member by member
    (new members after the inherited ones), lists element by element with the patch's length, anything else, a matcher
    and a Replace replace; a body of one form drops an inherited body of another; !delete removes a member."""
    if isinstance(patch, Replace):
        return plain(patch.value, path)
    if isinstance(base, dict) and isinstance(patch, dict) and not is_matcher(base) and not is_matcher(patch):
        out = dict(base)
        if tuple("*" if isinstance(k, int) else k for k in path) in BODY_LEVELS and BODY_FORMS & patch.keys():
            for form in BODY_FORMS - patch.keys():
                out.pop(form, None)
        for key, value in patch.items():
            if isinstance(value, Delete):
                if key not in out:
                    raise CompileError(f"{show(path + (key,))}: !delete, but nothing is inherited there")
                del out[key]
            else:
                out[key] = merge(out[key], value, path + (key,)) if key in out else plain(value, path + (key,))
        return out
    if isinstance(base, list) and isinstance(patch, list):
        return [merge(base[i], p, path + (i,)) if i < len(base) else plain(p, path + (i,)) for i, p in enumerate(patch)]
    return plain(patch, path)


class Compiler:
    def __init__(self, root: pathlib.Path, schema: dict | None):
        self.root = root.resolve()
        self.env = jinja2.Environment(
            loader=jinja2.FileSystemLoader(str(self.root)),
            undefined=jinja2.StrictUndefined,
            keep_trailing_newline=True,
            trim_blocks=True,
            lstrip_blocks=True,
            autoescape=False,
        )
        # `tojson` keeps member order (bodies are compared as written) and stays ASCII, so any value is a valid
        # YAML flow scalar.
        self.env.policies["json.dumps_kwargs"] = {"sort_keys": False}
        self.validator = jsonschema.Draft202012Validator(schema) if schema else None
        self.documents: dict[pathlib.Path, list] = {}
        self.bases: dict[pathlib.Path, dict] = {}

    # ---- rendering and parsing ----

    def load(self, file: pathlib.Path) -> list:
        """The file's YAML documents after Jinja2 rendering, with Ref markers left in place; cached."""
        file = file.resolve()
        if file not in self.documents:
            name = rel(file, self.root)
            try:
                text = self.env.get_template(name).render() if file.suffix == TEMPLATE else file.read_text("utf-8")
            except jinja2.TemplateNotFound as e:
                raise CompileError(f"{name}: template not found: {e.name}") from e
            except jinja2.TemplateSyntaxError as e:
                where = rel(pathlib.Path(e.filename).resolve(), self.root) if e.filename else name
                raise CompileError(f"{where}:{e.lineno}: Jinja2 syntax error: {e.message}") from e
            except jinja2.UndefinedError as e:
                raise CompileError(f"{template_line(e, self.root) or name}: undefined Jinja2 variable: {e.message}") from e
            jinja = file.suffix != TEMPLATE and JINJA.search(text)
            if jinja:
                line = text.count("\n", 0, jinja.start()) + 1
                raise CompileError(f"{name}:{line}: Jinja2 syntax in a plain YAML file: name it {file.name}{TEMPLATE}")
            try:
                docs = [d for d in yaml.load_all(text, Loader=loader_for(file)) if d is not None]
            except yaml.YAMLError as e:
                mark, lines = getattr(e, "problem_mark", None), text.splitlines()
                at = min(mark.line, len(lines) - 1) if mark and lines else None
                line = f"\n  rendered line {at + 1}: {lines[at]!r}" if at is not None else ""
                raise CompileError(f"{name}: invalid YAML after rendering: {e}{line}") from e
            self.documents[file] = docs
        return self.documents[file]

    # ---- references ----

    def target_file(self, written_in: pathlib.Path, file_part: str, what: str) -> pathlib.Path:
        """A file named in `written_in`: relative to its directory, or to the root when it starts with /."""
        base = self.root if file_part.startswith("/") else written_in.parent
        path = (base / file_part.lstrip("/")).resolve()
        if path != self.root and self.root not in path.parents:
            raise CompileError(f"{what} points outside {self.root}")
        if not path.is_file():
            raise CompileError(f"{what}: no file {rel(path, self.root)}")
        return path

    def lookup(self, ref: Ref, doc, stack: tuple) -> tuple:
        """The referenced value, the file and document it lives in, and its identity for cycle detection."""
        file_part, colon, path = ref.target.partition(":")
        if not colon:
            file_part, path = (ref.target, "") if YAML_FILE.search(ref.target) else ("", ref.target)
        if file_part:
            file = self.target_file(ref.file, file_part, f"{ref.where(self.root)}: !ref {ref.target!r}")
            docs = self.load(file)
            if len(docs) != 1:
                raise CompileError(f"{ref.where(self.root)}: !ref {ref.target!r}: {rel(file, self.root)} has "
                                   f"{len(docs)} YAML documents, a referenced file needs exactly one")
            target_doc = docs[0]
        else:
            file, target_doc = ref.file, doc
        value = target_doc
        walked = []
        for segment in path.split(".") if path else []:
            if isinstance(value, Ref):  # a path through another reference
                value = self.resolve(value, target_doc, file, stack)
            if isinstance(value, Replace):
                value = value.value
            walked.append(segment)
            here = ".".join(walked[:-1]) or "<document>"
            if isinstance(value, dict):
                if segment not in value:
                    keys = ", ".join(map(str, value)) or "none"
                    hint = "" if file_part else \
                        " (a file and a path in it are written <file>.yaml:<path>)" if re.search(r"\.ya?ml\.", path) \
                        else " (a reference to a file names a .yaml or .yml file)" if "/" in path else ""
                    raise CompileError(f"{ref.where(self.root)}: !ref {ref.target!r}: no key {segment!r} in "
                                       f"{here} (keys: {keys}){hint}")
                value = value[segment]
            elif isinstance(value, list):
                if not segment.isdigit() or int(segment) >= len(value):
                    raise CompileError(f"{ref.where(self.root)}: !ref {ref.target!r}: {here} is a list of "
                                       f"{len(value)}, {segment!r} is not an index in it")
                value = value[int(segment)]
            else:
                raise CompileError(f"{ref.where(self.root)}: !ref {ref.target!r}: {here} is a scalar, "
                                   f"it has no {segment!r}")
        return value, file, target_doc, (file, id(target_doc), path or "")

    def resolve(self, value, doc, file: pathlib.Path, stack: tuple = (), whole: bool = False):
        """The value, written in this file and document, with every reference replaced by a copy of its resolved
        target and every function call in its strings by its result. With `whole` (a case or a base.yaml), a value
        written as a !ref comes back as a Replace: a reference names a whole value, which is never merged."""
        if isinstance(value, Ref):
            target, target_file, target_doc, identity = self.lookup(value, doc, stack)
            chain = stack + ((identity, value),)
            if any(seen == identity for seen, _ in stack):
                steps = " -> ".join(f"{r.where(self.root)} !ref {r.target}" for _, r in chain)
                raise CompileError(f"circular !ref: {steps}")
            resolved = self.resolve(copy.deepcopy(target), target_doc, target_file, chain)
            return Replace(resolved) if whole else resolved
        if isinstance(value, Replace):
            return Replace(self.resolve(value.value, doc, file, stack))
        if isinstance(value, dict):
            return {k: self.resolve(v, doc, file, stack, whole) for k, v in value.items()}
        if isinstance(value, list):
            return [self.resolve(v, doc, file, stack, whole) for v in value]
        if isinstance(value, str) and "$base64" in value:
            return self.functions(value, file)
        return value

    def functions(self, text: str, file: pathlib.Path) -> str:
        """The text with each $base64('file') and $base64url('file') replaced by that file's bytes in base64."""
        def call(m: re.Match) -> str:
            if m.group(1):
                return m.group(0)[1:]
            name = m.group(3) if m.group(3) is not None else m.group(4)
            path = self.target_file(file, name, f"{rel(file, self.root)}: {m.group(0)[:200]}")
            return ENCODERS[m.group(2)](path.read_bytes()).decode("ascii")

        bad = MALFORMED.search(FUNCTION.sub("", text))
        if bad:
            raise CompileError(f"{rel(file, self.root)}: malformed call {text[bad.start():bad.start() + 60]!r}: write "
                               f"$base64('<file>') or $base64url('<file>'), or $$base64 for the text $base64")
        return FUNCTION.sub(call, text)

    # ---- checks ----

    @staticmethod
    def check_json(value, path="") -> None:
        """Compiled cases stay inside the JSON data model, so that any runner can read them."""
        if isinstance(value, dict):
            for k, v in value.items():
                if not isinstance(k, str):
                    raise CompileError(f"{path or '<case>'}: key {k!r} is a {type(k).__name__}, keys must be strings "
                                       f"(quote it)")
                Compiler.check_json(v, f"{path}.{k}" if path else k)
        elif isinstance(value, list):
            for i, v in enumerate(value):
                Compiler.check_json(v, f"{path}[{i}]")
        elif isinstance(value, float) and not math.isfinite(value):
            raise CompileError(f"{path}: {value} is not a JSON number")
        elif value is not None and not isinstance(value, (str, bool, int, float)):
            raise CompileError(f"{path}: a YAML {type(value).__name__} is not a JSON value (quote it)")

    def validate(self, case, where: str) -> None:
        if self.validator is None:
            return
        errors = sorted(self.validator.iter_errors(case), key=lambda e: list(e.absolute_path))
        if errors:
            lines = [f"  {'/'.join(map(str, e.absolute_path)) or '<case>'}: {e.message}" for e in errors[:10]]
            raise CompileError(f"{where}: does not match the test-case schema:\n" + "\n".join(lines))

    # ---- bases ----

    def bases_of(self, directory: pathlib.Path) -> list[pathlib.Path]:
        """The base.yaml files a template in this directory is merged onto, the outermost first."""
        chain = [directory, *directory.parents]
        chain = chain[:chain.index(self.root) + 1]
        return [b for b in map(self.base_file, reversed(chain)) if b]

    def base_file(self, directory: pathlib.Path) -> pathlib.Path | None:
        found = [directory / name for name in BASES if (directory / name).is_file()]
        if len(found) > 1:
            raise CompileError(f"{rel(directory, self.root)}: both {' and '.join(BASES)}: keep one")
        return found[0] if found else None

    def base_for(self, directory: pathlib.Path) -> dict:
        """What the cases of this directory inherit: its base.yaml merged onto its parent's; cached."""
        if directory not in self.bases:
            inherited = {} if directory == self.root else self.base_for(directory.parent)
            file = self.base_file(directory)
            if file:
                docs, where = self.load(file), rel(file, self.root)
                doc = docs[0] if docs else {}
                if len(docs) > 1 or not isinstance(doc, dict):
                    raise CompileError(f"{where}: a base.yaml holds one mapping: the members its cases inherit")
                values = self.resolve(doc, doc, file, whole=True)
                assert isinstance(values, dict)
                values.pop("definitions", None)
                try:
                    inherited = merge(inherited, values)
                except CompileError as e:
                    raise CompileError(f"{where}: {e}") from e
            self.bases[directory] = inherited
        return self.bases[directory]

    # ---- one template ----

    def compile_file(self, file: pathlib.Path) -> list:
        file = file.resolve()
        cases, base, bases = [], self.base_for(file.parent), self.bases_of(file.parent)
        for index, doc in enumerate(self.load(file), 1):
            where = f"{rel(file, self.root)} document {index}"
            patch = self.resolve(doc, doc, file, whole=True)
            if isinstance(patch, dict):
                patch.pop("definitions", None)
            try:
                case = merge(copy.deepcopy(base), patch)
            except CompileError as e:
                raise CompileError(f"{where}: {e}") from e
            if not isinstance(case, dict):
                raise CompileError(f"{where}: a test case is a mapping, not a {type(case).__name__}")
            if bases:
                where += f" (on {', '.join(rel(b, self.root) for b in bases)})"
            self.check_json(case)
            self.validate(case, where)
            cases.append(case)
        return cases


def discover(root: pathlib.Path) -> list[pathlib.Path]:
    files = [p for p in root.rglob("*") if YAML_FILE.search(p.name) and p.name not in BASES and p.is_file()]
    return sorted((p for p in files if not any(part.startswith(("_", ".")) for part in p.relative_to(root).parts)),
                  key=lambda p: p.relative_to(root).as_posix())


def prepare_output(output: pathlib.Path) -> None:
    if output.exists():
        if any(output.iterdir()) and not (output / MARKER).exists():
            raise CompileError(f"{output} is not empty and was not written by this script: not touching it")
        for p in sorted(output.rglob("*"), key=lambda p: len(p.parts), reverse=True):
            if p.is_dir() and not p.is_symlink():
                p.rmdir()
            else:
                p.unlink()
    output.mkdir(parents=True, exist_ok=True)
    (output / MARKER).write_text("Generated by compile_test_cases.py: this directory is emptied on each run.\n")


def dump(cases: list) -> str:
    return yaml.safe_dump_all(cases, sort_keys=False, allow_unicode=True, width=120, explicit_start=True)


def compile_tree(root: pathlib.Path, output: pathlib.Path, schema: dict | None) -> dict[str, int]:
    root = root.resolve()
    if not root.is_dir():
        raise CompileError(f"{root}: no such directory")
    compiler = Compiler(root, schema)
    results, names = {}, {}
    for file in discover(root):
        cases = compiler.compile_file(file)
        suite = file.relative_to(root).parts[0] if len(file.relative_to(root).parts) > 1 else ""
        for case in cases:
            if (suite, case["name"]) in names:
                raise CompileError(f"{rel(file, root)}: test case name {case['name']!r} is used twice "
                                   f"(also in {names[suite, case['name']]})")
            names[suite, case["name"]] = rel(file, root)
        name = rel(file, root).removesuffix(TEMPLATE)
        if name in results:
            raise CompileError(f"{rel(file, root)} and {name} compile to one file: keep one")
        results[name] = cases
    if not results:
        raise CompileError(f"{root}: no test-case templates found")
    prepare_output(output)
    for name, cases in results.items():
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(dump(cases), encoding="utf-8", newline="\n")
    return {name: len(cases) for name, cases in results.items()}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", required=True, type=pathlib.Path, help="directory of test-case templates")
    parser.add_argument("--output", required=True, type=pathlib.Path, help="directory for the compiled test cases")
    parser.add_argument("--schema", type=pathlib.Path, default=DEFAULT_SCHEMA, help="JSON Schema of a compiled case")
    args = parser.parse_args(argv)
    try:
        schema = json.loads(args.schema.read_text(encoding="utf-8"))
        counts = compile_tree(args.input, args.output, schema)
    except CompileError as e:
        print(f"compile_test_cases: {e}", file=sys.stderr)
        return 1
    print(f"compile_test_cases: {sum(counts.values())} test cases from {len(counts)} templates -> {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
