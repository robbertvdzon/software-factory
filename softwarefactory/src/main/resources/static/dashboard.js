/*
 * Live updates voor alle dashboard-pagina's: de backend pusht via SSE (`/events`) een
 * "changed"-signaal; de pagina ververst dan alleen z'n data-laag (`main.content`), niet de
 * hele pagina. Een vangnet-poll dekt het geval dat de SSE-verbinding wegvalt. Een cyclus
 * wordt overgeslagen zodra de gebruiker bezig is — een <details>-menu open heeft, tekst
 * geselecteerd heeft of in een invoerveld typt — zodat die interactie niet verloren gaat.
 * Er wordt alleen vervangen als de data daadwerkelijk anders is.
 */
(function(){
  var hasRefresh = document.body.hasAttribute('data-refresh');
  var badge = document.querySelector('[data-myactions-badge]');
  function busy(){
    if (document.querySelector('dialog[open]')) return true;
    if (document.querySelector('details[open]')) return true;
    var sel = window.getSelection && String(window.getSelection());
    if (sel && sel.length) return true;
    var a = document.activeElement;
    if (a && (a.tagName === 'TEXTAREA' || a.tagName === 'INPUT')) return true;
    return false;
  }
  var refreshing = false;
  async function refreshMain(){
    if (!hasRefresh || busy() || refreshing) return;
    refreshing = true;
    try {
      var res = await fetch(location.href, { credentials: 'same-origin' });
      if (!res.ok) return;
      var text = await res.text();
      var doc = new DOMParser().parseFromString(text, 'text/html');
      var fresh = doc.querySelector('main.content');
      var cur = document.querySelector('main.content');
      if (!fresh || !cur || busy()) return;
      if (fresh.innerHTML === cur.innerHTML) return;
      var y = window.scrollY;
      cur.replaceWith(fresh);
      window.scrollTo(0, y);
    } catch (e) {
    } finally {
      refreshing = false;
    }
  }
  var badgeAt = 0, badgeBusy = false;
  async function updateBadge(){
    if (!badge || badgeBusy) return;
    var now = Date.now();
    if (now - badgeAt < 3000) return; // debounce bursts van SSE-events
    badgeAt = now; badgeBusy = true;
    try {
      var res = await fetch('/my-actions/count', { credentials: 'same-origin' });
      if (!res.ok) return;
      var n = parseInt((await res.text()).trim(), 10);
      if (isNaN(n) || n <= 0) { badge.hidden = true; badge.textContent = ''; }
      else { badge.textContent = String(n); badge.hidden = false; }
    } catch (e) {
    } finally {
      badgeBusy = false;
    }
  }
  function onChange(){ refreshMain(); updateBadge(); }
  updateBadge();
  // Eén gedeelde SSE-verbinding voor zowel de content-refresh als het badge-bolletje.
  try {
    var es = new EventSource('/events');
    es.addEventListener('changed', onChange);
  } catch (e) {}
  setInterval(onChange, 30000);
})();
