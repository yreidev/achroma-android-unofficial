// 应用内更新提示：画在游戏画布里，照搬网页版“全屏 + 横屏”询问框（touch.js 的 FSQ）的样式和操作方式，
// 只在主菜单首页弹出，不打断对局；新版本下好后也等回到主菜单首页才换上。由 MainActivity 在页面加载完后注入，
// 玩家的选择经 AchromaBridge 发回应用。
// 询问框的样式和操作在 panel.js（应用把它拼在本文件前面一起注入）。
// 用到的游戏函数缺了就返回 false，运行中出错就停用并发 broken，应用都会改用系统对话框。
(function () {
  if (window.ACH_UPD) return !window.ACH_UPD.dead;
  try {
    if (typeof AchromaBridge === 'undefined' || !window.ACH_PANEL) return false;
    [updateMenu, drawTouchUI, rect, box, text, tr];
    [G, MENU, AU, NET, C, W];
  } catch (e) {
    return false;
  }
  var P = ACH_PANEL;

  var S = {
    ask: { en: 'NEW VERSION V{n} IS OUT (YOU HAVE V{c}, ABOUT {mb}MB). UPDATE NOW?', zh: '發現新版本 v{n}（目前 v{c}，約 {mb} MB），現在更新嗎？', zhs: '发现新版本 v{n}（当前 v{c}，约 {mb} MB），现在更新吗？', ja: '新しいバージョン v{n} があります（現在 v{c}・約 {mb} MB）。今すぐ更新しますか？' },
    busy: { en: 'DOWNLOADING V{n}...', zh: '正在下載新版本 v{n}…', zhs: '正在下载新版本 v{n}…', ja: 'v{n} をダウンロード中…' },
    fail: { en: 'DOWNLOAD FAILED. CHECK YOUR CONNECTION AND TRY AGAIN.', zh: '下載失敗，請檢查網路後重試。', zhs: '下载失败，请检查网络后重试。', ja: 'ダウンロードに失敗しました。通信環境を確認してもう一度お試しください。' },
    update: { en: 'UPDATE', zh: '更新', zhs: '更新', ja: '更新する' },
    later: { en: 'LATER', zh: '稍後', zhs: '稍后', ja: 'あとで' },
    cancel: { en: 'CANCEL', zh: '取消', zhs: '取消', ja: 'キャンセル' },
    retry: { en: 'RETRY', zh: '重試', zhs: '重试', ja: '再試行' },
    updated: { en: 'UPDATED TO V{n}', zh: '已更新到 v{n}', zhs: '已更新到 v{n}', ja: 'v{n} に更新しました' }
  };
  // 每种状态的按钮；第一个是主操作，默认选中
  var BTNS = { ask: ['update', 'later'], busy: ['cancel'], fail: ['retry', 'later'] };

  // 下面的函数由应用调用。要弹的面板先记在 want 里，到主菜单首页才弹；面板已经开着就直接换内容
  var U = window.ACH_UPD = {
    open: false, want: '', apply: false, dead: false, mode: 'ask', sel: 0, down: -1, t: 0, cur: 0, next: 0, mb: '', done: 0, total: 0,
    offer: function (cur, next, mb) { U.cur = cur; U.next = next; U.mb = mb; if (!U.open) U.want = 'ask'; },
    // 页面重新载入后接着显示下载进度
    busy: function (next, done, total) { U.next = next; U.progress(done, total); want('busy'); },
    progress: function (done, total) { U.done = done; U.total = total; },
    failed: function () { want('fail'); },
    // 新版本下好了：到主菜单首页时告诉应用换上
    ready: function () { U.apply = true; },
    idle: function () { U.want = ''; close(); },
    updated: function (v) { NET.toast = { txt: say('updated', v), c: C.green, t: 0 }; }
  };

  function say(k, n) { return tr(S[k]).replace('{n}', n != null ? n : U.next).replace('{c}', U.cur).replace('{mb}', U.mb); }
  function post(msg) { AchromaBridge.postMessage(msg); }
  function show(mode) { if (!U.open) U.t = 0; U.open = true; U.want = ''; U.mode = mode; U.sel = 0; U.down = -1; }
  function want(mode) { if (!U.open) U.want = mode; else if (U.mode !== mode) show(mode); }
  function close() { U.open = false; }
  function broken(e) { U.dead = true; U.open = false; try { post('broken:' + e); } catch (x) { } }

  // 下载中在文字和按钮之间多一行进度条
  function lay() { return P.lay(say(U.mode), BTNS[U.mode].length, U.mode === 'busy' ? 16 : 0); }

  function choose(key) {
    P.done();
    try { AU.init(); AU.play(key === 'later' || key === 'cancel' ? 'menu' : 'menuOk', key === 'later' || key === 'cancel' ? .8 : 1); } catch (e) { }
    if (key === 'update' || key === 'retry') { U.done = 0; U.total = 0; show('busy'); post('update'); }
    else { close(); post(key); }
  }

  function step() {
    var i = P.step(U, lay());
    if (i === 'esc') { if (U.mode !== 'busy') choose('later'); } // 下载中按返回不打断，要停就点取消
    else if (i >= 0) choose(BTNS[U.mode][i]);
  }

  function draw() {
    P.draw(U, lay(), BTNS[U.mode].map(function (k) { return tr(S[k]); }), U.mode === 'busy' ? function (l) {
      var bx = l.px + 24, bw = l.pw - 48 - 44, frac = U.total ? U.done / U.total : 0;
      box(bx, l.extraY, bw, 6, '#09090a', '#3b3b42'); if (frac > 0) rect(bx + 1, l.extraY + 1, Math.round((bw - 2) * frac), 4, '#f0efe8');
      if (U.total) text(U.done + ' / ' + U.total, l.px + l.pw - 24, l.extraY + 1, '#8c8c93', 1, 'right');
    } : null);
  }

  // 和 FSQ 一样挂在 updateMenu / drawTouchUI 上：面板开着时接管主菜单的输入和最上层的绘制
  var atMain = function () { return G.state === 'menu' && MENU.page === 'main' && !MENU.stack.length; };
  var updateMenu0 = updateMenu, drawTouchUI0 = drawTouchUI;
  updateMenu = function (rdt) {
    if (!U.dead) {
      try {
        if (atMain()) {
          if (U.apply) { U.apply = false; close(); post('apply'); }
          else if (!U.open && U.want) show(U.want);
        }
        if (U.open) { step(); return; }
      } catch (e) { broken(e); }
    }
    updateMenu0(rdt);
  };
  drawTouchUI = function () {
    if (U.open && !U.dead) { try { draw(); return; } catch (e) { broken(e); } }
    drawTouchUI0();
  };
  return true;
})()
