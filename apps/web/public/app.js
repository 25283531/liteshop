(function () {
    "use strict";
    var state = {member: null, types: [], offset: 0, pending: null, busy: false, form: null, opener: null, detailRequest: 0, searchRequest: 0, searchTimer: null};
    var bridge = window.LiteShopTerminal;
    function el(id) { return document.getElementById(id); }
    function esc(value) { return String(value === null || value === undefined ? "" : value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;"); }
    function storageGet(key) { try { return sessionStorage.getItem(key); } catch (e) { return null; } }
    function storageSet(key, value) { sessionStorage.setItem(key, value); }
    function notice(message, error) { el("message").textContent = message; el("message").className = error ? "error" : ""; el("message").style.display = "block"; }
    function money(value) { var a = Math.abs(value); return (value < 0 ? "-" : "") + Math.floor(a / 100) + "." + ("0" + a % 100).slice(-2); }
    function date(value) { var d = new Date(value); return (d.getMonth() + 1) + "/" + d.getDate() + " " + ("0" + d.getHours()).slice(-2) + ":" + ("0" + d.getMinutes()).slice(-2); }
    var names = {RECHARGE: "充值", CONSUME: "消费", GIFT: "赠送", ADJUST: "调整", REFUND: "退款", CREDIT_TIMES: "充次", DEDUCT_TIMES: "扣次"};
    var errors = {INSUFFICIENT_BALANCE: "余额或剩余次数不足", INSUFFICIENT_POINTS: "积分不足", INVALID_INPUT: "请检查填写内容", CONSTRAINT_VIOLATION: "资料冲突，请检查手机号是否已存在", REFUND_EXCEEDED: "退款超过原消费剩余可退数量", MEMBER_INACTIVE: "会员已停用", CARD_INACTIVE: "卡已停用、挂失或过期", VERSION_CONFLICT: "资料已发生变化，请刷新后再操作", IDEMPOTENCY_CONFLICT: "请求编号冲突，请核查流水", NOT_FOUND: "记录不存在"};
    var bridgeCallbacks = {}, bridgeSequence = 0, pageId = String(new Date().getTime());
    window.LiteShopReceive = function (id, code, text) {
        if (bridgeCallbacks[id]) { bridgeCallbacks[id](code, text); }
    };
    function api(method, path, body, done) {
        var completed = false, timer, requestId;
        function finish(error, data, uncertain) {
            if (completed) { return; }
            completed = true;
            if (timer) { window.clearTimeout(timer); }
            if (requestId) { delete bridgeCallbacks[requestId]; }
            done(error, data, uncertain);
        }
        function receive(code, text) {
            var response;
            try { response = JSON.parse(text); if (typeof response.ok !== "boolean" || (!response.ok && (!response.error || typeof response.error.code !== "string"))) { throw new Error(); } }
            catch (e) { finish("服务响应无法识别，请重试", null, true); return; }
            if (!response.ok) {
                if (response.error.code === "UNAUTHORIZED") { el("login").hidden = !!bridge; }
                finish(errors[response.error.code] || response.error.message, null, response.error.definitive !== true);
            } else { finish(null, response.data, false); }
        }
        function offline() { finish(bridge ? "设备本地数据库响应中断，请重载页面后重试原请求" : "开发服务连接中断，请检查测试服务", null, true); }
        if (bridge) {
            requestId = pageId + "-" + (++bridgeSequence);
            bridgeCallbacks[requestId] = receive;
            timer = window.setTimeout(offline, 25000);
            try { bridge.request(requestId, method, path, body ? JSON.stringify(body) : ""); }
            catch (e) { offline(); }
            return;
        }
        var xhr = new XMLHttpRequest();
        xhr.open(method, "/api/v1/" + path, true);
        xhr.timeout = 20000;
        xhr.setRequestHeader("Authorization", "Bearer " + (storageGet("liteshop.token") || ""));
        if (body) { xhr.setRequestHeader("Content-Type", "application/json"); }
        xhr.onload = function () { receive(xhr.status, xhr.responseText); };
        xhr.onerror = xhr.ontimeout = offline;
        xhr.send(body ? JSON.stringify(body) : null);
    }
    function status() {
        api("GET", "status", null, function (error, data) {
            el("connection").innerHTML = error ? '<span class="dot dot-error"></span>本地账本不可用' : '<span class="dot dot-ok"></span>本地账本已连接';
            if (error) { notice(error, true); return; }
            el("message").style.display = "none";
            el("login").hidden = true;
            el("shop-name").textContent = data.shop.name;
            el("member-count").textContent = data.member_count;
            el("event-count").textContent = data.pending_events;
            var syncNames = {NOT_CONFIGURED: "未配置", SYNCING: "同步中", OFFLINE: "离线待同步", PENDING: "待同步", SYNCED: "已同步"};
            el("cloud-sync").textContent = syncNames[data.cloud_sync] || "状态未知";
            var cloudStates = {NOT_CONFIGURED: {text: "云端服务未配置", cls: "dot-gray"}, SYNCING: {text: "云端服务已连接", cls: "dot-ok"}, OFFLINE: {text: "云端服务未连接", cls: "dot-error"}, PENDING: {text: "云端服务已连接", cls: "dot-ok"}, SYNCED: {text: "云端服务已连接", cls: "dot-ok"}};
            var cloud = cloudStates[data.cloud_sync] || cloudStates.NOT_CONFIGURED;
            el("cloud-connection").innerHTML = '<span class="dot ' + cloud.cls + '"></span>' + cloud.text;
        });
    }
    function search() {
        var seq = ++state.searchRequest;
        api("GET", "members?q=" + encodeURIComponent(el("search").value) + "&offset=" + state.offset, null, function (error, data) {
            if (seq !== state.searchRequest) { return; }
            if (error) { notice(error, true); return; }
            el("member-list").innerHTML = data.length ? data.map(function (m) { return '<button class="member-item' + (state.member && state.member.member.id === m.id ? ' selected' : '') + '" data-member="' + esc(m.id) + '"><strong>' + esc(m.name) + (m.status ? '' : ' · 已停用') + '</strong><span>' + esc(m.phone || "未留手机号") + '</span></button>'; }).join("") : '<p class="empty">没有找到会员</p>';
            el("prev").disabled = state.offset === 0; el("next").disabled = data.length < 50;
            el("page").textContent = 1 + state.offset / 50;
        });
    }
    function loadMember(id) {
        var seq = ++state.detailRequest;
        api("GET", "members/" + encodeURIComponent(id), null, function (error, data) {
            if (seq !== state.detailRequest) { return; }
            if (error) { notice(error, true); return; }
            state.member = data; render(); search();
        });
    }
    function button(action, label, id) { return '<button class="secondary" data-action="' + action + '" data-id="' + esc(id || "") + '">' + label + '</button>'; }
    function render() {
        var d = state.member, m = d.member;
        var html = '<h2 class="member-title">' + esc(m.name) + '<span class="badge">' + (m.status ? "正常" : "已停用") + '</span></h2><p class="meta">' + esc(m.phone || "未留手机号") + ' · ' + esc(m.member_no) + ' · 邀请注册 ' + (m.invited_count || 0) + ' 人</p><p>' + esc(m.remark) + (m.inviter_name ? '<br>邀请人：' + esc(m.inviter_name) : '') + '</p>';
        html += '<div class="toolbar">' + button("edit", "编辑会员") + button("member-status", m.status ? "停用会员" : "启用会员") + button("open", "＋ 开卡") + button("points", "积分 · " + d.points.balance) + button("delete-member", "删除会员") + '</div><h2>会员卡包</h2>';
        html += d.cards.length ? d.cards.map(function (c) {
            var stored = c.mode === "STORED";
            return '<div class="card"><h3>' + (stored ? "储值卡" : "次卡") + ' <small>' + esc({ACTIVE: "正常", LOST: "已挂失", DISABLED: "已停用"}[c.status] || c.status) + '</small></h3><strong>' + (stored ? '¥ ' + money(c.balance) : c.remaining_times + ' 次') + '</strong><small>' + esc(c.card_no) + '</small><div class="toolbar">' + button(stored ? "RECHARGE" : "CREDIT_TIMES", stored ? "充值" : "充次", c.id) + button(stored ? "CONSUME" : "DEDUCT_TIMES", stored ? "消费" : "扣次", c.id) + button("card-status", "卡片状态", c.id) + '</div></div>';
        }).join("") : '<p class="empty">尚未开卡，点击「开卡」开始</p>';
        html += '<h2>最近交易 <small>最近 50 笔</small></h2><div class="table-wrap"><table><thead><tr><th>时间 / 类型</th><th>变动</th><th>余额 / 次数</th><th>备注 / 操作</th></tr></thead><tbody>';
        html += d.transactions.map(function (t) {
            return '<tr><td>' + date(t.created_at) + ' ' + esc(names[t.kind] || t.kind) + '</td><td class="' + (t.amount < 0 || t.times < 0 ? "negative" : "positive") + '">' + (t.times ? t.times + " 次" : "¥ " + money(t.amount)) + '</td><td>¥ ' + money(t.balance_after) + ' / ' + t.times_after + '</td><td>' + esc(t.remark) + ' ' + ((t.kind === "CONSUME" || t.kind === "DEDUCT_TIMES") ? button("refund", "退款", t.id) : "") + '</td></tr>';
        }).join("") || '<tr><td colspan="4">暂无交易</td></tr>';
        html += '</tbody></table></div><h2>积分流水 <small>最近 50 笔</small></h2><div class="table-wrap"><table><thead><tr><th>时间</th><th>变动</th><th>剩余积分</th><th>原因</th></tr></thead><tbody>';
        html += d.points_transactions.map(function (p) { return '<tr><td>' + date(p.created_at) + '</td><td>' + p.points + '</td><td>' + p.balance_after + '</td><td>' + esc(p.remark) + '</td></tr>'; }).join("") || '<tr><td colspan="4">暂无积分流水</td></tr>';
        el("detail").innerHTML = html + '</tbody></table></div>';
    }
    function field(id, label, value, required) { return '<label for="f-' + id + '">' + label + '</label><input id="f-' + id + '" value="' + esc(value || "") + '" maxlength="' + (id === "remark" ? 1000 : id === "name" ? 200 : 128) + '"' + (required ? " required" : "") + ' autocomplete="off">'; }
    function select(id, label, values, current) { return '<label for="f-' + id + '">' + label + '</label><select id="f-' + id + '">' + values.map(function (v) { return '<option value="' + esc(v[0]) + '"' + (v[0] === current ? " selected" : "") + '>' + esc(v[1]) + '</option>'; }).join("") + '</select>'; }
    function value(id) { return el("f-" + id).value; }
    function openForm(action, id) {
        if (state.pending || state.busy) { notice("请先重试待确认的原请求", true); return; }
        var m = state.member ? state.member.member : null, fields = "", title = "", card = null, tx = null;
        if (action !== "new" && !m) { return; }
        if (state.member) {
            state.member.cards.forEach(function (c) { if (c.id === id) { card = c; } });
            state.member.transactions.forEach(function (t) { if (t.id === id) { tx = t; } });
        }
        if (action === "new" || action === "edit") { title = action === "new" ? "新增会员" : "编辑会员"; fields = field("name", "会员姓名", action === "edit" ? m.name : "", true) + field("phone", "手机号（可选）", action === "edit" ? m.phone : "") + field("inviter_name", "邀请人（可稍后补填）", action === "edit" ? m.inviter_name : "") + field("remark", "备注", action === "edit" ? m.remark : ""); }
        else if (action === "member-status") { title = m.status ? "停用会员" : "启用会员"; fields = '<p>停用后将无法开卡和进行交易，已有账本记录保留。</p>'; }
        else if (action === "delete-member") { title = "删除会员数据"; fields = '<p>删除后会员将从本地搜索中隐藏，账本和审计记录仍保留。此操作需要本地设置密码。</p>' + '<label for="f-password">本地设置密码</label><input id="f-password" type="password" required autocomplete="current-password">'; }
        else if (action === "open") { title = "开通会员卡"; fields = select("type", "卡类型", state.types.map(function (t) { return [t.id, t.mode === "STORED" ? "储值卡" : "次卡"]; })); }
        else if (action === "points") { title = "调整积分"; fields = field("quantity", "积分变动（增加填正数，扣除填负数）", "", true) + field("remark", "调整原因", "", true); }
        else if (action === "card-status") { title = "修改卡片状态"; fields = select("status", "卡片状态", [["ACTIVE", "正常"], ["LOST", "挂失"], ["DISABLED", "停用"]], card.status); }
        else {
            if (action === "refund") {
                state.member.cards.forEach(function (c) { if (c.id === tx.card_id) { card = c; } });
                title = "退回原消费"; fields = '<p>原消费：' + (tx.times ? -tx.times + ' 次' : '¥ ' + money(-tx.amount)) + '。已退部分由账本核验。</p>';
            } else { title = names[action]; }
            if (!card) { return; }
            fields += field("quantity", card.mode === "STORED" ? "金额（元，最多两位小数）" : "次数（正整数）", "", true) + field("remark", "备注", "");
        }
        state.form = {action: action, member: m, card: card, tx: tx};
        state.opener = document.activeElement;
        el("fields").innerHTML = fields; el("form-title").textContent = title;
        el("form-context").textContent = action === "new" ? "保存后可为会员开卡。" : m.name + " · " + (m.phone || "未留手机号");
        el("form-error").textContent = ""; el("modal").hidden = false;
        var first = el("fields").querySelector("input,select");
        (first || el("submit")).focus();
    }
    function closeForm() { if (state.busy) { return; } el("modal").hidden = true; if (state.opener) { state.opener.focus(); } }
    function quantity(stored, signed) {
        var raw = value("quantity").replace(/^\s+|\s+$/g, "");
        if (!(stored ? /^\d+(\.\d{1,2})?$/ : signed ? /^-?\d+$/ : /^\d+$/).test(raw)) { throw new Error("请输入正确的金额或整数数量"); }
        var parts = raw.split("."), n = stored ? Number(parts[0]) * 100 + Number(((parts[1] || "") + "00").slice(0, 2)) : Number(raw);
        if (!isFinite(n) || n === 0 || Math.abs(n) > 2000000000) { throw new Error("数量须非零且在允许范围内"); }
        return n;
    }
    function pendingUI() {
        el("pending").hidden = !state.pending; el("retry").disabled = state.busy;
        el("pending-description").textContent = state.pending ? state.pending.description : "";
    }
    function submitPending() {
        if (!state.pending || state.busy) { return; }
        state.busy = true; pendingUI(); el("submit").disabled = true;
        var p = state.pending;
        api("POST", "commands/" + p.action, p.payload, function (error, result, uncertain) {
            state.busy = false; el("submit").disabled = false;
            if (!uncertain) {
                state.pending = null;
                try { var saved = JSON.parse(localStorage.getItem("liteshop.pending") || "null"); if (saved && saved.payload.request_id === p.payload.request_id) { localStorage.removeItem("liteshop.pending"); } } catch (e) {}
            }
            pendingUI();
            if (error) { notice(error + (uncertain ? "；请重试原请求" : ""), true); return; }
            closeForm(); notice("已保存，账本记录已更新。", false);
            status(); search();
            if (p.action === "create-member") { loadMember(result.id); }
            else if (p.memberId) { loadMember(p.memberId); }
        });
    }
    el("action-form").onsubmit = function (event) {
        event.preventDefault();
        if (state.busy || state.pending) { return; }
        var f = state.form, a = f.action, p = {}, command;
        try {
            if (a === "new" || a === "edit") {
                command = a === "new" ? "create-member" : "update-member";
                p = {name: value("name"), phone: value("phone") || null, inviter_name: value("inviter_name") || null, remark: value("remark")};
                if (a === "edit") { p.member_id = f.member.id; p.version = f.member.version; }
            } else if (a === "member-status") { command = "update-member"; p = {member_id: f.member.id, version: f.member.version, status: f.member.status ? 0 : 1}; }
            else if (a === "delete-member") { command = "delete-member"; p = {member_id: f.member.id, version: f.member.version, password: value("password")}; }
            else if (a === "open") { command = "open-card"; p = {member_id: f.member.id, card_type_id: value("type")}; }
            else if (a === "points") { command = "points"; p = {member_id: f.member.id, points: quantity(false, true), remark: value("remark")}; }
            else if (a === "card-status") { command = "card-status"; p = {card_id: f.card.id, version: f.card.version, status: value("status")}; }
            else { command = "transact"; p = {card_id: f.card.id, kind: a === "refund" ? "REFUND" : a, remark: value("remark")}; p[f.card.mode === "STORED" ? "amount" : "times"] = quantity(f.card.mode === "STORED", false); if (a === "refund") { p.source_id = f.tx.id; } }
            p.request_id = "web-" + new Date().getTime() + "-" + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);
            var pending = {action: command, payload: p, memberId: f.member ? f.member.id : null, description: el("form-title").textContent + " · " + el("form-context").textContent};
            if (localStorage.getItem("liteshop.pending")) { throw new Error("已有待确认请求，请刷新页面后处理"); }
            localStorage.setItem("liteshop.pending", JSON.stringify(pending));
            state.pending = pending; el("modal").hidden = true; submitPending();
        } catch (error) { el("form-error").textContent = error.message || "浏览器无法保存重试信息，请开启本地存储"; }
    };
    el("detail").onclick = function (event) { var t = event.target; if (t.getAttribute("data-action")) { openForm(t.getAttribute("data-action"), t.getAttribute("data-id")); } };
    el("member-list").onclick = function (event) { var t = event.target; while (t && t !== this) { if (t.getAttribute("data-member")) { loadMember(t.getAttribute("data-member")); return; } t = t.parentNode; } };
    el("new-member").onclick = function () { openForm("new"); };
    el("cancel").onclick = closeForm;
    el("retry").onclick = submitPending;
    el("search-form").onsubmit = function (e) { e.preventDefault(); state.offset = 0; search(); };
    el("prev").onclick = function () { state.offset = Math.max(0, state.offset - 50); search(); };
    el("next").onclick = function () { state.offset += 50; search(); };
    el("search").oninput = function () { state.offset = 0; if (state.searchTimer) { window.clearTimeout(state.searchTimer); } state.searchTimer = window.setTimeout(search, 280); };
    el("login-form").onsubmit = function (e) { e.preventDefault(); try { storageSet("liteshop.token", el("token").value); el("token").value = ""; connect(); } catch (error) { notice("请开启浏览器会话存储", true); } };
    function connect() {
        status(); search();
        api("GET", "card-types", null, function (error, data) { if (!error) { state.types = data; } });
        if (state.member) { loadMember(state.member.member.id); }
    }
    el("reconnect").onclick = connect;
    function cardTypeField(card) {
        var code = String(card.category_code || card.id).replace(/[^A-Za-z0-9_-]/g, "_");
        var checked = Number(card.status) === 1;
        var detail = card.description || ({STORED: "余额储值，可用于消费", COUNT: "按次数充值和扣次", POINTS: "消费后累计积分", RECHARGE_GIFT: "充值按比例赠送余额", DISCOUNT: "消费按折扣比例结算"}[card.category_code] || "自定义会员卡");
        return '<div class="card-type-setting" data-card-code="' + esc(card.category_code || code) + '">' +
            '<div class="type-head"><input type="checkbox" id="card-enabled-' + code + '" data-card-field="enabled"' + (checked ? " checked" : "") + '><strong>' + esc(card.name) + '</strong><span>' + (card.mode === "COUNT" ? "计次" : "储值") + '</span></div>' +
            '<small>' + esc(detail) + '</small><div class="type-options"><label>显示名称<input data-card-field="name" value="' + esc(card.name) + '" maxlength="80"></label>' +
            '<label>赠费比例 %<input data-card-field="gift_percent" type="number" min="0" max="100" value="' + (card.gift_percent || 0) + '"></label>' +
            '<label>折扣 %<input data-card-field="discount_percent" type="number" min="1" max="100" value="' + (card.discount_percent || 100) + '"></label>' +
            '<label>积分倍率<input data-card-field="points_rate" type="number" min="0" max="100000" value="' + (card.points_rate || 0) + '"></label></div></div>';
    }
    function openSettings() {
        api("GET", "settings", null, function (error, data) {
            if (error) { notice(error, true); return; }
            el("shop-setting-name").value = data.name || "";
            api("GET", "card-types", null, function (cardError, cards) {
                if (!cardError) { el("card-type-settings").innerHTML = cards.map(cardTypeField).join(""); }
                el("settings-error").textContent = ""; el("settings-modal").hidden = false; el("shop-setting-name").focus();
            });
        });
    }
    el("settings").onclick = openSettings;
    el("settings-cancel").onclick = function () { el("settings-modal").hidden = true; };
    el("settings-form").onsubmit = function (event) {
        event.preventDefault();
        var types = [], cards = el("card-type-settings").querySelectorAll(".card-type-setting");
        for (var i = 0; i < cards.length; i++) { var row = cards[i], code = row.getAttribute("data-card-code"), number = function (field, fallback) { var n = Number(row.querySelector('[data-card-field="' + field + '"]').value); return isFinite(n) ? n : fallback; }; types.push({category_code: code, name: row.querySelector('[data-card-field="name"]').value, enabled: row.querySelector('[data-card-field="enabled"]').checked, gift_percent: number("gift_percent", 0), discount_percent: number("discount_percent", 100), points_rate: number("points_rate", 0)}); }
        api("POST", "commands/update-settings", {request_id: "settings-" + new Date().getTime(), name: el("shop-setting-name").value, settings: {card_types: types}, local_password: el("local-setting-password").value || null}, function (error) {
            if (error) { el("settings-error").textContent = error; return; }
            el("settings-modal").hidden = true; el("local-setting-password").value = ""; notice("设置已保存", false); connect();
        });
    };
    el("cloud-login").onclick = function () { window.open("https://liteshop.250886.xyz", "_blank"); };
    document.onkeydown = function (e) {
        if (el("modal").hidden) { if (e.keyCode === 113) { el("search").focus(); el("search").select(); e.preventDefault(); } return; }
        if (e.keyCode === 27) { closeForm(); }
        if (e.keyCode === 9) {
            var list = el("modal").querySelectorAll("input,select,button"), first = list[0], last = list[list.length - 1];
            if (e.shiftKey && document.activeElement === first) { last.focus(); e.preventDefault(); }
            else if (!e.shiftKey && document.activeElement === last) { first.focus(); e.preventDefault(); }
        }
    };
    try {
        state.pending = JSON.parse(localStorage.getItem("liteshop.pending") || "null");
        if (bridge) { var info = JSON.parse(bridge.getTerminalInfo()); el("terminal-info").textContent = "Android " + info.androidVersion + " · " + info.model + " · 机顶盒本地账本"; bridge.reportReady('{"version":"0.3.0"}'); }
    } catch (error) { notice("终端状态读取失败，请重新连接", true); }
    pendingUI(); connect(); window.setInterval(status, 30000);
}());
