/*
 * Adapted from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
(() => {
  if (location.origin !== 'https://libspace.haut.edu.cn') return JSON.stringify({kind:'official'});
  const clean = (node, max=1000) => (node?.textContent || '').replace(/\s+/g,' ').trim().slice(0,max);
  const root = document.querySelector('.search-list-wrapper .book-list');
  const cards = root ? [...root.querySelectorAll('.van-list > .van-cell')].filter(x=>x.querySelector('.book-info')) : [];
  const readBook = (row,index) => ({index,title:clean(row.querySelector('.book-info .title span') || row.querySelector('.book-info .title')),
    bibliography:clean(row.querySelector('.book-info .info')),summary:clean(row.querySelectorAll('.book-info .info')[1],500),
    resources:clean(row.querySelector('.source')),coverUrl:row.querySelector('img')?.currentSrc || row.querySelector('img')?.src || ''});
  if (location.pathname === '/mspace/searchList') return JSON.stringify({kind:'results',books:cards.slice(0,200).map(readBook),
    finished:!!root?.querySelector('.van-list__finished-text'),empty:!!document.querySelector('.van-empty'),
    loading:!!root?.querySelector('.van-list__loading')});
  const detail = document.querySelector('.search-detail .top-book-info');
  if (/^\/mspace\/searchDetailLocal\/[A-Za-z0-9_-]+$/.test(location.pathname) && detail) {
    const holdings = [...document.querySelectorAll('.paper-resources .van-list > .van-cell')].slice(0,150).map(row=>({
      location:clean(row.querySelector('.list > .title')), lines:[...row.querySelectorAll('.info > .left p')].map(x=>clean(x)),
      status:clean(row.querySelector('.info > .right')),shelf:clean(row.querySelector('.position-info'))
    }));
    return JSON.stringify({kind:'detail',title:clean(detail.querySelector('.title')),coverUrl:detail.querySelector('img')?.currentSrc || detail.querySelector('img')?.src || '',bibliography:[...detail.querySelectorAll('.info')].map(x=>clean(x)).join('\n'),holdings,
      description:[...document.querySelectorAll('.search-detail .book-info .info p')].map(x=>clean(x)).join('\n').slice(0,10000)});
  }
  if (['/mspace/index','/mspace/search'].includes(location.pathname)) {
    const recommendations = [...document.querySelectorAll('.hot-lend .book-item .right .title, .hot-lend .book-list a, .hot-search .item .left')]
      .map(x=>clean(x,100).replace(/^\d+[.、]?\s*/, '')).filter(Boolean).slice(0,8);
    return JSON.stringify({kind:'home',recommendations});
  }
  return JSON.stringify({kind:document.readyState==='complete'?'official':'loading'});
})();
