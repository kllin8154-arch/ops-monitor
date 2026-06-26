/* admin-app.js — OpsMonitor Vue Admin v2.22
 * 修复：正确挂载、侧边栏丝滑折叠、完整功能
 */
(function() {
    'use strict';

    // 等待 Vue 就绪
    function waitForVue(cb, attempts) {
        if (window.Vue) { cb(); return; }
        if ((attempts || 0) > 50) {
            console.error('[OpsMonitor] Vue 未能加载');
            return;
        }
        setTimeout(() => waitForVue(cb, (attempts || 0) + 1), 100);
    }

    waitForVue(function() {
        const { createApp, ref, reactive, computed, onMounted, onUnmounted } = Vue;

        // 从 <template id="app-template"> 读取模板内容，挂载到 #app
        const templateEl = document.getElementById('app-template');
        if (!templateEl) { console.error('[OpsMonitor] app-template 未找到'); return; }

        const app = createApp({
            template: templateEl.innerHTML,
            setup() {

                // ==================== Toast ====================
                const toasts = ref([]);
                let toastId = 0;
                function toast(msg, type = 'info', duration = 3500) {
                    const id = ++toastId;
                    const icons = { ok:'✅', err:'❌', warn:'⚠️', info:'ℹ️' };
                    toasts.value.push({ id, msg, type, icon: icons[type] || '📢' });
                    setTimeout(() => {
                        const el = document.getElementById('toast-' + id);
                        if (el) { el.style.opacity = '0'; el.style.transform = 'translateX(20px)'; }
                        setTimeout(() => { toasts.value = toasts.value.filter(t => t.id !== id); }, 250);
                    }, duration);
                }

                // ==================== 侧边栏 ====================
                const sidebarCollapsed = ref(false);
                const sidebarPinned = ref(true);
                let sidebarHoverTimer = null;
                function toggleSidebar() {
                    if (sidebarCollapsed.value) {
                        sidebarCollapsed.value = false; sidebarPinned.value = true;
                    } else if (sidebarPinned.value) {
                        sidebarCollapsed.value = true; sidebarPinned.value = false;
                    } else {
                        sidebarPinned.value = true;
                    }
                }
                const onSidebarEnter = () => {
                    if (sidebarPinned.value) return;
                    clearTimeout(sidebarHoverTimer);
                    sidebarCollapsed.value = false;
                };
                const onSidebarLeave = () => {
                    if (sidebarPinned.value) return;
                    sidebarHoverTimer = setTimeout(() => {
                        sidebarCollapsed.value = true;
                    }, 300);
                };

                // ==================== 主题切换 v2.32 ====================
                const currentTheme = ref(localStorage.getItem('ops-theme')||'default');
                const toggleTheme = () => {
                    currentTheme.value = currentTheme.value==='console'?'default':'console';
                    localStorage.setItem('ops-theme', currentTheme.value);
                    if(currentTheme.value==='console') document.documentElement.setAttribute('data-theme','console');
                    else document.documentElement.removeAttribute('data-theme');
                };
                if(currentTheme.value==='console') document.documentElement.setAttribute('data-theme','console');

                // ==================== 认证 ====================
                const loggedIn       = ref(false);
                const currentUser    = ref('');
                // v2.24: 统一转为大写，确保与模板 v-if 比较一致
                const currentUserRole = ref('VIEWER');
                const roleNormalize = (r) => (r || 'VIEWER').toUpperCase();
                const loginForm      = reactive({ username: '', password: '' });
                const loginError   = ref('');
                const loginLoading = ref(false);
                const token        = ref('');

                // FIX-TOKEN: 初始化认证状态
                // 优先使用 httpOnly Cookie（由后端 Set-Cookie 设置，JS 无法直接读取）
                // 降级兼容：读取 localStorage 中的旧格式 Token（供 Authorization Header 使用）
                const savedToken = localStorage.getItem('ops_token');
                const savedUser  = localStorage.getItem('ops_user');
                // FIX-TOKEN: 用用户名存在性判断 UI 显示状态，实际认证由 Cookie 决定
                if (savedUser) {
                    token.value = savedToken || '';
                    currentUser.value = savedUser;
                    currentUserRole.value = roleNormalize(localStorage.getItem('ops_role'));
                    loggedIn.value = true;
                }

                const userInitial = computed(() => currentUser.value ? currentUser.value[0].toUpperCase() : '?');

                const authHeaders = () => {
                    const h = { 'Content-Type': 'application/json' };
                    if (token.value) h['Authorization'] = 'Bearer ' + token.value;
                    return h;
                };

                // FIX-TOKEN: 统一的 fetch 配置 — credentials:'include' 确保 httpOnly Cookie 随请求发送
                const fetchOpts = (method, body) => {
                    const opts = { method: method || 'GET', headers: authHeaders(), credentials: 'include' };
                    if (body !== undefined) opts.body = JSON.stringify(body);
                    return opts;
                };

                const api = async (url) => {
                    try {
                        const r = await fetch(url, fetchOpts('GET'));
                        if (r.status === 401) { doLogout(); return null; }
                        return (await r.json()).data;
                    } catch { return null; }
                };
                const apiPut = async (url, body) => {
                    try {
                        const r = await fetch(url, fetchOpts('PUT', body));
                        if (r.status === 401) { doLogout(); return null; }
                        return (await r.json()).data;
                    } catch { return null; }
                };
                const apiPost = async (url, body) => {
                    try {
                        const r = await fetch(url, fetchOpts('POST', body || {}));
                        if (r.status === 401) { doLogout(); return null; }
                        return (await r.json()).data;
                    } catch { return null; }
                };
                const apiDel = async (url, body) => {
                    try {
                        const r = await fetch(url, fetchOpts('DELETE', body || undefined));
                        if (r.status === 401) { doLogout(); return null; }
                        return await r.json();
                    } catch { return null; }
                };
                const apiPatch = async (url) => {
                    try {
                        const r = await fetch(url, fetchOpts('PATCH'));
                        if (r.status === 401) { doLogout(); return null; }
                        return await r.json();
                    } catch { return null; }
                };

                const doLogin = async () => {
                    loginError.value = ''; loginLoading.value = true;
                    try {
                        // FIX-TOKEN: credentials:'include' 确保浏览器接收并存储服务端的 Set-Cookie（httpOnly）
                        const r = await fetch('/api/auth/login', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({ username: loginForm.username, password: loginForm.password }),
                            credentials: 'include'   // FIX-TOKEN: 关键 — 接收 httpOnly Cookie
                        });
                        const j = await r.json();
                        if (r.ok && j.data?.token) {
                            // FIX-TOKEN: Token 仍保存到 localStorage 作为降级兼容（Bearer Header 备用）
                            // 主要认证由 httpOnly Cookie 承载，localStorage 中的值仅在 Cookie 不可用时使用
                            token.value = j.data.token;
                            currentUser.value = loginForm.username;
                            currentUserRole.value = roleNormalize(j.data.role);
                            localStorage.setItem('ops_token', j.data.token);
                            localStorage.setItem('ops_user',  loginForm.username);
                            localStorage.setItem('ops_role', roleNormalize(j.data.role));
                            loggedIn.value = true;
                            toast('欢迎回来，' + loginForm.username + ' 👋', 'ok');
                            loadDashboard();
                        } else { loginError.value = j.message || '登录失败'; }
                    } catch { loginError.value = '网络错误'; }
                    loginLoading.value = false;
                };

                const doLogout = (silent) => {
                    fetch('/api/auth/logout', {
                        method: 'POST',
                        headers: authHeaders(),
                        credentials: 'include'
                    }).catch(()=>{});
                    token.value = ''; currentUser.value = ''; loggedIn.value = false;
                    // P0-2 fix: Cookie 由服务端清除，前端只清 username
                    localStorage.removeItem('ops_user');
                    // P2-6 fix: 非主动登出时给用户提示（如 token 过期被踢出）
                    if (!silent) {
                        try { toast('登录已过期，请重新登录', 'warn'); } catch(e) {}
                    }
                };

                // ==================== 路由 ====================
                const page    = ref('dashboard');
                const loading = ref(false);

                async function navigate(p, loadFn) {
                    page.value = p;
                    loading.value = true;
                    try { await loadFn(); } finally { loading.value = false; }
                }

                // ==================== 数据 ====================
                const overview = reactive({ agentTotal:0, agentOnline:0, exporterTotal:0, exporterUp:0, auditScore:0, auditGrade:'' });
                const appVersion    = ref('v2.22');
                const healthSummary = ref({ level: '', text: '' });
                const alertCount    = ref(0);
                const agents        = ref([]);
                const exporters     = ref([]);
                const alerts        = ref([]);
                const silences      = ref([]);
                const topology      = ref({});
                const audit         = ref({});
                const configs       = ref({});
                const tenants       = ref([]);
                const displayNames  = ref({});
                const editing       = ref(null);
                const editValue     = ref('');
                const notifyChannels = ref([]);
                const notifyMsg      = ref('');
                const newChannel     = reactive({ name:'', type:'', webhookUrl:'', triggerPolicy:'ALL', notifyOnFiring:true, notifyOnResolved:true, filterAlertName:'', filterServerName:'' });
                const configContent  = ref('');
                const configViewing  = ref('');
                const configLoading  = ref(false);
                const configCopied   = ref(false);

                const firingCount = computed(() => alerts.value.filter(a => a.state === 'FIRING').length);

                // ==================== 用户管理状态 ====================
                const users        = ref([]);
                const userRole     = ref('');       // 当前登录用户角色（从 API 获取）
                const newUser      = reactive({ username:'', password:'', role:'VIEWER', displayName:'' });
                const newUserError = ref('');
                const newUserLoading = ref(false);
                const changePwdForm  = reactive({ oldPassword:'', newPassword:'', confirm:'' });
                const changePwdError = ref('');
                const changePwdMsg   = ref('');

                // ==================== Sentinel 状态 ====================
                const incidents          = ref([]);
                const fingerprints       = ref([]);
                const sentinelServers    = ref([]);
                const incidentFilter     = ref('open');
                const selectedIncident   = ref(null);
                const showDiagnosePanel  = ref(false);
                const showFingerprints   = ref(false);
                const showRunbookTest    = ref(false);
                const diagnoseServerId   = ref('');
                const diagnoseResult     = ref(null);
                const diagnosing         = ref(false);
                const diagnosisFilter    = ref('');
                const fingerprintSearch  = ref('');
                const fpFilterLevel      = ref('');
                const exporterDiagResult = ref(null);
                const incFilter          = ref('');
                const runbookTesting     = ref(false);
                const runbookTestResult  = ref(null);
                const testStep = reactive({ name: 'Prometheus健康检查', type: 'HTTP', command: 'http://localhost:9090/-/healthy', timeoutSeconds: 5, failFast: true });

                // ─ Sentinel 执行历史展示优化 ─
                const incidentDetailTab  = ref('runbook');  // 'runbook' | 'history' | 'snapshot'
                const historyExpanded    = ref({});         // { resultIndex: bool } 控制步骤折叠
                const toggleHistoryStep  = (key) => { historyExpanded.value[key] = !historyExpanded.value[key]; };

                const sentinelOpenCount = computed(() =>
                    incidents.value.filter(i => i.status === 'OPEN' || i.status === 'INVESTIGATING').length
                );
                const filteredIncidents = computed(() => {
                    let list = incidentFilter.value === 'open'
                        ? incidents.value.filter(i => i.status === 'OPEN' || i.status === 'INVESTIGATING')
                        : incidents.value;
                    if (incFilter.value === 'P0') list = list.filter(i => i.severity === 'P0' && i.status !== 'CLOSED' && i.status !== 'RESOLVED');
                    if (incFilter.value === 'P1') list = list.filter(i => i.severity === 'P1' && i.status !== 'CLOSED' && i.status !== 'RESOLVED');
                    if (incFilter.value === 'INVESTIGATING') list = list.filter(i => i.status === 'INVESTIGATING');
                    if (incFilter.value === 'RESOLVED') list = list.filter(i => i.status === 'RESOLVED' && i.endTime && new Date(i.endTime).toDateString() === new Date().toDateString());
                    return list;
                });

                // v2.28: 过滤函数（完整空值防护）
                const fpFilterFunc = (fp) => {
                    if (!fp) return false;
                    const matchLevel = !fpFilterLevel.value || fp.severity === fpFilterLevel.value;
                    if (!matchLevel) return false;
                    const s = (fingerprintSearch.value || '').trim().toLowerCase();
                    if (!s) return true;
                    return (fp.name || '').toLowerCase().includes(s)
                        || (fp.id || '').toLowerCase().includes(s)
                        || (fp.description || '').toLowerCase().includes(s)
                        || (fp.severity || '').toLowerCase().includes(s);
                };
                const diagFilterFunc = (r) => {
                    if (!r) return false;
                    const s = (diagnosisFilter.value || '').trim().toLowerCase();
                    if (!s) return true;
                    return (r.faultName || '').toLowerCase().includes(s)
                        || (r.severity || '').toLowerCase().includes(s)
                        || (r.rootCause || '').toLowerCase().includes(s)
                        || (r.faultId || '').toLowerCase().includes(s);
                };

                // ==================== 数据加载 ====================
                const loadDashboard = async () => {
                    const [ov, al, dn, st, hc] = await Promise.all([
                        api('/api/overview'), api('/api/alert-center/active'), api('/api/display-name'), api('/api/system/status'), api('/api/health')
                    ]);
                    if (ov) Object.assign(overview, ov);
                    alerts.value = al || []; alertCount.value = al?.length || 0;
                    if (dn) displayNames.value = dn;
                    if (hc && hc.version) appVersion.value = hc.version;
                    // v2.19: 计算健康摘要
                    if (st) {
                        const issues = [];
                        const cpu = st.cpuUsage || -1, mem = st.memoryUsage || -1, disk = st.diskUsage || -1;
                        const up = overview.exporterUp || 0, total = overview.exporterTotal || 0;
                        if (cpu > 85) issues.push('CPU 使用率过高 (' + cpu.toFixed(1) + '%)，建议检查高负载进程');
                        else if (cpu > 60) issues.push('CPU 使用率偏高 (' + cpu.toFixed(1) + '%)');
                        if (mem > 90) issues.push('内存即将耗尽 (' + mem.toFixed(1) + '%)，建议释放内存或扩容');
                        else if (mem > 75) issues.push('内存使用率偏高 (' + mem.toFixed(1) + '%)');
                        if (disk > 85) issues.push('磁盘空间不足 (' + disk.toFixed(1) + '%)，建议清理或扩容');
                        else if (disk > 75) issues.push('磁盘使用率偏高 (' + disk.toFixed(1) + '%)');
                        if (up < total && total > 0) issues.push((total - up) + ' 个 Exporter 离线，建议检查服务状态');
                        if (issues.length === 0) healthSummary.value = { level: 'ok', text: '✅ 系统运行正常，所有指标在安全范围内' };
                        else if (issues.some(i => i.includes('耗尽') || i.includes('不足') || i.includes('离线'))) healthSummary.value = { level: 'critical', text: '🔴 ' + issues.join('；') };
                        else healthSummary.value = { level: 'warn', text: '⚠️ ' + issues.join('；') };
                    }
                };
                const loadAgents    = async () => { agents.value    = (await api('/api/agents'))   || []; };
                const deleteAgent   = async (id) => {
                    if (!confirm('确定移除 Agent ' + id + '？')) return;
                    const r = await apiDel('/api/agents/' + id);
                    if (r) { toast('Agent 已移除', 'ok'); loadAgents(); } else toast('移除失败', 'err');
                };
                const loadExporters = async () => {
                    exporters.value = (await api('/api/exporters')) || [];
                    // v2.21: 清除 checked 状态
                    exporters.value.forEach(e => { if (e.checked === undefined) e.checked = false; });
                    const dn = await api('/api/display-name'); if (dn) displayNames.value = dn;
                    loadTemplates();
                };
                const loadAlerts   = async () => {
                    alerts.value   = (await api('/api/alert-center/active'))   || [];
                    silences.value = (await api('/api/alert-center/silences')) || [];
                };
                const loadTopology      = async () => { topology.value = (await api('/api/topology'))         || {}; };
                const loadAudit         = async () => { audit.value    = (await api('/api/audit'))             || {}; };
                const loadConfigs       = async () => { configs.value  = (await api('/api/platform/config'))  || {}; };
                // v2.22: 租户管理
                const showNewTenant = ref(false);
                const newTenant = reactive({ tenantId: '', displayName: '', quotaMaxAgents: 100, quotaMaxExporters: 500 });
                const loadTenants = async () => {
                    tenants.value = (await api('/api/platform/tenants')) || [];
                    tenants.value.forEach(t => { if (t._editing === undefined) t._editing = false; });
                };
                const createTenant = async () => {
                    if (!newTenant.tenantId) { toast('请输入租户 ID', 'warn'); return; }
                    const r = await apiPost('/api/platform/tenants', newTenant);
                    if (r) { toast('租户已创建', 'ok'); showNewTenant.value = false; loadTenants(); }
                    else toast('创建失败', 'err');
                };
                const saveTenant = async (t) => {
                    const r = await apiPut('/api/platform/tenants/' + t.tenantId, {
                        displayName: t.displayName, status: t.status,
                        quotaMaxAgents: t.quotaMaxAgents, quotaMaxExporters: t.quotaMaxExporters
                    });
                    if (r) toast('已更新', 'ok'); else toast('更新失败', 'err');
                };
                const deleteTenant = async (id) => {
                    if (!confirm('确定删除租户 ' + id + '？')) return;
                    const r = await apiDel('/api/platform/tenants/' + id);
                    if (r) { toast('已删除', 'ok'); loadTenants(); } else toast('删除失败', 'err');
                };
                const loadNotifyChannels = async () => { notifyChannels.value = (await api('/api/v2/notifications/channels')) || []; };
                const loadUsers = async () => {
                    const res = await api('/api/auth/users');
                    users.value = res || [];
                };

                // ==================== Sentinel 加载 ====================
                const loadSentinel = async () => {
                    const [all, fps, srvs] = await Promise.all([
                        api('/api/sentinel/incidents'),
                        api('/api/sentinel/fingerprints'),
                        api('/api/servers')
                    ]);
                    incidents.value   = all  || [];
                    fingerprints.value = fps  || [];
                    sentinelServers.value = (srvs || []).filter(s => s.id !== 'local');
                };

                const triggerDiagnose = async () => {
                    if (!diagnoseServerId.value) return;
                    diagnosing.value = true;
                    diagnoseResult.value = null;
                    try {
                        const r = await fetch('/api/sentinel/diagnose/' + diagnoseServerId.value, {
                            method: 'POST', headers: authHeaders()
                        });
                        const j = await r.json();
                        if (j.code === 200) {
                            diagnoseResult.value = j.data || [];
                            toast(j.data && j.data.length > 0 ? '发现 ' + j.data.length + ' 个故障指纹匹配' : '✅ 未检测到异常', j.data && j.data.length > 0 ? 'warn' : 'ok');
                            await loadSentinel(); // 刷新 Incident 列表
                        } else {
                            toast('诊断失败: ' + (j.message || '未知错误'), 'err');
                        }
                    } catch (e) {
                        toast('请求失败: ' + e.message, 'err');
                    }
                    diagnosing.value = false;
                };

                const investigateIncident = async (id) => {
                    const r = await fetch('/api/sentinel/incidents/' + id + '/investigate', {
                        method: 'POST', headers: authHeaders()
                    });
                    const j = await r.json();
                    if (j.code === 200) { toast('已标记为调查中', 'ok'); await loadSentinel(); }
                    else toast(j.message, 'err');
                };

                const resolveIncident = async (id) => {
                    const notes = prompt('填写解决备注（可选）：') || '';
                    const r = await fetch('/api/sentinel/incidents/' + id + '/resolve', {
                        method: 'POST', headers: authHeaders(),
                        body: JSON.stringify({ notes })
                    });
                    const j = await r.json();
                    if (j.code === 200) { toast('✅ Incident 已解决', 'ok'); await loadSentinel(); selectedIncident.value = null; }
                    else toast(j.message, 'err');
                };

                const executeRunbook = async (inc) => {
                    if (!confirm('确认执行 Runbook？\n\n故障: ' + inc.faultName + '\n服务器: ' + (inc.serverName||inc.serverId) + '\n\n⚠️ 此操作将执行自动化步骤，请确认服务状态后再执行。')) return;
                    const r = await fetch('/api/sentinel/incidents/' + inc.id + '/execute', {
                        method: 'POST', headers: authHeaders(), body: JSON.stringify({})
                    });
                    const j = await r.json();
                    if (j.code === 200) {
                        const res = j.data;
                        toast((res.overallSuccess ? '✅ Runbook 执行成功' : '⚠️ Runbook 执行完成（部分步骤失败）') + ' ' + res.successCount + '/' + (res.successCount + res.failureCount) + ' 步成功', res.overallSuccess ? 'ok' : 'warn', 5000);
                        await loadSentinel();
                        // 执行后自动切换到历史 tab，展示刚刚的执行结果
                        selectedIncident.value = incidents.value.find(i => i.id === inc.id) || null;
                        if (selectedIncident.value) incidentDetailTab.value = 'history';
                    } else {
                        toast('执行失败: ' + (j.message || '未知错误'), 'err');
                    }
                };

                const viewIncidentDetail = (inc) => {
                    selectedIncident.value = inc;
                    incidentDetailTab.value = 'runbook';
                    historyExpanded.value = {};
                };

                const closeIncidentDetail = () => {
                    selectedIncident.value = null;
                    incidentDetailTab.value = 'runbook';
                    historyExpanded.value = {};
                };

                // 格式化执行历史时间轴
                const formatExecTime = (ts) => {
                    if (!ts) return '-';
                    return new Date(ts).toLocaleString('zh-CN', {
                        month: '2-digit', day: '2-digit',
                        hour: '2-digit', minute: '2-digit', second: '2-digit'
                    });
                };

                // 计算 Incident 持续时长（人性化）
                const formatDuration = (ms) => {
                    if (!ms || ms <= 0) return '-';
                    const s = Math.floor(ms / 1000);
                    if (s < 60)   return s + '秒';
                    if (s < 3600) return Math.floor(s / 60) + '分钟';
                    return Math.floor(s / 3600) + '小时' + Math.floor((s % 3600) / 60) + '分钟';
                };

                // 步骤类型图标映射
                const stepTypeIcon = (type) => {
                    const m = { HTTP: '🌐', SCRIPT: '📜', SSH: '🔑', LOG: '⚖' };
                    return m[(type||'').toUpperCase()] || '⚙';
                };

                const testRunbookStep = async () => {
                    if (!testStep.command) { toast('请填写命令/URL', 'warn'); return; }
                    runbookTesting.value = true;
                    runbookTestResult.value = null;
                    const r = await fetch('/api/sentinel/runbook/execute', {
                        method: 'POST', headers: authHeaders(),
                        body: JSON.stringify({ name: '测试执行', steps: [{ ...testStep }] })
                    });
                    const j = await r.json();
                    runbookTesting.value = false;
                    if (j.code === 200) { runbookTestResult.value = j.data; }
                    else toast('执行失败: ' + j.message, 'err');
                };

                const isToday = (ts) => {
                    if (!ts) return false;
                    const d = new Date(ts);
                    const now = new Date();
                    return d.getDate() === now.getDate() && d.getMonth() === now.getMonth() && d.getFullYear() === now.getFullYear();
                };

                // ==================== 操作 ====================
                const ackAlert = async (id) => {
                    await apiPost('/api/alert-center/' + id + '/ack');
                    toast('告警已确认', 'ok'); await loadAlerts();
                };

                const goClassic = () => { window.location.href = '/'; };
                const goGrafana = () => { window.open('http://127.0.0.1:3000', '_blank'); };

                const viewConfig = async (name) => {
                    if (configViewing.value === name) { configContent.value = ''; configViewing.value = ''; return; }
                    configViewing.value = name; configContent.value = ''; configLoading.value = true;
                    try {
                        const r = await fetch('/api/platform/config/' + name, { headers: authHeaders() });
                        if (r.status === 401) { doLogout(); return; }
                        const j = await r.json();
                        configContent.value = j.data || '（配置内容为空）';
                    } catch (e) { configContent.value = '加载失败: ' + e.message; }
                    finally { configLoading.value = false; }
                };

                const copyConfig = async () => {
                    if (!configContent.value) return;
                    try {
                        await navigator.clipboard.writeText(configContent.value);
                        configCopied.value = true; toast('已复制到剪贴板', 'ok');
                        setTimeout(() => configCopied.value = false, 2500);
                    } catch { toast('复制失败，请手动选中文本', 'warn'); }
                };

                const getDisplayName = (kind, name) => displayNames.value[kind + ':' + name] || name;
                const startEdit      = (id, current) => { editing.value = id; editValue.value = current; };
                const saveDisplayName = async (kind, name) => {
                    await apiPut('/api/display-name/' + kind + '/' + name, { displayName: editValue.value });
                    editing.value = null; toast('显示名称已更新', 'ok');
                    const dn = await api('/api/display-name'); if (dn) displayNames.value = dn;
                };
                // v2.21: 批量操作
                const batchServerId = ref('local');
                const batchTypes = ref([]);
                const batchCustomPorts = reactive({});
                const batchCheckAll = reactive({});
                const showBatchRegister = ref(false);
                const batchRegisterLoading = ref(false);
                const batchResults = ref([]);
                const exporterTemplates = ref([]);

                const loadTemplates = async () => {
                    exporterTemplates.value = (await api('/api/exporters/templates')) || [];
                };
                const batchSelectAll = () => {
                    if (batchTypes.value.length === exporterTemplates.value.length) {
                        batchTypes.value = [];
                    } else {
                        batchTypes.value = exporterTemplates.value.map(t => t.type);
                    }
                };

                const batchRegister = async () => {
                    if (!batchTypes.value.length) { toast('请至少选择一种 Exporter 类型', 'warn'); return; }
                    batchRegisterLoading.value = true; batchResults.value = [];
                    const body = { serverId: batchServerId.value, exporters: batchTypes.value.map(t => {
                        const exp = { type: t };
                        const port = batchCustomPorts[t];
                        if (port) { exp.targetAddress = port.includes(':') ? port : ('127.0.0.1:' + port); }
                        return exp;
                    })};
                    const res = await apiPost('/api/exporters/batch-register', body);
                    if (res) {
                        batchResults.value = res.results || [];
                        const ok = batchResults.value.filter(r => r.status === 'ok').length;
                        const fail = batchResults.value.length - ok;
                        toast('批量注册完成: ' + ok + ' 成功, ' + fail + ' 失败', fail > 0 ? 'warn' : 'ok', 5000);
                        if (ok > 0) loadExporters();
                    } else { toast('批量注册失败', 'err'); }
                    batchRegisterLoading.value = false;
                };

                const batchUnregisterSelected = async () => {
                    const ids = exporters.value.filter(e => e.checked).map(e => e.id);
                    if (!ids.length) { toast('请先勾选要注销的 Exporter', 'warn'); return; }
                    if (!confirm('确定要注销 ' + ids.length + ' 个 Exporter？此操作不可撤销。')) return;
                    const res = await apiDel('/api/exporters/batch', { exporterIds: ids });
                    if (res) {
                        const ok = (res.results || []).filter(r => r.status === 'ok').length;
                        toast('批量注销: ' + ok + '/' + ids.length + ' 成功', ok === ids.length ? 'ok' : 'warn');
                        loadExporters();
                    } else { toast('批量注销失败', 'err'); }
                };

                // v2.19: Exporter 全链路诊断
                const diagnoseExporter = async (exporterId) => {
                    toast('正在诊断 ' + exporterId + ' ...', 'info');
                    const result = await api('/api/exporters/' + exporterId + '/health-check');
                    if (!result) { toast('诊断失败：网络错误', 'err'); return; }
                    // v2.31: 使用模态弹窗展示，代替 toast 堆积
                    exporterDiagResult.value = {
                        exporterId: exporterId,
                        summary: result.summary,
                        checks: result.checks,
                        allOk: result.summary === '全链路正常'
                    };
                    toast(result.summary === '全链路正常' ? '✅ 全链路正常' : '⚠ 存在问题，请查看详情',
                          result.summary === '全链路正常' ? 'ok' : 'warn');
                };
                // v2.17: Exporter 标签编辑
                const editLabels = async (exporterId, currentProject, currentService) => {
                    const p = prompt('项目名称（project）：', currentProject || '');
                    if (p === null) return;
                    const s = prompt('服务名称（service）：', currentService || '');
                    if (s === null) return;
                    const result = await apiPut('/api/exporters/' + exporterId + '/labels', { project: p, service: s });
                    if (result) { toast('标签已更新', 'ok'); await loadExporters(); }
                    else { toast('标签更新失败', 'err'); }
                };

                const timeAgo = (ts) => {
                    if (!ts) return '-';
                    const s = Math.floor((Date.now() - ts) / 1000);
                    if (s < 60) return s + '秒前';
                    if (s < 3600) return Math.floor(s/60) + '分钟前';
                    if (s < 86400) return Math.floor(s/3600) + '小时前';
                    return Math.floor(s/86400) + '天前';
                };
                const formatBytes = (mb) => { if (!mb) return '-'; return mb >= 1024 ? (mb/1024).toFixed(1)+' GB' : mb+' MB'; };
                const severityColor = (s) => s === 'critical' ? 'badge-down' : 'badge-warn';

                // ==================== 用户管理操作 ====================
                const createUser = async () => {
                    newUserError.value = ''; newUserLoading.value = true;
                    if (!newUser.username || !newUser.password) { newUserError.value = '用户名和密码不能为空'; newUserLoading.value = false; return; }
                    if (newUser.password.length < 6) { newUserError.value = '密码长度不能少于6位'; newUserLoading.value = false; return; }
                    const r = await fetch('/api/auth/users', {
                        method: 'POST', headers: authHeaders(),
                        body: JSON.stringify({ username: newUser.username, password: newUser.password, role: newUser.role, displayName: newUser.displayName || newUser.username })
                    });
                    const j = await r.json();
                    if (r.ok && j.code === 200) {
                        toast('用户 ' + newUser.username + ' 已创建', 'ok');
                        Object.assign(newUser, { username:'', password:'', role:'VIEWER', displayName:'' });
                        await loadUsers();
                    } else { newUserError.value = j.message || '创建失败'; }
                    newUserLoading.value = false;
                };

                const deleteUser = async (id, username) => {
                    if (!confirm('确认删除用户「' + username + '」？此操作不可撤销。')) return;
                    const r = await fetch('/api/auth/users/' + id, { method: 'DELETE', headers: authHeaders() });
                    const j = await r.json();
                    if (r.ok && j.code === 200) { toast('用户已删除', 'ok'); await loadUsers(); }
                    else { toast(j.message || '删除失败', 'err'); }
                };

                const changePassword = async () => {
                    changePwdError.value = ''; changePwdMsg.value = '';
                    if (!changePwdForm.oldPassword || !changePwdForm.newPassword) { changePwdError.value = '请填写所有字段'; return; }
                    if (changePwdForm.newPassword !== changePwdForm.confirm) { changePwdError.value = '两次密码不一致'; return; }
                    if (changePwdForm.newPassword.length < 6) { changePwdError.value = '新密码不能少于6位'; return; }
                    const r = await fetch('/api/auth/change-password', {
                        method: 'POST', headers: authHeaders(),
                        body: JSON.stringify({ oldPassword: changePwdForm.oldPassword, newPassword: changePwdForm.newPassword })
                    });
                    const j = await r.json();
                    if (r.ok && j.code === 200) {
                        changePwdMsg.value = '密码已修改，请重新登录';
                        setTimeout(() => doLogout(true), 1500);
                    } else { changePwdError.value = j.message || '修改失败'; }
                };

                // ==================== 通知渠道 ====================
                const createNotifyChannel = async () => {
                    notifyMsg.value = '';
                    if (!newChannel.name || !newChannel.type || !newChannel.webhookUrl) { toast('请填写名称、类型和 URL', 'warn'); return; }
                    const payload = { ...newChannel };
                    if (payload.triggerPolicy !== 'CUSTOM') { delete payload.filterAlertName; delete payload.filterServerName; }
                    const result = await apiPost('/api/v2/notifications/channels', payload);
                    if (result) {
                        toast('通知渠道已创建', 'ok');
                        Object.assign(newChannel, { name:'', type:'', webhookUrl:'', triggerPolicy:'ALL', notifyOnFiring:true, notifyOnResolved:true, filterAlertName:'', filterServerName:'' });
                        await loadNotifyChannels();
                    }
                };
                const testNotifyChannel   = async (id, name) => { toast('发送测试通知到「'+name+'」...','info'); await apiPost('/api/v2/notifications/test/'+id); setTimeout(()=>toast('测试通知已发送','ok'),800); };
                const toggleNotifyChannel = async (id, enabled) => { await apiPatch('/api/v2/notifications/channels/'+id+'/toggle?enabled='+enabled); toast(enabled?'渠道已启用':'渠道已禁用', enabled?'ok':'warn'); await loadNotifyChannels(); };
                const deleteNotifyChannel = async (id, name) => { if (!confirm('确认删除「'+name+'」？')) return; await apiDel('/api/v2/notifications/channels/'+id); toast('渠道已删除','ok'); await loadNotifyChannels(); };

                // ==================== 自动刷新 ====================
                let refreshTimer = null;
                onMounted(() => {
                    if (loggedIn.value) {
                        loadDashboard();
                        refreshTimer = setInterval(() => {
                            api('/api/alert-center/active').then(al => { if (al) { alerts.value = al; alertCount.value = al.length; } });
                            if (page.value === 'dashboard') loadDashboard();
                            if (page.value === 'sentinel') loadSentinel();
                        }, 12000);
                    }
                });
                onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer); });

                return {
                    toasts, toast, sidebarCollapsed, sidebarPinned, toggleSidebar, onSidebarEnter, onSidebarLeave, currentTheme, toggleTheme,
                    loggedIn, currentUser, currentUserRole, userInitial, loginForm, loginError, loginLoading, doLogin, doLogout,
                    page, loading, navigate,
                    overview, healthSummary, appVersion, alertCount, firingCount, agents, exporters, alerts, silences, topology, audit, configs, tenants,
                    displayNames, editing, editValue,
                    notifyChannels, notifyMsg, newChannel,
                    configContent, configViewing, configLoading, configCopied,
                    loadDashboard, loadAgents, deleteAgent, loadExporters, loadAlerts, loadTopology, loadAudit, loadConfigs, loadTenants, loadNotifyChannels,
                    showNewTenant, newTenant, createTenant, saveTenant, deleteTenant,
                    ackAlert, goClassic, goGrafana, viewConfig, copyConfig,
                    getDisplayName, startEdit, saveDisplayName, editLabels, diagnoseExporter,
                    batchServerId, batchTypes, batchCustomPorts, batchCheckAll, showBatchRegister, batchRegisterLoading, batchResults,
                    exporterTemplates, batchRegister, batchUnregisterSelected, batchSelectAll, loadTemplates,
                    timeAgo, formatBytes, severityColor,
                    createNotifyChannel, testNotifyChannel, toggleNotifyChannel, deleteNotifyChannel,
                    users, newUser, newUserError, newUserLoading, loadUsers, createUser, deleteUser,
                    changePwdForm, changePwdError, changePwdMsg, changePassword,
                    // Sentinel
                    incidents, fingerprints, sentinelServers, incidentFilter, selectedIncident,
                    showDiagnosePanel, showFingerprints, showRunbookTest,
                    diagnoseServerId, diagnoseResult, diagnosing, diagnosisFilter, fingerprintSearch, fpFilterLevel, incFilter, fpFilterFunc, diagFilterFunc, exporterDiagResult,
                    runbookTesting, runbookTestResult, testStep,
                    sentinelOpenCount, filteredIncidents,
                    loadSentinel, triggerDiagnose, investigateIncident, resolveIncident,
                    executeRunbook, viewIncidentDetail, closeIncidentDetail, testRunbookStep, isToday,
                    // Sentinel 执行历史展示
                    incidentDetailTab, historyExpanded, toggleHistoryStep,
                    formatExecTime, formatDuration, stepTypeIcon,
                };
            }
        });

        // 清空占位内容，挂载 Vue 应用
        const mountEl = document.getElementById('app');
        mountEl.innerHTML = '';
        app.mount(mountEl);
    });
})();