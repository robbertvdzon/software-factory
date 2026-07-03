/*
 * Nieuwe-story-formulier: filtert het AI-model-dropdown op de gekozen supplier
 * (model-ids verschillen per supplier). Modellen die niet bij de supplier horen worden
 * verborgen; staat er een ongeldig model geselecteerd, dan valt het terug op
 * "automatisch". "automatisch" (lege waarde) blijft altijd beschikbaar.
 */
(function(){
  var sup = document.getElementById('nsf-supplier');
  var mod = document.getElementById('nsf-model');
  if (!sup || !mod) return;
  function sync(){
    var s = sup.value;
    Array.prototype.forEach.call(mod.options, function(o){
      if (!o.value) return;
      var ok = o.getAttribute('data-supplier') === s;
      o.hidden = !ok;
      o.disabled = !ok;
    });
    var cur = mod.options[mod.selectedIndex];
    if (cur && cur.value && cur.disabled) mod.value = '';
  }
  sup.addEventListener('change', sync);
  sync();
})();
