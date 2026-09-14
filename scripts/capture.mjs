#!/usr/bin/env node
// -----------------------------------------------------------------------------------------------
// Turntable capture for the Modrinth gallery.
//
// Drives the real creature editor — the page EditorServer serves, with the real Java bake behind it
// — through Playwright, and steps it one frame at a time. Nothing about the creature is rendered or
// invented here; this file is a clock, a camera crank and an encoder.
//
// Two rules make the loop seamless:
//   * the turntable angle is 2*pi * i / frames, a pure function of the frame index, so the last
//     frame is one step short of the first rather than a copy of it, and a re-roll can never nudge
//     the spin;
//   * every re-roll is awaited to completion — bake, skin upload, walk clip — before the frame that
//     shows it is taken, so no frame catches a half-baked mesh or a coarser LOD tier.
//
//   node capture.mjs --duration 12 --fps 20 --reroll-interval 20 --width 480 --seed-start 12345
// -----------------------------------------------------------------------------------------------

import { spawn, spawnSync } from 'node:child_process';
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const OUT_DIR = path.join(ROOT, 'build', 'capture');
const EDITOR_URL = 'http://127.0.0.1:8090';
const GIFSKI = path.join(HERE, 'node_modules', 'gifski', 'bin',
  process.platform === 'darwin' ? 'macos' : process.platform === 'win32' ? 'windows' : 'debian',
  process.platform === 'win32' ? 'gifski.exe' : 'gifski');

// ------------------------------------------------------------------------------------- arguments
const DEFAULTS = {
  duration: 12,            // seconds for one full revolution
  fps: 20,
  'reroll-interval': 20,   // frames between genome re-rolls
  width: 480,              // GIF width; the mp4 and webp follow it
  'seed-start': null,      // null → a fresh random run, printed so it can be replayed
  viewport: '720x420',
  bg: '#0e1013',           // '#rrggbb' or 'transparent'
  margin: 1.05,            // camera pull-back past the creature's bounds
  pitch: 0.22,             // radians above the horizon
  'walk-speed': 1.4,       // m/s for the gait; 0 stands the creature up and lets it breathe instead
  exposure: 0.6,           // capture-only light scale; 1.0 is exactly what the editor shows
  'min-size': 2.0,         // metres of body length; smaller animals read as specks and are skipped
  archetype: 'CHAOS',
  border: 'minecraft',     // 'none' for a bare viewport
  zoom: 1.0,               // 1.15 puts the camera close enough to fill 15% more of the frame
  'border-px': 6,          // thickness of one bevel ring, in CSS pixels
  quality: 90,
  out: path.join(ROOT, 'build', 'primordia_editor.gif'),
  'max-bytes': 1024 * 1024   // 1 MiB — the ceiling the retry ladder below is working under
};

function parseArgs(argv) {
  const o = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) throw new Error(`unexpected argument: ${a}`);
    const [flag, inline] = a.slice(2).split('=');
    if (!(flag in DEFAULTS)) throw new Error(`unknown flag: --${flag}\nknown: ${Object.keys(DEFAULTS).map(k => '--' + k).join(' ')}`);
    const raw = inline !== undefined ? inline : argv[++i];
    if (raw === undefined) throw new Error(`--${flag} needs a value`);
    o[flag] = typeof DEFAULTS[flag] === 'number' || DEFAULTS[flag] === null ? Number(raw) : raw;
  }
  return o;
}

const args = parseArgs(process.argv.slice(2));
const FPS = Math.round(args.fps);
const FRAMES = Math.round(args.duration * FPS);
const INTERVAL = Math.round(args['reroll-interval']);
const SEED_START = Number.isFinite(args['seed-start']) && args['seed-start'] !== null
  ? Math.floor(args['seed-start'])
  : Math.floor(Math.random() * 1e9);
const [VW, VH] = String(args.viewport).split('x').map(Number);
if (!FRAMES || !INTERVAL || !VW || !VH) throw new Error('bad --duration/--fps/--reroll-interval/--viewport');

// ------------------------------------------------------------------------------------ the server
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function editorAlive() {
  try {
    const r = await fetch(EDITOR_URL + '/api/meta', { signal: AbortSignal.timeout(1500) });
    return r.ok;
  } catch { return false; }
}

/**
 * Starts `gradle editor` unless one is already listening, and returns a stop function.
 * A server someone else started is left running — this script is not the owner of it.
 */
async function ensureEditor() {
  if (await editorAlive()) {
    console.log('editor: already running on ' + EDITOR_URL);
    return () => {};
  }
  // Launched from the Gradle task, the classpath is already resolved and handed over, so the server
  // starts as a plain `java` process. Starting a second Gradle build from inside one would block on
  // the project lock instead.
  let cmd, cmdArgs;
  if (process.env.PRIMORDIA_EDITOR_CP) {
    cmd = process.env.PRIMORDIA_JAVA || 'java';
    cmdArgs = ['-cp', process.env.PRIMORDIA_EDITOR_CP, 'dev.jsz.primordia.editor.EditorServer'];
  } else {
    cmd = process.env.GRADLE_BIN
      || [path.join(process.env.HOME, 'dev/tools/gradle-9.6.1/bin/gradle'), 'gradle']
        .find(g => g === 'gradle' || fs.existsSync(g));
    cmdArgs = ['editor', '--quiet'];
  }
  console.log(`editor: starting via "${cmd} ${cmdArgs[0]}" (a cold compile takes a minute)`);
  const child = spawn(cmd, cmdArgs, {
    cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, JAVA_HOME: process.env.JAVA_HOME || '/opt/homebrew/opt/openjdk@25' }
  });
  let log = '';
  child.stdout.on('data', d => { log += d; });
  child.stderr.on('data', d => { log += d; });
  const deadline = Date.now() + 8 * 60_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error('gradle editor exited early:\n' + log.slice(-4000));
    if (await editorAlive()) {
      console.log('editor: up on ' + EDITOR_URL);
      return () => child.kill('SIGTERM');
    }
    await sleep(1000);
  }
  child.kill('SIGKILL');
  throw new Error('editor did not come up in time:\n' + log.slice(-4000));
}

// ------------------------------------------------------------------------------------- capturing
async function captureFrames() {
  fs.rmSync(OUT_DIR, { recursive: true, force: true });
  fs.mkdirSync(OUT_DIR, { recursive: true });

  const browser = await chromium.launch({
    headless: true,
    args: [
      // Headless Chromium has no GPU, so WebGL2 comes from SwiftShader. Slower than a real GPU and
      // completely deterministic, which is the trade this script wants.
      '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
      '--disable-lcd-text', '--force-color-profile=srgb', '--hide-scrollbars'
    ]
  });
  const page = await browser.newPage({
    viewport: { width: VW, height: VH },
    deviceScaleFactor: 2
  });
  page.on('pageerror', e => console.error('page error:', e.message));
  page.on('console', m => { if (m.type() === 'error') console.error('console:', m.text()); });

  const url = `${EDITOR_URL}/?capture=1&bg=${encodeURIComponent(args.bg)}`
    + `&margin=${args.margin}&pitch=${args.pitch}&walk=${args['walk-speed']}`
    + `&exposure=${args.exposure}&archetype=${encodeURIComponent(args.archetype)}`
    + `&border=${encodeURIComponent(args.border)}&bordersize=${args['border-px']}&zoom=${args.zoom}`;
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction('window.__captureReady === true', null, { timeout: 60_000 });

  // With the Minecraft frame on, the shot is of the framed panel rather than of the bare canvas.
  const target = page.locator(await page.evaluate(() => window.__captureTarget));
  const transparent = args.bg === 'transparent';
  const seeds = [];
  let cursor = SEED_START;   // the next seed to consider, whether or not the last one was used

  /**
   * Walks forward from the cursor until a creature big enough to read at gallery size turns up.
   * Rejects are probed at the cheapest LOD and never baked properly, so the search costs little,
   * and every seed considered is printed — a run stays reproducible even though it skips.
   */
  async function nextSeed() {
    const skipped = [];
    for (let tries = 0; tries < 500; tries++) {
      const seed = cursor++;
      const p = await page.evaluate(s => window.__capture.probe(s), seed);
      if (p.bodyLength >= args['min-size']) {
        if (skipped.length) console.log(`  skipped ${skipped.length} under ${args['min-size']}m: ${skipped.join(' ')}`);
        return { seed, probe: p };
      }
      skipped.push(`${seed}(${p.bodyLength.toFixed(2)}m)`);
    }
    throw new Error(`no creature at or above --min-size ${args['min-size']} in 500 seeds`);
  }

  for (let i = 0; i < FRAMES; i++) {
    if (i % INTERVAL === 0) {
      const { seed, probe } = await nextSeed();
      const info = await page.evaluate(s => window.__capture.setSeed(s), seed);
      seeds.push(seed);
      console.log(`reroll @frame ${String(i).padStart(4, '0')}  seed ${seed}`
        + `  (${probe.bodyLength.toFixed(2)}m long, ${probe.legs} legs, `
        + `${info.verts.toLocaleString()} verts, ${info.bones} bones, tier ${info.tier})`);
    }
    // Awaited every frame, not just after a re-roll: cheap when nothing is pending, and it is the
    // one thing standing between a gallery GIF and a single blurry low-tier frame in the middle.
    await page.evaluate(() => window.__capture.waitForBake());

    await page.evaluate(([angle, t]) => {
      window.__capture.setAngle(angle);
      window.__capture.setTime(t);
      window.__capture.draw();
    }, [2 * Math.PI * i / FRAMES, i / FPS]);

    await target.screenshot({
      path: path.join(OUT_DIR, `frame_${String(i).padStart(4, '0')}.png`),
      omitBackground: transparent,
      animations: 'disabled'
    });
    if ((i + 1) % 20 === 0) process.stdout.write(`  frames ${i + 1}/${FRAMES}\r`);
  }
  console.log(`\nframes: ${FRAMES} written to ${path.relative(ROOT, OUT_DIR)}/frame_%04d.png`);
  await browser.close();
  return seeds;
}

// -------------------------------------------------------------------------------------- encoding
function run(cmd, cmdArgs) {
  const r = spawnSync(cmd, cmdArgs, { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
  if (r.error) throw r.error;
  if (r.status !== 0) throw new Error(`${path.basename(cmd)} failed (${r.status}):\n${r.stderr || r.stdout}`);
  return r.stdout;
}

const framePaths = () => fs.readdirSync(OUT_DIR).filter(f => f.endsWith('.png')).sort()
  .map(f => path.join(OUT_DIR, f));

/**
 * Picks the subset of captured frames that plays the same 12 seconds at a lower rate.
 * <p>
 * Necessary because gifski's --fps does not mean what it looks like when the input is PNGs: it keeps
 * every frame and changes the playback speed, so asking for 15 fps off a 20 fps capture yields a
 * clip that is the same size and a third longer, rather than a smaller one. Dropping frames here is
 * what actually trades rate for bytes, and sampling by time rather than by stride keeps the
 * turntable's constant angular speed — and so the seamless loop — intact at any ratio.
 */
function framesAtRate(fps) {
  if (fps >= FPS) return framePaths();
  const all = framePaths();
  const out = [];
  for (let k = 0; k < Math.round(FRAMES * fps / FPS); k++) {
    out.push(all[Math.round(k * FPS / fps) % all.length]);
  }
  return out;
}

function encodeGif(out, width, fps, quality) {
  fs.rmSync(out, { force: true });
  // --repeat 0 is gifski's spelling of "loop forever"; there is no --loop flag.
  run(GIFSKI, ['--fps', String(fps), '--width', String(width), '--quality', String(quality),
    '--repeat', '0', '--no-sort', '-o', out, ...framesAtRate(fps)]);
  return fs.statSync(out).size;
}

function encodeMp4(out, width, fps) {
  fs.rmSync(out, { force: true });
  run('ffmpeg', ['-y', '-framerate', String(fps), '-pattern_type', 'glob',
    '-i', path.join(OUT_DIR, 'frame_*.png'),
    // yuv420p needs even dimensions, and every player needs yuv420p.
    '-vf', `scale=${width}:-2:flags=lanczos`,
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20', '-movflags', '+faststart', out]);
  return fs.statSync(out).size;
}

const hasLibwebp = () => {
  const r = spawnSync('ffmpeg', ['-hide_banner', '-encoders'], { encoding: 'utf8' });
  return r.status === 0 && /\blibwebp\b/.test(r.stdout);
};

/**
 * Animated WebP. Homebrew's ffmpeg is routinely built without libwebp, so the fallback goes through
 * the webp tools' own `img2webp` — which cannot scale, hence the pass through ffmpeg first.
 */
function encodeWebp(out, width, fps) {
  fs.rmSync(out, { force: true });
  if (hasLibwebp()) {
    run('ffmpeg', ['-y', '-framerate', String(fps), '-pattern_type', 'glob',
      '-i', path.join(OUT_DIR, 'frame_*.png'), '-vf', `scale=${width}:-2:flags=lanczos`,
      '-c:v', 'libwebp', '-lossless', '0', '-q:v', '80', '-loop', '0', '-an', out]);
    return fs.statSync(out).size;
  }
  // Outside build/capture deliberately: that directory is wiped wholesale at the start of a run, so
  // a scratch directory living inside it is one concurrent invocation away from vanishing mid-encode.
  const scaled = fs.mkdtempSync(path.join(os.tmpdir(), 'primordia-webp-'));
  try {
    run('ffmpeg', ['-y', '-pattern_type', 'glob', '-i', path.join(OUT_DIR, 'frame_*.png'),
      '-vf', `scale=${width}:-2:flags=lanczos`, path.join(scaled, 'f_%04d.png')]);
    const files = fs.readdirSync(scaled).filter(f => f.endsWith('.png')).sort()
      .map(f => path.join(scaled, f));
    if (files.length !== framePaths().length) {
      throw new Error(`rescale produced ${files.length} frames, expected ${framePaths().length}`);
    }
    run('img2webp', ['-loop', '0', '-q', '80', '-d', String(Math.round(1000 / fps)), ...files,
      '-o', out]);
  } finally {
    fs.rmSync(scaled, { recursive: true, force: true });
  }
  return fs.statSync(out).size;
}

const mb = n => (n / 1024 / 1024).toFixed(2) + ' MiB';

// ------------------------------------------------------------------------------------------ main
const stopEditor = await ensureEditor();
try {
  console.log(`capture: ${FRAMES} frames @ ${FPS} fps (${args.duration}s), re-roll every ${INTERVAL} `
    + `frames (${Math.ceil(FRAMES / INTERVAL)} genomes), seeds from ${SEED_START}`);
  const seeds = await captureFrames();

  // The retry ladder, ordered by what a viewer notices least. Quality goes first — gifski's palette
  // and dithering effort cost bytes long before they cost anything visible on a flat-shaded voxel
  // animal. Width is next. Frame rate goes last, because a turntable dropping to 15 or 10 fps is the
  // one change that reads as cheap.
  const attempts = [
    { width: args.width, fps: FPS, quality: args.quality },
    { width: args.width, fps: FPS, quality: 80 },
    // q75 is the rung that usually wins at 1 MiB: q80 lands just over on a 12s clip and q70 gives up
    // more palette than it needs to.
    { width: args.width, fps: FPS, quality: 75 },
    { width: args.width, fps: FPS, quality: 70 },
    { width: 400, fps: FPS, quality: 70 },
    { width: 400, fps: 15, quality: 70 },
    { width: 360, fps: 15, quality: 65 },
    { width: 320, fps: 10, quality: 60 }
  ];
  let won = null, gifSize = 0;
  for (const a of attempts) {
    gifSize = encodeGif(args.out, a.width, a.fps, a.quality);
    console.log(`gif: ${a.width}px @ ${a.fps}fps q${a.quality} → ${mb(gifSize)}`);
    won = a;
    if (gifSize <= args['max-bytes']) break;
    if (a !== attempts[attempts.length - 1]) {
      console.log(`     over ${mb(args['max-bytes'])}, retrying smaller`);
    }
  }

  const mp4 = args.out.replace(/\.gif$/, '.mp4');
  const webp = args.out.replace(/\.gif$/, '.webp');
  const mp4Size = encodeMp4(mp4, won.width, won.fps);
  const webpSize = encodeWebp(webp, won.width, won.fps);

  console.log('\n--- output ---------------------------------------------------');
  console.log(`gif   ${path.relative(ROOT, args.out).padEnd(34)} ${mb(gifSize)}`);
  console.log(`mp4   ${path.relative(ROOT, mp4).padEnd(34)} ${mb(mp4Size)}`);
  console.log(`webp  ${path.relative(ROOT, webp).padEnd(34)} ${mb(webpSize)}`);
  console.log(`settings that won: --width ${won.width} --fps ${won.fps} --quality ${won.quality}`
    + (gifSize > args['max-bytes'] ? `  (still over ${mb(args['max-bytes'])})` : ''));
  console.log(`reproduce: node scripts/capture.mjs --seed-start ${SEED_START} `
    + `--duration ${args.duration} --fps ${FPS} --reroll-interval ${INTERVAL} --width ${args.width} --min-size ${args['min-size']} --walk-speed ${args['walk-speed']} --exposure ${args.exposure}`);
  console.log(`seeds: ${seeds.join(' ')}`);
} finally {
  stopEditor();
}
