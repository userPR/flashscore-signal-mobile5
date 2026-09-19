(() => {
  if (window.__flashMobileDetailRunning) return;
  window.__flashMobileDetailRunning = true;

  const matchId = window.__mobileMatchId || "";
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  const norm = s => (s || "")
    .toString()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim();

  const parseNumber = raw => {
    const t = String(raw || "").trim().replace(",", ".");
    if (!/^\d{1,2}(?:\.\d+)?$/.test(t)) return null;
    const n = Number(t);
    return Number.isFinite(n) && n >= 0 && n <= 60 ? n : null;
  };

  function textNodes(container) {
    const out = [];
    const w = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
    let n = null;
    while ((n = w.nextNode())) {
      const raw = String(n.nodeValue || "").trim();
      if (!raw) continue;
      out.push({node:n, raw, normalized:norm(raw)});
      if (out.length > 100) break;
    }
    return out;
  }

  function parsePair(labels) {
    const wanted = new Set(labels.map(norm));
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const labelNodes = [];
    let n = null;

    while ((n = walker.nextNode())) {
      if (wanted.has(norm(n.nodeValue || ""))) labelNodes.push(n);
    }

    for (const labelNode of labelNodes) {
      let c = labelNode.parentElement;
      for (let depth = 0; depth < 9 && c; depth++, c = c.parentElement) {
        const nodes = textNodes(c);
        if (!nodes.length || nodes.length > 100) continue;
        const idx = nodes.findIndex(x => x.node === labelNode);
        if (idx < 0) continue;

        let left = null;
        let right = null;
        for (let i = idx - 1; i >= Math.max(0, idx - 14); i--) {
          const v = parseNumber(nodes[i].raw);
          if (v !== null) { left = v; break; }
        }
        for (let i = idx + 1; i < Math.min(nodes.length, idx + 15); i++) {
          const v = parseNumber(nodes[i].raw);
          if (v !== null) { right = v; break; }
        }
        if (left !== null && right !== null) return {home:left, away:right};

        const before = [];
        const after = [];
        for (let i = Math.max(0, idx - 14); i < idx; i++) {
          const v = parseNumber(nodes[i].raw);
          if (v !== null) before.push(v);
        }
        for (let i = idx + 1; i < Math.min(nodes.length, idx + 15); i++) {
          const v = parseNumber(nodes[i].raw);
          if (v !== null) after.push(v);
        }
        if (before.length >= 2 && after.length === 0) {
          return {home:before[before.length - 2], away:before[before.length - 1]};
        }
        if (after.length >= 2 && before.length === 0) {
          return {home:after[0], away:after[1]};
        }
      }
    }

    // Body-text fallback for layouts that flatten the row.
    const lines = String(document.body?.innerText || "")
      .split(/\n+/).map(x => x.trim()).filter(Boolean);
    const normalized = lines.map(norm);
    for (let i = 0; i < normalized.length; i++) {
      if (!wanted.has(normalized[i])) continue;
      const left = [];
      const right = [];
      for (let j = Math.max(0, i - 5); j < i; j++) {
        const v = parseNumber(lines[j]); if (v !== null) left.push(v);
      }
      for (let j = i + 1; j < Math.min(lines.length, i + 6); j++) {
        const v = parseNumber(lines[j]); if (v !== null) right.push(v);
      }
      if (left.length && right.length) return {home:left[left.length - 1], away:right[0]};
      if (left.length >= 2) return {home:left[left.length - 2], away:left[left.length - 1]};
      if (right.length >= 2) return {home:right[0], away:right[1]};
    }
    return null;
  }

  function parseSot() {
    return parsePair([
      "Shots on target", "Shots on goal",
      "İsabetli şut", "İsabetli şutlar",
      "Kaleyi bulan şut", "Kaleyi bulan şutlar"
    ]);
  }

  function parseBig() {
    return parsePair([
      "Big chances", "Big chance",
      "Büyük fırsatlar", "Büyük şanslar", "Net gol fırsatları"
    ]);
  }

  async function clickText(labels) {
    const wanted = new Set(labels.map(norm));
    const candidates = document.querySelectorAll("button,a,[role='button'],[role='tab'],div,span");
    for (const el of candidates) {
      const t = norm(el.textContent || "");
      if (!wanted.has(t)) continue;
      const clickable = el.closest("button,a,[role='button'],[role='tab']") || el;
      try {
        clickable.scrollIntoView({block:"center", behavior:"instant"});
        clickable.click();
        await sleep(600);
        return true;
      } catch (_) {}
    }
    return false;
  }

  function statsEvidence() {
    const t = norm(document.body?.innerText || "");
    return t.includes("ball possession") ||
      t.includes("total shots") ||
      t.includes("expected goals") ||
      t.includes("shots on target") ||
      t.includes("big chances") ||
      t.includes("top stats");
  }

  async function ensureStats() {
    if (statsEvidence()) return;
    await clickText(["Stats", "Statistics", "İstatistik", "İstatistikler"]);
    if (!location.hash.includes("match-statistics")) {
      try { location.hash = "#/match-summary/match-statistics/0"; } catch (_) {}
    }
    await sleep(700);
  }

  async function expandShowMore() {
    for (let i = 0; i < 4; i++) {
      const clicked = await clickText([
        "Show more", "Show more stats", "Show all", "More",
        "Daha fazla", "Daha fazla göster", "Tümünü göster"
      ]);
      if (clicked) {
        await sleep(550);
        return true;
      }
      try { window.scrollTo(0, 900 + i * 350); } catch (_) {}
      await sleep(350);
    }
    return false;
  }

  let finished = false;
  function done(payload) {
    if (finished) return;
    finished = true;
    try { AndroidWorker.onDetailResult(matchId, JSON.stringify(payload)); } catch (_) {}
  }

  (async () => {
    const started = Date.now();
    let lastBig = null;

    try {
      await ensureStats();
      await expandShowMore();

      const scrolls = [0, 350, 700, 1050, 1400, 1750, 2100];
      let scrollIndex = 0;

      while (Date.now() - started < 23_000) {
        const sot = parseSot();
        const big = parseBig();
        if (big) lastBig = big;

        if (sot) {
          done({
            homeSot:sot.home,
            awaySot:sot.away,
            homeBig:lastBig ? lastBig.home : null,
            awayBig:lastBig ? lastBig.away : null,
            status:"stats-complete"
          });
          return;
        }

        if ((Date.now() - started) > 2500) await expandShowMore();
        try {
          window.scrollTo({top:scrolls[scrollIndex % scrolls.length], behavior:"instant"});
          scrollIndex++;
        } catch (_) {}
        await sleep(650);
      }

      done({
        homeSot:null,
        awaySot:null,
        homeBig:lastBig ? lastBig.home : null,
        awayBig:lastBig ? lastBig.away : null,
        status:"timeout"
      });
    } catch (e) {
      done({homeSot:null, awaySot:null, homeBig:null, awayBig:null, status:"parse-failed"});
    }
  })();
})();
