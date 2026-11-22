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
#   ["long_press", "Text"]
#   ["type", "some text"]     type with key events (`input text`), as a hardware keyboard does
#   ["ime_type", "some text"] type by tapping the on-screen keyboard's keys (the IME's own path)
#   ["key", "ENTER"]          one key event (KEYCODE_ENTER); ["key", "CTRL+K"] a key combination
#   ["hide_keyboard"], ["back"], ["wait", seconds], ["wait_text", "Text", seconds]
#
# Checks (keyword arguments of step()):
#   expect=["…"]      each text is visible (substring of a node's text or content description)
#   absent=["…"]      none of these is visible
#   foreground=True   the app's activity is the resumed one (default True; False after ["home"])
#   same_process=True the app's process is the one of the previous step (it was not restarted)
#   new_process=True  the app runs in a new process
#   wide=True/False   the window is at least LARGE_WINDOW_DP wide / narrower
#   only="phone"|"tablet"  the step runs on this profile only
#   soft=True         a failed check is reported as a warning and does not fail the run
# A crash (FATAL EXCEPTION, native crash) or an ANR of the app always fails the step.
# ---------------------------------------------------------------------------------------------


def step(name, do=(), expect=(), absent=(), foreground=True, same_process=False, new_process=False,
         wide=None, only=None, soft=False):
    return dict(name=name, do=[list(a) for a in do], expect=list(expect), absent=list(absent),
                foreground=foreground, same_process=same_process, new_process=new_process,
                wide=wide, only=only, soft=soft)


# Scenario 0: the skeleton on android-port (one screen saying the app is being set up).
S0_EN_TITLE = "Inky is being set up"
S0_EN_TEXT = "skeleton of the Android port"
S0_EN_NAV = "Story"
S0_TR_TITLE = "Inky kuruluyor"
S0_TR_TEXT = "Android portunun iskeleti"
S0_TR_NAV = "Hikâye"


def _lifecycle(lang, title, text, nav):
    """The same screen through rotation, background, process death and force-stop."""
    seen = [title, text, nav]
    return [
        step(f"{lang}-rotated", do=[["rotate", "flip"]], expect=seen, same_process=True),
        step(f"{lang}-rotated-back", do=[["rotate", "natural"]], expect=seen, same_process=True),
        step(f"{lang}-background", do=[["home"]], foreground=False, absent=[title]),
        step(f"{lang}-foreground", do=[["launch"]], expect=seen, same_process=True),
        step(f"{lang}-process-death-restored", do=[["home"], ["kill_background"], ["launch"]],
             expect=seen, new_process=True),
        step(f"{lang}-force-stop-relaunch", do=[["force_stop"], ["launch"]], expect=seen, new_process=True),
    ]


SCENARIO_0 = (
    [
        step("en-cold-start", do=[["locale", "en-US"], ["cold_start"]],
             expect=[S0_EN_TITLE, S0_EN_TEXT, S0_EN_NAV], new_process=True),
        step("en-large-window", only="tablet", expect=[APP_LABEL, S0_EN_NAV], wide=True),
        step("en-compact-window", only="phone", expect=[S0_EN_NAV], wide=False),
    ]
    + _lifecycle("en", S0_EN_TITLE, S0_EN_TEXT, S0_EN_NAV)
    + [
        step("tr-cold-start", do=[["locale", "tr-TR"], ["cold_start"]],
             expect=[S0_TR_TITLE, S0_TR_TEXT, S0_TR_NAV], absent=[S0_EN_TITLE], new_process=True),
    ]
    + _lifecycle("tr", S0_TR_TITLE, S0_TR_TEXT, S0_TR_NAV)
    + [
        step("en-again", do=[["locale", "en-US"], ["cold_start"]], expect=[S0_EN_TITLE], absent=[S0_TR_TITLE]),
    ]
)

SCENARIOS = {
    "0": dict(title="Skeleton: the setup screen, in English and Turkish, through the lifecycle",
              steps=SCENARIO_0),
}
DEFAULT_SCENARIOS = ["0"]


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
            dialog = next((n for n in nodes if "isn't responding" in n.text or "keeps stopping" in n.text
                           or "has stopped" in n.text), None)
            if dialog is None:
                return nodes
            ours = APP_LABEL in dialog.text
            if ours:
                self.problem("system dialog: " + dialog.text)
            else:
                self.note("system dialog of another app waited out: " + dialog.text)
            button = next((n for n in nodes if n.text in (("Close app",) if ours else ("Wait", "Close app"))
                           and n.visible()), None)
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
        self.note(f"the UI did not settle within {timeout} s")
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
        nodes = self.wait_idle(timeout=8)
        n = self.find(nodes, label, exact)
        if n is None:
            raise StepFailure(f"'{label}' is not on the screen")
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

    def do_tap_contains(self, label):
        self.tap_label(label, exact=False)

    def do_long_press(self, label):
        self.tap_label(label, long=True)

    def do_type(self, text):
        # `input text` takes %s for a space; the device's shell sees the argument quoted
        shell("input text " + shlex.quote(text.replace(" ", "%s")))
        time.sleep(1)

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
        nodes = self.wait_idle()
        fname, size, nbytes = self.snap(st["name"])
        labels = self.visible_labels(nodes)
        self.pid = self.current_pid()
        checks = []

        for text in st["expect"]:
            ok = any(text in t for t in labels)
            checks.append(("seen" if ok else "MISSING") + f" '{text}'")
            if not ok:
                self.problem(f"'{text}' is not on the screen")
        for text in st["absent"]:
            ok = not any(text in t for t in labels)
            checks.append(("absent" if ok else "PRESENT") + f" '{text}'")
            if not ok:
                self.problem(f"'{text}' is on the screen")
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
