// 存档导出、导入：游戏设置里加“导出存档”“导入存档”两项，文件由应用用系统的文件选择器存取，经 AchromaBridge 来回传。
// 只有从主菜单进的设置里有：导入后要重新载入页面，对局中做会打断对局。顺手去掉在应用里不起作用的全屏开关，腾出位置。
// 进行中的那一局（achroma_run）不导出，免得拿存档反复读档重打，排行榜是大家共用的。
// 导入时设置换成存档里的，已通关标记、图鉴和待上传的成绩两边合并，进度不会变少。
// 存档文件格式、合并规则和 README 里给网页版用的控制台脚本一致，改这里要一起改那边。
// 询问框用 panel.js（应用把它拼在本文件前面一起注入）。用到的游戏函数缺了就返回 false，运行中出错就停用，设置里不再显示这两项
(function () {
  if (window.ACH_SAVE) return !window.ACH_SAVE.dead;
  try {
    if (typeof AchromaBridge === 'undefined' || !window.ACH_PANEL) return false;
    [menuItems, updateMenu, drawTouchUI, toggleFS, tr];
    [G, AU, NET, C, GAME_VER];
  } catch (e) {
    return false;
  }
  var P = ACH_PANEL, KEYS = ['achroma_set', 'achroma_codex', 'achroma_pending'], IMPORTED = 'ach_save_imported';

  var S = {
    export: { en: 'EXPORT SAVE', zh: '匯出存檔', zhs: '导出存档', ja: 'セーブを書き出す' },
    import: { en: 'IMPORT SAVE', zh: '匯入存檔', zhs: '导入存档', ja: 'セーブを読み込む' },
    ask: { en: 'IMPORT THE SAVE EXPORTED AT {at}? SETTINGS WILL BE REPLACED, CODEX AND CLEARS MERGED, THEN THE GAME RELOADS.', zh: '匯入 {at} 匯出的存檔嗎？設定會換成存檔裡的，圖鑑和通關紀錄會合併，然後重新載入。', zhs: '导入 {at} 导出的存档吗？设置会换成存档里的，图鉴和通关记录会合并，然后重新载入。', ja: '{at} に書き出したセーブを読み込みますか？設定は置き換え、図鑑とクリア記録は統合して再読み込みします。' },
    ok: { en: 'IMPORT', zh: '匯入', zhs: '导入', ja: '読み込む' },
    cancel: { en: 'CANCEL', zh: '取消', zhs: '取消', ja: 'キャンセル' },
    exported: { en: 'SAVE EXPORTED', zh: '存檔已匯出', zhs: '存档已导出', ja: 'セーブを書き出しました' },
    imported: { en: 'SAVE IMPORTED', zh: '存檔已匯入', zhs: '存档已导入', ja: 'セーブを読み込みました' },
    bad: { en: 'NOT AN ACHROMA SAVE FILE', zh: '不是 Achroma 的存檔檔案', zhs: '不是 Achroma 的存档文件', ja: 'Achroma のセーブファイルではありません' },
    failed: { en: 'COULD NOT READ OR WRITE THE FILE', zh: '檔案讀寫失敗', zhs: '文件读写失败', ja: 'ファイルを読み書きできませんでした' }
  };

  // 下面的函数由应用调用：文件存好了没有、玩家选的文件读出来的内容、读文件失败
  var A = window.ACH_SAVE = {
    dead: false, open: false, sel: 0, down: -1, t: 0, file: null,
    exported: function (ok) { toast(ok ? 'exported' : 'failed', ok ? C.green : C.red); },
    loaded: function (txt) {
      if (G.state !== 'menu') return; // 选文件期间页面重新载入过、玩家已经开了一局：作罢
      var f = parse(txt); if (!f) return toast('bad', C.red);
      A.file = f; if (!A.open) A.t = 0; A.open = true; A.sel = 0; A.down = -1;
    },
    failed: function () { toast('failed', C.red); }
  };

  function post(msg) { AchromaBridge.postMessage(msg); }
  function toast(k, c) { NET.toast = { txt: tr(S[k]), c: c, t: 0 }; }
  function broken(e) { A.dead = true; A.open = false; try { post('save-broken:' + e); } catch (x) { } }
  function isObj(o) { return !!o && typeof o === 'object' && !Array.isArray(o); }
  function read(k) { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } }
  function write(k, v) { localStorage.setItem(k, JSON.stringify(v)); }
  // a 后面接上 b 里 a 没有的（按 key 比较）
  function union(a, b, key) {
    var out = Array.isArray(a) ? a.slice() : [], seen = out.map(key);
    b.forEach(function (x) { var k = key(x); if (seen.indexOf(k) < 0) { out.push(x); seen.push(k); } });
    return out;
  }

  function exportSave() {
    var data = {};
    KEYS.forEach(function (k) { var v = read(k); if (v != null) data[k] = v; });
    AU.play('menuOk');
    post('export:' + JSON.stringify({ app: 'achroma-save', v: 1, at: new Date().toISOString(), game: GAME_VER, data: data }));
  }

  function importSave() { AU.play('menuOk'); post('import'); }

  function parse(txt) {
    var f; try { f = JSON.parse(txt); } catch (e) { return null; }
    if (!isObj(f) || f.app !== 'achroma-save' || f.v !== 1 || !isObj(f.data)) return null;
    var d = f.data;
    if (d.achroma_set != null && !isObj(d.achroma_set)) return null;
    if (d.achroma_codex != null && !Array.isArray(d.achroma_codex)) return null;
    if (d.achroma_pending != null && !Array.isArray(d.achroma_pending)) return null;
    return f;
  }

  function apply(f) {
    var d = f.data;
    if (d.achroma_set) {
      var cur = read('achroma_set'), set = Object.assign({}, isObj(cur) ? cur : {}, d.achroma_set);
      if (isObj(cur) && cur.cleared) set.cleared = true;
      write('achroma_set', set);
    }
    if (d.achroma_codex) write('achroma_codex', union(read('achroma_codex'), d.achroma_codex, function (x) { return x; }));
    // 游戏自己也只留最近 5 条（net.js 的 pendSet）
    if (d.achroma_pending) write('achroma_pending', union(read('achroma_pending'), d.achroma_pending, function (x) { return x && x.rid; }).slice(-5));
    sessionStorage.setItem(IMPORTED, '1');
    location.reload();
  }

  function when(at) {
    var d = new Date(at); if (isNaN(d)) return '?';
    var p = function (n) { return (n < 10 ? '0' : '') + n; };
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }
  function lay() { return P.lay(tr(S.ask).replace('{at}', when(A.file.at)), 2); }

  function choose(ok) {
    P.done(); A.open = false;
    try { AU.init(); AU.play(ok ? 'menuOk' : 'menu', ok ? 1 : .8); } catch (e) { }
    var f = A.file; A.file = null;
    if (ok) { try { apply(f); } catch (e) { toast('failed', C.red); } }
  }

  // 设置里去掉全屏开关（应用本来就全屏，点了什么都不做）；从主菜单进的设置里，在“语言”前面加导出、导入
  var menuItems0 = menuItems;
  menuItems = function (page) {
    var it = menuItems0(page);
    if (page !== 'settings' || A.dead) return it;
    try {
      it = it.filter(function (i) { return i.act !== toggleFS; });
      if (G.state === 'menu') it.splice(Math.max(0, it.length - 2), 0, { l: function () { return tr(S.export); }, act: exportSave }, { l: function () { return tr(S.import); }, act: importSave });
    } catch (e) { broken(e); }
    return it;
  };

  // 和 update.js 一样挂在 updateMenu / drawTouchUI 上：确认框开着时接管设置页的输入和最上层的绘制
  var updateMenu0 = updateMenu, drawTouchUI0 = drawTouchUI;
  updateMenu = function (rdt) {
    if (A.open && !A.dead) {
      try {
        var i = P.step(A, lay());
        if (i === 0) choose(true); else if (i === 1 || i === 'esc') choose(false);
        return;
      } catch (e) { broken(e); }
    }
    updateMenu0(rdt);
  };
  drawTouchUI = function () {
    if (A.open && !A.dead) { try { P.draw(A, lay(), [tr(S.ok), tr(S.cancel)]); return; } catch (e) { broken(e); } }
    drawTouchUI0();
  };

  // 导入后重新载入的这一次：提示导入成功
  try { if (sessionStorage.getItem(IMPORTED)) { sessionStorage.removeItem(IMPORTED); toast('imported', C.green); } } catch (e) { }
  return true;
})()
