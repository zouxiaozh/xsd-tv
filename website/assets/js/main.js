/* 学时兑官网交互：主题、导航、滚动动效、截图切换、安装方式切换、复制命令 */
(function () {
  'use strict';
  var $ = function (s, ctx) { return (ctx || document).querySelector(s); };
  var $$ = function (s, ctx) { return Array.prototype.slice.call((ctx || document).querySelectorAll(s)); };

  /* ---------- 主题 ---------- */
  var root = document.documentElement;
  var themeBtn = $('#themeToggle');
  var saved = null;
  try { saved = localStorage.getItem('xsd-theme'); } catch (e) {}
  if (saved === 'light' || saved === 'dark') root.setAttribute('data-theme', saved);

  function syncThemeIcon() {
    var isDark = root.getAttribute('data-theme') !== 'light';
    var use = $('use', themeBtn);
    use.setAttribute('href', isDark ? '#i-sun' : '#i-moon');
    themeBtn.setAttribute('aria-label', isDark ? '切换到浅色主题' : '切换到深色主题');
    themeBtn.setAttribute('title', isDark ? '切换到浅色主题' : '切换到深色主题');
    var meta = $('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', isDark ? '#070c16' : '#f6f9fd');
  }
  syncThemeIcon();
  themeBtn.addEventListener('click', function () {
    var next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    root.setAttribute('data-theme', next);
    try { localStorage.setItem('xsd-theme', next); } catch (e) {}
    syncThemeIcon();
  });

  /* ---------- 移动端菜单（点击外部 / Esc / 滚动 / 切回桌面都会收起） ---------- */
  var menuBtn = $('#menuBtn');
  var navLinks = $('#navLinks');
  function closeMenu() {
    navLinks.classList.remove('open');
    menuBtn.setAttribute('aria-expanded', 'false');
  }
  menuBtn.addEventListener('click', function (e) {
    e.stopPropagation();
    var open = navLinks.classList.toggle('open');
    menuBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
  });
  $$('a', navLinks).forEach(function (a) { a.addEventListener('click', closeMenu); });
  document.addEventListener('click', function (e) {
    if (navLinks.classList.contains('open') && !navLinks.contains(e.target) && !menuBtn.contains(e.target)) closeMenu();
  });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeMenu(); });
  window.addEventListener('resize', function () { if (window.innerWidth > 980) closeMenu(); });
  window.addEventListener('scroll', function () { if (navLinks.classList.contains('open')) closeMenu(); }, { passive: true });

  /* ---------- 滚动进度 / 导航 / 回到顶部 ---------- */
  var nav = $('#nav');
  var progress = $('#progress');
  var toTop = $('#toTop');
  var sectionIds = ['intro', 'how', 'features', 'screens', 'guard', 'install', 'adb', 'faq', 'download'];
  var linkMap = {};
  $$('.nav-links a').forEach(function (a) {
    var href = a.getAttribute('href') || '';
    // 只把页内锚点纳入滚动高亮。指向其它页面的链接（如「题库下载」）保持
    // 自身状态，否则当前页写死的 active 会被 onScroll 清掉。
    if (href.charAt(0) === '#') linkMap[href.slice(1)] = a;
  });

  function onScroll() {
    var y = window.scrollY || document.documentElement.scrollTop;
    var max = document.documentElement.scrollHeight - window.innerHeight;
    progress.style.width = (max > 0 ? Math.min(100, (y / max) * 100) : 0) + '%';
    nav.classList.toggle('scrolled', y > 12);
    toTop.classList.toggle('show', y > 600);

    var current = '';
    sectionIds.forEach(function (id) {
      var el = document.getElementById(id);
      if (el && el.getBoundingClientRect().top <= 150) current = id;
    });
    Object.keys(linkMap).forEach(function (id) {
      linkMap[id].classList.toggle('active', id === current);
    });
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll);
  onScroll();

  /* ---------- 滚动出现 ---------- */
  var reveals = $$('.reveal');
  if ('IntersectionObserver' in window) {
    var ro = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          en.target.classList.add('visible');
          ro.unobserve(en.target);
        }
      });
    }, { threshold: .12, rootMargin: '0px 0px -40px 0px' });
    reveals.forEach(function (el) { ro.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add('visible'); });
  }

  /* ---------- 数字滚动 ---------- */
  var counters = $$('[data-count]');
  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var counted = false;
  function runCounters() {
    if (counted) return;
    counted = true;
    counters.forEach(function (el) {
      var target = parseInt(el.getAttribute('data-count'), 10) || 0;
      if (reduceMotion) { el.textContent = target.toLocaleString('zh-CN'); return; }
      var dur = 1100;
      var start = null;
      function tick(ts) {
        if (!start) start = ts;
        var p = Math.min(1, (ts - start) / dur);
        var eased = 1 - Math.pow(1 - p, 3);
        el.textContent = Math.round(target * eased).toLocaleString('zh-CN');
        if (p < 1) requestAnimationFrame(tick);
        else el.textContent = target.toLocaleString('zh-CN');
      }
      requestAnimationFrame(tick);
    });
  }
  var statsEl = $('#stats');
  if ('IntersectionObserver' in window && statsEl) {
    var co = new IntersectionObserver(function (entries) {
      for (var i = 0; i < entries.length; i++) {
        if (entries[i].isIntersecting) { runCounters(); co.disconnect(); break; }
      }
    }, { threshold: .2 });
    co.observe(statsEl);
  } else {
    window.addEventListener('scroll', runCounters, { passive: true });
    runCounters();
  }

  /* ---------- 界面截图切换 ---------- */
  var screens = {
    home: {
      bar: '首页', img: 'assets/img/screens/home.png', num: '01',
      alt: '学时兑首页：守护运行中、距下次出题实时倒数、五个快捷按钮',
      title: '首页 · 一眼看清约定',
      desc: '守护状态、出题频率、每轮题数、答错停留、距下次出题的秒数都摆在首页；底部五个大按钮用遥控器左右键就能选中。打开、后台到被杀后的空白期都照常扣时。',
      tags: ['守护运行中', '距下次出题实时倒数', '当前方案 · 严谨守护']
    },
    quiz: {
      bar: '答题浮层 · 中文注音', img: 'assets/img/screens/quiz-pinyin.png', num: '02',
      alt: '注音答题界面：题干汉字上方标注带声调的拼音，四个大字选项',
      title: '注音答题 · 低年级也读得懂',
      desc: '开启中文注音后，题干和选项里的汉字上方都会标出带声调的拼音；第 1 次答错看解析并重新排列选项，第 2 次再答错就记入错题库并自动换题。',
      tags: ['中文注音', '方向键 / 数字键 1-4', '答对才能继续']
    },
    tv: {
      bar: '小米盒子 4 · 实机', img: 'assets/img/screens/quiz-tv.png', num: '03',
      alt: '小米盒子 4 实机答题界面：题干居中，四个选项大字完整显示',
      title: '电视实机 · 大字不截断',
      desc: '在 1920×1080 的小米盒子 4（Android 6.0.1）上完整跑通：浮层全屏、题干居中、四个选项排布宽松，内置离线题库，遥控器和数字键都能作答。',
      tags: ['Android TV', 'TV 过扫描适配', '遥控器友好']
    },
    settings: {
      bar: '家长设置', img: 'assets/img/screens/settings.png', num: '04',
      alt: '家长设置页：中文注音、出题频率、每轮题数、答错停留、每题可得时长',
      title: '家长设置 · 参数一处管完',
      desc: '默认 10 分钟出题、每轮 1 题、每题可得 1 分钟、答错停留 30 秒，中文注音默认关闭。严谨守护与自主可控两套方案分区显示，改完即时生效。',
      tags: ['PIN 保护', '两套方案', '默认参数一目了然']
    },
    qr: {
      bar: '扫码管理', img: 'assets/img/screens/qr-manage.png', num: '05',
      alt: '扫码管理页：二维码与局域网管理地址、返回家长设置按钮',
      title: '扫码管理 · 手机即控制台',
      desc: '电视上显示二维码与局域网地址，手机同 Wi-Fi 扫码即可调整参数与年级学期、开关中文注音、切换答题方案、立即出题、锁屏 / 解除锁屏、暂停或恢复播放、重置观看时间。',
      tags: ['一次性随机令牌', '同 Wi-Fi', '退出应用即失效']
    },
    lock: {
      bar: '远程锁屏', img: 'assets/img/screens/lock.png', num: '06',
      alt: '远程锁屏界面：家长已锁屏，家长已在手机上锁屏 · 休息一下吧',
      title: '远程锁屏 · 温柔地喊停',
      desc: '在手机控制台点一下「锁屏」，电视立即出现全屏提示「家长已锁屏」，并附上「家长已在手机上锁屏 · 休息一下吧」；解锁同样在手机上完成，不用抢遥控器。',
      tags: ['全屏提示', '一键解锁', '家庭局域网']
    }
  };

  var screenImg = $('#screenImg');
  var screenBar = $('#screenBar');
  var screenNum = $('#screenNum');
  var screenTitle = $('#screenTitle');
  var screenDesc = $('#screenDesc');
  var screenTags = $('#screenTags');
  $$('.screen-tabs .tab').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var key = btn.getAttribute('data-screen');
      var d = screens[key];
      if (!d) return;
      $$('.screen-tabs .tab').forEach(function (b) {
        var on = b === btn;
        b.classList.toggle('active', on);
        b.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      screenImg.src = d.img;
      screenImg.alt = d.alt;
      screenImg.style.animation = 'none';
      void screenImg.offsetWidth;
      screenImg.style.animation = '';
      screenBar.textContent = d.bar;
      screenNum.textContent = d.num;
      screenTitle.textContent = d.title;
      screenDesc.textContent = d.desc;
      screenTags.textContent = '';
      d.tags.forEach(function (t) {
        var s = document.createElement('span');
        s.textContent = t;
        screenTags.appendChild(s);
      });
    });
  });

  /* ---------- 安装方式切换 ---------- */
  $$('.method-tabs .tab').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var key = btn.getAttribute('data-method');
      $$('.method-tabs .tab').forEach(function (b) {
        var on = b === btn;
        b.classList.toggle('active', on);
        b.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      $$('[data-method-panel]').forEach(function (p) {
        p.classList.toggle('active', p.getAttribute('data-method-panel') === key);
      });
    });
  });

  /* ---------- 复制到剪贴板 ---------- */
  function fallback(text, done) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); done(); } catch (e) {}
    document.body.removeChild(ta);
  }
  function copyText(text, label) {
    var old = label ? label.textContent : '';
    function done() {
      if (!label) return;
      label.textContent = '已复制';
      setTimeout(function () { label.textContent = old; }, 1600);
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { fallback(text, done); });
    } else {
      fallback(text, done);
    }
  }
  $$('[data-copy]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      copyText(btn.getAttribute('data-copy') || '', $('span', btn));
    });
  });
  $$('[data-copy-from]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var code = $((btn.getAttribute('data-copy-from') || '') + ' code');
      copyText(code ? code.textContent : '', $('span', btn));
    });
  });

  /* ---------- 给豆包 / WorkBuddy 的安装提示词 ---------- */
  var SITE_URL = 'https://xsd.qx47.com/';
  var APK_URL = 'https://xsd.qx47.com/download/XsdAnser-v30-ui.apk';
  var commonSteps = [
    '【基本信息】',
    '- App：学时兑（XsdAnser v2.02），当前包名 com.xsdanser.tv，Android 5.0+；适配小米、TCL、创维、索尼等主流智能电视，也能装在电视盒子与投影仪上，已在小米盒子 4（Android 6.0.1）实机验证。',
    '- 官网：' + SITE_URL + ' ；安装包直链：' + APK_URL + '（约 2,713,119 字节 / 约 2.6 MB）。若直链失效，请到官网首页点「下载 v2.02」。',
    '- 电脑与盒子必须在同一局域网。盒子的 IP 我不知道，请先指导我在「设置 → 网络」里查看，或帮我扫描后让我确认；把下面所有【电视IP】替换成实际 IP 再执行。',
    '',
    '【需要开通哪些权限 / 开关】',
    '1. 开发者选项 + USB / 网络 ADB 调试（盒子端，必须我本人操作）：设置 → 关于本机 → 连点版本号 7 次 → 开发者选项 → 打开 USB 调试 / 网络 ADB 调试。做到这一步请停下来等我确认。',
    '2. 显示悬浮窗 SYSTEM_ALERT_WINDOW（App 端，最关键）：不给权限就不会弹出答题浮层，也无法远程锁屏。装完用 adb shell appops set com.xsdanser.tv SYSTEM_ALERT_WINDOW allow 授权，并提醒我在 设置 → 应用 → 学时兑 → 显示悬浮窗 里确认已允许。',
    '3. 允许未知来源安装：自签名应用，系统弹窗选「允许 / 仍然安装」；adb install 通常不需要手动开。',
    '4. 建议开启自启动 / 后台运行 / 电池无限制，保证守护服务长期计时；请按我的机型给出设置路径。',
    '5. 不需要：外部存储、通讯录、定位、相机、麦克风、账号登录。题库随包内置，作答记录不上传。',
    '',
    '【执行步骤】',
    '1) 检查 adb version；本机没有就准备 Android platform-tools，或使用项目自带的 adb-tools/platform-tools/adb.exe。',
    '2) 下载安装包到当前目录并核对大小约 2.6 MB：',
    '   Windows PowerShell：Invoke-WebRequest -Uri "' + APK_URL + '" -OutFile "XsdAnser-v30-ui.apk"',
    '   macOS / Linux：curl -L -o XsdAnser-v30-ui.apk ' + APK_URL,
    '3) 连接：adb connect 【电视IP】:5555，然后 adb devices 确认状态是 device。',
    '   - connect 不要带 -s；connect 与后续 adb 操作请放在同一条命令里，避免 adb daemon 重启导致 offline。',
    '   - offline 时依次执行 adb kill-server、adb start-server，再重新 connect；unauthorized 时提示我在电视上勾选「一律允许」。',
    '   - 5555 才是 ADB 端口，小米盒子的 6095 是「小米电视助手」，不要连错。',
    '4) 安装前先确认设备上是否已装当前版本（执行）：',
    '   adb shell pm list packages com.xsdanser.tv',
    '   - 若已安装同包名（同签名升级），可直接 -r -d 覆盖，家长设置会保留。',
    '   - 若报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，说明签名不一致，先 adb uninstall com.xsdanser.tv 再装（会清除本地设置与错题，需重开悬浮窗权限）。',
    '   - 全新设备跳过检查，直接执行：adb install -r -d XsdAnser-v30-ui.apk。',
    '5) 授权并核对：',
    '   adb shell appops set com.xsdanser.tv SYSTEM_ALERT_WINDOW allow',
    '   adb shell appops get com.xsdanser.tv SYSTEM_ALERT_WINDOW   （应返回 allow）',
    '   adb shell pm path com.xsdanser.tv',
    '   adb shell dumpsys package com.xsdanser.tv | findstr versionName   （Windows；macOS / Linux 用 grep，应为 2.02）',
    '6) 启动并验收：adb shell monkey -p com.xsdanser.tv -c android.intent.category.LAUNCHER 1',
    '   - 请我在电视首页点「立即出题」，应出现全屏答题浮层；若提示「题库暂不可用」，等 5~10 秒再点一次（首次启动会在后台复制约 5 MB 题库）。',
    '   - 不要用 am startservice 触发出题：守护服务未导出会被系统拒绝，必须走界面点击。',
    '7) 最后告诉我：安装结果、versionName / versionCode、悬浮窗授权状态、守护是否运行中，以及我还要做的两件事——在 App 内设置 4–8 位家长密码；手机连同一 Wi-Fi 扫「扫码管理」二维码打开控制台。',
    '',
    '【边界】',
    '- 不要修改、反编译或重新签名 APK；只连接局域网里的这台电视，不要连公网 ADB 地址。',
    '- 卸载、清空数据等不可逆操作必须先征得我同意；本次包名变更场景下尤其要先说明数据丢失。任何一步失败先诊断原因，不要盲目重试或跳步。'
  ];
  function buildPrompt(head, tail) {
    return head.concat([''], commonSteps, [''], [tail]).join('\n');
  }
  var prompts = {
    workbuddy: buildPrompt([
      '【任务】请用 ADB 帮我把「学时兑」安装到家里的 Android 电视 / 盒子上，并完成首次运行所需的授权与验收。',
      '【执行方式】你可以直接在这台电脑上执行终端命令、读写文件，请把每一步的命令和输出原样展示给我；涉及我本人在电视上操作的步骤，请先停下来等我确认。'
    ], '【输出】结束时用简短清单汇总每一步的结果与是否全部通过；若有步骤没做成，给出下一步建议。'),
    doubao: buildPrompt([
      '【任务】请指导我用 ADB 把「学时兑」安装到家里的 Android 电视 / 盒子上，并完成授权与验收。',
      '【执行方式】你不能访问我的电脑，请把每一步要做的事和完整可复制的命令一次给我一条（优先给 Windows PowerShell 版，必要时附 macOS / Linux 版）；等我执行并把输出贴回后，再给下一步，不要假设命令已经跑过。'
    ], '【输出】最后用清单汇总我已完成 / 未完成的步骤，并提醒我还差什么。')
  };

  var promptText = $('#promptText');
  var currentPrompt = 'workbuddy';
  function setPrompt(key) {
    currentPrompt = key;
    if (promptText) promptText.textContent = prompts[key] || '';
    var box = $('#promptBox');
    if (box) box.scrollTop = 0;
  }
  setPrompt('workbuddy');
  $$('.prompt-tabs .tab').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var key = btn.getAttribute('data-prompt');
      $$('.prompt-tabs .tab').forEach(function (b) {
        var on = b === btn;
        b.classList.toggle('active', on);
        b.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      setPrompt(key);
    });
  });
  var copyPromptBtn = $('#copyPrompt');
  if (copyPromptBtn) {
    copyPromptBtn.addEventListener('click', function () {
      copyText(prompts[currentPrompt], $('span', copyPromptBtn));
    });
  }
  var copyUrlBtn = $('#copyUrl');
  if (copyUrlBtn) {
    copyUrlBtn.addEventListener('click', function () {
      copyText(APK_URL, $('span', copyUrlBtn));
    });
  }

  /* ---------- 窄屏表格：滑动后淡出「可左右滑动」提示 ---------- */
  var bankScroll = $('.bank-scroll');
  if (bankScroll) {
    var bankWrap = $('.bank-table-wrap');
    function syncBankHint() {
      if (bankWrap) bankWrap.classList.toggle('scrolled', bankScroll.scrollLeft > 8);
    }
    bankScroll.addEventListener('scroll', syncBankHint, { passive: true });
    window.addEventListener('resize', syncBankHint);
    syncBankHint();
  }

  /* ---------- 年份 ---------- */
  var y = $('#year');
  if (y) y.textContent = new Date().getFullYear();
})();
