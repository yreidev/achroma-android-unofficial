// 游戏风格的询问框，照搬网页版“全屏 + 横屏”询问框（touch.js 的 FSQ）的尺寸、配色和操作方式。
// 新版本提示（update.js）和导入存档的确认（save.js）共用；状态（选中哪个、按下哪个、弹出多久）由调用方保存。
// 用到的游戏函数缺了就返回 false，用它的脚本据此放弃注入
(function () {
  if (window.ACH_PANEL) return true;
  try {
    [rect, box, text, wrapW, textW, isCJK, NOSTART, lineH, fitText, clamp, pressed];
    [Input, ctx, W, H, FRAME_DT];
  } catch (e) {
    return false;
  }

  // 游戏的 wrapW 对中日文逐字断行，会把“0.9 MB”拆成两行；这里把英文数字串（连同后面的 MB）当成一个整体，其余照它的规则断
  function wrap(s, maxW) {
    if (!isCJK(s)) return wrapW(s, maxW, 2);
    var out = [], line = '';
    (s.match(/[A-Za-z0-9.:-]+(?: MB)?|[\s\S]/g) || []).forEach(function (t) {
      if (line && textW(line + t, 2) > maxW && NOSTART.indexOf(t) < 0) { out.push(line.replace(/ +$/, '')); line = t === ' ' ? '' : t; } else line += t;
    });
    if (line) out.push(line);
    return out;
  }

  function hit(l, x, y) { for (var i = 0; i < l.btns.length; i++) { var b = l.btns[i]; if (x >= b.x - 4 && x <= b.x + b.w + 4 && y >= b.y - 4 && y <= b.y + b.h + 4) return i; } return -1; }

  window.ACH_PANEL = {
    // 和 fsLayout 同一套尺寸：一到两个按钮，正文和按钮之间可以多留 extra 高（下载进度条）
    lay: function (msg, n, extra) {
      var pw = 300, lines = wrap(msg, pw - 28), lh = lineH(2), bh = lines.length * lh, ex = extra || 0,
        ph = 26 + bh + ex + 14 + 24 + 14, px = Math.round(W / 2 - pw / 2), py = Math.round(H / 2 - ph / 2), by = py + 26 + bh + ex + 10, bw = 118, gap = 12,
        btns = n === 1 ? [{ x: W / 2 - bw / 2, y: by, w: bw, h: 24 }]
          : [{ x: W / 2 - gap / 2 - bw, y: by, w: bw, h: 24 }, { x: W / 2 + gap / 2, y: by, w: bw, h: 24 }];
      return { pw: pw, ph: ph, px: px, py: py, lines: lines, lh: lh, extraY: py + 26 + bh + 2, btns: btns };
    },

    // 处理一帧输入，返回点中的按钮序号，按了 Esc 返回 'esc'，什么都没有返回 -1。
    // 刚弹出的这一帧不处理输入：这时按下的键、点下去的位置是冲着下面的菜单去的（比如返回键转成的 Esc）
    step: function (s, l) {
      if (s.t === 0) { s.t += FRAME_DT; Input.clicked = false; return -1; }
      s.t += FRAME_DT;
      if (pressed('KeyA') || pressed('ArrowLeft')) s.sel = 0;
      if (pressed('KeyD') || pressed('ArrowRight')) s.sel = l.btns.length - 1;
      // 和 FSQ 一样按下时选中、松手时还在同一个按钮上才算点，滑出去再松手就作废
      if (Input.clicked) { Input.clicked = false; s.down = hit(l, Input.mx, Input.my); if (s.down >= 0) s.sel = s.down; }
      else if (s.down >= 0 && !Input.down) { var d = s.down; s.down = -1; if (hit(l, Input.mx, Input.my) === d) return d; }
      else if (pressed('Enter') || pressed('Space')) return s.sel;
      else if (pressed('Escape')) return 'esc';
      return -1;
    },

    // 选定后收尾：吞掉这次点击，免得落到下面的菜单上
    done: function () { Input.clicked = false; Input.down = false; },

    // extra(l) 画正文和按钮之间多留的那块
    draw: function (s, l, labels, extra) {
      var k = clamp(s.t / .25, 0, 1), px = l.px, py = l.py, pw = l.pw;
      ctx.globalAlpha = .75 * k; rect(0, 0, W, H, '#000'); ctx.globalAlpha = k;
      rect(px, py, pw, l.ph, '#0b0b0d'); box(px, py, pw, l.ph, null, '#2b2b30'); rect(px + 14, py + 10, 20, 1, '#f0efe8');
      l.lines.forEach(function (t, i) { text(t, W / 2, py + 18 + i * l.lh, '#e8e8ec', 2, 'center'); });
      if (extra) extra(l);
      l.btns.forEach(function (b, i) {
        var on = s.sel === i; if (on) rect(b.x, b.y, b.w, b.h, '#f0efe8'); else box(b.x, b.y, b.w, b.h, null, '#3b3b42');
        text(fitText(labels[i], b.w - 10, 2), b.x + b.w / 2, b.y + 7, on ? '#09090a' : '#8c8c93', 2, 'center');
      });
      ctx.globalAlpha = 1;
    }
  };
  return true;
})()
