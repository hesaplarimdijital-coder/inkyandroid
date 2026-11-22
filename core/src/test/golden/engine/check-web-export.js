// Proves that what "Export for web" writes plays in the ink.js it ships.
//
// Not part of the Gradle build. Run it after the JVM tests, which write the exported folders
// (InkExportTest.exportedFoldersAreWrittenForTheNodeCheck → core/build/web-export/<story>/):
//
//   /home/claude/work/gradle-core :core:test --tests 'com.inkyandroid.core.engine.InkExportTest'
//   node core/src/test/golden/engine/check-web-export.js
//
// For each exported folder it does what the page's <script> tags do, in a bare JavaScript context:
// evaluate ink.js (defines the global `inkjs`), evaluate the story file (defines `storyContent`),
// then, as main.js does, `new inkjs.Story(storyContent)`. It plays the story by always taking the
// first choice and compares the text, tags and choices with the transcript the reference inklecate
// 1.2.0 produced for the same ink and the same choices (the committed goldens,
// core/src/test/resources/com/inkyandroid/core/golden/engine/expected/<story>.json, script "first").
// It also checks that index.html names exactly the files that are there.
"use strict";
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const root = path.resolve(__dirname, "../../../..");
const exported = process.argv[2] || path.join(root, "build/web-export");
const goldens = path.join(root, "src/test/resources/com/inkyandroid/core/golden/engine/expected");

if (!fs.existsSync(exported)) {
  console.error(`No exported folders in ${exported}: run InkExportTest first.`);
  process.exit(2);
}

let failures = 0;
const stories = fs.readdirSync(exported).filter((name) => fs.statSync(path.join(exported, name)).isDirectory()).sort();
for (const name of stories) {
  const folder = path.join(exported, name);
  try {
    const page = fs.readFileSync(path.join(folder, "index.html"), "utf8");
    const scripts = [...page.matchAll(/<script src="([^"]+)"><\/script>/g)].map((m) => m[1]);
    const storyScript = name === "main" ? "story.js" : `${name}.js`;
    assertEqual(JSON.stringify(scripts), JSON.stringify(["ink.js", storyScript, "main.js"]), "the page's scripts");
    for (const file of [...scripts, "style.css"]) {
      if (!fs.existsSync(path.join(folder, file))) throw new Error(`index.html needs ${file}, which was not exported`);
    }
    if (!page.includes(`<title>${name}</title>`)) throw new Error("the title was not filled in");

    // the browser's global scope, as far as ink.js and the story file need it
    const context = vm.createContext({});
    vm.runInContext(fs.readFileSync(path.join(folder, "ink.js"), "utf8"), context, { filename: "ink.js" });
    vm.runInContext(fs.readFileSync(path.join(folder, storyScript), "utf8"), context, { filename: storyScript });
    const played = vm.runInContext(`(${play.toString()})(200)`, context);

    const golden = JSON.parse(fs.readFileSync(path.join(goldens, `${name}.json`), "utf8"));
    const expected = golden.transcripts.first.filter((e) => "text" in e || "tags" in e || "choices" in e || "chose" in e || "end" in e);
    assertEqual(played.length, expected.length, "number of transcript events");
    for (let i = 0; i < expected.length; i++) {
      assertEqual(JSON.stringify(played[i]), JSON.stringify(expected[i]), `transcript event ${i}`);
    }
    console.log(`ok   ${name}: ${played.filter((e) => "text" in e).length} lines, ${played.filter((e) => "chose" in e).length} choices, same as the reference`);
  } catch (e) {
    failures++;
    console.log(`FAIL ${name}: ${e.message}`);
  }
}
if (stories.length === 0) {
  console.log("FAIL: nothing was exported");
  failures++;
}
process.exit(failures === 0 ? 0 : 1);

function assertEqual(actual, expected, what) {
  if (actual !== expected) throw new Error(`${what}: expected ${expected} but was ${actual}`);
}

// Runs inside the page's context: `inkjs` and `storyContent` are the globals the two scripts defined.
function play(maxTurns) {
  var story = new inkjs.Story(storyContent);
  story.allowExternalFunctionFallbacks = true;
  var out = [];
  story.onError = function (message) { out.push({ issues: [message] }); };
  for (var turns = 0; ; turns++) {
    while (story.canContinue) {
      out.push({ text: story.Continue() });
      if (story.currentTags.length) out.push({ tags: story.currentTags });
    }
    var choices = story.currentChoices;
    if (!choices.length) { out.push({ end: true }); break; }
    out.push({ choices: choices.map(function (c) { return [c.text, c.tags || []]; }) });
    if (turns >= maxTurns) break;
    out.push({ chose: 0 });
    story.ChooseChoiceIndex(0);
  }
  return out;
}
