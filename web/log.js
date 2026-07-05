// Minimal ring-buffer logger for glasses web companion debugging.
(function (root) {
  'use strict';
  var MAX = 200;
  var buf = [];

  function ts() {
    var d = new Date();
    return d.toISOString().slice(11, 23);
  }

  function log(tag, msg, data) {
    var extra = '';
    if (data !== undefined && data !== null) {
      try { extra = ' ' + JSON.stringify(data); } catch (e) { extra = ' [unserializable]'; }
    }
    var line = ts() + ' [' + tag + '] ' + (msg || '') + extra;
    console.log(line);
    buf.push(line);
    if (buf.length > MAX) buf.shift();
  }

  root.AmbientLog = {
    log: log,
    dump: function () { return buf.join('\n'); },
    buffer: function () { return buf.slice(); },
  };
})(typeof window !== 'undefined' ? window : globalThis);
