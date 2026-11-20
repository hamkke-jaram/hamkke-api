// node --test scripts/facilities/convert.test.mjs   (원본 → 정규화 CSV 바꾸기 시험. 값은 모두 가짜다)
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { ConvertError, FORMER_SIDO, PHONE, SIDO, canonicalSido, convert, decode, normalisePhone, parseCsv } from './convert.mjs';

const map = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'column-map.json'), 'utf8'));
const opts = { category: 'SPECIAL_SCHOOL', sourceName: '시험 공공데이터', sourceDate: '2026-04-01', map };
const HEADER = 'category,name,sido,sigungu,address,phone,homepage,operator,tags,source_name,source_date';
const MH_HEADER = '기관번호,기관명,운영방식,운영시작연도,시도,시군구,도로명주소,우편번호,전화번호,위도,경도';
const mh = { source: 'mental-health-centers', sourceDate: '2026-09-21', map };

test('정신건강복지센터 파일: 주소에 시도·시군구를 붙이고 옛 시·도 이름을 바꾼다', () => {
  const raw = `${String.fromCharCode(0xfeff)}${[
    MH_HEADER,
    '900001,시험시정신건강복지센터,위탁,2005,서울특별시,시험구,시험로 1,00001,02-0000-0001,37.0,127.0',
    '900002,시험군정신건강복지센터,,2010,강원도,시험군,시험길 2,00002,02-0000-0002,37.5,128.0',
    '900003,시험광역정신건강복지센터,위탁,2012,전남광주통합특별시,시험구,시험대로 3,00003,02-0000-0003,35.1,126.8',
    '900004,시험도정신건강복지센터,위탁,2012,제주도,시험시,시험로 4,00004,,33.5,126.5',
    '900005,시험자치시정신건강복지센터,,2015,세종자치시,,시험로 5,00005,02-0000-0005,36.5,127.2',
  ].join('\r\n')}\r\n`;
  const { csv, stats } = convert(decode(new TextEncoder().encode(raw)), mh);
  assert.deepEqual(csv.split('\n'), [
    HEADER,
    'MENTAL_HEALTH_CENTER,시험시정신건강복지센터,서울특별시,시험구,서울특별시 시험구 시험로 1,02-0000-0001,,PUBLIC,위탁 운영,한국보건의료정보원 정신건강복지센터 목록,2026-09-21',
    'MENTAL_HEALTH_CENTER,시험군정신건강복지센터,강원특별자치도,시험군,강원특별자치도 시험군 시험길 2,02-0000-0002,,PUBLIC,,한국보건의료정보원 정신건강복지센터 목록,2026-09-21',
    'MENTAL_HEALTH_CENTER,시험광역정신건강복지센터,전남광주통합특별시,시험구,전남광주통합특별시 시험구 시험대로 3,02-0000-0003,,PUBLIC,위탁 운영,한국보건의료정보원 정신건강복지센터 목록,2026-09-21',
    'MENTAL_HEALTH_CENTER,시험도정신건강복지센터,제주특별자치도,시험시,제주특별자치도 시험시 시험로 4,,,PUBLIC,위탁 운영,한국보건의료정보원 정신건강복지센터 목록,2026-09-21',
    'MENTAL_HEALTH_CENTER,시험자치시정신건강복지센터,세종특별자치시,,세종특별자치시 시험로 5,02-0000-0005,,PUBLIC,,한국보건의료정보원 정신건강복지센터 목록,2026-09-21',
    '',
  ]);
  assert.equal(stats.output, 5);
  assert.equal(stats.sidoRenamed, 3);
});

test('정신건강복지센터 파일: 열이 다르거나 모르는 값이 있으면 멈춘다', () => {
  const row = '900001,시험시정신건강복지센터,위탁,2005,서울특별시,시험구,시험로 1,00001,02-0000-0001,37.0,127.0';
  assert.throws(() => convert(`${MH_HEADER},새열\n${row},x\n`, mh), (e) => e instanceof ConvertError && e.message.includes('새열'));
  assert.throws(() => convert(`${MH_HEADER.replace(',경도', '')}\n${row.replace(',127.0', '')}\n`, mh),
    (e) => e instanceof ConvertError && e.message.includes('경도'));
  assert.throws(() => convert(`${MH_HEADER}\n${row.replace(',위탁,', ',직영,')}\n`, mh),
    (e) => e instanceof ConvertError && e.message.includes('직영'));
  assert.throws(() => convert(`${MH_HEADER}\n${row.replace('서울특별시', '광주직할시')}\n`, mh),
    (e) => e instanceof ConvertError && e.message.includes('광주직할시'));
  assert.throws(() => convert(`${MH_HEADER}\n${row}\n`, { ...mh, source: 'none' }), ConvertError);
  assert.throws(() => convert(`${MH_HEADER}\n${row}\n`, { ...mh, category: 'SPECIAL_SCHOOL' }), ConvertError);
});

test('짐작 표: 원본 열을 짝지어 정규화 CSV를 만든다', () => {
  const raw = [
    '연번,학교명,설립구분,도로명주소,전화번호,홈페이지,장애영역,우편번호',
    '1,시험 특수학교 1,공립,서울특별시 시험구 시험로 1,02)000-0001,www.example.com,"지적장애, 자폐성장애",00001',
    '2,시험 특수학교 2,사립,"강원도 시험군 시험로 2, 2층",033 0000 0002,,지체장애/지적장애,00002',
    '3,시험 특수학교 1,공립,서울특별시 시험구 시험로 1,,,,00001',
  ].join('\r\n');
  const { csv, stats } = convert(raw, opts);
  assert.deepEqual(csv.split('\n'), [
    HEADER,
    'SPECIAL_SCHOOL,시험 특수학교 1,서울특별시,시험구,서울특별시 시험구 시험로 1,02-000-0001,https://www.example.com,PUBLIC,지적장애;자폐성장애,시험 공공데이터,2026-04-01',
    'SPECIAL_SCHOOL,시험 특수학교 2,강원특별자치도,시험군,"강원도 시험군 시험로 2, 2층",033-0000-0002,,PRIVATE,지체장애;지적장애,시험 공공데이터,2026-04-01',
    '',
  ]);
  assert.equal(stats.input, 3);
  assert.equal(stats.output, 2);
  assert.equal(stats.duplicates, 1);
});

test('짐작 표: 모르는 열이 있으면 멈추고 그 이름을 알린다', () => {
  const raw = '학교명,도로명주소,새로운열\n시험 특수학교 1,서울특별시 시험로 1,x\n';
  assert.throws(() => convert(raw, opts), (e) => e instanceof ConvertError && e.message.includes('새로운열'));
});

test('짐작 표: 모르는 설립 구분 값이 있으면 멈춘다', () => {
  const raw = '학교명,도로명주소,설립구분\n시험 특수학교 1,서울특별시 시험로 1,위탁\n';
  assert.throws(() => convert(raw, opts), (e) => e instanceof ConvertError && e.message.includes('위탁'));
});

test('시·도를 알 수 없으면 멈춘다(줄인 이름도)', () => {
  assert.throws(() => convert('학교명,도로명주소\n시험 특수학교 1,시험나라 시험로 1\n', opts),
    (e) => e instanceof ConvertError && e.message.includes('시험나라'));
  assert.throws(() => convert('학교명,도로명주소\n시험 특수학교 1,서울 시험로 1\n', opts), ConvertError);
});

test('이름·주소 열이 없거나 인자가 틀리면 멈춘다', () => {
  assert.throws(() => convert('학교명,전화번호\n시험,000\n', opts), ConvertError);
  assert.throws(() => convert('학교명,주소\n시험,서울특별시 시험로\n', { ...opts, category: 'HOSPITAL' }), ConvertError);
  assert.throws(() => convert('학교명,주소\n시험,서울특별시 시험로\n', { ...opts, sourceDate: '2026/04/01' }), ConvertError);
});

test('누리집은 http(s)만 남기고 이상한 값은 버린다', () => {
  const raw = '학교명,주소,홈페이지\n시험 1,서울특별시 시험로 1,javascript:alert(1)\n시험 2,서울특별시 시험로 2,http://example.com/a\n';
  const { csv, stats } = convert(raw, opts);
  const lines = csv.trim().split('\n');
  assert.equal(lines[1].split(',')[6], '');
  assert.equal(lines[2].split(',')[6], 'http://example.com/a');
  assert.equal(stats.homepageDropped, 1);
});

test('EUC-KR 원본도 읽는다', () => {
  // '시험'을 EUC-KR로 적은 바이트: BD C3 C7 E8
  const bytes = Uint8Array.from([0x41, 0x2c, 0xbd, 0xc3, 0xc7, 0xe8]);
  assert.equal(decode(bytes), 'A,시험');
  assert.equal(decode(new TextEncoder().encode(`${String.fromCharCode(0xfeff)}A,시험`)), 'A,시험');
});

test('시·도 표는 16개이고 정해진 옛 이름만 바꾼다', () => {
  assert.equal(SIDO.length, 16);
  assert.equal(canonicalSido('광주광역시'), '전남광주통합특별시');
  assert.equal(canonicalSido('전라남도'), '전남광주통합특별시');
  assert.equal(canonicalSido('전라북도'), '전북특별자치도');
  assert.equal(canonicalSido('세종자치시'), '세종특별자치시');
  assert.equal(canonicalSido('서울'), null);
  assert.equal(canonicalSido('서울특별시교육청'), null);
  assert.deepEqual(parseCsv('a,"b,""c"""\n\nd,e'), [['a', 'b,"c"'], ['d', 'e']]);
});

test('Sido.java와 같은 표를 쓴다', () => {
  const java = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..',
    'src/main/java/kr/hamkkejaram/support/Sido.java'), 'utf8');
  const block = (from) => java.slice(java.indexOf(from), java.indexOf(');', java.indexOf(from)));
  const names = [...block('NAMES = List.of(').matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual(names, SIDO);
  const pairs = [...block('FORMER_NAMES = Map.of(').matchAll(/"([^"]+)", "([^"]+)"/g)].map((m) => [m[1], m[2]]);
  assert.deepEqual(Object.fromEntries(pairs), FORMER_SIDO);
});

test('전화번호: 국내 번호 모양만 남기고, 다른 모양은 비운다', () => {
  const kept = {
    '02-123-4567': '02-123-4567', '02-1234-5678': '02-1234-5678', '033-251-1970': '033-251-1970',
    '051-242-2575': '051-242-2575', '064-000-0000': '064-000-0000', '070-4158-4676': '070-4158-4676',
    '010-1234-5678': '010-1234-5678', '0507-1234-5678': '0507-1234-5678', '1577-0199': '1577-0199',
    '080-000-0000': '080-000-0000',
    // 모양만 다른 같은 번호는 맞춰 준다
    '02)123-4567': '02-123-4567', '(02) 123-4567': '02-123-4567', '02 123 4567': '02-123-4567',
    '02.123.4567': '02-123-4567', '0212345678': '02-1234-5678', '0332511970': '033-251-1970',
    '050712345678': '0507-1234-5678', '15770199': '1577-0199', ' 033-251-1970 ': '033-251-1970',
  };
  for (const [raw, want] of Object.entries(kept)) assert.equal(normalisePhone(raw), want, raw);
  const dropped = [
    '033-251-1970(240)', '033-251-1970240', '033-251-1970-240', '033-251-197', '000-0000-0001', '039-000-0000',
    '02-12-3456', '1577-019', '1234-5678', '112', '129', '+82-2-123-4567', '전화', '02-123-4567 내선 3', '',
  ];
  for (const raw of dropped) assert.equal(normalisePhone(raw), null, raw);
});

test('전화번호: 모양이 다른 번호는 그 칸만 비우고 줄은 남긴다', () => {
  const raw = [
    MH_HEADER,
    '900001,시험시정신건강복지센터,위탁,2005,서울특별시,시험구,시험로 1,00001,033-000-0000(240),37.0,127.0',
    '900002,시험군정신건강복지센터,위탁,2010,강원도,시험군,시험길 2,00002,0507-0000-0002,37.5,128.0',
  ].join('\n');
  const { csv, stats } = convert(raw, mh);
  const lines = csv.trim().split('\n');
  assert.equal(lines.length, 3);
  assert.equal(lines[1].split(',')[5], '');
  assert.equal(lines[2].split(',')[5], '0507-0000-0002');
  assert.equal(stats.phoneDropped, 1);
  assert.deepEqual(stats.phoneDroppedLines, [2]);
});

test('전화번호 모양은 FacilityCsv.java와 같은 식이다', () => {
  const java = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..',
    'src/main/java/kr/hamkkejaram/support/FacilityCsv.java'), 'utf8');
  const m = java.match(/PHONE = Pattern\.compile\(\s*"([^"]+)"\);/);
  assert.ok(m, 'FacilityCsv.java에서 PHONE을 찾지 못함');
  assert.equal(m[1].replaceAll('\\\\', '\\'), PHONE.source);
});
