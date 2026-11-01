/*
 * Adapted from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
if (location.origin !== 'https://wslib.haut.edu.cn' || location.pathname !== '/h5/index.html') return;
if (!document.body || !document.head) return 'waiting';
if (window.__hautLibraryDesign) { window.__hautLibraryDesign.configure(config); return window.__hautLibraryDesign.status(); }
let settings = config;
let timer;
const roots = new WeakMap();
const allowedRoutes = new Set(['/seatPage/SelectRoom/SelectRoom', '/seatPage/SelectSeat/SelectSeat']);
const stateNames = { '不可选': 'disabled', '可选': 'available', '已占': 'occupied', '选中': 'selected', '提示': 'info' };
const attr = (node, key, value) => { if (node.getAttribute(key) !== value) node.setAttribute(key, value); };
const text = (node, value) => { if (node.textContent !== value) node.textContent = value; };
const normalize = value => value.replace(/\s+/g, '').toLowerCase();
const route = () => location.hash.split('?')[0].replace(/^#/, '');
function toolbar(root, mode) {
    let state = roots.get(root);
    if (state && state.panel.isConnected) return state;
    const panel = document.createElement('section');
    panel.className = 'hp-tools'; panel.setAttribute('data-hp-ui', 'true');
    panel.innerHTML = '<div class="hp-tools-heading"><strong></strong><span class="hp-summary" aria-live="polite"></span></div><div class="hp-search-row"><input type="search" autocomplete="off"><button type="button" aria-pressed="false"></button></div><div class="hp-filter-note" aria-live="polite"></div>';
    const input = panel.querySelector('input');
    const button = panel.querySelector('button');
    text(panel.querySelector('strong'), mode === 'seats' ? '座位列表' : '选择区域');
    input.placeholder = mode === 'seats' ? '搜索桌号、座位号' : '搜索当前区域名称';
    input.setAttribute('aria-label', input.placeholder);
    text(button, mode === 'seats' ? '只看可选' : '只看有余座');
    input.addEventListener('input', schedule);
    button.addEventListener('click', () => { button.setAttribute('aria-pressed', String(button.getAttribute('aria-pressed') !== 'true')); schedule(); });
    if (mode === 'seats') (root.querySelector('.legend') || root.querySelector('.seat-list')).before(panel);
    else root.prepend(panel);
    state = { panel, input, button }; roots.set(root, state);
    return state;
}
function enhance() {
    const active = settings.enabled && allowedRoutes.has(route());
    document.documentElement.classList.toggle('hp-library', !!active);
    if (!active) { document.querySelectorAll('[data-hp-ui]').forEach(node => node.remove()); return; }
    const seatRoot = document.querySelector('.select-seat');
    const roomRoot = document.querySelector('.select-room');
    const root = route().includes('SelectSeat') ? seatRoot : roomRoot;
    if (!root) return;
    const seatsMode = root === seatRoot;
    if (seatsMode && !root.querySelector('.seat-list')) return;
    const controls = toolbar(root, seatsMode ? 'seats' : 'rooms');
    const query = normalize(controls.input.value);
    const onlyAvailable = controls.button.getAttribute('aria-pressed') === 'true';
    let total = 0, available = 0, occupied = 0, shown = 0;
    if (seatsMode) {
        const legend = [...root.querySelectorAll('.legend-item')].map(item => {
            const label = item.innerText.trim();
            const state = stateNames[label];
            if (state) attr(item, 'data-hp-state', state);
            return { src: item.querySelector('img')?.src, label, state };
        });
        root.querySelectorAll('.seat-list .seat').forEach(seat => {
            total++;
            const name = seat.querySelector('.num');
            const fullName = name?.textContent.trim() || '';
            const match = legend.find(item => item.src && item.src === seat.querySelector('img')?.src);
            const state = match?.state || 'unknown';
            attr(seat, 'data-hp-state', state);
            attr(seat, 'data-hp-status', match?.label || '以官方提示为准');
            if (name) {
                const shortName = fullName.match(/(\d+\s*桌\s*[^\s]+\s*座)\s*$/)?.[1] || fullName;
                attr(name, 'data-hp-label', shortName);
            }
            if (state === 'available') available++;
            if (state === 'occupied') occupied++;
            const visible = normalize(fullName).includes(query) && (!onlyAvailable || state === 'available' || state === 'selected');
            attr(seat, 'data-hp-hidden', String(!visible));
            if (visible) shown++;
        });
        text(controls.panel.querySelector('.hp-summary'), `${available} 可选 · ${occupied} 已占 · 共 ${total} 座`);
    } else {
        root.querySelectorAll('.room-list .room-item').forEach(room => {
            total++;
            const name = room.querySelector('.room-title')?.textContent.trim() || '';
            const usage = [...room.querySelectorAll('.room-time')].find(node => /剩余\s*\/\s*已预约/.test(node.textContent));
            if (usage) attr(usage, 'data-hp-usage', 'true');
            const raw = usage?.querySelector('span')?.textContent.trim() || '';
            const hasSeats = /^\d+$/.test(raw) && Number(raw) > 0;
            if (hasSeats) available++;
            const visible = normalize(name).includes(query) && (!onlyAvailable || hasSeats);
            attr(room, 'data-hp-hidden', String(!visible));
            if (visible) shown++;
        });
        text(controls.panel.querySelector('.hp-summary'), `${total} 个区域 · ${available} 处有余座`);
    }
    text(controls.panel.querySelector('.hp-filter-note'), query || onlyAvailable ? (shown ? `筛选当前已加载数据 · 显示 ${shown} 项` : '没有符合条件的结果，清空搜索或关闭筛选可查看全部。') : '按所选日期和时段显示 · 数据来自图书馆');
}
function schedule() { clearTimeout(timer); timer = setTimeout(enhance, 60); }
function configure(value) {
    settings = value;
    let style = document.getElementById('hp-library-style');
    if (!style) { style = document.createElement('style'); style.id = 'hp-library-style'; document.head.append(style); }
    text(style, settings.css);
    const html = document.documentElement;
    for (const [key, value] of Object.entries(settings.colors)) html.style.setProperty('--hp-' + key, value);
    html.style.setProperty('--hp-font-scale', String(settings.fontScale));
    enhance();
}
new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['class', 'src'] });
window.addEventListener('hashchange', schedule);
function status() { return !settings.enabled ? 'disabled' : !allowedRoutes.has(route()) ? 'unsupported' : document.documentElement.classList.contains('hp-library') ? 'applied' : 'waiting'; }
window.__hautLibraryDesign = { configure, status };
configure(settings);
return status();
