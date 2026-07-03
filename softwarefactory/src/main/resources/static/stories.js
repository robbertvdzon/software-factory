/*
 * Stories-overzicht: toont/verbergt story-rijen op basis van de aangevinkte
 * status-buckets (finished / in-progress / todo). De keuze wordt in localStorage
 * bewaard zodat het filter over pageloads heen blijft staan.
 */
(function () {
  var bar = document.querySelector('[data-story-filter]');
  if (!bar) return;
  var rows = Array.prototype.slice.call(document.querySelectorAll('.list.stories .lrow[data-bucket]'));
  var STORAGE_KEY = 'storyPageFilters';

  function getCheckboxes() {
    var checkboxes = {};
    bar.querySelectorAll('[data-bucket-toggle]').forEach(function (cb) {
      checkboxes[cb.getAttribute('data-bucket-toggle')] = cb.checked;
    });
    return checkboxes;
  }

  function setCheckboxes(checkboxes) {
    bar.querySelectorAll('[data-bucket-toggle]').forEach(function (cb) {
      var key = cb.getAttribute('data-bucket-toggle');
      if (key in checkboxes) {
        cb.checked = checkboxes[key];
      }
    });
  }

  function loadStoredState() {
    try {
      var stored = localStorage.getItem(STORAGE_KEY);
      if (stored) {
        var parsed = JSON.parse(stored);
        setCheckboxes(parsed);
      }
    } catch (e) {
    }
  }

  function saveState() {
    try {
      var state = getCheckboxes();
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (e) {
    }
  }

  function apply() {
    var on = getCheckboxes();
    rows.forEach(function (row) {
      row.style.display = on[row.getAttribute('data-bucket')] ? '' : 'none';
    });
  }

  loadStoredState();
  apply();
  bar.addEventListener('change', function () {
    apply();
    saveState();
  });
})();
