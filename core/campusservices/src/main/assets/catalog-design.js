/*
 * Adapted from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
if (location.origin !== 'https://libspace.haut.edu.cn') return;
if (!document.body || !document.head) return 'waiting';
if (window.__hautCatalogDesign) { window.__hautCatalogDesign.configure(config); return window.__hautCatalogDesign.status(); }
let settings = config;
let timer;
const allowed = () => /^\/mspace\/(index|search|searchList|searchAdvanced|searchDetailLocal\/[A-Za-z0-9_-]+)$/.test(location.pathname);
const text = (node, value) => { if (node.textContent !== value) node.textContent = value; };
function note(parent, before, kind, value) {
    let panel = parent.querySelector(':scope > [data-hp-catalog-note="' + kind + '"]');
    if (!panel) {
        panel = document.createElement('div'); panel.className = 'hp-catalog-note';
        panel.setAttribute('data-hp-catalog-note', kind); panel.setAttribute('role', 'status');
        parent.insertBefore(panel, before);
    }
    text(panel, value);
}
function enhance() {
    const active = settings.enabled && allowed();
    document.documentElement.classList.toggle('hp-catalog', !!active);
    if (!active) { document.querySelectorAll('[data-hp-catalog-note]').forEach(node => node.remove()); return; }
    const home = document.querySelector('.search-general');
    if (home && location.pathname === '/mspace/search') {
        note(home, home.firstChild, 'home', '查找馆藏\n输入关键词检索书目，点开结果查看馆藏位置与可借状态。');
    }
    const books = document.querySelector('.search-list-wrapper .book-list');
    if (books) {
        const count = books.querySelectorAll('.book-info').length;
        note(books.parentElement, books, 'results', `已加载 ${count} 条书目 · 点书名看详情，点纸本资源看馆藏`);
    }
    document.querySelectorAll('.paper-resources, .paper-popup').forEach(root => {
        const list = root.querySelector('.van-list');
        if (!list) return;
        const total = list.querySelectorAll(':scope > .van-cell').length;
        const available = [...list.querySelectorAll('.can')].filter(node => node.textContent.trim() === '可借').length;
        note(root, list, 'holdings', `当前已加载 ${total} 份馆藏 · ${available} 份标记可借`);
    });
    [['.iconmenu', '菜单'], ['.iconadvanced-search', '高级检索'], ['.iconuser', '读者登录'], ['.iconsearch', '搜索']].forEach(([selector, label]) => {
        const icon = document.querySelector('#header ' + selector);
        const target = icon?.closest('a, .van-field__right-icon');
        if (target && !target.getAttribute('aria-label')) target.setAttribute('aria-label', label);
    });
}
function schedule() { clearTimeout(timer); timer = setTimeout(enhance, 60); }
function configure(value) {
    settings = value;
    let style = document.getElementById('hp-catalog-style');
    if (!style) { style = document.createElement('style'); style.id = 'hp-catalog-style'; document.head.append(style); }
    text(style, settings.css);
    for (const [key, value] of Object.entries(settings.colors)) document.documentElement.style.setProperty('--hp-' + key, value);
    document.documentElement.style.setProperty('--hp-font-scale', String(settings.fontScale));
    enhance();
}
new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true, characterData: true });
window.addEventListener('popstate', schedule);
function status() { return !settings.enabled ? 'disabled' : !allowed() ? 'unsupported' : document.documentElement.classList.contains('hp-catalog') ? 'applied' : 'waiting'; }
window.__hautCatalogDesign = { configure, status };
configure(settings);
return status();
