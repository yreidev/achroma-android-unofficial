# Achroma Android（非官方）

把网页版 [Achroma](https://game.unsnow.online/ach/) 打包成安卓应用。游戏文件放在 APK 里本地运行，打开即玩、断网也能玩；联网时 linux.do 登录、成绩上传和排行榜和网页版一样可用。

> **非官方打包。** Achroma 由原作者开发，游戏的代码、美术和玩法版权都归原作者所有；本项目与原作者无关，只做安卓外壳。仓库里只有外壳代码，不含游戏文件和图标的像素画，构建时从网页版拉取、生成。

## 怎么做到的

- 游戏文件在 `app/src/main/assets/ach/`，内容与网页版逐字节一致，不做改动。这个目录不入库，构建时没有就自动从网页版拉取（见下文）。
- 用 AndroidX 的 `WebViewAssetLoader` 把这些文件挂在原地址 `https://game.unsnow.online/ach/` 下，页面的源还是线上域名；只有 `api/` 开头的请求真正发到服务器，所以登录 cookie、`localStorage` 存档都和网页版走同一套逻辑。
- linux.do 授权页在应用内打开，授权完跳回来时页面仍从 APK 加载。来回要经过好几次重定向，常常要好几秒，这期间屏幕顶边显示一条加载进度条。
- 应用本身全屏横屏、常亮，画面铺满到挖孔下面（挖孔在横屏短边正中，默认布局不挡按钮，挡到了可以在游戏的“设置 → 按键布局”里拖开、调大小）；网页里的“全屏 + 横屏”询问和全屏按钮会被屏蔽，设置里的全屏开关也去掉了。
- 返回键 = 游戏里的 Esc（对局中暂停、菜单里回上一级），主菜单连按两次退出。切到后台自动暂停并静音。

## 存档导出、导入

从主菜单进“设置”，“语言”上面有“导出存档”和“导入存档”。对局中进的设置里没有这两项：导入后要重新载入页面，会打断对局。

- 导出：弹出系统的“保存到”界面，存成 `achroma-save-日期-时间.json`。里面有设置（含按键布局、已通关标记）、图鉴和还没上传的成绩；进行中的那一局不导出，免得拿存档反复读档重打，排行榜是大家共用的。
- 导入：从文件里选一个存档，游戏里弹出确认框，显示存档的导出时间。确认后设置换成存档里的，已通关标记、图鉴和待上传的成绩两边合并，进度只增不减；然后重新载入，提示“存档已导入”。选的不是存档文件就只提示，什么都不改。
- 换手机、正式版和调试包之间都可以这样转。登录状态不在存档里，换了设备要重新登录。
- 导出、导入是应用注入的脚本 `app/src/main/assets/app/save.js`，文件由应用用系统的文件选择器读写。

### 和网页版互通

网页版自己没有导入导出，但存档就在浏览器的本地存储里，文件格式通用。在电脑浏览器打开网页版，按 F12 打开控制台，粘贴下面的脚本后回车（Chrome 第一次往控制台粘贴时会拦一下，照提示输入那句话允许粘贴）。

导出，浏览器会下载一个存档文件：

```js
(() => {
  const data = {};
  for (const k of ['achroma_set', 'achroma_codex', 'achroma_pending']) { try { const v = JSON.parse(localStorage.getItem(k)); if (v != null) data[k] = v; } catch (e) {} }
  const d = new Date(), p = n => String(n).padStart(2, '0'), a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([JSON.stringify({ app: 'achroma-save', v: 1, at: d.toISOString(), game: GAME_VER, data })], { type: 'application/json' }));
  a.download = `achroma-save-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}.json`;
  a.click();
})();
```

导入，会弹出选择文件的窗口；没弹出来就点页面左上角出现的选择文件按钮：

```js
(() => {
  const read = k => { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } };
  const isObj = o => !!o && typeof o === 'object' && !Array.isArray(o);
  const union = (a, b, key) => { const out = Array.isArray(a) ? a.slice() : [], seen = out.map(key); for (const x of b) if (!seen.includes(key(x))) { out.push(x); seen.push(key(x)); } return out; };
  const input = Object.assign(document.createElement('input'), { type: 'file', accept: '.json,application/json' });
  input.style.cssText = 'position:fixed;top:8px;left:8px;z-index:99;background:#fff;font-size:16px';
  input.onchange = async () => {
    input.remove();
    let f; try { f = JSON.parse(await input.files[0].text()); } catch (e) {}
    const d = isObj(f) && f.data;
    if (!isObj(f) || f.app !== 'achroma-save' || f.v !== 1 || !isObj(d) || (d.achroma_set != null && !isObj(d.achroma_set))
      || (d.achroma_codex != null && !Array.isArray(d.achroma_codex)) || (d.achroma_pending != null && !Array.isArray(d.achroma_pending))) return alert('不是 Achroma 的存档文件');
    if (!confirm(`导入 ${new Date(f.at).toLocaleString()} 导出的存档吗？设置会换成存档里的，图鉴和通关记录会合并，然后重新载入。`)) return;
    if (d.achroma_set) { const cur = read('achroma_set'), set = Object.assign({}, isObj(cur) ? cur : {}, d.achroma_set); if (isObj(cur) && cur.cleared) set.cleared = true; localStorage.setItem('achroma_set', JSON.stringify(set)); }
    if (d.achroma_codex) localStorage.setItem('achroma_codex', JSON.stringify(union(read('achroma_codex'), d.achroma_codex, x => x)));
    if (d.achroma_pending) localStorage.setItem('achroma_pending', JSON.stringify(union(read('achroma_pending'), d.achroma_pending, x => x && x.rid).slice(-5)));
    location.reload();
  };
  document.body.append(input);
  input.click();
})();
```

这两段和应用里的 `save.js` 用同一套文件格式和合并规则，改一边要一起改另一边。

## 更新游戏内容

### 应用内更新

网页版更新后，已装的应用自己会跟上，不用重新发 APK：

- 每次打开应用、或距上次检查超过 6 小时回到前台时，后台看一眼网页版 `index.html` 脚本链接上的 `?v=` 版本号（只下这一个文件；注释掉的引用不算）。
- 有新版本时，回到主菜单首页会弹出询问框，样式和操作照搬游戏原来的“全屏 + 横屏”询问框：显示新旧版本号和估算大小，选“更新”或“稍后”。对局中不会弹，等回到主菜单再问；弹出那一刻正好按下的键和触摸不算数。
- 选“更新”后框里显示下载进度（第几个文件 / 共几个），可以取消，失败可以重试；整套文件下到 `files/game/` 下的临时目录，全部成功才生效。下好后在主菜单首页换上并自动重新载入，提示“已更新到 vN”；万一下载途中页面重新载入过、玩家已经开了一局，就等回到主菜单首页再换，没换就退出了的话下次打开直接用新版本。存档和设置在 `localStorage`，不受影响。
- 选“稍后”、按返回键或下载中点“取消”：这次不再问，下次检查（重新打开应用，或回到前台时距上次检查超过 6 小时）再问。点更新时线上已经撤回了新版本，询问框直接收起。
- 下载来的版本第一次启动时，检查页面加载时有没有文件加载失败或脚本报错；有就退回原来的版本并提示“新版本 vN 启动失败”，这个版本以后不再提示。
- 询问框是应用注入的脚本 `app/src/main/assets/app/update.js`（框的样式和操作在和存档导入共用的 `panel.js` 里），借用游戏自己的绘制函数和输入；玩家的选择经 `AchromaBridge`（androidx.webkit 的 `addWebMessageListener`，只对游戏域名开放）传回应用。游戏改版导致注入不上或运行出错时，改用系统对话框，同样等到主菜单首页才弹（认不出游戏状态时直接弹），仍然能更新。
- 安装比已下载版本更新的 APK 时，旧的下载会被清掉，改用 APK 自带的文件。

### 重新打包

改了应用本身，或想让新安装的人直接拿到最新游戏时，重新打包：

```sh
./scripts/sync-web.sh                 # 拉取网页版最新文件，覆盖 assets/ach/
# 改 app/build.gradle.kts 里的 appVersion（如 1.0.0 → 1.0.1），versionCode 自动变大
./gradlew assembleRelease
```

`sync-web.sh` 和应用内更新按同一套规则找 `index.html` 引用的文件（注释掉的不算），一个都没找到或任何一个下载失败就整体放弃，不留半套文件。

## 构建

需要 JDK 17 以上、Android SDK（compileSdk 37）、`bash` 和 `curl`。刚克隆下来时没有游戏文件，第一次构建会先自动跑 `scripts/sync-web.sh` 从网页版拉取，需要联网；之后不再自动拉，要更新就手动跑一次这个脚本。启动图标的前景层和单色层也在构建时由拉下来的 `favicon.svg` 生成（`app/build.gradle.kts` 里的 `LauncherIcon`），仓库里只有引用它们的 `ic_launcher.xml`。

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

`JAVA_HOME` 无效时，把它指向任意 JDK 17 以上即可，例如 Gradle 自动下载到 `~/.gradle/jdks/` 下的那份。

发版签名读根目录的 `keystore.properties`（不入库），格式：

```properties
storeFile=/绝对路径/achroma-release.jks
storePassword=…
keyAlias=achroma
keyPassword=…
```

没有这个文件时 release 包不签名。签名密钥丢了就无法覆盖升级已装的应用，务必备份。

`./gradlew assembleDebug` 打出的调试包包名带 `.debug`、名字叫 Achroma Debug，能和正式版同时装，测试时不碰正式版的存档和登录；调试包可以用 Chrome 的 `chrome://inspect` 调试页面。
