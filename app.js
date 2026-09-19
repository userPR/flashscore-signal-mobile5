let state={matches:[],stats:[],signals:[],enabled:true,allMatchesMode:false};
let currentTab="stats";
let pendingEnabled=null;
let pendingAllMode=null;
let pendingEnabledAt=0;
let pendingAllAt=0;
const PENDING_MS=6000;

const $=id=>document.getElementById(id);
const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const finite=v=>typeof v==="number"&&Number.isFinite(v);

function getState(){
  try{return JSON.parse(MobileBridge.getState());}catch(e){return state;}
}
function fmtTime(ms){
  try{return new Date(ms).toLocaleString("tr-TR",{day:"2-digit",month:"2-digit",year:"numeric",hour:"2-digit",minute:"2-digit",second:"2-digit"});}catch(e){return "";}
}
function redText(m){
  const h=m?.homeRed||0,a=m?.awayRed||0;
  if(!h&&!a)return "🟥 Yok";
  return `🟥 ${esc(m.home)} ${h} · ${esc(m.away)} ${a}`;
}
function statStatus(s){
  const x=s?.status;
  if(x==="stats-complete")return ["Hazır","ready"];
  if(x==="reading")return ["Okunuyor…",""];
  if(x==="queued")return ["Sırada",""];
  if(x==="timeout")return ["Bu tur atlandı",""];
  if(x==="parse-failed")return ["Okunamadı",""];
  return ["Sırada / tekrar denenecek",""];
}
function render(){
  const fresh=getState();
  const now=Date.now();

  if(pendingEnabled!==null){
    if(fresh.enabled===pendingEnabled || now-pendingEnabledAt>PENDING_MS){
      pendingEnabled=null;
    }else{
      fresh.enabled=pendingEnabled;
    }
  }

  if(pendingAllMode!==null){
    if(fresh.allMatchesMode===pendingAllMode || now-pendingAllAt>PENDING_MS){
      pendingAllMode=null;
    }else{
      fresh.allMatchesMode=pendingAllMode;
    }
  }

  state=fresh;
  const matches=state.matches||[],stats=state.stats||[],signals=state.signals||[];
  const statMap=Object.fromEntries(stats.map(s=>[s.matchId,s]));

  $("monitorStatus").textContent=(state.enabled?"Takip açık · ":"Takip kapalı · ")+(state.monitorStatus||"");
  $("tracking").classList.toggle("on",!!state.enabled);
  $("signalCount").textContent=signals.length;
  $("candidateCount").textContent=state.candidateCount||0;
  $("matchCount").textContent=matches.length;
  $("statsTab").textContent=`İstatistikler (${matches.length})`;
  $("signalsTab").textContent=`Bildirimler (${signals.length})`;
  $("allMode").textContent=`Tüm Maçlar Modu: ${state.allMatchesMode?"Açık":"Kapalı"}`;
  $("modeHelp").textContent=state.allMatchesMode
    ? "Tüm canlı maçlar · yalnızca 15–30 0-0 ve 60–82 0-0"
    : "Takipli takımlar · tüm kurallar";
  $("scanState").textContent=state.currentWorkerId?"Okunuyor…":state.queueSize>0?`Sırada ${state.queueSize}`:"Hazır";

  const sorted=[...matches].sort((a,b)=>(b.minute||0)-(a.minute||0));
  $("statsView").innerHTML=sorted.length?sorted.map(m=>{
    const s=statMap[m.id]||{};const [status,cls]=statStatus(s);
    const hs=finite(s.homeSot)?s.homeSot:"?",as=finite(s.awaySot)?s.awaySot:"?";
    const hb=finite(s.homeBig)?s.homeBig:"?",ab=finite(s.awayBig)?s.awayBig:"?";
    return `<article class="card">
      <div class="topline"><span>${esc(m.league||"")}</span><span class="status ${cls}">${status}</span></div>
      <div class="team">${esc(m.minute)}' ${esc(m.home)} <span class="score">${esc(m.homeScore)}-${esc(m.awayScore)}</span> ${esc(m.away)}</div>
      <div class="metric">🎯 İsabetli şut: <b>${hs} - ${as}</b></div>
      <div class="metric">🔥 Big chances: <b>${hb} - ${ab}</b></div>
      <div class="submetric">Deneme: ${s.attempts||0}</div>
      <div class="meta"><span>${redText(m)}</span></div>
    </article>`;
  }).join(""):`<div class="empty">Henüz canlı maç verisi yok.</div>`;

  $("signalsView").innerHTML=signals.length?[...signals].reverse().map(sig=>{
    const m=sig.match||{};const s=sig.stat||statMap[sig.matchId]||{};
    const hs=finite(s.homeSot)?s.homeSot:"?",as=finite(s.awaySot)?s.awaySot:"?";
    const hb=finite(s.homeBig)?s.homeBig:"?",ab=finite(s.awayBig)?s.awayBig:"?";
    return `<article class="card">
      <div class="topline"><span>${esc(m.league||"")}</span><span class="rule">${esc(sig.ruleName||sig.rule)}</span></div>
      <div class="team">${esc(m.minute)}' ${esc(m.home)} <span class="score">${esc(m.homeScore)}-${esc(m.awayScore)}</span> ${esc(m.away)}</div>
      <div class="metric">🎯 İsabetli şut: <b>${hs} - ${as}</b></div>
      <div class="metric">🔥 Big chances: <b>${hb} - ${ab}</b></div>
      <div class="meta"><span>🔔 ${fmtTime(sig.createdAt)}</span><span>${redText(m)}</span></div>
    </article>`;
  }).join(""):`<div class="empty">Henüz aktif bildirim yok.</div>`;
}


function bindTap(el, handler){
  if(!el)return;
  let last=0;

  const fire=e=>{
    const now=Date.now();
    if(now-last<450)return;
    last=now;

    if(e){
      try{e.preventDefault();}catch(_){}
      try{e.stopPropagation();}catch(_){}
    }

    el.classList.add("pressed");
    setTimeout(()=>el.classList.remove("pressed"),140);

    try{handler();}catch(err){
      console.error("tap action failed",err);
    }
  };

  // Android WebView'da touchend doğrudan ve hızlı çalışır.
  el.addEventListener("touchend",fire,{passive:false});
  // Mouse / stylus / accessibility için click fallback.
  el.addEventListener("click",fire,false);
}

for(const btn of document.querySelectorAll(".tab"))bindTap(btn,()=>{
  currentTab=btn.dataset.tab;
  document.querySelectorAll(".tab").forEach(x=>x.classList.toggle("active",x===btn));
  $("statsView").classList.toggle("hidden",currentTab!=="stats");
  $("signalsView").classList.toggle("hidden",currentTab!=="signals");
});

bindTap($("tracking"),()=>{
  const next=!state.enabled;
  pendingEnabled=next;
  pendingEnabledAt=Date.now();
  state.enabled=next;
  $("tracking").classList.toggle("on",next);
  $("monitorStatus").textContent=next
    ? "Takip açılıyor…"
    : "Takip kapatılıyor…";

  try{
    MobileBridge.setTrackingEnabled(next);
  }catch(e){
    console.error(e);
  }
});

bindTap($("allMode"),()=>{
  const next=!state.allMatchesMode;
  pendingAllMode=next;
  pendingAllAt=Date.now();
  state.allMatchesMode=next;
  $("allMode").textContent=`Tüm Maçlar Modu: ${next?"Açık":"Kapalı"}`;
  $("modeHelp").textContent=next
    ? "Tüm canlı maçlar · yalnızca 15–30 0-0 ve 60–82 0-0"
    : "Takipli takımlar · tüm kurallar";

  try{
    MobileBridge.setAllMatchesMode(next);
  }catch(e){
    console.error(e);
  }
});

bindTap($("scanNow"),()=>{
  $("scanState").textContent="Tarama başlatılıyor…";
  try{MobileBridge.scanNow();}catch(e){console.error(e);}
});

bindTap($("openFlashscore"),()=>{
  try{MobileBridge.openFlashscore();}catch(e){console.error(e);}
});

render();
setInterval(render,2000);
