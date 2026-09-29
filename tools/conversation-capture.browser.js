/* IO Matrix: explicit, local DOM capture. Run in a page you own/access, then press Start.
 * No fetch, XHR, hidden application state, cross-origin frames or model API access.
 * Public start(options) is an explicit programmatic equivalent of pressing Start.
 */
(() => {
  'use strict';
  if (window.IOConversationCapture) { window.IOConversationCapture.show(); return; }
  const VERSION = 1;
  let host, running;
  const labels = [
    /^(show|view|read) (more|details|reasoning|thinking|thought process|tool calls|tool results|sources)(\s*[:·(].{0,60})?$/i,
    /^(thought|thinking) for .{1,60}$/i,
    /^(reasoning|thinking|thought process|tool calls|tool results|sources)(\s*[:·(].{0,60})?$/i,
    /^(pokaż|rozwiń|czytaj) (więcej|szczegóły|rozumowanie|tok rozumowania|myślenie|wyniki narzędzi|wywołania narzędzi|źródła)$/i,
    /^(rozumowanie|myślenie|wywołania narzędzi|wyniki narzędzi|źródła)$/i,
    /^(toon|bekijk|lees) (meer|details|redenering|gedachten|bronnen)$/i
  ];
  const privateSelector = 'input,textarea,select,[contenteditable]:not([contenteditable="false"]),[role="textbox"],script,style,noscript,template';
  const attributes = ['id', 'role', 'aria-label', 'aria-expanded', 'data-message-id', 'data-message-author-role', 'data-testid', 'alt', 'href'];
  const visible = e => {
    if (e === host || e.closest(privateSelector)) return false;
    for (let a = e; a; a = a.parentElement) {
      if (a.hidden || a.getAttribute('aria-hidden') === 'true') return false;
      const s = getComputedStyle(a);
      if (s.display === 'none' || s.visibility === 'hidden' || s.visibility === 'collapse') return false;
      if (a.localName === 'details' && !a.open && e !== a && !a.querySelector(':scope > summary')?.contains(e)) return false;
    }
    return true;
  };
  const delay = (ms, signal) => new Promise(resolve => {
    const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve(); };
    const timer = setTimeout(finish, ms);
    if (signal.aborted) finish(); else signal.addEventListener('abort', finish, { once: true });
  });
  function quiet(root, ms, signal) {
    return new Promise(resolve => {
      let timer, ceiling;
      const end = settled => { observer.disconnect(); clearTimeout(timer); clearTimeout(ceiling); signal.removeEventListener('abort', abort); resolve(settled); };
      const abort = () => end(false);
      const restart = () => { clearTimeout(timer); timer = setTimeout(() => end(true), ms); };
      const observer = new MutationObserver(restart);
      observer.observe(root, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['open', 'hidden', 'aria-expanded', 'class', 'style'] });
      ceiling = setTimeout(() => end(false), Math.max(2000, ms * 4));
      restart();
      if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true });
    });
  }
  function snapshot(root, budget) {
    let nodes = 0, chars = 0, clipped = false;
    const take = s => {
      const allowed = Math.max(0, Math.min(100000, budget - chars));
      if (s.length > allowed) clipped = true;
      const v = s.slice(0, allowed); chars += v.length; return v;
    };
    function walk(n, path, depth) {
      if (++nodes > 20000 || depth > 64 || chars >= budget) { clipped = true; return null; }
      if (n.nodeType === Node.TEXT_NODE) {
        const parent = n.parentElement;
        if (!parent || !visible(parent)) return null;
        const closed = parent.closest('details:not([open])');
        if (closed && !closed.querySelector(':scope > summary')?.contains(parent)) return null;
        if (!n.textContent.trim()) return n.parentElement?.closest('pre,code') ? { path, text: take(n.textContent) } : null;
        return { path, text: take(n.textContent) };
      }
      if (!(n instanceof Element) || !visible(n)) return null;
      const a = {};
      // Never read values, event handlers, application state or arbitrary data-* fields.
      for (const key of attributes) if (n.hasAttribute(key)) {
        if (key === 'aria-label' && n.querySelector(privateSelector)) continue;
        a[key] = take(n.getAttribute(key));
      }
      const value = { path, tag: n.localName, attributes: a, children: [] };
      if (['iframe', 'canvas', 'video', 'audio'].includes(n.localName)) {
        value.uninspected = 'media/frame content not extracted'; return value;
      }
      let i = 0;
      for (const child of n.childNodes) {
        if (nodes >= 20000 || chars >= budget) { clipped = true; break; }
        const c = walk(child, `${path}/${i++}`, depth + 1); if (c) value.children.push(c);
      }
      return value;
    }
    return { tree: walk(root, '0', 0), clipped };
  }
  const descendants = tree => {
    const out = [];
    function walk(n) { if (!n) return; out.push(n); (n.children || []).forEach(walk); }
    walk(tree); return out;
  };
  function turns(tree) {
    const out = [];
    function walk(n) {
      if (!n?.tag) return;
      const a = n.attributes;
      if (a['data-message-id'] || /^conversation-turn-\d+$/.test(a['data-testid'] || '') || n.tag === 'article' || a.role === 'article') {
        const all = descendants(n);
        const message = all.find(c => c.attributes?.['data-message-id']);
        const role = all.find(c => c.attributes?.['data-message-author-role']);
        const key = message ? `message:${message.attributes['data-message-id']}` :
          /^conversation-turn-\d+$/.test(a['data-testid'] || '') ? `testid:${a['data-testid']}` : a.id ? `id:${a.id}` : null;
        out.push({ key, role: role?.attributes['data-message-author-role'] || null, tree: n });
        return;
      }
      (n.children || []).forEach(walk);
    }
    walk(tree); return out;
  }
  function treeText(n) {
    if (!n) return '';
    if ('text' in n) return n.text;
    const body = (n.children || []).map(treeText).join('');
    if (n.tag === 'pre') return `\n\n\`\`\`\n${body}\n\`\`\`\n\n`;
    if (n.tag === 'br') return '\n';
    if (n.tag === 'img') return ` [image: ${n.attributes.alt || 'no exposed description'}] `;
    if (n.uninspected) return ` [${n.tag}: ${n.uninspected}] `;
    return /^(p|div|article|section|li|h[1-6]|tr|summary)$/.test(n.tag) ? `\n${body}\n` : body;
  }
  function text(result) {
    let out = `IO Matrix · rendered conversation capture\n${result.sourcePath}\nStop: ${result.stopReason}; completeness: UNVERIFIED; start: ${result.startCoverage}\n`;
    out += 'Only exposed DOM content. Editable inputs, hidden panels and unexposed reasoning/tool data are excluded. Raw JSON retains hierarchy and revisions.\n';
    if (result.warnings.length) out += `Warnings: ${result.warnings.join('; ')}\n`;
    const known = result.turns;
    if (known.length) for (const t of known) out += `\n--- ${t.role || 'role not exposed'} · ${t.key} ---\n${treeText(t.tree)}\n`;
    // Unidentified virtualized content cannot safely be deduplicated into invented messages.
    if (!known.length || result.hasUnkeyedTurns) for (const f of result.frames.filter(f => f.phase === 'capture'))
      out += `\n--- Raw observed frame ${f.sequence}; possible overlap/revision ---\n${treeText(f.tree)}\n`;
    if (!result.frames.some(f => f.phase === 'capture')) for (const f of result.frames)
      out += `\n--- Interrupted seek frame ${f.sequence} ---\n${treeText(f.tree)}\n`;
    return out;
  }
  function scrollContainer(root) {
    for (let e = root; e; e = e.parentElement) if (e.scrollHeight > e.clientHeight + 4 && /auto|scroll/.test(getComputedStyle(e).overflowY)) return e;
    return document.scrollingElement;
  }
  function expandOne(root, attempted) {
    // Setting native <details>.open does not click links or execute a summary's click handler.
    for (const d of root.querySelectorAll('details:not([open])')) {
      if (!visible(d) || d.closest('form') || attempted.has(d)) continue;
      attempted.add(d); d.open = true; return true;
    }
    for (const b of root.querySelectorAll('button[aria-expanded="false"]')) {
      if (!visible(b) || b.form || b.getAttribute('type') === 'submit' || attempted.has(b)) continue;
      const label = (b.getAttribute('aria-label') || b.textContent).trim().replace(/\s+/g, ' ');
      if (!labels.some(r => r.test(label))) continue;
      const ids = (b.getAttribute('aria-controls') || '').trim().split(/\s+/).filter(Boolean);
      if (ids.some(id => !root.contains(document.getElementById(id)))) continue;
      attempted.add(b); b.click(); return true;
    }
    return false;
  }
  function start(options = {}) {
    if (running) throw new Error('A capture is already running');
    const root = options.root || document.querySelector('main,[role="main"]');
    if (!(root instanceof Element) || root.ownerDocument !== document || !visible(root)) throw new Error('Choose a visible conversation root in this document');
    const scroller = options.scroller || scrollContainer(root);
    if (!(scroller instanceof Element) || scroller.ownerDocument !== document || !(root.contains(scroller) || scroller.contains(root))) throw new Error('Scroller must belong to this conversation document');
    const config = { autoScroll: options.autoScroll !== false, seekStart: options.seekStart !== false, expand: options.expand !== false,
      maxFrames: options.maxFrames ?? 200, maxChars: options.maxChars ?? 8000000, maxSteps: options.maxSteps ?? 500,
      maxMillis: options.maxMillis ?? 600000, settleMs: options.settleMs ?? 600 };
    for (const [key, min, max] of [['maxFrames',1,1000],['maxChars',1000,16000000],['maxSteps',1,2000],['maxMillis',1000,1800000],['settleMs',100,5000]])
      if (!Number.isInteger(config[key]) || config[key] < min || config[key] > max) throw new Error(`Invalid ${key}`);
    const controller = new AbortController(), signal = controller.signal;
    const locationKey = location.origin + location.pathname, began = performance.now();
    const result = { schemaVersion: VERSION, source: 'rendered-dom', sourcePath: locationKey, startedAt: new Date().toISOString(),
      completeness: 'unverified', startCoverage: 'current_position', stopReason: 'in_progress',
      order: config.autoScroll ? 'first observation in forward pass' : 'observation order; may not be chronological',
      options: config, warnings: [], frames: [], turns: [], hasUnkeyedTurns: false, expanded: 0 };
    const turnMap = new Map(); let stored = 0, last = null, lastPhase = null;
    const warn = s => { if (!result.warnings.includes(s)) result.warnings.push(s); };
    const stop = reason => { if (result.stopReason === 'in_progress') result.stopReason = reason; controller.abort(); };
    const valid = () => {
      if (signal.aborted) return false;
      if (!root.isConnected || location.origin + location.pathname !== locationKey || document.hidden) { stop('target_changed_or_hidden'); return false; }
      if (performance.now() - began >= config.maxMillis) { stop('time_limit'); return false; }
      return true;
    };
    const record = phase => {
      if (!valid()) return;
      const s = snapshot(root, Math.min(2000000, Math.max(0, config.maxChars - stored)));
      const encoded = JSON.stringify(s.tree);
      if (s.clipped) warn('DOM node/text/depth limit reached');
      if (encoded === last && phase === lastPhase) return;
      if (result.frames.length >= config.maxFrames || stored + encoded.length > config.maxChars) { warn('Archive limit reached; later content omitted'); stop('archive_limit'); return; }
      last = encoded; lastPhase = phase; stored += encoded.length;
      result.frames.push({ sequence: result.frames.length, elapsedMillis: Math.round(performance.now() - began), phase, clipped: s.clipped, tree: s.tree });
      if (phase === 'capture') {
        const observed = turns(s.tree);
        if (!observed.length || observed.some(t => !t.key)) result.hasUnkeyedTurns = true;
        const turnPaths = observed.map(t => t.tree.path);
        if (descendants(s.tree).some(n => n.text?.trim() && !turnPaths.some(p => n.path === p || n.path.startsWith(p + '/')))) {
          result.hasUnkeyedTurns = true; warn('Text outside keyed messages retained in raw frame sections');
        }
        const seen = new Set();
        for (const t of observed) if (t.key) {
          if (seen.has(t.key)) { result.hasUnkeyedTurns = true; warn('Duplicate message identifier; consult raw frames'); }
          seen.add(t.key); turnMap.set(t.key, t);
        }
      }
      if (s.clipped) stop('dom_limit');
    };
    const settle = async () => { if (!await quiet(root, config.settleMs, signal) && !signal.aborted) warn('Content did not settle; delayed content may be missing'); };
    const move = direction => {
      if (!valid()) return false;
      const before = scroller.scrollTop;
      scroller.scrollTop = before + direction * Math.max(100, Math.floor(scroller.clientHeight * 0.75));
      return Math.abs(scroller.scrollTop - before) > 1;
    };
    const handle = { stop: () => stop('user_stopped'), done: null };
    running = handle;
    handle.done = (async () => {
      try {
        if (config.autoScroll && config.seekStart) {
          result.startCoverage = 'seeking'; let idle = 0;
          for (let i = 0; i < config.maxSteps && valid(); i++) {
            record('seeking'); if (!valid()) break;
            if (!move(-1)) idle++; else idle = 0;
            await settle();
            if (idle >= 3) { result.startCoverage = 'local_boundary_not_proof_of_start'; break; }
            if (i === config.maxSteps - 1) result.startCoverage = 'seek_step_limit';
          }
        }
        let idle = 0, attempted = new WeakSet();
        for (let i = 0; i < config.maxSteps && valid(); i++) {
          await settle(); record('capture');
          if (!valid()) break;
          if (config.expand) for (let j = 0; j < 32 && result.expanded < 1000 && valid(); j++) {
            if (!expandOne(root, attempted)) break;
            result.expanded++; await settle(); record('capture');
          }
          if (!valid()) break;
          if (!config.autoScroll) { await delay(config.settleMs, signal); continue; }
          if (!move(1)) idle++; else { idle = 0; attempted = new WeakSet(); }
          if (idle >= 3) { stop('local_boundary_completeness_unverified'); break; }
        }
        if (result.stopReason === 'in_progress') stop('step_limit');
      } catch (_) { stop('capture_error'); warn('Capture interrupted; inspect raw frames'); }
      finally { result.turns = [...turnMap.values()]; if (running === handle) running = null; }
      return result;
    })();
    return handle;
  }
  function download(name, type, content) {
    const url = URL.createObjectURL(new Blob([content], { type }));
    const a = document.createElement('a'); a.href = url; a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(url), 60000);
  }
  function show() {
    if (host?.isConnected) return;
    host = document.createElement('div'); host.style.cssText = 'position:fixed;right:12px;top:12px;z-index:2147483647;max-width:calc(100vw - 24px)';
    const ui = host.attachShadow({ mode: 'closed' });
    const panel = document.createElement('div'); panel.style.cssText = 'background:#fff;color:#111;border:1px solid #888;border-radius:10px;padding:12px;width:340px;max-width:calc(100vw - 50px);max-height:80vh;overflow:auto;font:14px system-ui;box-shadow:0 2px 16px #0005';
    const status = document.createElement('p'); status.textContent = 'IO Matrix: capture the rendered conversation. Start can scroll and expand details. Inputs, hidden model internals and media bytes are not read. Nothing is uploaded. Stay in this chat until stopped.';
    panel.append(status);
    function choice(label, checked) { const c = document.createElement('input'); c.type = 'checkbox'; c.checked = checked; const l = document.createElement('label'); l.append(c, document.createTextNode(label)); panel.append(l, document.createElement('br')); return c; }
    const auto = choice(' Scroll automatically', true), top = choice(' Start at beginning', true), expand = choice(' Expand exposed details', true);
    const controls = document.createElement('div'); panel.append(controls);
    const btn = (label, action) => { const b = document.createElement('button'); b.textContent = label; b.style.cssText = 'margin:5px;padding:7px'; b.onclick = action; controls.append(b); return b; };
    let task;
    const begin = btn('Start', async () => {
      try {
        task = start({ autoScroll: auto.checked, seekStart: top.checked, expand: expand.checked }); begin.disabled = true;
        status.textContent = 'Capturing locally. Keep this tab active. Stop is always available.';
        const result = await task.done;
        status.textContent = `Stopped: ${result.stopReason}. ${result.frames.length} snapshots; ${result.expanded} expanded. Completeness UNVERIFIED.`;
        const preview = document.createElement('textarea'); preview.readOnly = true; preview.value = text(result); preview.style.cssText = 'width:100%;height:220px;box-sizing:border-box'; panel.append(preview);
        btn('Select all', () => { preview.focus(); preview.select(); });
        btn('Save text', () => download('IO-Matrix-conversation.txt', 'text/plain;charset=utf-8', preview.value));
        btn('Save JSON', () => download('IO-Matrix-conversation.json', 'application/json', JSON.stringify(result, null, 2)));
      } catch (e) { status.textContent = e.message; }
    });
    btn('Stop', () => task?.stop());
    btn('Close', () => { if (task && !confirm('Close capture controls? Save/copy the result first.')) return; task?.stop(); host.remove(); });
    ui.append(panel); document.body.append(host);
  }
  window.IOConversationCapture = Object.freeze({ version: VERSION, start, text, show });
  show();
})();
