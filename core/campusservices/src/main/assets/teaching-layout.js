/*
 * Adapted from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
(() => {
  // The verified N9 mobile timetable page clips its entire document at height 0.
  // Presentation only: do not touch login fields, requests or timetable data.
  if (location.origin !== 'https://jwglxt.haut.edu.cn' ||
      location.pathname !== '/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html' ||
      !document.head || !document.body) return false;
  let style = document.getElementById('haut-pocket-teaching-layout');
  if (!style) {
    style = document.createElement('style');
    style.id = 'haut-pocket-teaching-layout';
    style.textContent = 'html,body{height:auto!important;min-height:100vh!important;overflow:auto!important}';
    document.head.appendChild(style);
  }
  return true;
})();
