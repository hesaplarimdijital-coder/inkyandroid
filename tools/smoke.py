#!/usr/bin/env python3
"""Drives Inky on an Android emulator or device over adb and reports what it saw.

Installs the APK, then runs scenarios: lists of steps, each a few actions (launch, tap, type,
rotate, background, force-stop, change the language…) followed by checks on what is on the screen.
After every step it waits until the UI is idle, takes a numbered screenshot, reads the visible
texts and content descriptions through `uiautomator dump` (Compose exposes them there) and scans
logcat for a crash or an ANR of the app. It writes, into the output directory:

  NN-<step>.png     a screenshot per step
  report.txt        step by step: actions, checks, visible texts; last line RESULT: PASS / FAIL
  logcat.txt        logcat of the app's processes (and the system's lines about the app)
  logcat-full.txt.gz  the whole logcat of the run

Only adb and the Python standard library are needed. Usage:

  tools/smoke.py <apk> <out dir> [--profile phone|tablet] [--scenarios 0,1] [--no-install]

To add steps, edit SCENARIOS below; the actions and checks a step can use are listed there.
"""
import argparse
import gzip
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.inkyandroid"
ACTIVITY = PKG + "/.MainActivity"
# the app's label, as the system's crash and ANR dialogs name it
APP_LABEL = "Inky"
# the kit's large window (menu bar and sidebar) starts at this width
LARGE_WINDOW_DP = 1200

# ---------------------------------------------------------------------------------------------
# Scenarios
#
# A step is a dict made with step(name, ...). Its actions ("do") run in order; then the driver
# waits for the UI to settle, takes the screenshot NN-<name>.png and runs the checks.
#
# Actions, each a list [verb, arguments…]:
#   ["locale", "tr-TR"]       the device's language (restarts Android's framework; with no root
#                             the app's own language instead, noted in the report)
#   ["cold_start"]            force-stop, then launch from the launcher intent
#   ["launch"]                launch from the launcher intent (brings a running app to the front)
#   ["home"]                  Home: the app goes to the background
#   ["kill_background"]       kill the app's process while it is in the background, as Android
#                             does when it needs memory (do ["home"] first); ["launch"] restores it
#   ["force_stop"]            force-stop the app
#   ["rotate", "flip"|"natural"|"landscape"|"portrait"]
#   ["tap", "Text"]           tap the node whose text or content description is exactly "Text"
#   ["tap_contains", "Te"]    … that contains "Te"
#   ["tap_field", 1]          tap the first text field (EditText, top to bottom), e.g. the editor
#   ["tap_field_at", 1, dx, dy]  tap it dx, dy dp from its top left corner (negative dx: from the right)
#   ["long_press", "Text"]
#   ["type", "some text"]     type with key events (`input text`), as a hardware keyboard does
#   ["ime_type", "some text"] type by tapping the on-screen keyboard's keys (the IME's own path)
#   ["key", "ENTER"]          one key event (KEYCODE_ENTER); ["key", "CTRL+K"] a key combination
#   ["hide_keyboard"], ["back"], ["wait", seconds], ["wait_text", "Text", seconds]
#   ["shell", "command"]      a device shell command, its output in the report
#   ["save", "file.txt", "command"]  a device shell command, its output saved next to the report
#
# Checks (keyword arguments of step()):
#   expect=["…"]      each text is visible (substring of a node's text or content description,
#                     ignoring case: the on-screen keyboard capitalises at the start of a line)
#   played=["…"]      … visible outside the text fields (the player shows it, not only the editor)
#   absent=["…"]      none of these is visible
#   unplayed=["…"]    none of these is visible outside the text fields (the editor may still hold it)
#   foreground=True   the app's activity is the resumed one (default True; False after ["home"])
#   same_process=True the app's process is the one of the previous step (it was not restarted)
#   new_process=True  the app runs in a new process
#   wide=True/False   the window is at least LARGE_WINDOW_DP wide / narrower
#   only="phone"|"tablet"  the step runs on this profile only
#   soft=True         a failed check is reported as a warning and does not fail the run
#   read=False       the screen is only photographed, not read (for screens uiautomator cannot read)
# A crash (FATAL EXCEPTION, native crash) or an ANR of the app always fails the step.
# ---------------------------------------------------------------------------------------------


def step(name, do=(), expect=(), played=(), absent=(), unplayed=(), foreground=True, same_process=False,
         new_process=False, wide=None, only=None, soft=False, read=True):
    return dict(name=name, do=[list(a) for a in do], expect=list(expect), played=list(played), absent=list(absent),
                unplayed=list(unplayed), read=read,
                foreground=foreground, same_process=same_process, new_process=new_process,
                wide=wide, only=only, soft=soft)


# Scenario 1: the app (architecture 4.3), as the owner uses it. On the phone: the first-launch
# project in the editor and the player; a line typed with key events and a word typed on the
# on-screen keyboard, both played; choose, rewind, restart; words typed on the on-screen keyboard
# right before an existing word (the cursor put there by a tap, and by the Home key); an error in
# the issues and its fix; files, documentation, settings; rotation, background, process death;
# a word typed on the on-screen keyboard right before a force-stop is there after relaunch;
# Turkish. In the large window: menu bar, sidebar, editor and player side by side, rewind and
# restart, palette (Ctrl+K), find (Ctrl+F), the File menu, issues, force-stop, the rail when
# rotated, background, process death, Turkish. The app's texts are collected in S1 and S1_TR.
S1 = dict(
    write="Write", play="Play", files="Files", more="More", issues="Issues", docs="Documentation",
    settings="Settings", rewind="Rewind a single choice", restart="Restart story", player="Player",
    menus=["File", "Edit", "View", "Story", "Help"],
    file_menu=["New project…", "Export to JSON…"],
    main_file="First story.ink",
    story_start="Once upon a time",
    choice="There were two choices.",
    after_choice="They lived happily ever after.",
    one_error="1 error",
    no_issues="No issues",
    docs_title="Writing with ink",
    settings_section="Appearance",
    palette_group="Knots and stitches",
    find_option="Match case",
)
S1_TR = dict(write="Yaz", play="Oyna", files="Dosyalar", menu_file="Dosya")
TYPED = "Smoke line from keys."
IME_WORD = "imeword"
KEPT_WORD = "keptword"
MISSING_KNOT = "smoke_missing_knot"

# Where a finger puts the cursor in the phone's editor (portrait), in dp from the text field's top
# left corner, measured in the screenshots: the field starts under the status bar and its text below
# the glass top bar; the middle of line n, the first character's left half, the space after a line's
# end. Scroll to the top first (TOP) so that line 1 is the file's first line.
def line_dy(n):
    return 74.3 + (n - 1) * 22.5


LINE_START_DX = 58
LINE_END_DX = -24

# the cursor to the start of the main file with the keyboard (the first text field is the editor)
TOP = [["tap_field", 1], ["key", "CTRL+MOVE_HOME"]]
# removes the first line (an error written there)
DELETE_FIRST_LINE = TOP + [["key", "SHIFT+DPAD_DOWN"], ["key", "DEL"]]

SCENARIO_1_PHONE = [
    step("cold-start-first-story", only="phone", do=[["locale", "en-US"], ["cold_start"]], wide=False,
         expect=[S1["write"], S1["play"], S1["files"], S1["story_start"]], new_process=True),
    step("play-first-story", only="phone", do=[["tap", S1["play"]]], played=[S1["story_start"], S1["choice"]]),
    step("write-line-with-keys", only="phone", do=[["tap", S1["write"]]] + TOP + [["type", TYPED], ["key", "ENTER"]],
         expect=[TYPED]),
    step("append-word-on-screen-keyboard", only="phone",
         do=TOP + [["tap_field_at", 1, LINE_END_DX, line_dy(1)], ["ime_type", " " + IME_WORD], ["hide_keyboard"]],
         expect=[TYPED + " " + IME_WORD]),
    step("play-typed-text", only="phone", do=[["tap", S1["play"]]], played=[TYPED, IME_WORD]),
    step("choose", only="phone", do=[["tap", S1["choice"]]], played=[S1["after_choice"]]),
    step("rewind", only="phone", do=[["tap", S1["rewind"]]], played=[S1["choice"]], unplayed=[S1["after_choice"]]),
    step("restart", only="phone", do=[["tap", S1["restart"]]], played=[TYPED, S1["choice"]]),
    # A word typed on the on-screen keyboard just before an existing word must not replace it: with
    # the cursor put there by a finger, by the Home key, and by the Home key with Enter after the word
    # (each on a line of its own, typed with key events first). ime-control does the same in a stock field.
    step("insert-on-screen-keyboard-after-tap", only="phone",
         do=[["tap", S1["write"]]] + TOP + [["type", "victimtap"], ["key", "ENTER"],
                                            ["tap_field_at", 1, LINE_START_DX, line_dy(1)], ["ime_type", "tap"],
                                            ["hide_keyboard"]],
         expect=["tapvictimtap"]),
    step("insert-on-screen-keyboard-after-home-key", only="phone",
         do=TOP + [["type", "victimkey"], ["key", "ENTER"], ["key", "CTRL+MOVE_HOME"], ["ime_type", "key"],
                   ["hide_keyboard"]],
         expect=["keyvictimkey"]),
    step("insert-on-screen-keyboard-then-enter", only="phone",
         do=TOP + [["type", "victimenter"], ["key", "ENTER"], ["key", "CTRL+MOVE_HOME"], ["ime_type", "enter"],
                   ["key", "ENTER"], ["hide_keyboard"]],
         expect=["victimenter"]),
    step("error-in-issues", only="phone",
         do=TOP + [["type", "-> " + MISSING_KNOT], ["key", "ENTER"], ["hide_keyboard"], ["wait", 3],
                   ["tap", S1["more"]], ["tap", S1["issues"]]],
         expect=[S1["one_error"]], played=[MISSING_KNOT]),
    step("error-fixed", only="phone",
         do=[["tap", S1["write"]]] + DELETE_FIRST_LINE + [["hide_keyboard"], ["wait", 3],
                                                          ["tap", S1["more"]], ["tap", S1["issues"]]],
         expect=[S1["no_issues"]], absent=[MISSING_KNOT]),
    step("files", only="phone", do=[["tap", S1["files"]]], expect=[S1["main_file"]]),
    step("documentation", only="phone", do=[["tap", S1["more"]], ["tap", S1["docs"]], ["wait", 3]],
         expect=[S1["docs_title"]]),
    step("settings", only="phone", do=[["back"], ["tap", S1["more"]], ["tap", S1["settings"]]],
         expect=[S1["settings_section"]]),
    step("editor-again", only="phone", do=[["tap", S1["write"]]], expect=[TYPED, IME_WORD]),
    step("rotated", only="phone", do=[["rotate", "flip"]], expect=[TYPED], same_process=True),
    step("rotated-back", only="phone", do=[["rotate", "natural"]], expect=[TYPED], same_process=True),
    step("background-foreground", only="phone", do=[["home"], ["launch"]], expect=[TYPED], same_process=True),
    step("process-death-restored", only="phone", do=[["home"], ["kill_background"], ["launch"]],
         expect=[TYPED, IME_WORD], new_process=True),
    # typed on the on-screen keyboard three seconds before a force-stop: the draft keeps it
    step("typed-then-force-stopped", only="phone",
         do=[["tap", S1["write"]]] + TOP + [["tap_field_at", 1, LINE_END_DX, line_dy(1)],
                                            ["ime_type", " " + KEPT_WORD], ["wait", 3], ["force_stop"], ["launch"]],
         expect=[KEPT_WORD, TYPED, IME_WORD], new_process=True),
    step("turkish", only="phone", do=[["locale", "tr-TR"], ["cold_start"]],
         expect=[S1_TR["write"], S1_TR["play"], S1_TR["files"], KEPT_WORD], new_process=True),
    step("turkish-play", only="phone", do=[["tap", S1_TR["play"]]], played=[KEPT_WORD, S1["choice"]]),
]

SCENARIO_1_TABLET = [
    step("large-cold-start", only="tablet", do=[["locale", "en-US"], ["cold_start"]], wide=True,
         expect=S1["menus"] + [S1["main_file"], S1["story_start"]], played=[S1["choice"]], new_process=True),
    step("large-write-line", only="tablet", do=TOP + [["type", TYPED], ["key", "ENTER"], ["wait", 2]],
         expect=[TYPED], played=[TYPED]),
    step("large-choose", only="tablet", do=[["tap", S1["choice"]]], played=[S1["after_choice"]]),
    step("large-rewind", only="tablet", do=[["tap", S1["rewind"]]], played=[S1["choice"]],
         unplayed=[S1["after_choice"]]),
    step("large-restart", only="tablet", do=[["tap", S1["choice"]], ["tap", S1["restart"]]],
         played=[TYPED, S1["choice"]], unplayed=[S1["after_choice"]]),
    step("large-palette", only="tablet", do=[["key", "CTRL+K"]], expect=[S1["palette_group"]]),
    step("large-palette-closed", only="tablet", do=[["key", "ESCAPE"]], absent=[S1["palette_group"]]),
    step("large-find", only="tablet", do=[["tap_field", 1], ["key", "CTRL+F"]], expect=[S1["find_option"]]),
    step("large-find-closed", only="tablet", do=[["key", "ESCAPE"]], absent=[S1["find_option"]]),
    step("large-file-menu", only="tablet", do=[["tap", S1["menus"][0]]], expect=S1["file_menu"]),
    step("large-error-in-issues", only="tablet",
         do=[["key", "ESCAPE"]] + TOP + [["type", "-> " + MISSING_KNOT], ["key", "ENTER"], ["wait", 3],
                                         ["tap", S1["issues"]]],
         expect=[S1["one_error"]], played=[MISSING_KNOT]),
    step("large-error-fixed", only="tablet", do=DELETE_FIRST_LINE + [["wait", 3]],
         expect=[S1["no_issues"]], unplayed=[MISSING_KNOT]),
    step("large-typed-then-force-stopped", only="tablet",
         do=[["tap", S1["player"]]] + TOP + [["type", KEPT_WORD], ["key", "ENTER"], ["wait", 3],
                                             ["force_stop"], ["launch"]],
         expect=[KEPT_WORD, TYPED], new_process=True),
    step("large-rotated-rail", only="tablet", do=[["rotate", "flip"]], wide=False,
         expect=[KEPT_WORD, S1["write"], S1["files"]], same_process=True),
    step("large-rotated-back", only="tablet", do=[["rotate", "natural"]], wide=True, expect=[KEPT_WORD],
         same_process=True),
    step("large-background-foreground", only="tablet", do=[["home"], ["launch"]], expect=[KEPT_WORD],
         same_process=True),
    step("large-process-death-restored", only="tablet", do=[["home"], ["kill_background"], ["launch"]],
         expect=[KEPT_WORD], new_process=True),
    step("large-turkish", only="tablet", do=[["locale", "tr-TR"], ["cold_start"]], wide=True,
         expect=[S1_TR["menu_file"], KEPT_WORD], played=[KEPT_WORD], new_process=True),
]

# Typing on the on-screen keyboard right before an existing word, every way a cursor gets there:
# after a tap and after Ctrl+Home, with and without a space after the typed word, with and without
# Enter after it, and after opening an issue. Each victim is a line of its own typed with key events
# first; ime_type itself fails a step if anything but the typed text changed. Phone only.
def _victim(line):
    return TOP + [["type", line], ["key", "ENTER"]]


_tap_line_1 = ["tap_field_at", 1, LINE_START_DX, line_dy(1)]

SCENARIO_IME_EXPERIMENTS = [
    step("ime-setup", only="phone", do=[["locale", "en-US"], ["cold_start"], ["tap", S1["write"]]],
         expect=[S1["write"]]),
    step("ime-tap-word", only="phone", do=_victim("Pine tree") + [_tap_line_1, ["ime_type", "tap"], ["hide_keyboard"]],
         expect=["tappine tree"]),
    step("ime-tap-word-space", only="phone",
         do=_victim("Oak tree") + [_tap_line_1, ["ime_type", "tap "], ["hide_keyboard"]], expect=["tap oak tree"]),
    step("ime-tap-word-enter", only="phone",
         do=_victim("Bay tree") + [_tap_line_1, ["ime_type", "tap"], ["key", "ENTER"], ["hide_keyboard"]],
         expect=["bay tree"]),
    step("ime-home-word", only="phone",
         do=_victim("Elm tree") + [["key", "CTRL+MOVE_HOME"], ["ime_type", "home"], ["hide_keyboard"]],
         expect=["homeelm tree"]),
    step("ime-home-word-space", only="phone",
         do=_victim("Ash tree") + [["key", "CTRL+MOVE_HOME"], ["ime_type", "home "], ["hide_keyboard"]],
         expect=["home ash tree"]),
    step("ime-home-word-enter", only="phone",
         do=_victim("Fir tree") + [["key", "CTRL+MOVE_HOME"], ["ime_type", "enter"], ["key", "ENTER"],
                                   ["hide_keyboard"]],
         expect=["fir tree"]),
    step("ime-home-word-space-enter", only="phone",
         do=_victim("Yew tree") + [["key", "CTRL+MOVE_HOME"], ["ime_type", "enter "], ["key", "ENTER"],
                                   ["hide_keyboard"]],
         expect=["yew tree"]),
    step("ime-issue-click-then-type", only="phone",
         do=_victim("-> missing_knot") + [["hide_keyboard"], ["wait", 3], ["tap", S1["more"]], ["tap", S1["issues"]],
                                          ["tap_contains", "Divert target not found"], ["wait", 1],
                                          ["ime_type", "issue"], ["hide_keyboard"]],
         expect=["missing_knot"]),
]

# The same in a stock EditText (Settings > About > Device name, "sdk_gphone64_x86_64"), to tell the
# keyboard's behaviour from the editor's. uiautomator cannot read Settings: the screenshots show the
# result. Enter would close the dialog, so the Enter variants have no counterpart here.
_device_name = [["shell", "am start -W -a android.settings.DEVICE_INFO_SETTINGS"],
                # the "Device name" row and the field's first character, where they are drawn on the phone
                ["shell", "input tap 300 923"], ["wait", 3]]
_device_name_again = [["back"], ["back"], ["shell", "input tap 300 923"], ["wait", 3]]
_field_start = ["shell", "input tap 136 799"]

SCENARIO_IME_CONTROL = [
    step("control-tap-word", only="phone", foreground=False, soft=True, read=False,
         do=_device_name + [_field_start, ["ime_type", "tap", False]]),
    step("control-tap-word-space", only="phone", foreground=False, soft=True, read=False,
         do=_device_name_again + [_field_start, ["ime_type", "tap ", False]]),
    step("control-home-word", only="phone", foreground=False, soft=True, read=False,
         do=_device_name_again + [["key", "CTRL+MOVE_HOME"], ["ime_type", "home", False]]),
    step("control-home-word-space", only="phone", foreground=False, soft=True, read=False,
         do=_device_name_again + [["key", "CTRL+MOVE_HOME"], ["ime_type", "home ", False], ["back"], ["back"]]),
]

SCENARIOS = {
    "1": dict(title="The app: write and play, issues, files, lifecycle, Turkish; the large window's workspace",
              steps=SCENARIO_1_PHONE + SCENARIO_1_TABLET),
    "ime-experiments": dict(title="Typing on the on-screen keyboard before an existing word, every way",
                            steps=SCENARIO_IME_EXPERIMENTS),
    "ime-control": dict(title="The same in a stock text field (keyboard behaviour, not the app)",
                        steps=SCENARIO_IME_CONTROL),
}
DEFAULT_SCENARIOS = ["1"]


# ---------------------------------------------------------------------------------------------
# adb


def adb(*args, timeout=60, binary=False):
    """Runs adb; returns its output (stdout and stderr), empty on a timeout."""
    try:
        r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return b"" if binary else ""
    if binary:
        return r.stdout
    return (r.stdout + r.stderr).decode(errors="replace")


def shell(command, timeout=60):
    """One command line run by the device's shell."""
    return adb("shell", command, timeout=timeout)


def png_size(png):
    if len(png) < 24 or png[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return int.from_bytes(png[16:20], "big"), int.from_bytes(png[20:24], "big")


class Node:
    __slots__ = ("text", "desc", "cls", "rid", "pkg", "bounds", "focused")

    def __init__(self, e):
        self.text = (e.get("text") or "").strip()
        self.desc = (e.get("content-desc") or "").strip()
        self.cls = e.get("class") or ""
        self.rid = e.get("resource-id") or ""
        self.pkg = e.get("package") or ""
        self.focused = e.get("focused") == "true"
        m = re.findall(r"-?\d+", e.get("bounds", ""))
        self.bounds = tuple(map(int, m)) if len(m) == 4 else None

    def labels(self):
        return [t for t in (self.text, self.desc) if t]

    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    def visible(self):
        return self.bounds is not None and self.bounds[2] > self.bounds[0] and self.bounds[3] > self.bounds[1]


class StepFailure(Exception):
    pass


# ---------------------------------------------------------------------------------------------
# the driver


class Smoke:
    def __init__(self, out, profile):
        self.out = out
        self.profile = profile
        self.lines = []
        self.shot = 0
        self.failed_steps = []
        self.warnings = []
        self.app_pids = set()
        self.pid = None
        self.locale_method = None
        self.last_launch = None
        self.step_notes = []
        self.step_problems = []
        os.makedirs(out, exist_ok=True)
        self.logcat_path = os.path.join(out, "logcat-full.txt")
        self.logcat_offset = 0
        self.logcat_proc = None

    # ---- report

    def log(self, line=""):
        print(line, flush=True)
        self.lines.append(line)

    def note(self, line):
        self.step_notes.append(line)

    def problem(self, line):
        self.step_problems.append(line)

    # ---- logcat

    def start_logcat(self):
        adb("logcat", "-c")
        self.logcat_file = open(self.logcat_path, "wb")
        self.logcat_proc = subprocess.Popen(["adb", "logcat", "-v", "threadtime"], stdout=self.logcat_file,
                                            stderr=subprocess.STDOUT)

    def stop_logcat(self):
        if self.logcat_proc:
            self.logcat_proc.terminate()
            try:
                self.logcat_proc.wait(10)
            except subprocess.TimeoutExpired:
                self.logcat_proc.kill()
            self.logcat_file.close()

    def scan_logcat(self):
        """Crashes and ANRs of the app in the part of logcat written since the last scan."""
        time.sleep(0.5)
        with open(self.logcat_path, "rb") as f:
            f.seek(self.logcat_offset)
            chunk = f.read()
        # stop at the last complete line; the rest is read next time
        end = chunk.rfind(b"\n") + 1
        self.logcat_offset += end
        lines = chunk[:end].decode(errors="replace").splitlines()
        found = []
        for i, line in enumerate(lines):
            context = "\n".join(lines[i:i + 30])
            if "FATAL EXCEPTION" in line and f"Process: {PKG}" in "\n".join(lines[i:i + 4]):
                found.append("crash (FATAL EXCEPTION):\n" + context)
            elif f"ANR in {PKG}" in line:
                found.append("ANR:\n" + context)
            elif f">>> {PKG} <<<" in line:
                found.append("native crash:\n" + "\n".join(lines[max(0, i - 3):i + 30]))
            elif "Fatal signal" in line and self._line_pid(line) in self.app_pids:
                found.append("native crash (fatal signal):\n" + context)
        return found

    @staticmethod
    def _line_pid(line):
        # threadtime: "11-23 00:12:34.567  1234  1250 E Tag: message"
        parts = line.split()
        return parts[2] if len(parts) > 3 and parts[2].isdigit() else None

    def write_logcats(self):
        with open(self.logcat_path, "rb") as f:
            full = f.read().decode(errors="replace")
        app = [line for line in full.splitlines()
               if self._line_pid(line) in self.app_pids or PKG in line or APP_LABEL + " isn't" in line]
        with open(os.path.join(self.out, "logcat.txt"), "w") as f:
            f.write("\n".join(app) + "\n")
        with gzip.open(self.logcat_path + ".gz", "wt") as f:
            f.write(full)
        os.remove(self.logcat_path)

    # ---- device state

    def current_pid(self):
        pid = shell(f"pidof {PKG}").strip()
        if pid:
            self.app_pids.add(pid.split()[0])
            return pid.split()[0]
        return None

    def app_resumed(self):
        out = shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity:'")
        return PKG + "/" in out

    def density(self):
        nums = re.findall(r"density: (\d+)", shell("wm density"))
        return int(nums[-1]) if nums else 160

    def natural_is_landscape(self):
        m = re.findall(r"size: (\d+)x(\d+)", shell("wm size"))
        return bool(m) and int(m[0][0]) > int(m[0][1])

    def wake(self):
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")

    def wait_boot(self, timeout=300):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if shell("getprop sys.boot_completed").strip() == "1" and "package:" in shell("pm path android"):
                return True
            time.sleep(3)
        return False

    # ---- UI

    def dump(self):
        """The UI hierarchy of the active window, or None. Handles the system's dialogs: an ANR or
        crash dialog of the app is a problem of the step; another app's ANR dialog is waited out."""
        for _ in range(4):
            root = self._raw_dump()
            if root is None:
                return None
            nodes = [Node(e) for e in root.iter("node")]
            # the system's crash and ANR dialogs, known by their buttons' ids in any language
            buttons = {n.rid: n for n in nodes if n.rid.startswith("android:id/aerr_") and n.visible()}
            if not buttons:
                return nodes
            title = " / ".join(n.text for n in nodes if n.pkg == "android" and n.text and not n.rid.startswith("android:id/aerr_"))
            ours = APP_LABEL in title
            if ours:
                self.problem("system dialog about the app: " + title)
            else:
                self.note("system dialog about another app waited out: " + title)
            button = buttons.get("android:id/aerr_close") if ours else (
                buttons.get("android:id/aerr_wait") or buttons.get("android:id/aerr_close"))
            if button is not None:
                self.tap_xy(*button.center())
            else:
                shell("input keyevent KEYCODE_BACK")
            time.sleep(2)
        return [Node(e) for e in (self._raw_dump() or ET.Element("x")).iter("node")]

    def _raw_dump(self):
        for _ in range(3):
            raw = adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=40)
            start, end = raw.find("<?xml"), raw.rfind("</hierarchy>")
            if start < 0 or end < 0:
                shell("uiautomator dump /sdcard/ui.xml", timeout=40)
                raw = shell("cat /sdcard/ui.xml")
                start, end = raw.find("<?xml"), raw.rfind("</hierarchy>")
            if start >= 0 and end > start:
                try:
                    return ET.fromstring(raw[start:end + len("</hierarchy>")])
                except ET.ParseError:
                    pass
            time.sleep(1)
        return None

    @staticmethod
    def signature(nodes):
        return [(n.cls, n.text, n.desc, n.bounds) for n in nodes or []]

    def wait_idle(self, timeout=20):
        """Until two dumps in a row are the same (uiautomator itself waits for accessibility idle)."""
        deadline = time.time() + timeout
        previous = None
        nodes = None
        while time.time() < deadline:
            nodes = self.dump()
            sig = self.signature(nodes)
            if nodes and sig == previous:
                return nodes
            previous = sig
            time.sleep(0.8)
        self.note(f"the UI did not settle within {timeout} s" if nodes else
                  "uiautomator could not read the screen (it never went idle)")
        return nodes or []

    @staticmethod
    def visible_labels(nodes):
        seen = []
        for n in nodes:
            for t in n.labels():
                if t not in seen:
                    seen.append(t)
        return seen

    def find(self, nodes, label, exact=True):
        for n in nodes:
            if not n.visible():
                continue
            for t in n.labels():
                if (t == label) if exact else (label in t):
                    return n
        return None

    def tap_xy(self, x, y):
        shell(f"input tap {x} {y}")

    def tap_label(self, label, exact=True, long=False):
        deadline = time.time() + 15
        while True:
            n = self.find(self.wait_idle(timeout=8), label, exact)
            if n is not None:
                break
            if time.time() > deadline:
                raise StepFailure(f"'{label}' is not on the screen")
            time.sleep(1)
        x, y = n.center()
        if long:
            shell(f"input swipe {x} {y} {x} {y} 900")
        else:
            self.tap_xy(x, y)
        time.sleep(1)

    # ---- actions

    def do_locale(self, tag):
        current = shell("getprop persist.sys.locale").strip() or shell("getprop ro.product.locale").strip()
        if self.locale_method is None:
            self.locale_method = "device" if shell("id -u").strip() == "0" else "app"
            if self.locale_method == "app":
                self.warnings.append("no root on the device: the app's language is changed, not the device's")
        if self.locale_method == "app":
            shell(f"cmd locale set-app-locales {PKG} --locales {tag}")
            self.note(f"app language set to {tag}")
            return
        if current == tag:
            self.note(f"device language already {tag}")
            return
        # A new system language needs a framework restart on a running device; that is what
        # rebooting into the new language does, minus the kernel.
        shell("setprop sys.boot_completed 0")
        shell(f"setprop persist.sys.locale {tag}")
        shell("setprop ctl.restart zygote")
        time.sleep(8)
        if not self.wait_boot():
            raise StepFailure(f"Android did not come back after switching the language to {tag}")
        time.sleep(10)
        self.wake()
        now = shell("getprop persist.sys.locale").strip()
        if now != tag:
            raise StepFailure(f"the device language is {now}, not {tag}")
        self.note(f"device language switched to {tag} (framework restarted)")
        self.pid = None

    def _start(self):
        out = shell(f"am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {ACTIVITY}",
                    timeout=120)
        state = re.search(r"LaunchState: (\w+)", out)
        total = re.search(r"TotalTime: (\d+)", out)
        if "Error" in out or "Exception" in out:
            raise StepFailure("am start failed: " + out.strip())
        self.last_launch = (state.group(1) if state else "?", int(total.group(1)) if total else None)
        brought = "brought to the front" in out
        self.note("launch: " + (f"{self.last_launch[0]} {self.last_launch[1]} ms" if total else
                                ("existing task brought to the front" if brought else out.strip().replace("\n", " "))))
        time.sleep(2)

    def do_cold_start(self):
        shell(f"am force-stop {PKG}")
        time.sleep(1)
        self._start()

    def do_launch(self):
        self._start()

    def do_home(self):
        shell("input keyevent KEYCODE_HOME")
        time.sleep(2)

    def do_kill_background(self):
        if self.app_resumed():
            raise StepFailure("kill_background: the app is in the foreground (do home first)")
        before = self.current_pid()
        shell(f"am kill {PKG}")
        time.sleep(2)
        after = self.current_pid()
        if after and after == before:
            raise StepFailure(f"the app's process {before} survived am kill")
        self.note(f"process {before} killed in the background")

    def do_force_stop(self):
        shell(f"am force-stop {PKG}")
        time.sleep(1)

    def do_rotate(self, which):
        natural_landscape = self.natural_is_landscape()
        rotation = {
            "natural": 0,
            "flip": 1,
            "landscape": 0 if natural_landscape else 1,
            "portrait": 1 if natural_landscape else 0,
        }[which]
        before = png_size(adb("exec-out", "screencap", "-p", binary=True, timeout=60))
        shell("settings put system accelerometer_rotation 0")
        shell(f"settings put system user_rotation {rotation}")
        time.sleep(3)
        after = png_size(adb("exec-out", "screencap", "-p", binary=True, timeout=60))
        if before and after and (before[0] > before[1]) != (after[0] > after[1]):
            self.note(f"rotated: {before[0]}x{before[1]} -> {after[0]}x{after[1]} px")
        elif before == after:
            self.note(f"rotation {rotation}: the screen stays {after[0]}x{after[1]} px")

    def do_tap(self, label):
        self.tap_label(label)

    def fields(self, nodes):
        found = [n for n in nodes if n.cls == "android.widget.EditText" and n.visible()]
        return sorted(found, key=lambda n: (n.bounds[1], n.bounds[0]))

    def do_tap_field(self, index=1):
        fields = self.fields(self.wait_idle(timeout=8))
        if len(fields) < int(index):
            raise StepFailure(f"there is no text field {index} on the screen ({len(fields)} found)")
        self.tap_xy(*fields[int(index) - 1].center())
        time.sleep(1)

    def do_tap_field_at(self, index, dx_dp, dy_dp):
        """Taps a point of the index-th text field, dx_dp / dy_dp from its top left corner (a negative
        dx_dp from its right edge): where a finger puts the cursor, e.g. before a line's first word."""
        fields = self.fields(self.wait_idle(timeout=8))
        if len(fields) < int(index):
            raise StepFailure(f"there is no text field {index} on the screen ({len(fields)} found)")
        x1, y1, x2, y2 = fields[int(index) - 1].bounds
        px = self.density() / 160
        x = int(x1 + dx_dp * px) if dx_dp >= 0 else int(x2 + dx_dp * px)
        y = int(y1 + dy_dp * px)
        self.note(f"text field {index} at [{x1},{y1}][{x2},{y2}]: tapped at {x},{y}")
        self.tap_xy(x, y)
        time.sleep(1)

    def do_tap_contains(self, label):
        self.tap_label(label, exact=False)

    def do_long_press(self, label):
        self.tap_label(label, long=True)

    def do_type(self, text):
        # `input text` takes %s for a space; the device's shell sees the argument quoted
        shell("input text " + shlex.quote(text.replace(" ", "%s")))
        time.sleep(1)

    # Gboard's QWERTY layout (the emulator images' keyboard), measured on the phone emulator: each
    # letter row's vertical centre as a fraction of the keyboard's touchable area, and the
    # horizontal offset of the row's first key in key widths (a tenth of the keyboard's width).
    GBOARD_ROWS = [("qwertyuiop", 0.263, 0.0), ("asdfghjkl", 0.466, 0.5), ("zxcvbnm", 0.671, 1.5)]
    GBOARD_SPACE = (0.55, 0.876)

    def keyboard_area(self):
        """The on-screen keyboard's touchable area (left, top, right, bottom), or None when hidden."""
        out = shell("dumpsys window windows | grep -A40 ' u0 InputMethod}:'")
        m = re.search(r"touchable region=SkRegion\(\((\d+),(\d+),(\d+),(\d+)\)\)", out)
        if not m or "isVisible=false" in out or "mViewVisibility=0x8" in out:
            return None
        area = tuple(map(int, m.groups()))
        return area if area[3] - area[1] > 100 else None

    def focused_text(self):
        nodes = self.dump() or []
        node = next((n for n in nodes if n.focused and n.cls == "android.widget.EditText"), None)
        return None if node is None else node.text

    def do_ime_type(self, text, read=True):
        """Types by tapping the on-screen keyboard's keys, so the text arrives as the owner's typing
        does: through the IME's InputConnection, not as key events. Letters a-z and spaces only (a
        space lets the keyboard autocorrect the word before it). Checks the focused field afterwards."""
        ime = shell("settings get secure default_input_method").strip()
        if "com.google.android.inputmethod.latin" not in ime:
            raise StepFailure(f"ime_type knows Gboard's layout only; the keyboard is {ime}")
        area = self.keyboard_area()
        if area is None:
            raise StepFailure("the on-screen keyboard is not shown")
        left, top, right, bottom = area
        key_w, height = (right - left) / 10, bottom - top
        before = self.focused_text() if read else None
        for ch in text.lower():
            if ch == " ":
                x, y = left + (right - left) * self.GBOARD_SPACE[0], top + height * self.GBOARD_SPACE[1]
            else:
                row = next((r for r in self.GBOARD_ROWS if ch in r[0]), None)
                if row is None:
                    raise StepFailure(f"ime_type cannot type '{ch}'")
                x = left + key_w * (row[2] + row[0].index(ch) + 0.5)
                y = top + height * row[1]
            self.tap_xy(int(x), int(y))
            time.sleep(0.25)
        time.sleep(1)
        after = self.focused_text() if read else None
        word = text.strip().lower()
        if after is None or before is None:
            self.note("typed on the on-screen keyboard; the field could not be read back")
        elif after.lower().count(word) <= before.lower().count(word):
            raise StepFailure(f"typed '{text}' on the on-screen keyboard, but the field reads '{after[:80]}'")
        elif not self._inserted(before, after, text):
            # the keyboard's word arrived, but other text changed with it: say where
            i = next((k for k in range(min(len(before), len(after))) if before[k].lower() != after[k].lower()), 0)
            raise StepFailure(f"typing '{text}' on the on-screen keyboard changed other text: "
                              f"before {before[max(0, i - 10):i + 30]!r}, after {after[max(0, i - 10):i + 30]!r}")
        else:
            self.note(f"typed '{text}' on the on-screen keyboard; the field holds it and nothing else changed")

    @staticmethod
    def _inserted(before, after, text):
        """Whether after is before with text inserted at one place (letter case aside: the keyboard
        capitalises the start of a line)."""
        b, a, t = before.lower(), after.lower(), text.lower()
        if len(a) != len(b) + len(t):
            return False
        return any(a[:k] + a[k + len(t):] == b for k in range(len(a) - len(t) + 1) if a[k:k + len(t)] == t)

    def do_key(self, key):
        codes = ["KEYCODE_" + {"CTRL": "CTRL_LEFT", "SHIFT": "SHIFT_LEFT", "ALT": "ALT_LEFT", "META": "META_LEFT"}
                 .get(k.upper(), k.upper()) for k in key.split("+")]
        if len(codes) == 1:
            shell("input keyevent " + codes[0])
        else:
            shell("input keycombination " + " ".join(codes))
        time.sleep(1)

    def do_hide_keyboard(self):
        if "mInputShown=true" in shell("dumpsys input_method | grep mInputShown"):
            shell("input keyevent KEYCODE_BACK")
            time.sleep(1)

    def do_back(self):
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1.5)

    def do_wait(self, seconds):
        time.sleep(float(seconds))

    def do_shell(self, command):
        out = shell(command, timeout=120).strip()
        self.note(f"$ {command}" + (f"\n       {out[:400]}" if out else ""))

    def do_save(self, filename, command):
        with open(os.path.join(self.out, filename), "w") as f:
            f.write(shell(command, timeout=120))
        self.note(f"saved {filename}: $ {command}")

    def do_wait_text(self, text, seconds=30):
        deadline = time.time() + float(seconds)
        while time.time() < deadline:
            if self.find(self.dump() or [], text, exact=False) is not None:
                return
            time.sleep(1.5)
        raise StepFailure(f"'{text}' did not appear within {seconds} s")

    # ---- steps

    def snap(self, name):
        self.shot += 1
        fname = f"{self.shot:02d}-{name}.png"
        png = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        with open(os.path.join(self.out, fname), "wb") as f:
            f.write(png)
        return fname, png_size(png), len(png)

    def run_step(self, st):
        self.step_notes, self.step_problems = [], []
        pid_before = self.pid
        try:
            for action in st["do"]:
                getattr(self, "do_" + action[0])(*action[1:])
        except StepFailure as e:
            self.problem(str(e))
        if st["read"]:
            nodes = self.wait_idle()
        else:
            time.sleep(2)
            nodes = []
        fname, size, nbytes = self.snap(st["name"])
        labels = self.visible_labels(nodes)
        self.pid = self.current_pid()
        checks = []

        for text in st["expect"]:
            ok = any(text.lower() in t.lower() for t in labels)
            checks.append(("seen" if ok else "MISSING") + f" '{text}'")
            if not ok:
                self.problem(f"'{text}' is not on the screen")
        outside = self.visible_labels([n for n in nodes if n.cls != "android.widget.EditText"])
        for text in st["played"]:
            ok = any(text.lower() in t.lower() for t in outside)
            checks.append(("played" if ok else "NOT PLAYED") + f" '{text}'")
            if not ok:
                self.problem(f"'{text}' is not on the screen outside the text fields")
        for text in st["absent"]:
            ok = not any(text.lower() in t.lower() for t in labels)
            checks.append(("absent" if ok else "PRESENT") + f" '{text}'")
            if not ok:
                self.problem(f"'{text}' is on the screen")
        for text in st["unplayed"]:
            ok = not any(text.lower() in t.lower() for t in outside)
            checks.append(("not played" if ok else "PLAYED") + f" '{text}'")
            if not ok:
                self.problem(f"'{text}' is on the screen outside the text fields")
        resumed = self.app_resumed()
        if st["foreground"] and not resumed:
            self.problem("the app is not in the foreground")
        if not st["foreground"] and resumed:
            self.problem("the app is still in the foreground")
        if st["same_process"]:
            if self.pid is None or self.pid != pid_before:
                self.problem(f"the app's process changed: {pid_before} -> {self.pid}")
            else:
                checks.append(f"same process {self.pid}")
        if st["new_process"]:
            if self.pid is None or self.pid == pid_before:
                self.problem(f"the app did not start a new process: {pid_before} -> {self.pid}")
            else:
                checks.append(f"new process {self.pid}")
        if size:
            dp = size[0] * 160 // self.density()
            geometry = f"{size[0]}x{size[1]} px, {dp} dp wide"
            if st["wide"] is True and dp < LARGE_WINDOW_DP:
                self.problem(f"the window is {dp} dp wide, not a large window (>= {LARGE_WINDOW_DP} dp)")
            if st["wide"] is False and dp >= LARGE_WINDOW_DP:
                self.problem(f"the window is {dp} dp wide, a large window")
            # a nearly uniform image compresses to almost nothing: nothing was drawn
            if nbytes < size[0] * size[1] // 250:
                self.warnings.append(f"{fname} looks blank ({nbytes} bytes)")
                self.note(f"the screenshot looks blank ({nbytes} bytes)")
        else:
            geometry = "no screenshot"
            self.problem("screencap returned no image")

        crashes = self.scan_logcat()
        hard = list(crashes)
        soft = st["soft"]
        problems = self.step_problems
        failed = bool(hard) or (bool(problems) and not soft)
        verdict = "FAIL" if failed else ("WARN" if problems else "PASS")
        self.log(f"[{self.shot:02d}] {st['name']}: {verdict}   ({fname}, {geometry})")
        if st["do"]:
            self.log("     do: " + "; ".join(" ".join(map(str, a)) for a in st["do"]))
        for n in self.step_notes:
            self.log("     " + n)
        if checks:
            self.log("     checks: " + "; ".join(checks))
        for p in problems:
            self.log("     ! " + p)
        for c in hard:
            self.log("     !! " + c.replace("\n", "\n        "))
        self.log("     visible: " + " | ".join(labels[:60]))
        if failed:
            self.failed_steps.append(st["name"])
        elif problems:
            self.warnings.append(f"{st['name']}: " + "; ".join(problems))

    def run(self, apk, scenarios, install=True):
        sdk = shell("getprop ro.build.version.sdk").strip()
        release = shell("getprop ro.build.version.release").strip()
        model = shell("getprop ro.product.model").strip()
        self.log(f"Inky device smoke: profile {self.profile}, {model}, Android {release} (API {sdk}), "
                 f"{shell('wm size').strip().splitlines()[-1] if shell('wm size').strip() else '?'}, "
                 f"density {self.density()}")
        self.wake()
        # The launcher of these images often stops responding for a while after boot; its ANR
        # dialog would cover the app. Without error dialogs Android still logs every ANR and crash
        # (what the driver looks for) and kills an app that does not respond instead of asking.
        shell("settings put global hide_error_dialogs 1")
        if self.profile == "tablet":
            # With a keyboard attached, as in DeX, no on-screen keyboard covers half the window. The
            # emulator's keyboard does not keep Gboard away (show_ime_with_hard_keyboard 0 and
            # `ime disable` were tried: Android enables a default keyboard again), so the keyboard
            # apps are switched off; key events then reach the editor directly.
            packages = {ime.split("/")[0] for ime in shell("ime list -a -s").split() if "/" in ime}
            for package in sorted(packages):
                shell(f"pm disable-user --user 0 {package}")
            left = " ".join(shell("ime list -s").split()) or "none"
            self.log(f"tablet: keyboard apps switched off ({', '.join(sorted(packages))}); enabled now: {left}")
        if self.profile == "phone":
            # the owner's phone uses three-button navigation, not gestures
            shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton")
            nav = shell("cmd overlay list android | grep -i navbar").strip().replace("\n", "; ")
            self.log("navigation bar overlays: " + nav)
        # the launcher and other system apps are slow right after boot: let them settle
        time.sleep(20)
        self.wait_idle()
        if install:
            out = adb("install", "-r", "-g", apk, timeout=300)
            if "Success" not in out:
                self.log("install failed: " + out.strip())
                self.log("RESULT: FAIL")
                return False
            self.log("installed " + os.path.basename(apk))
        self.start_logcat()
        try:
            for key in scenarios:
                sc = SCENARIOS[key]
                self.log("")
                self.log(f"== Scenario {key}: {sc['title']}")
                for st in sc["steps"]:
                    if st["only"] and st["only"] != self.profile:
                        continue
                    self.run_step(st)
        finally:
            self.stop_logcat()
            self.write_logcats()
        self.log("")
        if self.locale_method:
            self.log(f"language changes: {'the device language' if self.locale_method == 'device' else 'the app language (no root)'}")
        for w in self.warnings:
            self.log("warning: " + w)
        if self.failed_steps:
            self.log("failed steps: " + ", ".join(self.failed_steps))
        ok = not self.failed_steps
        self.log("RESULT: " + ("PASS" if ok else "FAIL"))
        return ok


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("apk")
    parser.add_argument("out")
    parser.add_argument("--profile", default="phone", choices=["phone", "tablet"])
    parser.add_argument("--scenarios", default="", help="comma separated, e.g. 0,1 (default: %s)" %
                        ",".join(DEFAULT_SCENARIOS))
    parser.add_argument("--no-install", action="store_true")
    args = parser.parse_args()
    scenarios = [s.strip() for s in args.scenarios.split(",") if s.strip()] or DEFAULT_SCENARIOS
    unknown = [s for s in scenarios if s not in SCENARIOS]
    if unknown:
        parser.error("unknown scenarios: " + ", ".join(unknown))
    smoke = Smoke(args.out, args.profile)
    ok = smoke.run(args.apk, scenarios, install=not args.no_install)
    with open(os.path.join(args.out, "report.txt"), "w") as f:
        f.write("\n".join(smoke.lines) + "\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
