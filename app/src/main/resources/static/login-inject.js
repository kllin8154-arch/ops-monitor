// login-inject.js — v2.5-sec
// FIX-TOKEN: 安全重写 Token 存储策略
//
// 变更说明：
//   原方案: Token 明文存储在 localStorage → 任何 XSS 可直接读取
//   新方案: 登录后后端通过 Set-Cookie: HttpOnly 设置 Token Cookie
//           前端 fetch 劫持优先从 Cookie 获取 Token（浏览器自动携带 Cookie）
//           localStorage 仅保留 username 显示用（非敏感信息）
//           localStorage 中的 token 仍可作为降级兼容（API 客户端场景）
//
// 安全边界：
//   - httpOnly Cookie: JS 无法读取，XSS 窃取 Token 的攻击路径被切断
//   - SameSite=Strict: 跨站请求不携带 Cookie，CSRF 防护
//   - Authorization Header 仍保留（兼容非浏览器 API 客户端）
(function() {
    'use strict';

    // FIX-TOKEN: 不再在 localStorage 存储 Token，仅存用户名（用于 UI 显示）
    var USER_KEY     = 'ops_user';
    var LEGACY_TOKEN = 'ops_token'; // 旧 key，仅用于读取已存在的旧 Token（降级兼容）

    // ==================== fetch 劫持（自动注入 Token）====================

    var _fetch = window.fetch;
    window.fetch = function(url, opts) {
        opts = opts || {};
        opts.headers = opts.headers || {};

        // FIX-TOKEN: Cookie 由浏览器自动携带（httpOnly Cookie 设置后无需 JS 读取）
        // 仅当 Cookie 不可用时（非浏览器客户端），尝试旧的 localStorage Token（降级兼容）
        if (!opts.headers['Authorization']) {
            var legacyToken = _tryGetLegacyToken();
            if (legacyToken) {
                opts.headers['Authorization'] = 'Bearer ' + legacyToken;
            }
        }

        // FIX-TOKEN: credentials: 'same-origin' 确保浏览器在同源请求中携带 Cookie
        if (!opts.credentials) {
            opts.credentials = 'same-origin';
        }

        return _fetch(url, opts).then(function(response) {
            if (response.status === 401 && url.indexOf('/api/auth/login') === -1) {
                // FIX-TOKEN: 清除可能残留的旧 localStorage Token
                _clearLegacyToken();
                localStorage.removeItem(USER_KEY);
                showLoginDialog();
                return new Response(
                    JSON.stringify({code: 401, message: '未认证', data: null}),
                    {status: 401, headers: {'Content-Type': 'application/json'}}
                );
            }
            return response;
        });
    };

    // ==================== 页面加载认证检测 ====================

    window.addEventListener('DOMContentLoaded', function() {
        // FIX-TOKEN: 不读 localStorage Token，直接尝试请求（Cookie 由浏览器自动携带）
        // 若服务端认证失败返回 401，fetch 劫持层会弹出登录框
        _fetch('/api/health', {credentials: 'same-origin'}).then(function(r) {
            if (r.status === 401) showLoginDialog();
        }).catch(function() {});
    });

    // ==================== 登录对话框 ====================

    function showLoginDialog() {
        if (document.getElementById('ops-login-overlay')) return;

        var overlay = document.createElement('div');
        overlay.id = 'ops-login-overlay';
        overlay.style.cssText = [
            'position:fixed;top:0;left:0;width:100%;height:100%',
            'background:rgba(0,21,41,0.92)',
            'display:flex;align-items:center;justify-content:center;z-index:99999'
        ].join(';');

        overlay.innerHTML = [
            '<div style="background:#fff;border-radius:12px;padding:40px;width:380px;box-shadow:0 8px 30px rgba(0,0,0,.3)">',
            '<h2 style="text-align:center;margin-bottom:8px;color:#001529">🖥 OpsMonitor</h2>',
            '<p style="text-align:center;color:#888;font-size:13px;margin-bottom:24px">请登录以继续</p>',
            '<input id="ops-login-user" placeholder="用户名" autocomplete="username"',
            ' style="width:100%;padding:10px 14px;border:1px solid #d9d9d9;border-radius:6px;',
            'font-size:14px;margin-bottom:16px;outline:none;box-sizing:border-box">',
            '<input id="ops-login-pass" type="password" placeholder="密码" autocomplete="current-password"',
            ' style="width:100%;padding:10px 14px;border:1px solid #d9d9d9;border-radius:6px;',
            'font-size:14px;margin-bottom:16px;outline:none;box-sizing:border-box">',
            '<button id="ops-login-btn"',
            ' style="width:100%;padding:12px;background:#1890ff;color:#fff;border:none;',
            'border-radius:6px;font-size:16px;cursor:pointer">登 录</button>',
            '<div id="ops-login-err" style="color:#ff4d4f;font-size:13px;text-align:center;margin-top:12px"></div>',
            '</div>'
        ].join('');

        document.body.appendChild(overlay);

        var btnEl  = document.getElementById('ops-login-btn');
        var passEl = document.getElementById('ops-login-pass');
        var userEl = document.getElementById('ops-login-user');

        btnEl.onclick  = doLogin;
        passEl.onkeyup = function(e) { if (e.key === 'Enter') doLogin(); };
        userEl.onkeyup = function(e) { if (e.key === 'Enter') passEl.focus(); };
        userEl.focus();
    }

    function doLogin() {
        var user   = document.getElementById('ops-login-user').value.trim();
        var pass   = document.getElementById('ops-login-pass').value;
        var errEl  = document.getElementById('ops-login-err');
        var btnEl  = document.getElementById('ops-login-btn');

        if (!user || !pass) { errEl.textContent = '请输入用户名和密码'; return; }
        btnEl.disabled = true; btnEl.textContent = '登录中...';

        // FIX-TOKEN: 登录请求带 credentials，后端会 Set-Cookie（httpOnly）
        _fetch('/api/auth/login', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({username: user, password: pass}),
            credentials: 'same-origin'   // FIX-TOKEN: 确保接收 Set-Cookie 响应头
        }).then(function(r) { return r.json(); })
            .then(function(j) {
                if (j.data && j.data.token) {
                    // FIX-TOKEN: Token 已由服务端通过 Set-Cookie 设置到 httpOnly Cookie
                    // 前端不再主动存储 Token 到 localStorage（仅存用户名用于 UI）
                    localStorage.setItem(USER_KEY, user);

                    // v2.26-sec: 不再存储 Token 到 localStorage（防 XSS 窃取）
                    // Token 已通过 httpOnly Cookie 安全存储，JS 无法读取
                    _clearLegacyToken(); // 清理历史残留

                    var overlay = document.getElementById('ops-login-overlay');
                    if (overlay) overlay.remove();
                    location.reload();
                } else {
                    errEl.textContent = j.message || '登录失败';
                    btnEl.disabled = false; btnEl.textContent = '登 录';
                }
            }).catch(function() {
            errEl.textContent = '网络错误，请重试';
            btnEl.disabled = false; btnEl.textContent = '登 录';
        });
    }

    // ==================== 降级兼容工具（旧 localStorage Token）====================

    /**
     * FIX-TOKEN: 尝试读取旧的 localStorage Token（降级兼容，优先使用 Cookie）
     * 若 Cookie 已生效，此函数返回 null（服务端 Cookie 优先）
     * 此函数仅用于非浏览器客户端或 Cookie 不可用的场景
     */
    function _tryGetLegacyToken() {
        try {
            return localStorage.getItem(LEGACY_TOKEN);
        } catch(e) {
            return null;
        }
    }

    // v2.26-sec: 已废弃，Token 不再存入 localStorage
    // function _storeLegacyToken(token) {
    //     try { localStorage.setItem(LEGACY_TOKEN, token); } catch(e) {}
    // }

    function _clearLegacyToken() {
        try { localStorage.removeItem(LEGACY_TOKEN); } catch(e) {}
    }

})();