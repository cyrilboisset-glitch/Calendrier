// Tests des règles du calendrier : node tests/regles.test.cjs
const { chromium } = require('playwright');
const path = require('path');
const assert = require('assert');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
  await page.goto('file://' + path.resolve(__dirname, '../www/index.html'));
  const ev = (f, ...a) => page.evaluate(f, ...a);
  let ok = 0; const t = async (nom, f) => { await f(); ok++; console.log('ok -', nom); };

  await t('cycle : semaine du 29/12/2025 = matin lun-jeu, semaine du 5/01 = après-midi lun-ven', async () => {
    const r = await ev(() => ['2026-01-01','2026-01-05','2026-01-08','2026-01-09','2026-01-12','2026-01-16','2026-01-17','2026-12-31'].map(s => shift(parse(s))));
    assert.deepStrictEqual(r, ['OFF','PM','PM','PM','AM','OFF','OFF','OFF']);
  });

  await t('fractionnement : 2e CFR refusé avant novembre, refusé si < 4,5 CPP jan-avr, accepté sinon', async () => {
    const r = await ev(() => {
      resetAll = resetAll; state = defaultState(); year = 2027; yd();
      const out = {};
      out.base = left('CFR');
      // 1er CFR posé en mars : OK
      out.first = canPlace('CFR', 1, '2027-03-01');
      yd().leaves['2027-03-01'] = {t:'CFR', q:1};
      out.nov_sans = canPlace('CFR', 1, '2027-11-03');
      // 4,5 CPP entre janvier et avril
      ['2027-01-04','2027-01-05','2027-01-06','2027-01-07'].forEach(d => yd().leaves[d] = {t:'CPP', q:1});
      yd().leaves['2027-02-01'] = {t:'CPP', q:.5};
      out.acquis = fracInfo().earned;
      out.oct = canPlace('CFR', 1, '2027-10-04');
      out.nov = canPlace('CFR', 1, '2027-11-03');
      out.leftAvec = left('CFR');
      return out;
    });
    assert.strictEqual(r.base, 1);
    assert.strictEqual(r.first, null);
    assert.match(r.nov_sans, /non acquis/);
    assert.strictEqual(r.acquis, true);
    assert.match(r.oct, /1er novembre/);
    assert.strictEqual(r.nov, null);
    assert.strictEqual(r.leftAvec, 1);
  });

  await t('CPP posés en mai ne comptent pas pour le fractionnement', async () => {
    const r = await ev(() => { state = defaultState(); year = 2027; yd();
      ['2027-05-03','2027-05-04','2027-05-05','2027-05-06','2027-05-10'].forEach(d => yd().leaves[d] = {t:'CPP', q:1});
      return fracInfo().earned; });
    assert.strictEqual(r, false);
  });

  await t('férié : seul un CPP, un seul par an, pas si marqué + RECUP', async () => {
    const r = await ev(() => { state = defaultState(); year = 2026; yd();
      const h = holidays(2026).filter(h => worked(h[0])).map(h => h[0]);
      const out = {h};
      out.cp5 = canPlace('CP5', 1, h[0]);
      out.cpp = canPlace('CPP', 1, h[0]);
      yd().leaves[h[0]] = {t:'CPP', q:1};
      out.second = canPlace('CPP', 1, h[1]);
      delete yd().leaves[h[0]];
      yd().hm[h[1]] = 'recup';
      out.recup = canPlace('CPP', 1, h[1]);
      return out; });
    assert.match(r.cp5, /seul un CPP/);
    assert.strictEqual(r.cpp, null);
    assert.match(r.second, /un seul congé sur férié/);
    assert.match(r.recup, /\+ RECUP/);
  });

  await t('saisie d’une période avec 2 fériés en CPP : un seul férié posé', async () => {
    const r = await ev(() => { state = defaultState(); year = 2026; yd(); render(false);
      // 1er au 15 mai 2026 : 1er mai (ven) et 8 mai (ven), Ascension 14 mai
      selected = range('2026-05-01','2026-05-15'); openModal(); ltype.value = 'CPP'; qtys(); lqty.value = '1'; saveDays();
      return Object.keys(yd().leaves).filter(d => holidayName(d)); });
    assert.strictEqual(r.length, 1);
  });

  await t('CET : report = restes CSUP+CFE+CFR+CAN+CETC (pas CPP, CP5, RECUP)', async () => {
    const r = await ev(() => { state = defaultState(); year = 2026; yd();
      const a = cetStart(2027);
      yd(2026).leaves['2026-01-05'] = {t:'CSUP', q:1};
      yd(2026).leaves['2026-01-06'] = {t:'CPP', q:1};
      const b = cetStart(2027);
      return {a, b}; });
    assert.strictEqual(+r.a.toFixed(2), +(4.5 + .5 + 1 + 3 + 5.03).toFixed(2));
    assert.strictEqual(+r.b.toFixed(2), +(3.5 + .5 + 1 + 3 + 5.03).toFixed(2));
  });

  await t('« annotation seulement » ne supprime plus les congés', async () => {
    const r = await ev(() => { state = defaultState(); year = 2026; yd(); render(false);
      yd().leaves['2026-02-02'] = {t:'CPP', q:1};
      selected = ['2026-02-02']; openModal(); ltype.value = 'NONE'; qtys(); comment.value = 'RDV'; saveDays();
      return [!!yd().leaves['2026-02-02'], yd().comments['2026-02-02']]; });
    assert.deepStrictEqual(r, [true, 'RDV']);
  });

  await t('RECUP : liée à un férié antérieur, férié verrouillé tant qu’elle existe, case de même taille', async () => {
    const r = await ev(() => { state = defaultState(); year = 2026; yd();
      const h = holidays(2026).filter(h => worked(h[0]))[0][0];
      yd().hm[h] = 'recup'; render(false);
      const pose = '2026-12-01';
      selected = [pose]; openModal(); ltype.value = 'RECUP'; qtys(); recupSource.value = h; saveDays();
      const placed = yd().leaves[pose];
      setHM(h, 'none');
      const still = yd().hm[h];
      const cell = document.querySelector(`.day[data-d="${pose}"]`), other = document.querySelector('.day[data-d="2026-12-02"]');
      return {placed, still, hR: cell.getBoundingClientRect().height, hO: other.getBoundingClientRect().height, h}; });
    assert.strictEqual(r.placed.t, 'RECUP');
    assert.strictEqual(r.placed.sourceHoliday, r.h);
    assert.strictEqual(r.still, 'recup');
    assert.strictEqual(r.hR, r.hO);
  });

  await t('import d’une sauvegarde de la version 1', async () => {
    const v1 = JSON.stringify({currentYear:2026, schoolZone:'B', schoolVacations:{}, years:{2026:{balances:{CPP:19,CP5:4,CSUP:4.5,CFE:.5,CFR:1,CAN:3,CETC:5.03},leaves:{'2026-03-02':{t:'CPP',q:1,detail:''}},comments:{'2026-03-03':'test'},hm:{}}, 2027:{balances:{CPP:19,CP5:4,CSUP:4.5,CFE:.5,CFR:1,CAN:3,CETC:5.03},leaves:{},comments:{},hm:{}}}});
    const r = await ev(txt => { importText(txt); return {zone: state.schoolZone, v: state.version, cpp: left('CPP', 2026), note: yd(2026).comments['2026-03-03'], adj: yd(2027).cetAdj}; }, v1);
    assert.deepStrictEqual(r, {zone:'B', v:2, cpp:18, note:'test', adj:0});
  });

  await t('les données du navigateur (version 1) sont reprises au chargement', async () => {
    const p2 = await browser.newPage();
    await p2.goto('file://' + path.resolve(__dirname, '../www/index.html'));
    await p2.evaluate(() => localStorage.setItem('conges2026_2035_fix_retirer_v1', JSON.stringify({currentYear:2028, years:{2028:{balances:{CPP:10},leaves:{},comments:{},hm:{}}}})));
    await p2.reload();
    const r = await p2.evaluate(() => [year, left('CPP', 2028)]);
    assert.deepStrictEqual(r, [2028, 10]);
    await p2.close();
  });

  assert.deepStrictEqual(errors, []);
  console.log(ok + ' tests OK');
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
