// Generates the goldens of :core's text tests by running upstream Inky's own code in Ace 1.2.6, the Ace
// version upstream ships (app/renderer/acesrc is ace-builds 1.2.6, identical in every module used here).
//
// Regenerate (Node 22; the Gradle build never needs Node):
//
//     cd core/src/test/golden && npm ci && node generate.js
//
// Inputs:  the long snippets (src/main/resources/com/inkyandroid/core/snippets/*.ink) and the files in
//          src/test/resources/com/inkyandroid/core/golden/input (upstream's test.ink, torture.ink, project/).
// Code:    upstream/ holds upstream's files unchanged (see upstream/NOTICE.txt).
// Outputs: src/test/resources/com/inkyandroid/core/golden/{rules,tokens,symbols,snippets,find-symbol}…json
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const CORE = path.resolve(__dirname, '..', '..', '..');
const SNIPPETS_DIR = path.join(CORE, 'src/main/resources/com/inkyandroid/core/snippets');
const GOLDEN = path.join(CORE, 'src/test/resources/com/inkyandroid/core/golden');
const INPUT = path.join(GOLDEN, 'input');
const UPSTREAM = path.join(__dirname, 'upstream');

// ---------------------------------------------------------------------------------------------------
// Ace 1.2.6 in a context of its own. Everything that touches Ace's objects (upstream's ace-ink.js and
// inkFileSymbols.js too) runs in that context, so that `instanceof RegExp` and friends see one realm.

const aceSource = fs.readFileSync(require.resolve('ace-builds/src/ace.js'), 'utf8');
// The build ends by loading the whole editor into `window`; only the module definitions are wanted.
const editorBoot = aceSource.search(/\n\s*\(function\(\) \{\s*window\.require\(\["ace\/ace"\]/);
if (editorBoot < 0) throw new Error('Unexpected ace.js layout');

// Ace's reportError logs and then rethrows from a timer (an uncaught error in the browser console);
// background tokenizing also runs from timers. Neither is wanted here: tokens are asked for on demand.
const quietConsole = { log: console.log, warn: () => {}, error: () => {} };
const context = vm.createContext({ console: quietConsole, setTimeout: () => 0, clearTimeout: () => {} });
vm.runInContext(aceSource.slice(0, editorBoot), context, { filename: 'ace.js' });
context.ace = { require: (name) => context.require(name) };

// Loads a CommonJS file of upstream's into the Ace context.
function loadInContext(file, requireMap) {
    const source = fs.readFileSync(file, 'utf8');
    const wrapper = vm.runInContext(
        '(function (exports, require, module, __dirname, __filename) {' + source + '\n})', context, { filename: file });
    const module = { exports: {} };
    const localRequire = (name) => {
        if (name in requireMap) return requireMap[name];
        throw new Error('Unexpected require of ' + name + ' in ' + file);
    };
    wrapper(module.exports, localRequire, module, path.dirname(file), file);
    return module.exports;
}

const { InkMode } = loadInContext(path.join(UPSTREAM, 'ace-ink.js'), {});
const { InkFileSymbols } = loadInContext(path.join(UPSTREAM, 'inkFileSymbols.js'), {
    assert: require('assert'),
    lodash: require('lodash'),
});
const AceDocument = context.require('ace/document').Document;
const EditSession = context.require('ace/edit_session').EditSession;

// ---------------------------------------------------------------------------------------------------

// Pretty enough to diff, compact enough to commit: an array of scalars, or of scalars and arrays of
// scalars (a line of tokens), stays on one line.
function format(value, indent) {
    const pad = ' '.repeat(indent);
    if (Array.isArray(value)) {
        if (value.length === 0) return '[]';
        const scalar = (x) => x === null || typeof x !== 'object';
        const flat = value.every((v) => scalar(v) || (Array.isArray(v) && v.every(scalar)));
        if (flat) return JSON.stringify(value);
        return '[\n' + value.map((v) => pad + ' ' + format(v, indent + 1)).join(',\n') + '\n' + pad + ']';
    }
    if (value && typeof value === 'object') {
        const keys = Object.keys(value);
        if (keys.length === 0) return '{}';
        return '{\n' + keys.map((k) => pad + ' ' + JSON.stringify(k) + ': ' + format(value[k], indent + 1)).join(',\n') + '\n' + pad + '}';
    }
    return JSON.stringify(value);
}

function writeJson(relative, value) {
    const file = path.join(GOLDEN, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, format(value, 0) + '\n');
}

function readText(file) {
    return fs.readFileSync(file, 'utf8');
}

// Tokens per line: { stackNodes, lines }. A line is [state, [type, value], [type, value], …]. Ace's state
// value is a name (plain state) or a stack; stacks share their tails (and upstream's rules make them deep),
// so a stack is written as the index of its first node in stackNodes, each node being [name, index of the
// node below or -1].
function tokenizeText(tokenizer, text) {
    const lines = new AceDocument(text).getAllLines();
    const nodes = [];
    const nodeIndex = new Map();
    const node = (names, from) => {
        if (from >= names.length) return -1;
        const below = node(names, from + 1);
        const key = names[from] + '\u0000' + below;
        if (!nodeIndex.has(key)) {
            nodeIndex.set(key, nodes.length);
            nodes.push([names[from], below]);
        }
        return nodeIndex.get(key);
    };
    let state = 'start';
    const out = lines.map((line) => {
        const result = tokenizer.getLineTokens(line, state);
        state = result.state;
        const stateJson = Array.isArray(state) ? node(Array.from(state), 0) : state;
        return [stateJson].concat(result.tokens.map((t) => [t.type, t.value]));
    });
    return { stackNodes: nodes, lines: out };
}

// The rule table Ace runs after normalizeRules and the Tokenizer constructor
function rulesJson(tokenizer) {
    const states = {};
    for (const key in tokenizer.states) {
        states[key] = {
            regExp: tokenizer.regExps[key].source,
            flags: tokenizer.regExps[key].flags,
            defaultToken: tokenizer.matchMappings[key].defaultToken,
            rules: tokenizer.states[key].map((rule) => {
                const r = {};
                if (rule.regex !== undefined) r.regex = rule.regex;
                if (rule.token !== undefined) r.token = rule.token;
                if (rule.tokenArray !== undefined) r.tokenArray = rule.tokenArray;
                if (typeof rule.next === 'function') r.next = rule.next.name; // pushState / popState
                else if (rule.next !== undefined) r.next = rule.next;
                if (rule.nextState !== undefined) r.nextState = rule.nextState;
                if (rule.include !== undefined) r.include = rule.include;
                if (rule.defaultToken !== undefined) r.defaultToken = rule.defaultToken;
                if (rule.splitRegex) r.splitRegex = rule.splitRegex.source;
                return r;
            }),
        };
    }
    return states;
}

const standardTokenizer = new InkMode('').getTokenizer();

// --- 1. The rule tables
writeJson('rules/standard.json', rulesJson(standardTokenizer));
writeJson('rules/prefix-gt.json', rulesJson(new InkMode('>>>').getTokenizer()));

// --- 2. Tokens of every input
const inputs = [];
for (const name of fs.readdirSync(SNIPPETS_DIR).filter((n) => n.endsWith('.ink')).sort()) {
    inputs.push({ id: 'snippets/' + name, file: path.join(SNIPPETS_DIR, name) });
}
for (const name of ['test.ink', 'torture.ink']) inputs.push({ id: name, file: path.join(INPUT, name) });
for (const name of fs.readdirSync(path.join(INPUT, 'project')).filter((n) => n.endsWith('.ink')).sort()) {
    inputs.push({ id: 'project/' + name, file: path.join(INPUT, 'project', name) });
}

for (const input of inputs) {
    writeJson('tokens/' + input.id.replace(/\.ink$/, '.json'), tokenizeText(standardTokenizer, readText(input.file)));
}

// Instruction prefixes over the torture file: ordinary ones, one with a group (Ace reports a group
// count mismatch), one that can match nothing (Ace's runaway guard), one that is not a regex at all.
const prefixes = { gt: '>>>', dollar: '\\$', at: '@', star: 'x*', group: '(>>)', invalid: '(' };
for (const id in prefixes) {
    let value;
    try {
        value = { instructionPrefix: prefixes[id], tokens: tokenizeText(new InkMode(prefixes[id]).getTokenizer(), readText(path.join(INPUT, 'torture.ink'))) };
    } catch (e) {
        value = { instructionPrefix: prefixes[id], error: String(e) };
    }
    writeJson('tokens/prefix/' + id + '.json', value);
}

// --- 3. Symbols, with upstream's InkFileSymbols over an Ace EditSession
function symbolId(symbol) {
    return symbol ? symbol.row + ':' + symbol.column + ':' + symbol.name : null;
}

function symbolJson(symbol) {
    const s = { name: symbol.name, flowType: symbol.flowType.name, isfunc: symbol.isfunc, row: symbol.row, column: symbol.column };
    if (symbol.rangeIndex) {
        s.children = symbol.rangeIndex.map((r) => symbolJson(r.symbol));
        s.innerKeys = Object.keys(symbol.innerSymbols);
        s.innerValues = Object.values(symbol.innerSymbols).map(symbolId);
    }
    return s;
}

function parseSymbols(text) {
    const session = new EditSession(new AceDocument(text), new InkMode(''));
    const inkFile = { aceDocument: { on: () => {} }, getAceSession: () => session };
    const symbols = new InkFileSymbols(inkFile, { includesChanged: () => {} });
    symbols.parse();
    return { symbols, session };
}

function symbolsJson(text) {
    const { symbols, session } = parseSymbols(text);
    const rows = session.getLength();
    const symbolAtRow = [];
    const flowAtRow = [];
    for (let row = 0; row < rows; row++) {
        symbolAtRow.push(symbolId(symbols.symbolAtPos({ row, column: 0 })));
        const flow = symbols.flowAtPos({ row, column: 0 });
        const f = {};
        for (const key in flow) f[key] = symbolId(flow[key]);
        flowAtRow.push(f);
    }
    return {
        root: symbols.rangeIndex.map((r) => symbolJson(r.symbol)),
        topLevelKeys: Object.keys(symbols.symbols),
        topLevelValues: Object.values(symbols.symbols).map(symbolId),
        includes: symbols.includes,
        lastIncludeRow: symbols.lastIncludeRow,
        globalTags: symbols.globalTags,
        dictionaryTags: Object.entries(symbols.globalDictionaryStyleTags),
        divertTargets: Array.from(symbols.divertTargets),
        variables: Array.from(symbols.variables),
        externals: Array.from(symbols.externals),
        vocabWords: Array.from(symbols.vocabWords),
        symbolAtRow,
        flowAtRow,
    };
}

for (const input of inputs) {
    writeJson('symbols/' + input.id.replace(/\.ink$/, '.json'), symbolsJson(readText(input.file)));
}

// --- 4. findSymbol (upstream's InkProject.prototype.findSymbol, unchanged) over the project inputs
{
    const InkProject = function () {};
    vm.runInContext('var InkProject;', context);
    context.InkProject = InkProject;
    vm.runInContext(readText(path.join(UPSTREAM, 'inkProject.findSymbol.js')), context);
    const projectDir = path.join(INPUT, 'project');
    const files = fs.readdirSync(projectDir).filter((n) => n.endsWith('.ink')).sort((a, b) => (a === 'main.ink' ? -1 : b === 'main.ink' ? 1 : a < b ? -1 : 1));
    const parsed = files.map((name) => {
        const { symbols } = parseSymbols(readText(path.join(projectDir, name)));
        symbols.inkFile = name;
        return { name, symbols };
    });
    // every symbol keeps the file it came from (upstream: symbol.inkFile)
    const fileOf = new Map();
    const mark = (symbol, name) => {
        fileOf.set(symbol, name);
        if (symbol.rangeIndex) symbol.rangeIndex.forEach((r) => mark(r.symbol, name));
    };
    parsed.forEach((p) => p.symbols.rangeIndex.forEach((r) => mark(r.symbol, p.name)));

    const queries = JSON.parse(readText(path.join(projectDir, 'queries.json')));
    const silentConsole = { log: () => {} };
    const results = queries.map((q) => {
        const project = {
            activeInkFile: parsed.find((p) => p.name === q.activeFile),
            files: parsed,
        };
        let found;
        const savedConsole = context.console;
        context.console = silentConsole;
        try {
            found = InkProject.prototype.findSymbol.call(project, q.name, { row: q.row, column: 0 });
        } catch (e) {
            found = { error: String(e) };
        } finally {
            context.console = savedConsole;
        }
        if (found && found.error) return Object.assign({}, q, { error: found.error });
        return Object.assign({}, q, { found: found ? { file: fileOf.get(found), id: symbolId(found) } : null });
    });
    writeJson('find-symbol.json', { files, results });
}

// --- 5. The built-in snippets (upstream's inkSnippets.js, names untranslated) and their tokens
{
    const fileOfContent = new Map();
    const fakeFs = {
        accessSync: () => { throw new Error('no release folder'); },
        readFileSync: (file) => {
            const buffer = fs.readFileSync(path.join(SNIPPETS_DIR, path.basename(file)));
            fileOfContent.set(buffer.toString() + '\n', path.basename(file));
            return buffer;
        },
    };
    const { snippets } = loadInContext(path.join(UPSTREAM, 'inkSnippets.js'), {
        fs: fakeFs,
        path: require('path'),
        './i18n/i18n.js': { _: (s) => s },
    });
    const entry = (s) => {
        if (s.separator) return { separator: true };
        const e = { name: s.name, ink: s.ink };
        if (fileOfContent.has(s.ink)) e.file = fileOfContent.get(s.ink);
        else e.tokens = tokenizeText(standardTokenizer, s.ink);
        return e;
    };
    writeJson('snippets.json', snippets.map((c) => (c.separator ? { separator: true } : { categoryName: c.categoryName, snippets: c.snippets.map(entry) })));
}

console.log('Goldens written to ' + path.relative(process.cwd(), GOLDEN));
