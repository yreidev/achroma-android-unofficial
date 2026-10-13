// 应用内更新提示：画在游戏画布里，照搬网页版“全屏 + 横屏”询问框（touch.js 的 FSQ）的样式和操作方式，
// 只在主菜单首页弹出，不打断对局；新版本下好后也等回到主菜单首页才换上。由 MainActivity 在页面加载完后注入，
// 玩家的选择经 AchromaBridge 发回应用。
// 用到的游戏函数缺了就返回 false，运行中出错就停用并发 broken，应用都会改用系统对话框。
(function () {
  if (window.ACH_UPD) return !window.ACH_UPD.dead;
  try {
    if (typeof AchromaBridge === 'undefined') return false;
    [updateMenu, drawTouchUI, rect, box, text, wrapW, textW, isCJK, NOSTART, lineH, fitText, clamp, tr, pressed];
    [G, MENU, Input, AU, NET, C, ctx, W, H, FRAME_DT];
  } catch (e) {
    return false;
  }

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

  // 游戏的 wrapW 对中日文逐字断行，会把“0.9 MB”拆成两行；这里把英文数字串（连同后面的 MB）当成一个整体，其余照它的规则断
  function wrap(s, maxW) {
    if (!isCJK(s)) return wrapW(s, maxW, 2);
    var out = [], line = '';
    (s.match(/[A-Za-z0-9.]+(?: MB)?|[\s\S]/g) || []).forEach(function (t) {
      if (line && textW(line + t, 2) > maxW && NOSTART.indexOf(t) < 0) { out.push(line.replace(/ +$/, '')); line = t === ' ' ? '' : t; } else line += t;
    });
    if (line) out.push(line);
    return out;
  }

  // 和 fsLayout 同一套尺寸；下载中在文字和按钮之间多一行进度条
  function lay() {
    var pw = 300, lines = wrap(say(U.mode), pw - 28), lh = lineH(2), bh = lines.length * lh, barH = U.mode === 'busy' ? 16 : 0,
      ph = 26 + bh + barH + 14 + 24 + 14, px = Math.round(W / 2 - pw / 2), py = Math.round(H / 2 - ph / 2), by = py + 26 + bh + barH + 10, bw = 118, gap = 12,
      keys = BTNS[U.mode], btns = keys.length === 1 ? [{ x: W / 2 - bw / 2, y: by, w: bw, h: 24 }]
        : [{ x: W / 2 - gap / 2 - bw, y: by, w: bw, h: 24 }, { x: W / 2 + gap / 2, y: by, w: bw, h: 24 }];
    return { pw: pw, ph: ph, px: px, py: py, lines: lines, lh: lh, barY: py + 26 + bh + 2, keys: keys, btns: btns };
  }
  function hit(l, x, y) { for (var i = 0; i < l.btns.length; i++) { var b = l.btns[i]; if (x >= b.x - 4 && x <= b.x + b.w + 4 && y >= b.y - 4 && y <= b.y + b.h + 4) return i; } return -1; }

  function choose(key) {
    Input.clicked = false; Input.down = false;
    try { AU.init(); AU.play(key === 'later' || key === 'cancel' ? 'menu' : 'menuOk', key === 'later' || key === 'cancel' ? .8 : 1); } catch (e) { }
    if (key === 'update' || key === 'retry') { U.done = 0; U.total = 0; show('busy'); post('update'); }
    else { close(); post(key); }
  }

  function step() {
    // 刚弹出的这一帧不处理输入：这时按下的键、点下去的位置是冲着主菜单去的（比如返回键转成的 Esc）
    if (U.t === 0) { U.t += FRAME_DT; Input.clicked = false; return; }
    U.t += FRAME_DT;
    var l = lay();
    if (pressed('KeyA') || pressed('ArrowLeft')) U.sel = 0;
    if (pressed('KeyD') || pressed('ArrowRight')) U.sel = l.btns.length - 1;
    // 和 FSQ 一样按下时选中、松手时还在同一个按钮上才算点，滑出去再松手就作废
    if (Input.clicked) { Input.clicked = false; U.down = hit(l, Input.mx, Input.my); if (U.down >= 0) U.sel = U.down; }
    else if (U.down >= 0 && !Input.down) { var d = U.down; U.down = -1; if (hit(l, Input.mx, Input.my) === d) choose(l.keys[d]); }
    else if (pressed('Enter') || pressed('Space')) choose(l.keys[U.sel]);
    else if (pressed('Escape') && U.mode !== 'busy') choose('later'); // 下载中按返回不打断，要停就点取消
  }

  function draw() {
    var k = clamp(U.t / .25, 0, 1), l = lay(), px = l.px, py = l.py, pw = l.pw;
    ctx.globalAlpha = .75 * k; rect(0, 0, W, H, '#000'); ctx.globalAlpha = k;
    rect(px, py, pw, l.ph, '#0b0b0d'); box(px, py, pw, l.ph, null, '#2b2b30'); rect(px + 14, py + 10, 20, 1, '#f0efe8');
    l.lines.forEach(function (s, i) { text(s, W / 2, py + 18 + i * l.lh, '#e8e8ec', 2, 'center'); });
    if (U.mode === 'busy') {
      var bx = px + 24, bw = pw - 48 - 44, frac = U.total ? U.done / U.total : 0;
      box(bx, l.barY, bw, 6, '#09090a', '#3b3b42'); if (frac > 0) rect(bx + 1, l.barY + 1, Math.round((bw - 2) * frac), 4, '#f0efe8');
      if (U.total) text(U.done + ' / ' + U.total, px + pw - 24, l.barY + 1, '#8c8c93', 1, 'right');
    }
    l.btns.forEach(function (b, i) {
      var on = U.sel === i; if (on) rect(b.x, b.y, b.w, b.h, '#f0efe8'); else box(b.x, b.y, b.w, b.h, null, '#3b3b42');
      text(fitText(tr(S[l.keys[i]]), b.w - 10, 2), b.x + b.w / 2, b.y + 7, on ? '#09090a' : '#8c8c93', 2, 'center');
    });
    ctx.globalAlpha = 1;
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
