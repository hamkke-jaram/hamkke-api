#!/usr/bin/env node
// 공공데이터 원본 CSV → 정규화한 기관 CSV(SUP-04, docs/deploy.md 2.2 '지역 기관 목록 넣기').
// 사용(열을 확인한 파일 — column-map.json의 sources):
//   node scripts/facilities/convert.mjs --source mental-health-centers --in 원본.csv --out 정규화.csv --source-date 2026-09-21
// 사용(아직 확인하지 않은 파일 — column-map.json의 generic 짐작 표):
//   node scripts/facilities/convert.mjs --in 원본.csv --out 정규화.csv --category SPECIAL_SCHOOL \
//     --source-name "국립특수교육원 특수학교 현황" --source-date 2026-04-01
// 원본 한 파일은 한 유형이다. 원본이 UTF-8이 아니면 EUC-KR로 읽는다(--encoding으로 정할 수도 있다).
// 모르는 열, 모르는 설립 구분·운영 방식 값, 16개 시·도와 정해진 옛 이름 말고 다른 시·도가 있으면 아무것도 쓰지 않고 멈춘다(exit 2).
// 출력: UTF-8(BOM 없음), LF, 머리글 category,name,sido,sigungu,address,phone,homepage,operator,tags,source_name,source_date
//   status 열은 쓰지 않는다 → 새 기관은 PUBLIC_DATA, 우리가 확인한(CONFIRMED) 기관은 내용이 같으면 그대로 남는다.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const CATEGORIES = ['SPECIAL_SCHOOL', 'SPECIAL_ED_SUPPORT_CENTER', 'CHILDCARE_SUPPORT_CENTER',
  'MENTAL_HEALTH_CENTER', 'DISABILITY_WELFARE_CENTER'];
export const OUTPUT_COLUMNS = ['category', 'name', 'sido', 'sigungu', 'address', 'phone', 'homepage', 'operator',
  'tags', 'source_name', 'source_date'];

// hamkke-api의 Sido.java와 같은 표. 2026-07-01 광주광역시·전라남도가 전남광주통합특별시로 합쳐져 16개다.
export const SIDO = ['서울특별시', '부산광역시', '대구광역시', '인천광역시', '대전광역시', '울산광역시', '세종특별자치시',
  '경기도', '강원특별자치도', '충청북도', '충청남도', '전북특별자치도', '전남광주통합특별시', '경상북도', '경상남도',
  '제주특별자치도'];
// 공공데이터 원본에 남아 있는 옛 이름 → 공식 이름. 이 표에 없는 이름이면 멈춘다.
export const FORMER_SIDO = {
  광주광역시: '전남광주통합특별시', 전라남도: '전남광주통합특별시', 강원도: '강원특별자치도', 전라북도: '전북특별자치도',
  제주도: '제주특별자치도', 세종자치시: '세종특별자치시',
};

export function canonicalSido(raw) {
  if (!raw) return null;
  const s = String(raw).trim();
  if (SIDO.includes(s)) return s;
  return FORMER_SIDO[s] ?? null;
}

const BOM = String.fromCharCode(0xfeff);
// 국내 전화번호 모양(마지막 묶음 4자리). FacilityCsv.java의 PHONE과 같은 식이다(테스트가 비교한다).
//   02-XXX(X)-XXXX 서울, 0[3-6]X-XXX(X)-XXXX 지역번호(031~033, 041~044, 051~055, 061~064), 070·080,
//   01[016789]-XXX(X)-XXXX 휴대전화, 050X-XXXX-XXXX 안심번호, 1[5-9]XX-XXXX 대표번호
export const PHONE = /^(?:02-\d{3,4}|0(?:3[1-3]|4[1-4]|5[1-5]|6[1-4]|70|80)-\d{3,4}|01[016789]-\d{3,4}|050\d-\d{4}|1[5-9]\d{2})-\d{4}$/;
const URL = /^https?:\/\/[^\s"<>]+$/i;
const MAX_TAGS = 10;
const MAX_TAG_LENGTH = 20;

export class ConvertError extends Error {}

/** 바이트 → 글자. UTF-8로 읽다 깨지면 EUC-KR로 읽는다. */
export function decode(bytes, encoding = 'auto') {
  let text;
  if (encoding === 'auto') {
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch {
      text = new TextDecoder('euc-kr').decode(bytes);
    }
  } else {
    text = new TextDecoder(encoding, { fatal: true }).decode(bytes);
  }
  return text.startsWith(BOM) ? text.slice(1) : text;
}

/** RFC 4180 방식으로 줄과 칸을 나눈다. 빈 줄은 버린다. */
export function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  let any = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    any = true;
    if (quoted) {
      if (ch === '"') {
        if (text[i + 1] === '"') { cell += '"'; i++; } else quoted = false;
      } else cell += ch;
    } else if (ch === '"' && cell === '') quoted = true;
    else if (ch === ',') { row.push(cell); cell = ''; }
    else if (ch === '\r' || ch === '\n') {
      if (ch === '\r' && text[i + 1] === '\n') i++;
      row.push(cell); cell = ''; rows.push(row); row = []; any = false;
    } else cell += ch;
  }
  if (quoted) throw new ConvertError('큰따옴표가 닫히지 않았어요.');
  if (any) { row.push(cell); rows.push(row); }
  return rows.filter((r) => !(r.length === 1 && r[0].trim() === ''));
}

function csvCell(v) {
  const s = v ?? '';
  return /[",\r\n]/.test(s) || s !== s.trim() ? `"${s.replaceAll('"', '""')}"` : s;
}

const clean = (v) => (v ?? '').replace(/\s+/g, ' ').trim();

/** 숫자만 있는 번호에 '-'를 넣는다(02·050X·0XX·1XXX 앞자리 기준). 모양이 안 맞으면 null. */
function hyphenate(d) {
  if (d.startsWith('02') && (d.length === 9 || d.length === 10)) return `02-${d.slice(2, -4)}-${d.slice(-4)}`;
  if (d.startsWith('050') && d.length === 12) return `${d.slice(0, 4)}-${d.slice(4, 8)}-${d.slice(8)}`;
  if (d.startsWith('0') && (d.length === 10 || d.length === 11)) return `${d.slice(0, 3)}-${d.slice(3, -4)}-${d.slice(-4)}`;
  if (d.startsWith('1') && d.length === 8) return `${d.slice(0, 4)}-${d.slice(4)}`;
  return null;
}

/**
 * 국내 번호 모양(PHONE)으로 맞춘다. '02)123-4567', '02 123 4567', '0212345678'은 '02-123-4567' 꼴이 된다.
 * 내선이 붙은 번호('033-251-1970(240)')처럼 모양이 다르면 null — 그 칸을 비우고 버린 수를 알린다.
 * 화면이 이 값으로 바로 전화를 걸기 때문에, 짐작해서 고치지 않는다.
 */
export function normalisePhone(raw) {
  const s = clean(raw);
  if (!s) return null;
  const dashed = s.replace(/^\((\d{2,4})\)\s*/, '$1-').replace(/(\d)\)\s*/g, '$1-').replace(/[\s.]+/g, '-')
    .replace(/-+/g, '-').replace(/^-|-$/g, '');
  if (PHONE.test(dashed)) return dashed;
  if (/^\d+$/.test(dashed)) {
    const h = hyphenate(dashed);
    if (h && PHONE.test(h)) return h;
  }
  return null;
}

function normaliseHomepage(raw) {
  let s = clean(raw);
  if (!s) return '';
  if (!/^[a-z][a-z0-9+.-]*:/i.test(s)) s = `https://${s.replace(/^\/+/, '')}`;
  return URL.test(s) && s.length <= 300 ? s : null;
}

/** 열 짝 맞추기. 결과의 값은 모두 원본 열 번호다. */
function planFromSource(header, source) {
  const missing = source.columns.filter((c) => !header.includes(c));
  const extra = header.filter((h) => h && !source.columns.includes(h));
  if (missing.length || extra.length) {
    throw new ConvertError(`'${source.id}' 파일의 열이 표와 달라요.`
      + (missing.length ? ` 없는 열: ${missing.join(', ')}.` : '')
      + (extra.length ? ` 모르는 열: ${extra.join(', ')}.` : '')
      + '\ncolumn-map.json의 그 항목을 고친 뒤 다시 돌려 주세요.');
  }
  const at = (c) => (c === undefined ? undefined : header.indexOf(c));
  const f = source.fields;
  return {
    name: at(f.name), sido: at(f.sido), sigungu: at(f.sigungu), phone: at(f.phone), homepage: at(f.homepage),
    address: (Array.isArray(f.address) ? f.address : [f.address]).map(at),
    operator: source.operator ? { index: at(source.operator.column), values: source.operator.values } : null,
    tagLists: (source.tagLists ?? []).map(at),
    valueTags: Object.entries(source.valueTags ?? {}).map(([c, values]) => ({ column: c, index: at(c), values })),
  };
}

function planFromGeneric(header, g) {
  const known = new Set([...Object.values(g.fields).flat(), ...g.tagLists, ...g.ignore]);
  const unknown = header.filter((h) => h && !known.has(h));
  if (unknown.length) {
    throw new ConvertError(`모르는 열이 있어요: ${unknown.join(', ')}\n`
      + 'column-map.json에 이 파일의 항목(sources)을 만들거나 generic의 fields·tagLists·ignore에 더한 뒤 다시 돌려 주세요.');
  }
  // 필드마다 표에 먼저 적힌 이름이 이긴다. 나머지 별칭 열은 '아는 열'로만 친다.
  const pick = (field) => {
    for (const alias of g.fields[field] ?? []) {
      const i = header.indexOf(alias);
      if (i >= 0) return i;
    }
    return undefined;
  };
  const address = pick('address');
  const operator = pick('operator');
  return {
    name: pick('name'), sido: pick('sido'), sigungu: pick('sigungu'), phone: pick('phone'), homepage: pick('homepage'),
    address: address === undefined ? [] : [address],
    operator: operator === undefined ? null : { index: operator, values: { '': '', ...g.operatorValues } },
    tagLists: g.tagLists.map((t) => header.indexOf(t)).filter((i) => i >= 0),
    valueTags: [],
  };
}

/**
 * 원본 글자 → { csv, stats }. 멈춰야 하면 ConvertError(메시지에 무엇을 고칠지 적는다).
 * opts: { source?(sources의 id), category, sourceName, sourceDate, map }
 */
export function convert(text, opts) {
  const { map, sourceDate } = opts;
  let { category, sourceName } = opts;
  const source = opts.source ? map.sources.find((s) => s.id === opts.source) : null;
  if (opts.source && !source) {
    throw new ConvertError(`--source는 ${map.sources.map((s) => s.id).join(', ')} 가운데 하나예요.`);
  }
  if (source) {
    if (category && category !== source.category) throw new ConvertError(`'${source.id}'의 유형은 ${source.category}예요.`);
    category = source.category;
    sourceName = sourceName || source.sourceName;
  }
  if (!CATEGORIES.includes(category)) throw new ConvertError(`--category는 ${CATEGORIES.join(', ')} 가운데 하나예요.`);
  if (!clean(sourceName) || clean(sourceName).length > 100) throw new ConvertError('--source-name은 1~100자예요.');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(sourceDate ?? '') || Number.isNaN(Date.parse(sourceDate))) {
    throw new ConvertError('--source-date는 YYYY-MM-DD예요(공공데이터 기준일).');
  }

  const rows = parseCsv(text);
  if (rows.length === 0) throw new ConvertError('머리글이 없어요.');
  const header = rows[0].map((h) => clean(h));
  const plan = source ? planFromSource(header, source) : planFromGeneric(header, map.generic);
  if (plan.name === undefined || plan.address.length === 0) throw new ConvertError('이름이나 주소에 맞는 열이 없어요.');

  const problems = [];
  const unknownValues = new Map(); // 열 이름 → 모르는 값들
  const remember = (column, value) => {
    if (!unknownValues.has(column)) unknownValues.set(column, new Set());
    unknownValues.get(column).add(value);
  };
  const stats = { input: rows.length - 1, output: 0, duplicates: 0, phoneDropped: 0, phoneDroppedLines: [], homepageDropped: 0, tagsDropped: 0, sidoRenamed: 0 };
  const seen = new Set();
  const out = [OUTPUT_COLUMNS.join(',')];

  rows.slice(1).forEach((cells, k) => {
    const line = k + 2;
    if (cells.length !== header.length) { problems.push(`${line}번째 줄: 칸 수가 머리글과 달라요.`); return; }
    const cell = (i) => (i === undefined ? '' : clean(cells[i]));

    const name = cell(plan.name);
    if (!name || name.length > 100) { problems.push(`${line}번째 줄: 이름이 비었거나 100자보다 길어요.`); return; }

    // 시·도: 열이 있으면 그 값, 없으면 주소 첫 낱말. 옛 이름은 공식 이름으로 바꾼다.
    const rawSido = plan.sido !== undefined ? cell(plan.sido) : cell(plan.address[0]).split(' ')[0];
    const sido = canonicalSido(rawSido);
    if (!sido) { remember('시·도', rawSido || '(빈칸)'); return; }
    if (sido !== rawSido) stats.sidoRenamed++;

    // 주소: 여러 열이면 앞에서부터 붙인다(시·도 칸은 공식 이름으로). 뒤 칸이 이미 그 말로 시작하면 다시 붙이지 않는다.
    const parts = plan.address.map((i) => (i === plan.sido ? sido : cell(i)));
    let address = parts[parts.length - 1];
    for (let p = parts.length - 2; p >= 0; p--) {
      if (parts[p] && !address.startsWith(parts[p])) address = address ? `${parts[p]} ${address}` : parts[p];
    }
    if (!address || address.length > 200) { problems.push(`${line}번째 줄: 주소가 비었거나 200자보다 길어요.`); return; }

    let sigungu = cell(plan.sigungu);
    if (plan.sigungu === undefined) {
      const words = address.split(' ');
      if (canonicalSido(words[0]) && /[시군구]$/.test(words[1] ?? '')) sigungu = words[1];
    }
    if (sigungu.length > 30) sigungu = '';

    const rawPhone = cell(plan.phone);
    const phone = normalisePhone(rawPhone);
    if (rawPhone && !phone) {
      stats.phoneDropped++;
      stats.phoneDroppedLines.push(line);
    }
    const homepage = normaliseHomepage(cell(plan.homepage));
    if (homepage === null) stats.homepageDropped++;

    let operator = '';
    if (plan.operator) {
      const raw = cell(plan.operator.index);
      operator = plan.operator.values[raw];
      if (operator === undefined) { remember(header[plan.operator.index], raw || '(빈칸)'); return; }
    }

    const tags = [];
    const addTag = (t) => {
      if (!t || tags.includes(t)) return;
      if (t.length > MAX_TAG_LENGTH || tags.length >= MAX_TAGS) { stats.tagsDropped++; return; }
      tags.push(t);
    };
    for (const i of plan.tagLists) for (const part of String(cells[i] ?? '').split(/[,;/·]/)) addTag(clean(part));
    for (const vt of plan.valueTags) {
      const raw = cell(vt.index);
      if (!(raw in vt.values)) { remember(vt.column, raw || '(빈칸)'); return; }
      addTag(clean(vt.values[raw]));
    }

    const key = `${category}|${name}|${address}`;
    if (seen.has(key)) { stats.duplicates++; return; }
    seen.add(key);
    out.push([category, name, sido, sigungu, address, phone ?? '', homepage ?? '', operator ?? '', tags.join(';'),
      clean(sourceName), sourceDate].map(csvCell).join(','));
    stats.output++;
  });

  for (const [column, values] of [...unknownValues].reverse()) {
    problems.unshift(column === '시·도'
      ? `16개 시·도나 정해진 옛 이름이 아닌 값이 있어요: ${[...values].join(', ')}\n`
        + 'Sido.java와 convert.mjs의 표를 PM과 정한 뒤 함께 고쳐 주세요.'
      : `'${column}' 열에 모르는 값이 있어요: ${[...values].join(', ')}\n`
        + 'column-map.json에 그 값의 뜻(설립 구분 PUBLIC·PRIVATE·NONPROFIT, 또는 태그)을 더해 주세요.');
  }
  if (problems.length) throw new ConvertError(problems.slice(0, 20).join('\n') + (problems.length > 20 ? `\n…외 ${problems.length - 20}건` : ''));
  return { csv: `${out.join('\n')}\n`, stats };
}

function args(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (!argv[i].startsWith('--')) throw new ConvertError(`알 수 없는 인자: ${argv[i]}`);
    out[argv[i].slice(2)] = argv[i + 1];
    i++;
  }
  return out;
}

function main() {
  const a = args(process.argv.slice(2));
  if (!a.in || !a.out) throw new ConvertError('--in 원본.csv --out 정규화.csv가 필요해요.');
  const here = dirname(fileURLToPath(import.meta.url));
  const map = JSON.parse(readFileSync(a.map ? resolve(a.map) : join(here, 'column-map.json'), 'utf8'));
  const text = decode(readFileSync(resolve(a.in)), a.encoding ?? 'auto');
  const { csv, stats } = convert(text, {
    source: a.source, category: a.category, sourceName: a['source-name'], sourceDate: a['source-date'], map,
  });
  writeFileSync(resolve(a.out), csv, 'utf8');
  console.log(`원본 ${stats.input}줄 → ${stats.output}곳 (같은 기관 ${stats.duplicates}, 옛 시·도 이름 바꿈 ${stats.sidoRenamed}, `
    + `버린 전화번호 ${stats.phoneDropped}, 버린 누리집 ${stats.homepageDropped}, 버린 태그 ${stats.tagsDropped})`);
  if (stats.phoneDropped) {
    console.log(`국내 번호 모양이 아니라 비운 전화번호(원본 줄 번호): ${stats.phoneDroppedLines.join(', ')}`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  try {
    main();
  } catch (e) {
    if (!(e instanceof ConvertError)) throw e;
    console.error(e.message);
    process.exit(2);
  }
}
