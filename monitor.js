(() => {
  if (window.__flashMobileMonitorLoaded) return;
  window.__flashMobileMonitorLoaded = true;

  const sleep = ms => new Promise(r => setTimeout(r, ms));
  let snapshotBusy = false;
  let consentChecked = false;
  let lastLiveFilterCheck = 0;

  const norm = s => (s || "")
    .toString()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim();

  const parseMinute = text => {
    const m = String(text || "").match(/(\d{1,3})/);
    if (!m) return null;
    const v = Number(m[1]);
    return Number.isFinite(v) && v >= 1 && v <= 130 ? v : null;
  };

  const intText = el => {
    const m = String(el?.textContent || "").match(/-?\d+/);
    return m ? Number(m[0]) : null;
  };

  function participantName(row, side) {
    const selectors = side === "home"
      ? [
          ".event__homeParticipant [data-testid='wcl-scores-simpleText1']",
          ".event__homeParticipant",
          ".event__participant--home"
        ]
      : [
          ".event__awayParticipant [data-testid='wcl-scores-simpleText1']",
          ".event__awayParticipant",
          ".event__participant--away"
        ];

    for (const sel of selectors) {
      const t = row.querySelector(sel)?.textContent?.trim();
      if (t) return t;
    }
    return "";
  }

  function redCount(row, side) {
    const participant = row.querySelector(
      side === "home"
        ? ".event__homeParticipant, .event__participant--home"
        : ".event__awayParticipant, .event__participant--away"
    );
    if (!participant) return 0;
    const direct = participant.querySelectorAll(
      "[data-testid='wcl-icon-incidents-red-card'], [data-testid*='red-card'], [class*='redCard']"
    ).length;
    if (direct) return direct;
    const hits = String(participant.innerHTML || "").match(/red[-_ ]?card/gi);
    return hits ? hits.length : 0;
  }

  function findLeagueText(row) {
    let node = row.previousElementSibling;
    for (let hops = 0; node && hops < 80; hops++, node = node.previousElementSibling) {
      if (
        node.matches?.(".wclLeagueHeader, [class*='LeagueHeader'], .event__header") ||
        node.querySelector?.("[data-testid='wcl-scores-overline4']")
      ) {
        const country = node.querySelector?.("[data-testid='wcl-scores-overline4']")?.textContent?.trim() || "";
        const league = node.querySelector?.("[data-testid='wcl-textLink'], .event__titleBox a")?.textContent?.trim() || "";
        const combined = `${country}: ${league}`.replace(/^:\s*|:\s*$/g, "").trim();
        if (combined) return combined;
        const t = node.textContent?.trim();
        if (t) return t.slice(0, 160);
      }
    }
    return "";
  }

  function matchId(row, url) {
    const raw = row.id || "";
    if (raw) return raw.includes("_") ? raw.split("_").pop() : raw;
    const m = String(url || "").match(/\/match\/[^/]+\/([A-Za-z0-9]+)(?:\/|#|$)/);
    return m ? m[1] : "";
  }

  function parseRows() {
    const rows = [...document.querySelectorAll(".event__match[id^='g_'], .event__match")];
    const out = [];

    for (const row of rows) {
      const home = participantName(row, "home");
      const away = participantName(row, "away");
      if (!home || !away) continue;

      const homeScore = intText(row.querySelector(".event__score--home"));
      const awayScore = intText(row.querySelector(".event__score--away"));
      if (homeScore === null || awayScore === null) continue;

      const stageEl = row.querySelector(".event__stage--block") ||
        row.querySelector(".event__stage") ||
        row.querySelector(".event__time");
      const minute = parseMinute(stageEl?.textContent || "");
      if (minute === null) continue;

      const link = row.querySelector("a.eventRowLink") || row.querySelector("a[href*='/match/']");
      const url = link?.href || "";
      const id = matchId(row, url);
      if (!id) continue;

      out.push({
        id, home, away,
        league: findLeagueText(row),
        minute,
        homeScore,
        awayScore,
        homeRed: redCount(row, "home"),
        awayRed: redCount(row, "away"),
        url,
        live: true
      });
    }

    return out;
  }

  async function acceptConsent() {
    if (consentChecked) return;
    consentChecked = true;
    const accepted = [
      "accept all", "i accept", "accept", "agree", "allow all",
      "kabul et", "tümünü kabul et", "hepsini kabul et"
    ];
    for (const el of document.querySelectorAll("button,[role='button']")) {
      const t = norm(el.textContent || "");
      if (accepted.includes(t)) {
        try { el.click(); await sleep(300); } catch (_) {}
        break;
      }
    }
  }

  async function ensureLiveFilter() {
    const now = Date.now();
    if (now - lastLiveFilterCheck < 30000) return true;
    lastLiveFilterCheck = now;

    const exact = new Set(["live", "canli", "canlı"].map(norm));
    for (const el of document.querySelectorAll("button,a,[role='tab'],[role='button']")) {
      const txt = norm(el.textContent || "");
      if (!exact.has(txt)) continue;
      const clickable = el.closest("button,a,[role='tab'],[role='button']") || el;
      const selected = clickable.getAttribute?.("aria-selected") === "true" ||
        clickable.getAttribute?.("aria-pressed") === "true" ||
        /selected|active/i.test(String(clickable.className || ""));
      if (!selected) {
        try { clickable.click(); await sleep(650); } catch (_) {}
      }
      return true;
    }
    return false;
  }

  async function snapshot() {
    if (snapshotBusy) return 0;
    snapshotBusy = true;
    try {
      await acceptConsent();
      await ensureLiveFilter();
      const matches = parseRows();
      AndroidMonitor.onSnapshot(JSON.stringify(matches));
      return matches.length;
    } catch (e) {
      try { AndroidMonitor.onDiagnostic(String(e)); } catch (_) {}
      return 0;
    } finally {
      snapshotBusy = false;
    }
  }

  window.__flashMobileSnapshotNow = snapshot;

  // Avoid observing every DOM mutation. Flashscore updates the live page very
  // frequently and that created a near-continuous full-page scan on phones.
  setTimeout(snapshot, 1200);
  setInterval(snapshot, 5000);
})();
