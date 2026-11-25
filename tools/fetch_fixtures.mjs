// Downloads all saved workflows + trimmed object_info from the local ComfyUI
// as unit-test fixtures. Run: node tools/fetch_fixtures.mjs
import fs from 'node:fs';
import path from 'node:path';

const base = process.env.COMFY_URL || 'http://127.0.0.1:8188';
// project root = two levels up from tools/; resolve with plain paths (never URL-encoded)
const outDir = path.resolve(process.cwd(), 'app', 'src', 'test', 'resources', 'fixtures');

fs.mkdirSync(outDir, { recursive: true });

const list = await (await fetch(base + '/userdata?dir=workflows&recurse=true&split=false&full_info=true')).json();
const types = new Set();
// stable ASCII aliases (kept in sync with WorkflowConverterTest)
const KNOWN = {
  'MOSS-A-加速文生图.json': 'moss_a',
  'MOSS-B-底模加DMD.json': 'moss_b',
  'MOSS-布局海报.json': 'moss_layout',
  'Qwen-2.1-Turbo-极速文生图.json': 'qwen_turbo',
  'Qwen-2.1-图生图.json': 'qwen_i2i',
  'Qwen-2.1-图生图编辑.json': 'qwen_edit',
  'Qwen-2.1-文生图-全能版.json': 'qwen_t2i_all',
  'SDXL-动漫文生图.json': 'sdxl_anime',
  'SDXL-高清修复.json': 'sdxl_hires',
  'Anima-Aesthetic精调文生图.json': 'anima_aesthetic',
  'Anima-Turbo极速文生图.json': 'anima_turbo',
  'Krea2-二次元.json': 'krea2_anime',
};
const map = [];
let n = 0;
for (const e of list) {
  const enc = encodeURIComponent('workflows/' + e.path);
  const txt = await (await fetch(base + '/userdata/' + enc)).text();
  const fname = KNOWN[e.path] ?? ('extra_wf' + (n++));
  fs.writeFileSync(path.join(outDir, fname + '.json'), txt);
  map.push(`${fname} = ${e.path}`);
  const wf = JSON.parse(txt);
  for (const node of wf.nodes || []) types.add(node.type);
}
fs.writeFileSync(path.join(outDir, 'README.md'), '# fixtures from live ComfyUI (tools/fetch_fixtures.mjs)\n' + map.map(l => '# ' + l).join('\n') + '\n');
const info = await (await fetch(base + '/object_info')).json();
const trimmed = {};
for (const t of types) if (info[t]) trimmed[t] = info[t]; else console.log('WARN type not in object_info:', t);
fs.writeFileSync(path.join(outDir, 'object_info_trimmed.json'), JSON.stringify(trimmed));
console.log('fixtures written to', outDir);
