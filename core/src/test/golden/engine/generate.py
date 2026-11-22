#!/usr/bin/env python3
"""Generates the engine's golden files from the reference inklecate.

The engine package (com.inkyandroid.core.engine) replaces the inklecate child process of desktop
Inky with a library. This script records what the reference tool, the official inklecate 1.2.0,
does with a set of ink projects; the JVM test EngineGoldenTest then holds the engine to it. The
test needs only the committed outputs, not inklecate.

usage (from the repository root; -I keeps Python from loading code from the working directory):

    python3 -I core/src/test/golden/engine/generate.py [--inklecate /path/to/inklecate] [case ...]

Without case names every case is generated. The inklecate binary defaults to $INKLECATE, then to
/home/claude/work/inklecate-ref/bin/inklecate (the Linux build of the GitHub release
inklecate_linux_v.1.2.0.zip of inkle/ink).

Input, two kinds of case:

  * every bundled sample, core/src/main/resources/com/inkyandroid/core/snippets/<name>.ink
    (Inky's "longer ink snippets"), as a project of one file;
  * every folder core/src/test/resources/com/inkyandroid/core/golden/engine/cases/<name>/ : a
    project with main file main.ink, written for one subject each, with an optional case.json:

        {
          "first": 40,                    always the first choice, at most this many turns (default 40; 0: none)
          "random": [1, 2],               random walks; the seed drives this script's choice of choices, not the story
          "scripts": {"name": [0, 1]},    fixed choice sequences, 0-based
          "maxLines": 300,                stop reading after this many lines of one turn (endless stories)
          "timeout": 5,                   seconds to wait for the tool before recording {"timeout": true}
          "evaluate": {"after": [0], "inputs": ["x + 1", "\\"x is {x}\\""]}
                                          typed into play mode after the choices in "after"
        }

Output, in core/src/test/resources/com/inkyandroid/core/golden/engine/expected/:

  <name>.json        compile messages as inklecate prints them, whether it compiled, statistics
                     (inklecate -s), the play transcripts (inklecate -ckjp, the protocol Inky speaks)
                     and the evaluations
  <name>.story.json  the compiled story, byte for byte as inklecate -c -o wrote it (without the BOM)
  index.json         the cases and their files

A transcript is a list of events in the order the tool produced them:
  {"text": "…"} {"tags": […]} {"issues": ["RUNTIME ERROR: …"]} {"choices": [["text", ["tag"]], …]}
  {"chose": n} (what this script answered, 0-based) {"end": true}
  {"cut": true} (this script stopped reading: maxLines) {"timeout": true} (the tool went silent)
  {"crash": "first line of stderr"} (the tool died) {"exit": code} (the tool ended by itself)

The only editing of the tool's output: the temporary folder a case is copied to is removed from
messages (inklecate prints the absolute path in "Recursive INCLUDE detected").
"""
import json
import os
import random
import shutil
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CORE = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
SNIPPETS = os.path.join(CORE, "src", "main", "resources", "com", "inkyandroid", "core", "snippets")
GOLDEN = os.path.join(CORE, "src", "test", "resources", "com", "inkyandroid", "core", "golden", "engine")
CASES = os.path.join(GOLDEN, "cases")
EXPECTED = os.path.join(GOLDEN, "expected")

args = sys.argv[1:]
inklecate = os.environ.get("INKLECATE", "/home/claude/work/inklecate-ref/bin/inklecate")
if "--inklecate" in args:
    at = args.index("--inklecate")
    inklecate = args[at + 1]
    del args[at:at + 2]
only = set(args)


def decode_objects(text):
    """The JSON objects inklecate wrote, which it does not always separate by newlines."""
    decoder = json.JSONDecoder()
    objects = []
    pos = 0
    text = text.replace("\ufeff", "")
    while True:
        while pos < len(text) and text[pos].isspace():
            pos += 1
        if pos >= len(text):
            return objects
        obj, pos = decoder.raw_decode(text, pos)
        objects.append(obj)


def run(command, cwd, timeout=60):
    done = subprocess.run([inklecate] + command, cwd=cwd, capture_output=True, timeout=timeout)
    return done.returncode, done.stdout.decode("utf-8"), done.stderr.decode("utf-8")


class Player:
    """One inklecate -ckjp process, spoken to as Inky speaks to it."""

    def __init__(self, cwd, main, timeout):
        self.timeout = timeout
        self.proc = subprocess.Popen([inklecate, "-ckjp", main], cwd=cwd, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.buffer = bytearray()
        self.errors = bytearray()
        self.lock = threading.Lock()
        self.pos = 0
        threading.Thread(target=self._read, args=(self.proc.stdout, self.buffer), daemon=True).start()
        threading.Thread(target=self._read, args=(self.proc.stderr, self.errors), daemon=True).start()

    def _read(self, stream, into):
        while True:
            chunk = os.read(stream.fileno(), 65536)
            if not chunk:
                return
            with self.lock:
                into.extend(chunk)

    def until_prompt(self, max_lines=None):
        """The objects up to the next {"needInput": true}; ends early with a cut, timeout or crash marker."""
        decoder = json.JSONDecoder()
        out = []
        lines = 0
        deadline = time.time() + self.timeout
        while True:
            with self.lock:
                text = self.buffer.decode("utf-8", errors="replace").replace("\ufeff", " ")
            progressed = False
            while True:
                rest = text[self.pos:]
                stripped = rest.lstrip()
                if not stripped:
                    break
                try:
                    obj, end = decoder.raw_decode(stripped)
                except ValueError:
                    break
                self.pos += (len(rest) - len(stripped)) + end
                out.append(obj)
                progressed = True
                deadline = time.time() + self.timeout
                if obj.get("needInput"):
                    return out
                if "text" in obj:
                    lines += 1
                    if max_lines is not None and lines >= max_lines:
                        out.append({"cut": True})
                        return out
            if not progressed:
                if self.proc.poll() is not None:
                    time.sleep(0.2)  # let the readers drain
                    with self.lock:
                        if len(self.buffer.decode("utf-8", errors="replace").replace("\ufeff", " ")[self.pos:].strip()) == 0:
                            stderr = self.errors.decode("utf-8", errors="replace").replace("\ufeff", "").strip()
                            out.append({"crash": stderr.splitlines()[0]} if stderr else {"exit": self.proc.returncode})
                            return out
                elif time.time() > deadline:
                    out.append({"timeout": True})
                    return out
                time.sleep(0.01)

    def send(self, line):
        try:
            self.proc.stdin.write((line + "\n").encode("utf-8"))
            self.proc.stdin.flush()
        except BrokenPipeError:
            pass

    def close(self):
        try:
            self.proc.stdin.close()
        except BrokenPipeError:
            pass
        try:
            self.proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()


def to_events(objects):
    """inklecate's objects as transcript events; returns (events, the choices on offer, whether the turn finished)."""
    events = []
    choices = []
    finished = False
    after_compile = False
    for o in objects:
        if "compile-success" in o:
            # the next object repeats the compile's messages, which the golden holds under "compile"
            after_compile = True
            continue
        if after_compile:
            after_compile = False
            if "issues" in o:
                continue
        if "text" in o:
            events.append({"text": o["text"]})
        elif "tags" in o:
            events.append({"tags": o["tags"]})
        elif "choices" in o:
            choices = o["choices"]
            if choices:
                events.append({"choices": [[c["text"], c.get("tags") or []] for c in choices]})
        elif "issues" in o:
            if o["issues"]:
                events.append({"issues": o["issues"]})
        elif "end" in o:
            events.append({"end": True})
        elif "cut" in o or "timeout" in o or "crash" in o or "exit" in o:
            events.append(o)
        elif o.get("needInput"):
            finished = True
    return events, choices, finished


def play(cwd, main, pick, max_turns, max_lines, timeout):
    """Plays through; pick(turn, number of choices) gives the 0-based choice or None to stop."""
    player = Player(cwd, main, timeout)
    events = []
    try:
        for turn in range(max_turns + 1):
            turn_events, choices, finished = to_events(player.until_prompt(max_lines))
            events += turn_events
            if not finished or not choices or turn == max_turns:
                break
            choice = pick(turn, len(choices))
            if choice is None or choice >= len(choices):
                # a script that asks for a choice that is not there ends here (inklecate would ignore the number)
                break
            events.append({"chose": choice})
            player.send(str(choice + 1))
    finally:
        player.close()
    return events


def evaluate(cwd, main, after, text, timeout):
    """Types one line into play mode after the choices in `after`. A process of its own: the tool may never answer."""
    player = Player(cwd, main, timeout)
    try:
        player.until_prompt()
        for choice in after:
            player.send(str(choice + 1))
            player.until_prompt()
        player.send(text)
        objects = player.until_prompt()
    finally:
        player.close()
    result = {"input": text}
    for o in objects:
        if "cmdOutput" in o:
            result["output"] = o["cmdOutput"]
        elif "issues" in o and o["issues"]:
            result["issues"] = o["issues"]
        elif "timeout" in o:
            result["hang"] = True
        elif "crash" in o:
            result["crash"] = o["crash"]
        elif "exit" in o:
            result["exit"] = o["exit"]
    return result


def generate(name, files, config):
    work = tempfile.mkdtemp(prefix="inky-golden-")
    try:
        for path, text in files.items():
            target = os.path.join(work, path)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8", newline="") as f:
                f.write(text)
        main = config["main"]
        timeout = config.get("timeout", 20)
        max_lines = config.get("maxLines")

        # inklecate -c -j -o: {"compile-success": …}, {"issues": […]}, and the story file
        code, out, err = run(["-c", "-j", "-o", "story.json", main], work)
        compile_objects = decode_objects(out) if not err.strip() else []
        compiled = {
            "success": any(o.get("compile-success") is True for o in compile_objects),
            "issues": [issue for o in compile_objects for issue in o.get("issues", [])],
        }
        if err.strip():
            compiled["crash"] = err.replace("\ufeff", "").strip().splitlines()[0]
        story_path = os.path.join(work, "story.json")
        story = None
        if compiled["success"] and os.path.exists(story_path):
            with open(story_path, encoding="utf-8-sig") as f:
                story = f.read()

        # inklecate -s -j: counted from a parse, also for ink with errors
        code, out, err = run(["-s", "-j", main], work)
        stats = None
        if not err.strip():
            for o in decode_objects(out):
                if "stats" in o:
                    stats = o["stats"]

        transcripts = {}
        evaluations = []
        if compiled["success"]:
            first = config.get("first", 40)
            if first:
                transcripts["first"] = play(work, main, lambda turn, count: 0, first, max_lines, timeout)
            for seed in config.get("random", []):
                rng = random.Random(seed)
                transcripts["random-%d" % seed] = play(work, main, lambda turn, count: rng.randrange(count), 200, max_lines, timeout)
            for script, sequence in config.get("scripts", {}).items():
                transcripts[script] = play(
                    work, main, lambda turn, count: sequence[turn] if turn < len(sequence) else None,
                    len(sequence), max_lines, timeout)
            asked = config.get("evaluate")
            if asked:
                for text in asked["inputs"]:
                    evaluations.append(evaluate(work, main, asked.get("after", []), text, config.get("evaluateTimeout", 5)))

        golden = {
            "reference": "inklecate 1.2.0",
            "main": main,
            "files": sorted(files),
            "compile": compiled,
            "statistics": stats,
            "transcripts": transcripts,
        }
        if evaluations:
            golden["evaluate"] = {"after": config["evaluate"].get("after", []), "results": evaluations}
        if max_lines is not None:
            golden["maxLines"] = max_lines
        text = json.dumps(golden, ensure_ascii=False, indent=1)
        # inklecate names the temporary folder in one message; the project's own paths are what matter
        text = text.replace(json.dumps(work + os.sep)[1:-1], "").replace(json.dumps(work)[1:-1], "")
        with open(os.path.join(EXPECTED, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            f.write(text + "\n")
        story_file = os.path.join(EXPECTED, name + ".story.json")
        if story is not None:
            with open(story_file, "w", encoding="utf-8", newline="\n") as f:
                f.write(story)
        elif os.path.exists(story_file):
            os.remove(story_file)
        return golden
    finally:
        shutil.rmtree(work, ignore_errors=True)


def read(path):
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def main():
    os.makedirs(EXPECTED, exist_ok=True)
    index = []
    for file in sorted(os.listdir(SNIPPETS)):
        if not file.endswith(".ink"):
            continue
        name = file[:-len(".ink")]
        index.append({"name": name, "kind": "snippet", "main": file, "files": [file]})
        if only and name not in only:
            continue
        # the big story gets a longer walk; everything gets two random walks through its choices
        config = {"main": file, "first": 200, "random": [1, 2]}
        golden = generate(name, {file: read(os.path.join(SNIPPETS, file))}, config)
        report(name, golden)
    for name in sorted(os.listdir(CASES)):
        folder = os.path.join(CASES, name)
        if not os.path.isdir(folder):
            continue
        files = {}
        for root, _, names in os.walk(folder):
            for file in names:
                if file.endswith(".ink"):
                    full = os.path.join(root, file)
                    files[os.path.relpath(full, folder).replace(os.sep, "/")] = read(full)
        index.append({"name": name, "kind": "case", "main": "main.ink", "files": sorted(files)})
        if only and name not in only:
            continue
        config = {"main": "main.ink"}
        config_path = os.path.join(folder, "case.json")
        if os.path.exists(config_path):
            config.update(json.loads(read(config_path)))
        golden = generate(name, files, config)
        report(name, golden)
    with open(os.path.join(EXPECTED, "index.json"), "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(index, ensure_ascii=False, indent=1) + "\n")


def report(name, golden):
    compiled = golden["compile"]
    state = "crash" if "crash" in compiled else "ok" if compiled["success"] else "errors"
    walks = ", ".join("%s: %d events" % (k, len(v)) for k, v in golden["transcripts"].items())
    print("%-28s %-6s %2d issues  %s" % (name, state, len(compiled["issues"]), walks))


main()
