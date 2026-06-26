/* classic-app.js — OpsMonitor 经典界面交互逻辑 v2.32 */

// ===== 主题切换 =====
!function(){var t=localStorage.getItem('ops-theme')||'default';if(t==='console')document.documentElement.setAttribute('data-theme','console');document.addEventListener('DOMContentLoaded',function(){var b=document.getElementById('theme-btn');if(b)b.textContent=t==='console'?'☀️ 浅色':'🌙 深色'})}();
function toggleClassicTheme(){var c=localStorage.getItem('ops-theme')||'default',n=c==='console'?'default':'console';localStorage.setItem('ops-theme',n);if(n==='console')document.documentElement.setAttribute('data-theme','console');else document.documentElement.removeAttribute('data-theme');var b=document.getElementById('theme-btn');if(b)b.textContent=n==='console'?'☀️ 浅色':'🌙 深色'}

// ===== 全局状态 =====
let currentPage   = 0;
const pageSize    = 15;
let totalPages    = 0;
let currentLogId  = null;
let currentStatsId = null;
let debounceTimer = null;
let serverList    = [];
let exporterTemplates = [];

/**
 * Exporter 类型默认配置（ISSUE-2/5 修复：智能填充，远程服务器自动替换 IP）
 *
 * addr: 本机（local）默认地址，远程服务器注册时会自动替换 host 为服务器实际 IP
 * port: Exporter 监听端口（Prometheus 采集这个端口）
 * hint: 操作提示，告知用户需要做什么
 * addrLabel: 地址输入框的标签
 * addrPlaceholder: 地址输入框的占位提示
 * noAddr: true=不需要填写地址（node/windows Exporter 自身即采集宿主机数据）
 */
const typeDefaults = {
    // ── 系统监控 ──────────────────────────────────────────────────
    node: {
        addr: '', port: 9100, noAddr: true,
        addrLabel: '无需填写',
        addrPlaceholder: '（node-exporter 采集宿主机数据，无需指定目标地址）',
        hint: '◆ node-exporter 需在目标服务器上安装运行。远程服务器直接填写 IP，系统自动连接 [服务器IP]:9100'
    },
    windows: {
        addr: '', port: 9182, noAddr: true,
        addrLabel: '无需填写',
        addrPlaceholder: '（windows_exporter 运行在目标 Windows 机器上，无需指定地址）',
        hint: '◆ Windows 监控：在目标 Windows 主机上安装 windows_exporter.exe（端口9182），然后在此注册。\n下载地址: https://github.com/prometheus-community/windows_exporter/releases'
    },
    // ── 数据库 ────────────────────────────────────────────────────
    redis: {
        addr: 'redis://host.docker.internal:6379', port: 9121,
        addrLabel: 'Redis 连接地址',
        addrPlaceholder: '如: redis://192.168.1.10:6379 或 redis://:password@host:6379',
        hint: '◆ 填写 Redis 服务器地址（非 Exporter 端口）。Exporter 会自动连接 Redis 并采集指标。'
    },
    mysql: {
        addr: 'host.docker.internal:3306', port: 9104,
        addrLabel: 'MySQL 连接地址',
        addrPlaceholder: '如: 192.168.1.10:3306（Exporter 需有 PROCESS,REPLICATION CLIENT 权限的账号）',
        hint: '◆ 填写 MySQL 服务器的 host:port。需提前在 MySQL 中创建监控账号：GRANT PROCESS, REPLICATION CLIENT ON *.* TO \"exporter\"@\"%\" IDENTIFIED BY \"password\";'
    },
    postgres: {
        addr: 'host.docker.internal:5432', port: 9187,
        addrLabel: 'PostgreSQL 连接地址',
        addrPlaceholder: '如: 192.168.1.10:5432',
        hint: '◆ 填写 PostgreSQL 的 host:port。需创建监控账号：CREATE USER exporter WITH PASSWORD \"password\"; GRANT pg_monitor TO exporter;'
    },
    oracle: {
        addr: 'host.docker.internal:1521', port: 9161,
        addrLabel: 'Oracle 连接地址',
        addrPlaceholder: '如: 192.168.1.10:1521',
        hint: '◆ 填写 Oracle 数据库地址（端口通常是 1521）。需有 DBA 或 SELECT_CATALOG_ROLE 权限的账号。'
    },
    kingbase: {
        addr: 'host.docker.internal:54321', port: 9188,
        addrLabel: 'Kingbase 连接地址',
        addrPlaceholder: '如: 192.168.1.10:54321',
        hint: '◆ 人大金仓数据库监控，默认端口 54321。基于 postgres_exporter 实现。'
    },
    dm: {
        addr: 'host.docker.internal:5236', port: 9189,
        addrLabel: '达梦数据库连接地址',
        addrPlaceholder: '如: 192.168.1.10:5236',
        hint: '◆ 达梦数据库监控，默认端口 5236。基于 postgres_exporter 实现。'
    },
    // ── 中间件 ────────────────────────────────────────────────────
    nginx: {
        addr: 'http://host.docker.internal/stub_status', port: 9113,
        addrLabel: 'Nginx stub_status URL',
        addrPlaceholder: '如: http://192.168.1.10/stub_status',
        hint: '⚠ 必须先在 Nginx 配置中开启 stub_status 模块，否则 Exporter 将报 502 错误！\n\n在 nginx.conf 的 server {} 中添加：\nlocation /stub_status {\n    stub_status;\n    allow 127.0.0.1;\n    allow 172.16.0.0/12;\n    deny all;\n}\n\n然后 nginx -s reload'
    },
    geoserver: {
        addr: 'host.docker.internal:1099', port: 9404,
        addrLabel: 'GeoServer JMX 地址',
        addrPlaceholder: '如: 192.168.1.10:1099',
        hint: '◆ GeoServer 需开启 JMX 远程监控（端口默认 1099）。启动参数加 -Dcom.sun.management.jmxremote.port=1099'
    },
    jmx: {
        addr: 'host.docker.internal:1099', port: 9404,
        addrLabel: 'JMX 服务地址',
        addrPlaceholder: '如: service:jmx:rmi:///jndi/rmi://192.168.1.10:1099/jmxrmi',
        hint: '◆ Java 应用需开启 JMX 远程端口。启动参数: -Dcom.sun.management.jmxremote.port=1099 -Dcom.sun.management.jmxremote.authenticate=false'
    }
};

/** 根据服务器 host 和 Exporter 类型生成智能默认地址 */
function getSmartDefaultAddr(type, serverHost) {
    const d = typeDefaults[type];
    if (!d || d.noAddr) return '';
    const host = (serverHost && serverHost !== '127.0.0.1') ? serverHost : 'host.docker.internal';
    // 替换 host.docker.internal 为实际服务器 IP
    return d.addr.replace('host.docker.internal', host);
}

// ===== Toast 通知 =====
function toast(msg, type = 'info') {
    const wrap = document.getElementById('toastWrap');
    const el   = document.createElement('div');
    const icons = { ok:'✓', err:'✗', info:'⊙' };
    el.className = `toast toast-${type}`;
    // P1修复：toast msg 使用 textContent 避免 XSS
    const iconSpan = document.createElement('span');
    iconSpan.textContent = icons[type] || '📢';
    const msgSpan = document.createElement('span');
    msgSpan.textContent = msg;
    el.appendChild(iconSpan);
    el.appendChild(msgSpan);
    wrap.appendChild(el);
    setTimeout(() => { el.style.opacity='0'; el.style.transform='translateX(20px)'; el.style.transition='all .2s'; setTimeout(()=>el.remove(),200); }, 3200);
}

// ===== 工具函数 =====
function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

// ===== 系统总览（v2.29: 防重入） =====
let overviewLoading = false;
function loadOverview() {
    if (overviewLoading) return;
    overviewLoading = true;
    fetch('/api/system/status')
        .then(r => r.json())
        .then(resp => {
            if (resp.code !== 200 || !resp.data) return;
            const d = resp.data;

            const setBar = (elId, barId, val, warnHi, warnMid) => {
                const el  = document.getElementById(elId);
                const bar = document.getElementById(barId);
                if (val >= 0) {
                    el.textContent = val.toFixed(1) + '%';
                    bar.style.width      = Math.min(val, 100) + '%';
                    bar.style.background = val > warnHi ? 'var(--danger)' : val > warnMid ? 'var(--warning)' : 'var(--success)';
                } else { el.textContent = 'N/A'; }
            };

            setBar('ov-cpu',  'ov-cpu-bar',  d.cpuUsage,    80, 50);
            setBar('ov-mem',  'ov-mem-bar',  d.memoryUsage, 85, 60);
            setBar('ov-disk', 'ov-disk-bar', d.diskUsage,   90, 70);

            const cntEl = document.getElementById('containerCount');
            if (cntEl) cntEl.textContent = d.containerRunning + ' / ' + d.containerTotal;

            // 服务状态灯
            const grid = document.getElementById('serviceGrid');
            if (d.servicesByServer && Object.keys(d.servicesByServer).length > 0) {
                grid.innerHTML = Object.entries(d.servicesByServer).map(([sid, svcs]) => {
                    const srv     = serverList.find(s => s.id === sid);
                    const srvName = srv ? srv.name : sid;
                    const pills   = Object.entries(svcs).map(([type, status]) => {
                        const isUp      = status === 'UP';
                        const isPending = status === 'PENDING';
                        const bg  = isUp ? '#f0fff0' : (isPending ? '#fffbe6' : '#fff0f0');
                        const bd  = isUp ? '#b7eb8f' : (isPending ? '#ffe58f' : '#ffa39e');
                        const dc  = isUp ? 'dot-g'   : (isPending ? 'dot-y'   : 'dot-r');
                        const lbl = isPending ? type + ' ⏳' : type;
                        return `<span class="svc-pill" style="background:${bg};border-color:${bd}"><span class="dot ${dc}"></span>${lbl}</span>`;
                    }).join('');
                    return `<div class="svc-group"><div class="svc-name">${srvName}</div><div class="svc-pills">${pills}</div></div>`;
                }).join('');
            } else if (d.services && Object.keys(d.services).length > 0) {
                grid.innerHTML = Object.entries(d.services).map(([name, status]) => {
                    const isUp = status === 'UP';
                    return `<span class="svc-pill ${isUp?'svc-up':'svc-down'}"><span class="dot ${isUp?'dot-g':'dot-r'}"></span>${name}</span>`;
                }).join('');
            } else {
                grid.innerHTML = '<span style="color:var(--muted);font-size:12px;">暂无已注册的 Exporter</span>';
            }

            document.getElementById('overviewTime').textContent =
                '更新: ' + new Date(d.timestamp).toLocaleTimeString();

            // v2.19: 健康摘要
            const banner = document.getElementById('healthBanner');
            const bannerText = document.getElementById('healthBannerText');
            if (banner && bannerText) {
                const issues = [];
                const cpu = d.cpuUsage, mem = d.memoryUsage, disk = d.diskUsage;
                const upSvcs = Object.values(d.services || {}).filter(s => s === 'UP').length;
                const totalSvcs = Object.keys(d.services || {}).length;
                if (cpu > 85) issues.push('CPU 使用率过高 (' + cpu.toFixed(1) + '%)，建议检查高负载进程');
                else if (cpu > 60) issues.push('CPU 使用率偏高 (' + cpu.toFixed(1) + '%)');
                if (mem > 90) issues.push('内存即将耗尽 (' + mem.toFixed(1) + '%)，建议释放内存或扩容');
                else if (mem > 75) issues.push('内存使用率偏高 (' + mem.toFixed(1) + '%)');
                if (disk > 85) issues.push('磁盘空间不足 (' + disk.toFixed(1) + '%)，建议清理或扩容');
                else if (disk > 75) issues.push('磁盘使用率偏高 (' + disk.toFixed(1) + '%)');
                if (upSvcs < totalSvcs && totalSvcs > 0) issues.push((totalSvcs - upSvcs) + ' 个服务离线，建议检查服务状态');
                if (issues.length === 0) {
                    banner.style.display = 'block';
                    banner.style.background = '#f0fdf4'; banner.style.borderLeftColor = '#22c55e';
                    bannerText.style.color = '#16a34a'; bannerText.textContent = '✓ 系统运行正常，所有指标在安全范围内';
                } else if (issues.some(i => i.includes('耗尽') || i.includes('不足') || i.includes('离线'))) {
                    banner.style.display = 'block';
                    banner.style.background = '#fef2f2'; banner.style.borderLeftColor = '#ef4444';
                    bannerText.style.color = '#dc2626'; bannerText.textContent = '🔴 ' + issues.join('；');
                } else {
                    banner.style.display = 'block';
                    banner.style.background = '#fffbeb'; banner.style.borderLeftColor = '#f59e0b';
                    bannerText.style.color = '#92400e'; bannerText.textContent = '⚠ ' + issues.join('；');
                }
            }
        })
        .catch(() => {})
        .finally(() => { overviewLoading = false; });
}

// ===== 容器列表（静默刷新版 v2.29: 防重入）=====
let containersLoading = false;
// 上次渲染的容器数据摘要，用于差异检测
let _lastContainerSnapshot = '';
// 是否正在执行用户触发的主动刷新（显示加载态）
let _containerLoading = false;

function debounceLoad() {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => loadContainers(true), 300);
}

/**
 * 加载容器列表
 * @param {boolean} showLoading - true=用户主动触发（显示加载状态），false=后台静默刷新
 */
function loadContainers(showLoading = false) {
    if (containersLoading) return;
    containersLoading = true;
    const name   = document.getElementById('filterName').value;
    const status = document.getElementById('filterStatus').value;
    const params = new URLSearchParams({ page: currentPage, size: pageSize });
    if (status) params.set('status', status);
    if (name)   params.set('name', name);

    if (showLoading && !_containerLoading) {
        _containerLoading = true;
        const tbody = document.getElementById('containerList');
        // 只在列表为空时才显示加载中，已有数据时保留旧数据
        if (!tbody.querySelector('tr[data-cid]')) {
            tbody.innerHTML = `<tr><td colspan="7" class="loading">加载中...</td></tr>`;
        }
    }

    fetch(`/api/containers?${params}`)
        .then(r => r.json())
        .then(resp => {
            _containerLoading = false;
            if (resp.code !== 200) {
                // 只在首次或主动刷新时显示错误，静默刷新不覆盖已有数据
                if (showLoading) {
                    document.getElementById('containerList').innerHTML =
                        `<tr><td colspan="7" class="empty">✗ ${resp.message}</td></tr>`;
                }
                return;
            }
            const data = resp.data;
            totalPages = data.totalPages;
            document.getElementById('containerCount').textContent = data.total + ' 个';

            // 差异检测：只有数据实际变化时才重新渲染 DOM
            const snapshot = JSON.stringify(data.items.map(c => ({
                id: c.id, state: c.state, name: c.name
            })));

            if (snapshot !== _lastContainerSnapshot || showLoading) {
                _lastContainerSnapshot = snapshot;
                renderTable(data.items);
            }

            renderPager(data);

            // 更新时间（静默更新，不引起注意）
            const refreshEl = document.getElementById('lastRefresh');
            if (refreshEl) {
                refreshEl.textContent = '更新 ' + new Date().toLocaleTimeString();
            }

            // 异步加载运行中容器的资源使用率（始终静默执行）
            data.items.forEach(c => { if (c.state === 'running') loadInlineStats(c.id); });
        })
        .catch(e => {
            _containerLoading = false;
            if (showLoading) {
                document.getElementById('containerList').innerHTML =
                    `<tr><td colspan="7" class="empty">加载失败: ${e.message}</td></tr>`;
            }
        })
        .finally(() => { containersLoading = false; });
}

function renderTable(items) {
    const tbody = document.getElementById('containerList');
    if (!items || !items.length) {
        tbody.innerHTML = '<tr><td colspan="7" class="empty">暂无容器</td></tr>';
        return;
    }
    tbody.innerHTML = items.map(c => {
        const stateClass = {running:'state-running', exited:'state-exited', paused:'state-paused', created:'state-created'}[c.state] || '';
        const isRunning  = c.state === 'running';
        const ports      = (c.ports || []).filter(p => p.publicPort).map(p => `${p.publicPort}→${p.privatePort}`).join(', ') || '-';
        // P1修复：转义容器字段
        const safeCid   = escapeHtml(c.id   || '');
        const safeCname = escapeHtml(c.name  || '');
        const safeCimg  = escapeHtml(c.image || '');
        return `<tr data-cid="${safeCid}">
            <td><code style="font-size:12px;color:#666;">${safeCid}</code></td>
            <td><strong>${safeCname}</strong>${c.protectedContainer ? '<span style="font-size:10px;background:#f0f0f0;padding:1px 6px;border-radius:8px;margin-left:4px;">🔒</span>' : ''}</td>
            <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${safeCimg}">${safeCimg}</td>
            <td><span class="state-badge ${stateClass}"><span class="dot ${isRunning?'dot-g':'dot-r'}"></span>${c.state}</span></td>
            <td style="font-size:12px;">${ports}</td>
            <td class="stat-cell" id="stat-${c.id}">${isRunning ? '<span style="color:#bbb;font-size:11px;">采集中...</span>' : '<span style="color:#ccc;">-</span>'}</td>
            <td>
                <div style="display:flex;gap:4px;flex-wrap:wrap;">
                    ${isRunning
            ? `<button class="btn btn-w btn-sm" onclick="doAction('${c.id}','stop')">⏹ 停止</button>
                           <button class="btn btn-p btn-sm" onclick="doAction('${c.id}','restart')">🔄 重启</button>`
            : `<button class="btn btn-s btn-sm" onclick="doAction('${c.id}','start')">▶ 启动</button>`}
                    <button class="btn btn-v btn-sm" onclick="showLog('${c.id}','${c.name}')">⚖ 日志</button>
                    ${isRunning ? `<button class="btn btn-o btn-sm" onclick="showStats('${c.id}','${c.name}')">⊡ 资源</button>` : ''}
                    ${!c.protectedContainer ? `<button class="btn btn-d btn-sm" onclick="doRemove('${c.id}','${c.name}',${isRunning})">✕</button>` : ''}
                </div>
            </td>
        </tr>`;
    }).join('');
}

// ===== 行内 Stats =====
function loadInlineStats(id) {
    fetch(`/api/containers/${id}/stats`)
        .then(r => r.json())
        .then(resp => {
            if (resp.code !== 200) return;
            const s    = resp.data;
            const cell = document.getElementById(`stat-${id}`);
            if (!cell) return;
            const cpuC = s.cpuPercent > 80 ? 'prog-high' : s.cpuPercent > 50 ? 'prog-mid' : 'prog-low';
            const memC = s.memoryPercent > 80 ? 'prog-high' : s.memoryPercent > 50 ? 'prog-mid' : 'prog-low';
            cell.innerHTML = `
                <div style="margin-bottom:4px;">
                    <div style="display:flex;justify-content:space-between;font-size:11px;color:#666;"><span>CPU</span><span>${s.cpuPercent.toFixed(1)}%</span></div>
                    <div class="prog-bar"><div class="prog-fill ${cpuC}" style="width:${Math.min(s.cpuPercent,100)}%"></div></div>
                </div>
                <div>
                    <div style="display:flex;justify-content:space-between;font-size:11px;color:#666;"><span>MEM</span><span>${s.memoryUsedFormatted}</span></div>
                    <div class="prog-bar"><div class="prog-fill ${memC}" style="width:${Math.min(s.memoryPercent,100)}%"></div></div>
                </div>`;
        }).catch(() => {});
}

// ===== 分页 =====
function renderPager(data) {
    const pager = document.getElementById('pager');
    if (data.total <= pageSize) { pager.style.display = 'none'; return; }
    pager.style.display = 'flex';
    document.getElementById('pagerInfo').textContent =
        `共 ${data.total} 条，第 ${data.page + 1}/${data.totalPages} 页`;
    document.getElementById('prevBtn').disabled = data.page === 0;
    document.getElementById('nextBtn').disabled = data.page >= data.totalPages - 1;
}
function prevPage() { if (currentPage > 0) { currentPage--; loadContainers(true); } }
function nextPage() { if (currentPage < totalPages - 1) { currentPage++; loadContainers(true); } }

// ===== 容器操作 =====
function doAction(id, action) {
    fetch(`/api/containers/${id}/${action}`, { method: 'POST' })
        .then(r => r.json())
        .then(resp => {
            resp.code === 200 ? toast(resp.message || resp.data, 'ok') : toast(resp.message, 'err');
            setTimeout(() => loadContainers(true), 800);
        })
        .catch(e => toast('操作失败: ' + e.message, 'err'));
}

function doRemove(id, name, isRunning) {
    const msg = isRunning ? `容器 "${name}" 正在运行，确定要强制删除吗？` : `确定删除容器 "${name}" 吗？`;
    if (!confirm(msg)) return;
    fetch(`/api/containers/${id}?force=${isRunning}`, { method: 'DELETE' })
        .then(r => r.json())
        .then(resp => {
            resp.code === 200 ? toast('容器已删除', 'ok') : toast(resp.message, 'err');
            setTimeout(() => loadContainers(true), 500);
        })
        .catch(e => toast('删除失败: ' + e.message, 'err'));
}

// ===== 日志弹窗 =====
function showLog(id, name) {
    currentLogId = id;
    document.getElementById('logTitle').textContent = `⚖ ${name}`;
    document.getElementById('logOverlay').style.display = 'block';
    document.getElementById('logModal').style.display   = 'block';
    refreshLog();
}
function refreshLog() {
    if (!currentLogId) return;
    const tail = document.getElementById('logTail').value;
    document.getElementById('logBody').textContent = '加载中...';
    fetch(`/api/containers/${currentLogId}/logs?tail=${tail}&stdout=true&stderr=true&timestamps=true`)
        .then(r => r.json())
        .then(resp => {
            if (resp.code !== 200) { document.getElementById('logBody').textContent = '错误: ' + resp.message; return; }
            const logData = resp.data;
            if (!logData.lines || !logData.lines.length) { document.getElementById('logBody').textContent = '(无日志)'; return; }
            const html = logData.lines.map(l => {
                const cls = l.stream === 'stderr' ? 'stderr' : '';
                const ts  = l.timestamp ? `<span style="color:#6a9955">${l.timestamp}</span> ` : '';
                return `<span class="${cls}">${ts}${escapeHtml(l.content)}</span>`;
            }).join('\n');
            document.getElementById('logBody').innerHTML = html;
            const body = document.getElementById('logBody');
            body.scrollTop = body.scrollHeight;
        })
        .catch(e => { document.getElementById('logBody').textContent = '加载失败: ' + e.message; });
}
function closeLog() {
    document.getElementById('logOverlay').style.display = 'none';
    document.getElementById('logModal').style.display   = 'none';
    currentLogId = null;
}

// ===== Stats 弹窗 =====
function showStats(id, name) {
    currentStatsId = id;
    document.getElementById('statsTitle').textContent = `⊡ ${name} - 资源监控`;
    document.getElementById('statsOverlay').style.display = 'block';
    document.getElementById('statsModal').style.display   = 'block';
    refreshStats();
}
function refreshStats() {
    if (!currentStatsId) return;
    document.getElementById('statsBody').innerHTML = '<div class="loading">采样中...</div>';
    fetch(`/api/containers/${currentStatsId}/stats`)
        .then(r => r.json())
        .then(resp => {
            if (resp.code !== 200) { document.getElementById('statsBody').innerHTML = `<div class="empty">${resp.message}</div>`; return; }
            const s        = resp.data;
            const cpuColor = s.cpuPercent > 80 ? 'var(--danger)' : s.cpuPercent > 50 ? 'var(--warning)' : 'var(--success)';
            const memColor = s.memoryPercent > 80 ? 'var(--danger)' : s.memoryPercent > 50 ? 'var(--warning)' : 'var(--success)';
            document.getElementById('statsBody').innerHTML = `
                <div class="stats-grid">
                    <div class="stat-box">
                        <div class="stat-title">CPU 使用率</div>
                        <div class="stat-big" style="color:${cpuColor}">${s.cpuPercent.toFixed(2)}%</div>
                        <div class="prog-bar" style="height:8px;margin-top:8px;"><div class="prog-fill" style="width:${Math.min(s.cpuPercent,100)}%;background:${cpuColor}"></div></div>
                    </div>
                    <div class="stat-box">
                        <div class="stat-title">内存使用</div>
                        <div class="stat-big" style="color:${memColor}">${s.memoryPercent.toFixed(2)}%</div>
                        <div class="stat-sub">${s.memoryUsedFormatted} / ${s.memoryLimitFormatted}</div>
                        <div class="prog-bar" style="height:8px;margin-top:8px;"><div class="prog-fill" style="width:${Math.min(s.memoryPercent,100)}%;background:${memColor}"></div></div>
                    </div>
                    <div class="stat-box">
                        <div class="stat-title">网络 I/O</div>
                        <div class="stat-big" style="font-size:18px;">⬇ ${s.networkRxFormatted}</div>
                        <div class="stat-sub">⬆ ${s.networkTxFormatted}</div>
                    </div>
                    <div class="stat-box">
                        <div class="stat-title">进程数 (PIDs)</div>
                        <div class="stat-big">${s.pids || '-'}</div>
                        <div class="stat-sub">容器: ${s.containerName}</div>
                    </div>
                </div>`;
        })
        .catch(e => { document.getElementById('statsBody').innerHTML = `<div class="empty">获取失败: ${e.message}</div>`; });
}
function closeStats() {
    document.getElementById('statsOverlay').style.display = 'none';
    document.getElementById('statsModal').style.display   = 'none';
    currentStatsId = null;
}

// ===== 服务器管理 =====
function loadServers() {
    fetch('/api/servers').then(r => r.json()).then(resp => {
        if (resp.code !== 200 || !resp.data) return;
        serverList = resp.data;
        renderServerGrid();
        updateServerSelect();
    }).catch(() => {});
}
function renderServerGrid() {
    const grid = document.getElementById('serverGrid');
    if (!serverList.length) { grid.innerHTML = '<div style="color:var(--muted);font-size:13px;">暂无服务器</div>'; return; }
    // P1修复：使用 escapeHtml 转义服务器字段，防止存储型 XSS
    grid.innerHTML = serverList.map(s => {
        const isLocal = s.id === 'local';
        const safeName = escapeHtml(s.name || '');
        const safeHost = escapeHtml(s.host || '');
        const safeDesc = s.description ? escapeHtml(s.description) : '';
        const safeId   = escapeHtml(s.id || '');
        return `<div class="${isLocal?'srv-card srv-local':'srv-card'}">
            <div class="srv-card-header">
                <strong id="srv-name-${safeId}">${safeName}</strong>
                <span class="srv-badge ${isLocal?'srv-badge-local':'srv-badge-remote'}">${isLocal?'本机':'远程'}</span>
            </div>
            <div style="font-size:13px;color:var(--muted);">${safeHost}</div>
            ${safeDesc ? `<div style="font-size:12px;color:var(--muted);margin-top:4px;">${safeDesc}</div>` : ''}
            <div style="margin-top:8px;display:flex;gap:6px;">
                <button class="btn btn-o btn-sm" onclick="renameServer('${safeId}','${safeName.replace(/'/g,"\\'")}')">✏️ 重命名</button>
                ${!isLocal ? `<button class="btn btn-d btn-sm" onclick="deleteServer('${safeId}')">删除</button>` : ''}
            </div>
        </div>`;
    }).join('');
}
function updateServerSelect() {
    const sel = document.getElementById('regServer');
    if (!sel) return;
    // P1修复：转义 option 内容
    sel.innerHTML = serverList.map(s =>
        `<option value="${escapeHtml(s.id||'')}">${escapeHtml(s.name||'')} (${escapeHtml(s.host||'')})${s.id==='local'?' - Docker模式':' - 远程'}</option>`
    ).join('');
}
function showAddServerForm() { document.getElementById('addServerForm').style.display = 'block'; }
function hideAddServerForm() { document.getElementById('addServerForm').style.display = 'none'; }
/** ISSUE-3修复：添加服务器前进行基本格式校验 + 可选连通性测试 */
function doAddServer() {
    const name = document.getElementById('srvName').value.trim();
    const host = document.getElementById('srvHost').value.trim();
    const desc = document.getElementById('srvDesc').value.trim();

    // 基本校验
    if (!name || name.length < 1) { toast('请填写服务器名称', 'err'); return; }
    if (!host) { toast('请填写服务器 IP 地址', 'err'); return; }

    // 格式校验：必须是合法 IPv4 或含点主机名（BUG-A修复：拒绝无点纯字母字符串如 dsafd）
    const ipv4Re = /^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$/;
    // BUG-A: 主机名必须含点（如 myserver.local），禁止无点纯字母字符串（如 dsafd）
    const hostWithDotRe = /^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)+$/;
    if (!ipv4Re.test(host) && !hostWithDotRe.test(host)) {
        toast('IP 地址格式无效，请填写如 192.168.1.10 的格式，或含域后缀的主机名如 myserver.local', 'err');
        return;
    }
    if (host.startsWith('0.') || host === '0.0.0.0') {
        toast('IP 地址无效', 'err'); return;
    }

    // 禁止添加本机 IP
    if (host === '127.0.0.1' || host === 'localhost') {
        toast('本机已默认添加，无需重复添加 127.0.0.1', 'err'); return;
    }

    const btn = document.getElementById('addServerBtn');
    if (btn) { btn.disabled = true; btn.textContent = '添加中...'; }

    fetch('/api/servers', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, host, type: 'REMOTE', description: desc })
    }).then(r => r.json()).then(resp => {
        if (resp.code === 200) {
            toast('✓ 服务器已添加: ' + name + ' (' + host + ')', 'ok');
            hideAddServerForm();
            ['srvName','srvHost','srvDesc'].forEach(id => document.getElementById(id).value = '');
            loadServers();
        } else {
            toast('添加失败: ' + resp.message, 'err');
        }
    }).catch(e => toast('添加失败: ' + e.message, 'err'))
        .finally(() => { if (btn) { btn.disabled = false; btn.textContent = '添加'; } });
}
function deleteServer(id) {
    if (!confirm('确定删除此服务器？关联的 Exporter 将一并删除。')) return;
    fetch('/api/servers/' + id, { method: 'DELETE' }).then(r => r.json()).then(resp => {
        resp.code === 200 ? toast('已删除', 'ok') : toast(resp.message, 'err');
        loadServers(); loadExporters();
    }).catch(e => toast('删除失败: ' + e.message, 'err'));
}
function renameServer(id, currentName) {
    const newName = prompt('输入新的项目/服务器名称:', currentName);
    if (!newName || newName === currentName) return;
    fetch('/api/servers/' + id, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newName })
    }).then(r => r.json()).then(resp => {
        if (resp.code === 200) { toast('已重命名为: ' + newName, 'ok'); loadServers(); }
        else { toast(resp.message, 'err'); }
    }).catch(e => toast('重命名失败: ' + e.message, 'err'));
}
/** 服务器切换时，智能更新地址默认值 */
function onServerChange() {
    const sid  = document.getElementById('regServer').value;
    const srv  = serverList.find(s => s.id === sid);
    const type = document.getElementById('regType').value;
    if (!type) return;
    const d    = typeDefaults[type];
    const addrInput = document.getElementById('regTargetAddress');
    if (!d || d.noAddr) return;
    const serverHost = (srv && sid !== 'local') ? srv.host : null;
    const smartAddr  = getSmartDefaultAddr(type, serverHost);
    addrInput.value  = smartAddr;
}

// ===== Exporter 管理 =====
function loadExporters() {
    fetch('/api/exporters')
        .then(r => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
        .then(resp => {
            const tbody = document.getElementById('exporterList');
            if (resp.code !== 200 || !resp.data || !resp.data.length) {
                tbody.innerHTML = '<tr><td colspan="7" class="empty">暂无已注册的 Exporter，点击上方"注册 Exporter"添加</td></tr>';
                return;
            }
            tbody.innerHTML = resp.data.map(e => {
                const isRunning = e.state === 'running';
                const hs = e.healthStatus || '';
                // v2.31: 采集健康状态优先
                let stateHtml;
                if (hs === 'UP') stateHtml = '<span class="state-badge state-running"><span class="dot dot-g"></span>正常</span>';
                else if (hs === 'DOWN') stateHtml = '<span class="state-badge" style="background:#fff7ed;border-color:#fed7aa"><span class="dot dot-r"></span>采集失败</span>';
                else if (hs === 'PENDING') stateHtml = '<span class="state-badge" style="background:#f8fafc;border-color:#e2e8f0"><span class="dot dot-y"></span>等待采集</span>';
                else if (e.state === 'running') stateHtml = '<span class="state-badge" style="background:#f8fafc;border-color:#e2e8f0"><span class="dot dot-g"></span>检测中</span>';
                else stateHtml = `<span class="state-badge state-exited"><span class="dot dot-r"></span>${e.state||'已停止'}</span>`;

                const addr      = e.targetAddress || '-';
                const labelBadge = (e.project || e.service) ?
                    `<span style="font-size:10px;padding:2px 5px;background:#e0f2fe;color:#0369a1;border-radius:3px">${e.project||''}${e.project&&e.service?'/':''}${e.service||''}</span>` : '';
                return `<tr>
                    <td><code style="font-size:11px;">${e.id}</code></td>
                    <td><span style="font-size:12px;padding:2px 6px;background:#f6f8fa;border-radius:4px;">${e.serverId || 'local'}</span></td>
                    <td>${e.type}${labelBadge ? `<br>${labelBadge}` : ''}</td>
                    <td style="font-size:12px;max-width:180px;overflow:hidden;text-overflow:ellipsis;" title="${addr}">${addr}</td>
                    <td>${stateHtml}</td>
                    <td>${e.metricsPort}</td>
                    <td>
                        <div style="display:flex;gap:4px;flex-wrap:wrap;">
                            <button class="btn btn-v btn-sm" onclick="quickDiagnose('${e.id}')" title="全链路诊断">⚙</button>
                            ${e.managedByDocker && isRunning  ? `<button class="btn btn-w btn-sm" onclick="expAction('${e.id}','stop')">⏹</button>` : ''}
                            ${e.managedByDocker && !isRunning ? `<button class="btn btn-s btn-sm" onclick="expAction('${e.id}','start')">▶</button>` : ''}
                            ${e.managedByDocker ? `<button class="btn btn-v btn-sm" onclick="showExpLog('${e.id}')">⚖</button>` : ''}
                            <button class="btn btn-d btn-sm" onclick="expRemove('${e.id}')">✕</button>
                        </div>
                    </td>
                </tr>`;
            }).join('');
        })
        .catch(e => {
            document.getElementById('exporterList').innerHTML =
                `<tr><td colspan="8" class="empty">加载失败: ${e.message}</td></tr>`;
        });
}
function showRegisterForm() {
    document.getElementById('registerForm').style.display  = 'block';
    document.getElementById('testResult').style.display    = 'none';
    if (exporterTemplates.length === 0) {
        fetch('/api/exporters/templates').then(r => r.json()).then(resp => {
            if (resp.code === 200) {
                exporterTemplates = resp.data;
                const sel = document.getElementById('regType');
                sel.innerHTML = '<option value="">选择类型...</option>' +
                    resp.data.map(t => `<option value="${t.type}">${t.displayName} (${t.type})</option>`).join('');
            }
        });
    }
}
function hideRegisterForm() { document.getElementById('registerForm').style.display = 'none'; }
/** ISSUE-4/5修复：类型变更时智能填充地址、显示详细提示 */
function onTypeChange() {
    const type      = document.getElementById('regType').value;
    const tpl       = exporterTemplates.find(t => t.type === type);
    const hint      = document.getElementById('regHint');
    const portInput = document.getElementById('regPort');
    const addrInput = document.getElementById('regTargetAddress');
    const addrRow   = document.getElementById('regAddrRow');
    const addrLabel = document.getElementById('regAddrLabel');

    if (!tpl) { hint.innerHTML = ''; return; }

    const d = typeDefaults[type];
    const sid = document.getElementById('regServer').value;
    const srv = serverList.find(s => s.id === sid);
    const serverHost = (srv && sid !== 'local') ? srv.host : null;

    if (d) {
        // 是否需要地址输入框
        if (d.noAddr) {
            addrInput.value       = '';
            addrInput.placeholder = d.addrPlaceholder || '（无需填写）';
            addrInput.disabled    = true;
            addrInput.style.background = '#f5f5f5';
            if (addrLabel) addrLabel.textContent = d.addrLabel || '目标地址（无需填写）';
        } else {
            addrInput.disabled    = false;
            addrInput.style.background = '';
            // 智能填充地址：远程服务器自动替换 IP
            const smartAddr = getSmartDefaultAddr(type, serverHost);
            addrInput.value       = smartAddr;
            addrInput.placeholder = d.addrPlaceholder || '';
            if (addrLabel) addrLabel.textContent = d.addrLabel || '服务地址';
        }
        portInput.placeholder = d.port || tpl.metricsPort;
    }

    // 显示详细 hint（含换行支持）
    const hintText = d ? d.hint : '';
    const imgText = tpl.image ? tpl.image : 'N/A（需在目标机器上安装）';
    let hintHtml = '<span class="tpl-meta">镜像: <code>' + imgText + '</code> | Exporter端口: ' + tpl.metricsPort + '</span>';
    if (hintText) {
        hintHtml += '<div class="tpl-hint">' + escapeHtml(hintText) + '</div>';
    }
    hint.innerHTML = hintHtml;

    document.getElementById('testResult').style.display = 'none';
}
function doTestConnection() {
    const type = document.getElementById('regType').value;
    const addr = document.getElementById('regTargetAddress').value.trim();
    const box  = document.getElementById('testResult');
    if (!type || !addr) { toast('请先选择类型并填写地址', 'err'); return; }
    box.style.display = 'block'; box.style.background = '#f0f0f0'; box.style.color = '#666'; box.textContent = '正在测试连接...';
    fetch('/api/exporters/test', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type, targetAddress: addr })
    }).then(r => r.json()).then(resp => {
        const d = resp.data || {};
        // BUG-B修复：将 errorType 存入 dataset，供 doRegister 读取决定是否硬阻断
        box.dataset.errorType = d.errorType || '';
        if (d.success) {
            box.style.background = '#f0fff0'; box.style.color = '#389e0d';
            box.textContent = '✓ 连接成功';
        } else if (d.errorType === 'FORMAT_ERROR') {
            box.style.background = '#fff0f0'; box.style.color = '#cf1322';
            box.textContent = '✗ 地址格式错误：' + (d.message || '无法连接');
        } else {
            box.style.background = '#fff8e1'; box.style.color = '#ad6800';
            box.textContent = '⚠ 网络不通：' + (d.message || '无法连接');
        }
    }).catch(e => {
        box.dataset.errorType = 'NETWORK_ERROR';
        box.style.background = '#fff0f0'; box.style.color = '#cf1322';
        box.textContent = '✗ 测试失败: ' + e.message;
    });
}
/** ISSUE-4修复：注册前必须通过测试连接（noAddr 类型除外） */
function doRegister() {
    const type = document.getElementById('regType').value;
    if (!type) { toast('请选择 Exporter 类型', 'err'); return; }

    const serverId      = document.getElementById('regServer').value || 'local';
    const targetAddress = document.getElementById('regTargetAddress').value.trim();
    const portVal       = document.getElementById('regPort').value;
    const project       = document.getElementById('regProject').value.trim();
    const service       = document.getElementById('regService').value.trim();
    const d = typeDefaults[type];

    // 地址校验（非 noAddr 类型必须填写地址）
    if (d && !d.noAddr && !targetAddress) {
        toast('请填写服务地址（' + (d.addrLabel || '目标地址') + '）', 'err');
        return;
    }

    // 检测测试连接结果（非 noAddr 类型）BUG-B修复：区分格式错误（硬阻断）和网络错误（软警告）
    const testBox = document.getElementById('testResult');
    if (d && !d.noAddr) {
        const testOk         = testBox.style.display !== 'none' && testBox.textContent.includes('✓');
        const testFailed     = testBox.style.display !== 'none' && testBox.textContent.includes('✗');
        // BUG-B: 读取后端返回的 errorType，区分两种失败原因
        const lastErrorType  = testBox.dataset.errorType || '';

        if (testFailed) {
            if (lastErrorType === 'FORMAT_ERROR') {
                // 格式错误（地址无法解析/无意义）→ 硬阻断，禁止注册
                toast('✗ 地址格式错误，请修正后重新测试连接，无法注册。', 'err');
                return;
            }
            // 网络错误（格式合法但服务不可达）→ 软警告，允许预注册
            if (!confirm('⚠ 连接测试失败（网络不通或服务未启动）\n\n• 若目标服务尚未部署，可先预注册后再启动\n• 注册后 Prometheus 将显示该 Target DOWN，待服务启动后自动恢复\n\n确定注册吗？')) {
                return;
            }
        } else if (!testOk) {
            // 未做过测试：提示但允许继续
            if (!confirm('建议先点击【测试连接】确认服务可达。\n\n确定不测试直接注册吗？')) {
                return;
            }
        }
    }

    const body = { type, serverId };
    if (targetAddress) body.targetAddress = targetAddress;
    if (portVal && portVal !== '') body.metricsPort = parseInt(portVal);
    if (project) body.project = project;
    if (service) body.service = service;

    const registerBtn = document.getElementById('registerBtn');
    if (registerBtn) { registerBtn.disabled = true; registerBtn.textContent = '注册中...'; }

    const isLocal = serverId === 'local';
    toast((isLocal ? '正在注册（首次可能需要拉取 Docker 镜像，请耐心等待~30秒）...' : '正在注册远程 Exporter...'), 'ok');

    fetch('/api/exporters/register', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    })
        .then(r => { if (!r.ok) return r.text().then(t => { throw new Error('HTTP ' + r.status + ': ' + t); }); return r.json(); })
        .then(resp => {
            if (resp.code === 200) {
                toast('✓ Exporter 注册成功: ' + resp.data.id, 'ok');
                hideRegisterForm();
                loadExporters();
                setTimeout(loadContainers, 1500);
            } else {
                toast('注册失败: ' + (resp.message || '未知错误'), 'err');
            }
        })
        .catch(e => toast('注册失败: ' + e.message, 'err'))
        .finally(() => { if (registerBtn) { registerBtn.disabled = false; registerBtn.textContent = '注册'; } });
}
function toggleQuickMenu() {
    const m = document.getElementById('quickMenu');
    m.style.display = m.style.display === 'none' ? 'block' : 'none';
}
// v2.21: 一键全部注册本机 node + process
function quickRegisterAll() {
    document.getElementById('quickMenu').style.display = 'none';
    if (!confirm('一键注册本机 node + process Exporter？\n（首次需拉取镜像，约 1-2 分钟）')) return;
    toast('正在批量注册 node + process Exporter...', 'ok');
    fetch('/api/exporters/batch-register', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ serverId:'local', exporters: [{type:'node'}, {type:'process'}] })
    }).then(r => r.json()).then(resp => {
        if (resp.code === 200 && resp.data) {
            const d = resp.data;
            toast('批量注册完成: ' + d.success + '/' + d.total + ' 成功', d.failed > 0 ? 'warn' : 'ok');
            loadExporters(); setTimeout(loadContainers, 1500);
        } else { toast(resp.message, 'err'); }
    }).catch(e => toast('批量注册失败: ' + e.message, 'err'));
}
function quickRegister(type, targetAddress) {
    document.getElementById('quickMenu').style.display = 'none';
    const names = { redis:'Redis', mysql:'MySQL', postgres:'PostgreSQL', nginx:'Nginx', oracle:'Oracle', kingbase:'Kingbase', geoserver:'GeoServer' };
    const name  = names[type] || type;
    if (!confirm(`一键注册本机 ${name} Exporter？`)) return;
    toast(`正在注册 ${name} Exporter（首次需拉取镜像）...`, 'ok');
    fetch('/api/exporters/register', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type, targetAddress })
    }).then(r => r.json()).then(resp => {
        if (resp.code === 200) { toast(`${name} Exporter 注册成功!`, 'ok'); loadExporters(); setTimeout(loadContainers, 1500); }
        else { toast(resp.message, 'err'); }
    }).catch(e => toast('注册失败: ' + e.message, 'err'));
}
// v2.23: Exporter 全链路诊断
function quickDiagnose(id) {
    toast('正在诊断 ' + id + ' ...', 'info');
    fetch('/api/exporters/' + id + '/health-check')
        .then(r => r.json()).then(resp => {
            if (resp.code === 200 && resp.data) {
                const allOk = resp.data.summary === '全链路正常';
                toast('⚙ ' + id + ': ' + resp.data.summary, allOk ? 'ok' : 'warn');
            } else { toast('诊断失败', 'err'); }
        }).catch(e => toast('诊断失败: ' + e.message, 'err'));
}
function showExpLog(id) {
    fetch(`/api/exporters/${id}/logs`).then(r => r.json()).then(resp => {
        if (resp.code === 200 && resp.data) {
            document.getElementById('logTitle').textContent = 'Exporter 日志: ' + id;
            document.getElementById('logBody').textContent  = resp.data.logs || '(无日志)';
            document.getElementById('logOverlay').style.display = 'block';
            document.getElementById('logModal').style.display   = 'block';
            currentLogId = null;
        } else { toast('获取日志失败', 'err'); }
    }).catch(e => toast('获取日志失败: ' + e.message, 'err'));
}
function expAction(id, action) {
    fetch(`/api/exporters/${id}/${action}`, { method: 'POST' }).then(r => r.json()).then(resp => {
        resp.code === 200 ? toast(resp.data || resp.message, 'ok') : toast(resp.message, 'err');
        setTimeout(loadExporters, 800);
    }).catch(e => toast('操作失败: ' + e.message, 'err'));
}
function expRemove(id) {
    if (!confirm(`确定注销 Exporter "${id}" 吗？将停止容器并移除 Prometheus 配置。`)) return;
    fetch(`/api/exporters/${id}`, { method: 'DELETE' }).then(r => r.json()).then(resp => {
        resp.code === 200 ? toast('已注销', 'ok') : toast(resp.message, 'err');
        loadExporters(); setTimeout(loadContainers, 1000);
    }).catch(e => toast('注销失败: ' + e.message, 'err'));
}

// ===== 键盘 & 点击事件 =====
document.addEventListener('keydown', e => { if (e.key === 'Escape') { closeLog(); closeStats(); } });
document.addEventListener('click', e => {
    const btn  = document.getElementById('quickMenuBtn');
    const menu = document.getElementById('quickMenu');
    if (btn && menu && !btn.contains(e.target) && !menu.contains(e.target)) menu.style.display = 'none';
});

// ===== 初始化 =====
document.addEventListener('DOMContentLoaded', () => {
    loadOverview();
    loadServers();
    loadContainers(true);   // 首次：主动加载，显示加载状态
    loadExporters();
});

// v2.29: 系统总览 15 秒刷新（原 5 秒，降频 3x 减少服务器压力）
setInterval(loadOverview, 15000);

// v2.29: 容器列表 30 秒静默刷新（原 15 秒，降频 2x）
setInterval(() => loadContainers(false), 30000);

// Exporter 列表：30秒静默刷新
setInterval(() => loadExporters(), 30000);