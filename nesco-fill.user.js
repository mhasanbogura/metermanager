// ==UserScript==
// @name         NESCO Recharge History Auto-fill
// @namespace    metermanager
// @version      1.0
// @description  Auto-fills the customer number from the URL hash and opens recharge history on the NESCO portal
// @match        https://customer.nesco.gov.bd/pre/panel*
// @grant        none
// @run-at       document-idle
// ==/UserScript==
(function () {
  var h = (location.hash || '').replace(/\D/g, '');
  if (!h || h.length < 8) return;
  function go() {
    var i = document.getElementById('cust_no');
    var b = document.getElementById('recharge_hist_button');
    if (!i || !b) return false;
    i.value = h;
    b.click();
    return true;
  }
  if (go()) return;
  var tries = 0;
  var t = setInterval(function () {
    if (go() || ++tries > 20) clearInterval(t);
  }, 500);
})();
