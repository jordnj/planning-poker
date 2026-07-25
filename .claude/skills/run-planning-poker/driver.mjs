#!/usr/bin/env node
/**
 * Planning Poker driver — drives the running app through a headless browser.
 *
 * The app is end-to-end encrypted: a participant cannot join with only a
 * session code, and any page that lacks the room key renders "🔒 encrypted"
 * instead of the real text. Every join here goes through the invite link
 * (`/?join=CODE#k=KEY`) so that dance is handled for you — that is the main
 * reason this file exists rather than a few raw Playwright calls.
 *
 *   node driver.mjs smoke   # full multi-participant round + privacy asserts
 *   node driver.mjs repl    # line-based REPL on stdin, for tmux driving
 *
 * Requires the server to already be listening on $PP_URL (default
 * http://localhost:8080) — see SKILL.md for the launch line.
 */

import readline from 'node:readline';
import { mkdirSync } from 'node:fs';

// Playwright lives in the image's GLOBAL node_modules. Two traps here:
//  * a bare `import('playwright')` fails from this repo — there is no local
//    node_modules and NODE_PATH is empty, so ESM resolution never looks in
//    the global prefix;
//  * the global entry point is CommonJS, and Node's named-export detection
//    does not surface `chromium`, so it only exists on `.default`.
// Try both specifiers and both shapes.
let chromium;
for (const spec of ['playwright', '/opt/node22/lib/node_modules/playwright/index.js']) {
  try {
    const m = await import(spec);
    chromium = m.chromium ?? m.default?.chromium;
    if (chromium) break;
  } catch { /* try the next specifier */ }
}
if (!chromium) {
  console.error('Could not load Playwright. Expected it at the global prefix:\n' +
    '  /opt/node22/lib/node_modules/playwright\n' +
    'Override with PP_PLAYWRIGHT=/path/to/playwright/index.js, or install it:\n' +
    '  npm install -g playwright   # browsers already in /opt/pw-browsers');
  process.exit(2);
}

const BASE = process.env.PP_URL || 'http://localhost:8080';
const SHOTS = process.env.PP_SHOTS || '/tmp/pp-shots';
// Chromium ships in the image; never let Playwright try to download one.
const EXECUTABLE = process.env.PP_CHROMIUM || '/opt/pw-browsers/chromium';

mkdirSync(SHOTS, { recursive: true });

// ---------------------------------------------------------------- utilities

const log = (...a) => console.log(...a);
let failures = 0;
function check(ok, msg) {
  log((ok ? '  ok   ' : '  FAIL ') + msg);
  if (!ok) failures++;
}

async function waitForServer(timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(BASE + '/actuator/health');
      if (r.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 500));
  }
  throw new Error(
    `No server at ${BASE} after ${timeoutMs}ms. Start it first:\n` +
    `  java -jar build/libs/planning-poker-0.1.0.jar > /tmp/pp.log 2>&1 &`);
}

// ------------------------------------------------------------------ harness

class Harness {
  constructor() {
    this.browser = null;
    this.pages = new Map();     // name -> Page
    this.errors = [];           // page + console errors, for `errors`
    this.requestBodies = [];    // every /api/ request body the browser sent
    this.code = null;
    this.roomKey = null;
  }

  async start() {
    this.browser = await chromium.launch({
      executablePath: EXECUTABLE,
      args: ['--no-sandbox', '--disable-dev-shm-usage'],
    });
  }

  async newPage(name, width = 1280, height = 950) {
    const ctx = await this.browser.newContext({ viewport: { width, height } });
    const page = await ctx.newPage();
    // Kick, delete-story and facilitator-transfer all go through window.confirm.
    // Playwright auto-DISMISSES dialogs, which makes those actions silently
    // no-op; accept them so the driver can actually exercise those paths.
    page.on('dialog', d => d.accept().catch(() => {}));
    page.on('pageerror', e => this.errors.push(`[${name}] pageerror: ${e.message}`));
    page.on('console', m => {
      // Chromium's console text for a failed fetch omits the URL, which makes
      // a bare "Failed to load resource" useless; the response hook below
      // records the URL instead, so drop the duplicate console line.
      if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) {
        this.errors.push(`[${name}] console: ${m.text()}`);
      }
    });
    page.on('response', r => {
      if (r.status() >= 400 && !r.url().endsWith('/favicon.ico')) {
        this.errors.push(`[${name}] HTTP ${r.status()} ${r.url()}`);
      }
    });
    page.on('request', r => {
      if (r.url().includes('/api/')) {
        this.requestBodies.push({ url: r.url(), body: r.postData() || '' });
      }
    });
    this.pages.set(name, page);
    return page;
  }

  page(name) {
    const p = this.pages.get(name);
    if (!p) throw new Error(`No such participant "${name}". Known: ${[...this.pages.keys()].join(', ') || '(none)'}`);
    return p;
  }

  /** Creates a session as facilitator and captures the code + room key. */
  async create(sessionName, who = 'fac', displayName = 'Fay Facilitator') {
    const page = await this.newPage(who);
    await page.goto(BASE);
    await page.waitForSelector('#create-form button[type=submit]');
    await page.fill('#create-session-name', sessionName);
    await page.fill('#create-your-name', displayName);
    await page.click('#create-form button[type=submit]');
    await page.waitForSelector('#view-session:not([hidden])', { timeout: 20000 });
    this.code = (await page.textContent('#session-code-badge')).trim();
    // The room key never reaches the server; read it out of the facilitator's
    // own page, which is the only place it exists.
    await page.click('#btn-show-room-key');
    this.roomKey = await page.inputValue('#room-key-value');
    return { code: this.code, roomKey: this.roomKey };
  }

  /** Joins via the invite link, which carries the key in its fragment. */
  async join(who, displayName, { observer = false } = {}) {
    if (!this.code) throw new Error('No session yet — run `create` first');
    const page = await this.newPage(who, 1000, 950);
    await page.goto(`${BASE}/?join=${this.code}#k=${this.roomKey}`);
    await page.fill('#join-your-name', displayName);
    if (observer) await page.check('#join-as-observer');
    await page.click('#join-form button[type=submit]');
    await page.waitForSelector('#view-session:not([hidden])', { timeout: 20000 });
    return page;
  }

  async addStory(title, description = '', url = '') {
    const page = this.page('fac');
    const before = await page.$$eval('.story-list li', ls => ls.length);
    await page.fill('#story-title', title);
    if (description) await page.fill('#story-description', description);
    if (url) await page.fill('#story-url', url);
    await page.click('#btn-save-story');
    await page.waitForFunction(
      n => document.querySelectorAll('.story-list li .story-actions').length > n,
      before === 1 ? 0 : before);
  }

  /** Starts voting on the nth story (1-based). ▶ is the first action button. */
  async select(n = 1) {
    const page = this.page('fac');
    await page.click(`.story-list li:nth-child(${n}) .story-actions button:first-child`);
    await page.waitForSelector('#active-story:not([hidden])');
  }

  async vote(who, card) {
    const page = this.page(who);
    await page.waitForSelector('.card-grid .card:not([disabled])', { timeout: 15000 });
    await page.click(`.card-grid .card:text-is("${card}")`);
  }

  async reveal() {
    const page = this.page('fac');
    await page.click('#btn-reveal');
    await page.waitForSelector('#results:not([hidden])', { timeout: 15000 });
  }

  async reset() {
    const page = this.page('fac');
    await page.click('#btn-reset');
    await page.waitForFunction(() => !document.getElementById('btn-reveal').hidden);
  }

  async estimate(value) {
    const page = this.page('fac');
    await page.fill('#estimate-input', String(value));
    await page.click('#estimate-form button[type=submit]');
    await page.waitForFunction(
      () => !!document.querySelector('.story-list .story-badge.estimated'));
  }

  async shot(who, label) {
    const page = this.page(who);
    const path = `${SHOTS}/${label || who}.png`;
    await page.screenshot({ path, fullPage: true });
    return path;
  }

  async stop() {
    if (this.browser) await this.browser.close();
  }
}

// -------------------------------------------------------------------- smoke

async function smoke() {
  await waitForServer();
  const h = new Harness();
  await h.start();
  const TITLE = 'Checkout rewrite';
  const DESC = 'Confidential: replace the legacy basket.\nOwner: Fay';

  try {
    const { code, roomKey } = await h.create('Sprint 42');
    log(`\nsession ${code}  key ${roomKey.slice(0, 12)}…`);

    await h.addStory(TITLE, DESC);
    await h.select(1);
    check((await h.page('fac').textContent('#active-story-title')) === TITLE,
      'facilitator sees the story it created');

    await h.join('ana', 'Ana Voter');
    await h.join('ben', 'Ben Voter');
    await h.join('olly', 'Olly Observer', { observer: true });
    await h.page('fac').waitForFunction(
      () => document.querySelectorAll('#participant-list li').length === 4);
    check(true, 'three participants joined via the invite link');

    check((await h.page('ana').textContent('#active-story-desc')).includes('Confidential'),
      'joiner decrypts the story description');
    check(await h.page('olly').isDisabled('.card-grid .card'),
      'observer cannot vote');

    await h.vote('ana', '5');
    await h.vote('ben', '8');
    // pre-reveal: who voted is public, what they voted is not
    await h.page('fac').waitForFunction(() =>
      [...document.querySelectorAll('#participant-list li')]
        .filter(li => li.querySelector('.p-status')?.textContent === '✅').length === 2);
    check(await h.page('fac').isHidden('#results'), 'votes stay hidden before reveal');

    await h.reveal();
    const votes = await h.page('ana').textContent('#vote-list');
    check(votes.includes('Ana Voter') && votes.includes('Ben Voter'),
      'reveal shows decrypted participant names');
    const stats = await h.page('fac').textContent('#stats');
    check(/6\.5/.test(stats), `average of 5 and 8 is 6.5 (got: ${stats.replace(/\s+/g, ' ').trim()})`);

    // screenshot here: the revealed round is the most informative frame, and
    // recording the estimate clears it
    for (const who of ['fac', 'ana', 'olly']) log(`  shot  ${await h.shot(who)}`);

    await h.estimate(8);
    check((await h.page('fac').textContent('.story-list .story-badge.estimated')).includes('8'),
      'final estimate recorded on the story');

    // --- the property this project exists to have ---
    const sent = h.requestBodies.map(r => r.body).join('\n');
    check(!sent.includes(TITLE), 'story title never left the browser in the clear');
    check(!sent.includes('Confidential'), 'description never left the browser in the clear');
    check(!sent.includes('Ana Voter'), 'display name never left the browser in the clear');
    check(sent.includes('e1.'), 'request bodies carry e1. ciphertext envelopes');

    const token = await h.page('fac').evaluate(
      () => JSON.parse(sessionStorage.getItem('pp-auth')).token);
    const api = await fetch(`${BASE}/api/sessions/${code}`,
      { headers: { Authorization: 'Bearer ' + token } }).then(r => r.json());
    check(!JSON.stringify(api).includes(TITLE), 'server cannot serve the title in the clear');
    // read from `stories`, not `activeStory`: recording an estimate moves the
    // story out of VOTING, so activeStory is legitimately null by now
    check(api.stories[0].title.startsWith('e1.'), 'server holds the title as ciphertext');

    check(h.errors.length === 0,
      `no page errors or failed requests${h.errors.length ? ': ' + h.errors.join(' | ') : ''}`);
  } finally {
    await h.stop();
  }

  log(failures ? `\n${failures} FAILURE(S)` : '\nsmoke OK');
  process.exit(failures ? 1 : 0);
}

// --------------------------------------------------------------------- repl

const HELP = `commands:
  create <session name>        create a session as facilitator, print code + key
  join <who> <display name>    join via the invite link (key handled for you)
  observe <who> <display name> join as a non-voting observer
  story <title>                add a story (facilitator)
  desc <title> | <description> add a story with a description
  select [n]                   start voting on story n (default 1)
  vote <who> <card>            cast a vote
  reveal | reset               facilitator round controls
  estimate <value>             record the final estimate
  ss <who> [label]             screenshot -> ${SHOTS}
  text <who> <selector>        print textContent of the first match
  eval <who> <js>              evaluate JS in that page, print the result
  errors                       print collected page/console errors
  who                          list open participants
  quit`;

async function repl() {
  await waitForServer();
  const h = new Harness();
  await h.start();
  log('driver ready. type `help` for commands.');
  log('READY');   // stable marker for `tmux ... | grep -q READY`

  const rl = readline.createInterface({ input: process.stdin, terminal: false });
  for await (const line of rl) {
    const raw = line.trim();
    if (!raw) continue;
    const [cmd, ...rest] = raw.split(/\s+/);
    const arg = rest.join(' ');
    try {
      switch (cmd) {
        case 'help': log(HELP); break;
        case 'create': {
          const { code, roomKey } = await h.create(arg || 'Driver session');
          log(`code=${code}`);
          log(`key=${roomKey}`);
          log(`link=${BASE}/?join=${code}#k=${roomKey}`);
          break;
        }
        case 'join': await h.join(rest[0], rest.slice(1).join(' ') || rest[0]); break;
        case 'observe':
          await h.join(rest[0], rest.slice(1).join(' ') || rest[0], { observer: true });
          break;
        case 'story': await h.addStory(arg); break;
        case 'desc': {
          const [t, d] = arg.split('|').map(s => s.trim());
          await h.addStory(t, d || '');
          break;
        }
        case 'select': await h.select(Number(rest[0] || 1)); break;
        case 'vote': await h.vote(rest[0], rest[1]); break;
        case 'reveal': await h.reveal(); break;
        case 'reset': await h.reset(); break;
        case 'estimate': await h.estimate(arg); break;
        case 'ss': log(await h.shot(rest[0], rest[1])); break;
        case 'text': log(await h.page(rest[0]).textContent(rest.slice(1).join(' '))); break;
        case 'eval': log(JSON.stringify(await h.page(rest[0]).evaluate(rest.slice(1).join(' ')))); break;
        case 'errors': log(h.errors.length ? h.errors.join('\n') : '(none)'); break;
        case 'who': log([...h.pages.keys()].join(', ') || '(none)'); break;
        case 'quit': await h.stop(); log('OK bye'); process.exit(0);
        default: log(`unknown command: ${cmd} (try \`help\`)`);
      }
      log('OK');   // every command ends with OK, so tmux can poll for it
    } catch (e) {
      log('ERR ' + e.message.split('\n')[0]);
      log('OK');
    }
  }
  await h.stop();
}

// --------------------------------------------------------------------- main

const mode = process.argv[2] || 'smoke';
if (mode === 'smoke') await smoke();
else if (mode === 'repl') await repl();
else {
  console.error(`usage: driver.mjs [smoke|repl]\n\n${HELP}`);
  process.exit(2);
}
