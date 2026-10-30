const icons = {
  book:'<path d="M5 3h14v18H5a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3Z"/><path d="M6 3v18M11 8h4m-4 5h4"/>',
  today:'<rect x="3" y="4" width="18" height="17" rx="3"/><path d="M8 2v4m8-4v4M3 10h18m-13 5h3m3 0h2"/>',
  calendar:'<rect x="3" y="4" width="18" height="17" rx="3"/><path d="M8 2v4m8-4v4M3 10h18m-13 5h.01m4 0h.01m4 0h.01M8 18h.01m4 0h.01"/>',
  search:'<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
  settings:'<path d="m9.4 3-.7 2.4-2.4 1L4 5.9 2 9.3 3.8 11v2L2 14.7 4 18l2.3-.5 2.4 1 .7 2.5h5.2l.7-2.5 2.4-1 2.3.5 2-3.3-1.8-1.7v-2L22 9.3l-2-3.4-2.3.5-2.4-1-.7-2.4Z"/><circle cx="12" cy="12" r="3"/>',
  plus:'<path d="M12 5v14M5 12h14"/>',
  chevron:'<path d="m9 5 7 7-7 7"/>',
  left:'<path d="m15 5-7 7 7 7"/>',
  arrow:'<path d="M4 12h15m-5-5 5 5-5 5"/>',
  back:'<path d="M20 12H5m5-5-5 5 5 5"/>',
  shield:'<path d="M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6Z"/><path d="m8 12 3 3 5-6"/>',
  lock:'<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/>',
  mic:'<rect x="9" y="2" width="6" height="13" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"/>',
  screen:'<rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8m-4-4v4"/>',
  pause:'<path d="M8 5v14m8-14v14"/>',
  play:'<path d="m8 4 12 8-12 8Z"/>',
  stop:'<rect x="5" y="5" width="14" height="14" rx="2"/>',
  edit:'<path d="m15 4 5 5M4 20l5-1L21 7a2 2 0 0 0-5-5L4 14Z"/>',
  feather:'<path d="M20 3c-7-2-15 4-14 12l4 2c7-2 11-7 10-14ZM4 21l12-13m-7 8v-6m0 6h6"/>',
  star:'<path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9Z"/>',
  clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  close:'<path d="m6 6 12 12M6 18 18 6"/>',
  info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v.1"/>',
  check:'<path d="m5 12 4 4L20 5"/>',
  file:'<path d="M14 2H5a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9Zm0 0v7h7M7 13h9m-9 4h6"/>',
  download:'<path d="M12 3v12m-4-4 4 4 4-4M4 16v5h16v-5"/>',
  leaf:'<path d="M20 3c-2 3-9 0-14 4-4 4-1 12 5 12 7 0 10-8 9-16ZM4 21 15 10"/>',
  moon:'<path d="M20.5 13.2A9 9 0 0 1 10.8 3 9 9 0 1 0 20.5 13.2Z"/>',
  folder:'<path d="M3 6a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/>',
  undo:'<path d="M4 4v6h6m-6 0a8 8 0 1 1 1 9"/>',
  sprout:'<path d="M12 22v-9M12 14C4 14 2 9 3 5c7-1 10 3 9 9Zm0-4c0-6 4-9 10-8 0 6-3 10-10 10"/>',
};
const icon = name => `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.file}</svg>`;
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const TODAY = '2026-10-27';
const STORAGE = 'blackbox-diary-preview-v1';
const seedEvents = [
  {id:'evt-1',date:TODAY,time:'09:12',end:'10:06',source:'screen',title:'读了几篇关于本地 AI 的文章',description:'浏览器 · 模型部署、离线推理',text:'浏览页面：在移动设备上运行本地语言模型\n阅读了关于模型量化、设备内存和离线推理的资料。\n页面文字包含：INT4、Android、端侧模型部署。',confidence:'较高'},
  {id:'evt-2',date:TODAY,time:'11:30',end:'11:52',source:'audio',title:'聊了聊新项目的想法',description:'语音记录 · 22 分钟',text:'“我们先把最重要的流程做好。记录之后转成文字，再整理成日记。界面尽量简单，让人一眼能看懂。”\n部分语音因背景噪声未能清楚识别。',confidence:'部分文字低可信'},
  {id:'gap-1',date:TODAY,time:'12:40',end:'13:18',source:'gap',title:'这段时间，没有屏幕记录',description:'12:40–13:18 · 锁屏后屏幕采集会话结束',text:'屏幕采集因锁屏结束。该时段的麦克风记录独立运行。\n未获得屏幕活动证据，不补写这段时间发生的事情。',confidence:'系统状态'},
  {id:'evt-3',date:TODAY,time:'14:10',end:'15:24',source:'screen',title:'把项目需求又梳理了一遍',description:'文档阅读 · 黑匣子日记 PRD',text:'黑匣子日记（BlackBox Diary）\n产品需求文档 V1.0\n核心内容：本地离线处理、事件流水账、每日事实日志、自然语言日记、历史检索。',confidence:'较高'},
  {id:'evt-4',date:TODAY,time:'16:20',end:'16:20',source:'note',title:'给今天留一句话',description:'我的手记',text:'想把这个小工具做得简单一点。能安静地记住一些容易忘掉的小事，就很好。',confidence:'用户手写'},
  {id:'evt-5',date:'2026-10-26',time:'09:40',end:'10:20',source:'screen',title:'整理一周的工作安排',description:'文档阅读 · 工作计划',text:'本周工作计划：梳理产品需求，阅读离线模型资料，确认前端页面和基本交互。',confidence:'较高'},
  {id:'evt-6',date:'2026-10-26',time:'18:05',end:'18:25',source:'audio',title:'讨论周末去公园的安排',description:'语音记录 · 20 分钟',text:'“周末要不要去公园走走？可以带本书，找一个安静的地方坐一会儿。”',confidence:'较高'},
  {id:'evt-7',date:'2026-10-25',time:'15:16',end:'16:10',source:'screen',title:'读了一篇关于秋天的散文',description:'浏览器 · 阅读',text:'阅读文章：秋日里的日常。文中提到树叶、散步，以及生活里细小的变化。',confidence:'较高'},
  {id:'evt-8',date:'2026-10-24',time:'10:12',end:'11:00',source:'screen',title:'查了几家附近的书店',description:'地图 · 书店',text:'搜索关键词：附近的独立书店。查看了书店的位置和营业时间。',confidence:'较高'},
  {id:'evt-9',date:'2026-10-22',time:'14:20',end:'15:00',source:'screen',title:'继续学习 Kotlin',description:'浏览器 · Android 开发',text:'Kotlin 基础：数据类、协程和 Flow。阅读 Android 开发文档中的界面状态管理内容。',confidence:'较高'},
];
const defaultDiary = {
  title:'在阅读与交谈之间',
  prose:'上午，读了几篇关于本地 AI 模型的文章，从模型量化看到手机上的离线推理，留下了一些值得继续研究的线索。\n\n临近中午，和人聊起了新项目的想法。讨论里提到，先把记录、整理和回顾这条流程做好，界面也尽量简单。\n\n下午又打开了需求文档，把黑匣子日记的功能梳理了一遍。关于今天的想法，我自己补了一句：“能安静地记住一些容易忘掉的小事，就很好。”',
};
const diaryTitles = {'2026-10-26':'从一周的计划开始','2026-10-25':'周日，读一点闲书','2026-10-24':'留给书店的一个上午','2026-10-22':'继续学一点新东西'};
let saved = {};
try { saved = JSON.parse(localStorage.getItem(STORAGE) || '{}'); } catch {}
const state = {
  page:'today',tab:'diary',returnPage:'calendar',selectedDate:TODAY,month:9,year:2026,miniMonth:9,miniYear:2026,
  query:'',source:'all',range:'all',onlyFavorites:false,generating:false,
  mic:true,screen:true,paused:false,pauseUntil:0,notes:[],diaries:{},favorites:[],history:[],
  settings:{lock:true,biometric:false,mask:true,quiet:true,power:true,length:'标准',tone:'生活化',generateAt:'02:00'},
  ...saved,
};
state.page = ['today','calendar','search','settings','day'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'today';
state.settings={lock:true,biometric:false,mask:true,quiet:true,power:true,length:'标准',tone:'生活化',generateAt:'02:00',...saved.settings};
let lastFocus = null, toastTimer, generationTimer;
const sourceLabels={screen:'屏幕文字',audio:'语音转写',note:'我的手记',gap:'采集缺口'};
const sourceIcons={screen:'screen',audio:'mic',note:'edit',gap:'pause'};
function persist(){
  const {notes,diaries,favorites,history,mic,screen,paused,pauseUntil,settings}=state;
  try{localStorage.setItem(STORAGE,JSON.stringify({notes,diaries,favorites,history,mic,screen,paused,pauseUntil,settings}));}catch{toast('浏览器无法保存偏好；本次预览仍可继续使用');}
}
function allEvents(){return [...seedEvents,...state.notes];}
function dayEvents(date){return allEvents().filter(e=>e.date===date).sort((a,b)=>a.time.localeCompare(b.time));}
function isFavorite(date){return state.favorites.includes(date);}
function dayInfo(date){
  if(state.diaries[date])return state.diaries[date];
  if(date===TODAY)return defaultDiary;
  const events=dayEvents(date).filter(e=>e.source!=='gap');
  return {title:diaryTitles[date]||'这一天的小小记录',prose:events.length?events.map(e=>`${e.time}，${e.title}。${e.text}`).join('\n\n'):'这一天还没有留下记录。你可以补写一段手记，把想记住的事情留在这里。'};
}
function dateParts(date){const d=new Date(date+'T12:00:00');return {day:d.getDate(),month:d.getMonth()+1,weekday:['星期日','星期一','星期二','星期三','星期四','星期五','星期六'][d.getDay()]};}
function dateLabel(date){const p=dateParts(date);return `${p.month}月${p.day}日 · ${p.weekday}`;}
function go(page,date){
  if(page==='day'&&state.page!=='day')state.returnPage=state.page;
  state.page=page;if(date)state.selectedDate=date;
  if(page==='day')state.tab='diary';
  if(location.hash.slice(1)!==page)history.pushState(null,'','#'+page);
  render();window.scrollTo({top:0,behavior:'instant'});
  requestAnimationFrame(()=>document.getElementById('main')?.focus({preventScroll:true}));
}
function navItem(page,label,name,mobile=false){
  const parentPage=['today','calendar','search'].includes(state.returnPage)?state.returnPage:'calendar';
  const selected=state.page===page||(state.page==='day'&&page===parentPage);
  return `<button class="nav-item ${selected?'active':''}" data-action="nav" data-page="${page}" ${selected?'aria-current="page"':''}>${icon(name)}<span>${label}</span></button>`;
}
function render(){
  if(state.pauseUntil&&Date.now()>=state.pauseUntil){state.paused=false;state.pauseUntil=0;persist();}
  const activeLabel={today:'今天',calendar:'日历',search:'搜索',settings:'设置',day:'日记'}[state.page];
  document.title=`黑匣子日记 · ${activeLabel}`;
  document.getElementById('app').innerHTML=`<div class="app-shell"><div class="workspace">
    <header class="topbar"><div class="app-wordmark">${state.page==='day'?`<button class="icon-button app-back" data-action="nav" data-page="${['today','calendar','search'].includes(state.returnPage)?state.returnPage:'calendar'}" aria-label="返回上一页">${icon('back')}</button><span>日记</span>`:`${icon('book')}<span>黑匣子日记</span>`}</div><div class="app-header-actions"><span class="app-demo-tag">示例</span><button class="icon-button app-compose" data-action="note" aria-label="写一笔">${icon('edit')}</button></div></header>
    <main id="main" class="page" tabindex="-1">${({today:todayPage,calendar:calendarPage,search:searchPage,settings:settingsPage,day:dayPage}[state.page]||todayPage)()}</main></div>
    <nav class="mobile-nav" aria-label="底部导航">${navItem('today','今天','today',true)}${navItem('calendar','日历','calendar',true)}${navItem('search','搜索','search',true)}${navItem('settings','设置','settings',true)}</nav>
  </div>`;
  if(window.parent!==window)window.parent.postMessage({type:'blackbox:page',page:state.page},location.origin==='null'?'*':location.origin);
}

function pageHeader(eyebrow,title,subtitle,extra=''){return `<div class="page-header"><div><p class="eyebrow">${eyebrow}</p><h1 class="page-title">${title}</h1><p class="page-subtitle">${subtitle}</p></div>${extra}</div>`;}
function recordingBar(){
  const stopped=!state.mic&&!state.screen;
  const paused=state.paused||stopped;
  const label=stopped?'采集尚未开启':state.paused?'记录已暂停':'正在记录此刻';
  const description=stopped?'在设置中独立开启麦克风或屏幕':state.paused?(state.pauseUntil?`将在 ${new Date(state.pauseUntil).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})} 自动继续`:'直到你准备好，再继续'):`<span class="channel-line">${state.mic?`<span>${icon('mic')}麦克风</span>`:''}${state.screen?`<span>${icon('screen')}屏幕</span>`:''}<span>仅在本机处理</span></span>`;
  return `<section class="recording-bar" aria-label="示例采集状态"><div><div class="status-heading"><i class="status-dot ${paused?'paused':''}"></i>${label}</div><div class="status-description">${description}</div></div><button class="button ${paused?'primary':'dark'}" data-action="${stopped?'channels':state.paused?'resume':'pause'}">${icon(paused?'play':'pause')}${paused?'开始记录':'暂停'}</button></section>`;
}
function journalCard(date,full=false){
  const info=dayInfo(date),events=dayEvents(date),count=date===TODAY&&!state.diaries[date]?4:events.filter(e=>e.source!=='gap').length;
  const edited=!!state.diaries[date];
  const copy=info.prose.split('\n\n').map((p,i)=>`<p>${esc(p)}${date===TODAY&&!edited&&i<3?`<button class="source-ref" data-action="source-ref" data-id="${['evt-1','evt-2','evt-3'][i]}" aria-label="查看第 ${i+1} 段的来源">[${i+1}]</button>${i===2?'<button class="source-ref" data-action="source-ref" data-id="evt-4" aria-label="查看引用手记的来源">[4]</button>':''}`:''}</p>`).join('');
  return `<article class="journal-card"><div class="journal-head"><div class="journal-kicker">${icon('book')}<span>${date===TODAY?'今天的日记':'这一天的日记'}</span>${edited?'<span class="note-badge">已编辑</span>':''}</div><div class="journal-tools"><button class="icon-button ${isFavorite(date)?'favorite-on':''}" data-action="favorite" data-date="${date}" aria-label="${isFavorite(date)?'取消收藏':'收藏这篇日记'}" aria-pressed="${isFavorite(date)}">${icon('star')}</button><button class="icon-button" data-action="edit-diary" data-date="${date}" aria-label="编辑日记">${icon('edit')}</button></div></div>
    <div class="journal-body"><p class="journal-date">${date.replaceAll('-',' / ')} &nbsp; · &nbsp; ${date===TODAY?'TUESDAY':'DIARY'}</p><h2 class="journal-title">${esc(info.title)}</h2><div class="journal-copy">${copy}</div><div class="journal-footnote">${icon('info')} ${edited?'已保留原始版本，可在编辑中恢复。':'依据已记录的文字整理，未记录的时段不作补写。'}</div></div>
    <footer class="journal-footer"><div class="source-summary"><span>${icon('file')}${count} 条来源依据</span><span>${icon('shield')}本地整理</span></div><button class="text-link" data-action="${full?'day-tab':'open-day'}" ${full?'data-tab="timeline"':`data-date="${date}"`}>${full?'查看事实日志':'阅读完整日记'}${icon('arrow')}</button></footer></article>`;
}
function timelineMarkup(events){return `<div class="timeline">${events.map(e=>`<div class="timeline-item ${e.source==='gap'?'event-gap':''}"><time class="timeline-time">${esc(e.time)}</time><span class="timeline-icon">${icon(sourceIcons[e.source])}</span><button class="timeline-event" data-action="event" data-id="${e.id}"><h3 class="event-title">${esc(e.title)}</h3><p class="event-description">${esc(e.description)}</p>${e.source==='note'?'<p class="event-meta">手动添加 · 只属于自己的话</p>':''}</button></div>`).join('')}</div>`;}
function coverageMarkup(){return `<div class="coverage-track" role="img" aria-label="示例覆盖时间轴：上午 8 点至晚上 8 点，12:40 至 13:18 存在屏幕采集缺口">${Array.from({length:42},(_,i)=>`<i class="${i>=16&&i<19?'gap':i>35?'short':''}"></i>`).join('')}</div><div class="coverage-labels"><span>08:00</span><span>12:00</span><span>16:00</span><span>20:00</span></div>`;}
function todayPage(){
  const events=dayEvents(TODAY),count=events.filter(e=>e.source!=='gap').length;
  return `${pageHeader('','今天','10 月 27 日，星期二',`<div class="date-display"><span>27</span><i class="date-divider"></i><div>十月<br>2026</div></div>`)}
    <div class="main-column">${recordingBar()}
    <button class="capture-overview" data-action="report" aria-label="查看两路采集报告"><span class="capture-metric"><span class="capture-metric-label">${icon('mic')}麦克风</span><span class="capture-metric-value">8<small>时</small>06<small>分</small></span></span><i class="capture-metric-divider"></i><span class="capture-metric"><span class="capture-metric-label">${icon('screen')}屏幕采样</span><span class="capture-metric-value">6<small>时</small>42<small>分</small></span></span><span class="capture-report-link">采集报告${icon('chevron')}</span></button>
    ${journalCard(TODAY)}
    <section aria-label="今天的时间线"><div class="section-heading"><h2>一天的片段<small>${count} 条记录</small></h2><button class="text-link" data-action="open-timeline" data-date="${TODAY}">查看全部${icon('arrow')}</button></div>${timelineMarkup(events)}</section></div><p class="footer-line">${icon('lock')}所有记录，留在本机。</p>`;
}

function calendarCells(year,month){
  const first=new Date(year,month,1),offset=(first.getDay()+6)%7,start=new Date(year,month,1-offset);
  const length=Math.max(35,Math.ceil((offset+new Date(year,month+1,0).getDate())/7)*7);
  return Array.from({length},(_,i)=>{const d=new Date(start);d.setDate(start.getDate()+i);return {n:d.getDate(),other:d.getMonth()!==month,date:`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`};});
}
function hasRecord(date){return dayEvents(date).some(e=>e.source!=='gap');}
function miniCalendar(){return `<div class="mini-grid">${['一','二','三','四','五','六','日'].map(w=>`<span class="weekday">${w}</span>`).join('')}${calendarCells(state.miniYear,state.miniMonth).map(d=>`<button class="mini-day ${d.other?'other':''} ${d.date===TODAY?'today':''} ${hasRecord(d.date)?'has-record':''}" data-action="open-day" data-date="${d.date}" aria-label="${d.date}${hasRecord(d.date)?'，有记录':'，无记录'}">${d.n}</button>`).join('')}</div>`;}
function calendarPage(){
  const info=dayInfo(state.selectedDate),has=hasRecord(state.selectedDate),p=dateParts(state.selectedDate);
  return `${pageHeader('DAYS WORTH KEEPING','日历','翻到某一天，重温那些日常。')}<div class="calendar-layout"><section class="calendar-panel"><div class="calendar-top"><h2>${state.year} 年 ${state.month+1} 月</h2><div class="month-arrows"><button class="icon-button" data-action="month" data-delta="-1" aria-label="上个月">${icon('left')}</button><button class="button ghost" data-action="this-month">本月</button><button class="icon-button" data-action="month" data-delta="1" aria-label="下个月">${icon('chevron')}</button></div></div><div class="large-grid">${['一','二','三','四','五','六','日'].map(w=>`<span class="weekday">${w}</span>`).join('')}${calendarCells(state.year,state.month).map(d=>`<button class="large-day ${d.other?'other':''} ${d.date===state.selectedDate?'selected':''}" data-action="select-date" data-date="${d.date}" aria-label="${d.date}，${hasRecord(d.date)?d.date===TODAY?'部分覆盖':'有记录':'无记录'}" aria-pressed="${d.date===state.selectedDate}"><span>${d.n}</span><i class="day-indicator ${hasRecord(d.date)?d.date===TODAY?'partial':'':'empty'}"></i></button>`).join('')}</div><div class="large-legend"><span><i></i>有记录</span><span><i class="partial"></i>部分覆盖</span><span><i class="empty"></i>无记录</span></div></section>
  <section class="day-preview"><p class="eyebrow">${p.month} 月 ${p.day} 日 · ${p.weekday}</p><h2>${has?esc(info.title):'这页，还是空白的'}</h2><p>${has?esc(info.prose.slice(0,115))+'…':'这一天没有采集到活动记录。若还有想记住的事情，可以补写一笔。'}</p><div class="stat-label">${has?`${dayEvents(state.selectedDate).filter(e=>e.source!=='gap').length} 条文字记录${state.selectedDate===TODAY?' · 存在屏幕采集缺口':''}`:'暂无来源记录'}</div><button class="button ${has?'primary':''}" data-action="${has?'open-day':'note'}" data-date="${state.selectedDate}">${has?'翻开这一天':'补写一笔'}${icon(has?'arrow':'edit')}</button></section></div>`;
}
function dayPage(){
  const date=state.selectedDate,events=dayEvents(date),tabs=[['diary','日记'],['timeline','事实日志'],['raw','识别文字'],['report','采集报告']];
  let content='';
  if(state.tab==='diary')content=journalCard(date,true)+`<div class="section-heading"><span class="generation-info">${icon('shield')}示例日记 · ${state.diaries[date]?'已手动编辑':'依据文字事件整理'}</span><button class="text-link" data-action="regenerate" data-date="${date}" ${state.generating?'disabled':''}>${icon('undo')}${state.generating?'正在整理…':'重新整理'}</button></div>`;
  if(state.tab==='timeline')content=events.length?timelineMarkup(events):emptyState('这一天还没有记录','补写一段手记，也是一种记录。');
  if(state.tab==='raw')content=events.filter(e=>e.source!=='gap').map(e=>`<article class="raw-event"><div class="event-meta">${icon(sourceIcons[e.source])}${sourceLabels[e.source]} · ${e.time}–${e.end}<span> · ${e.confidence}</span></div><p>${esc(e.text).replaceAll('\n','<br>')}</p><button class="text-link" data-action="event" data-id="${e.id}">查看来源${icon('arrow')}</button></article>`).join('')||emptyState('还没有识别文字','有了采集记录，这里就会有据可查。');
  if(state.tab==='report')content=reportContent(date);
  const parts=dateParts(date),subtitle=`${date.slice(0,4)} 年 · ${parts.weekday}${date===TODAY?' · 部分覆盖':''}`;
  return `${pageHeader('A PAGE OF YOUR LIFE',`${parts.month}月${parts.day}日`,subtitle,`<button class="button" data-action="export" data-date="${date}">${icon('download')}导出日记</button>`)}<div class="tab-row" role="tablist" aria-label="日记详情">${tabs.map(([t,l])=>`<button class="tab ${state.tab===t?'active':''}" role="tab" aria-selected="${state.tab===t}" data-action="day-tab" data-tab="${t}">${l}</button>`).join('')}</div><div class="reading-paper" role="tabpanel">${content}</div>`;
}
function reportContent(date){
  if(!hasRecord(date))return emptyState('这一天没有采集报告','未记录的时段，不会被当作已经记录。');
  return `<p class="eyebrow">示例采集报告 · ${dateLabel(date)}</p>${coverageMarkup()}<div class="report-legend"><span><i></i>已采集</span><span><i style="background:#e0e5d6"></i>缺口或未采集</span></div><div class="report-row"><span>屏幕有效采集</span><strong>${date===TODAY?'6 小时 42 分钟':'4 小时 16 分钟'}</strong></div><div class="report-row"><span>麦克风有效采集</span><strong>${date===TODAY?'8 小时 06 分钟':'6 小时 20 分钟'}</strong></div><div class="report-row"><span>文字事件</span><strong>${dayEvents(date).filter(e=>e.source!=='gap').length} 条</strong></div><div class="report-row"><span>屏幕采集缺口</span><strong>${date===TODAY?'12:40–13:18 · 锁屏':'无已知中断'}</strong></div><div class="report-row"><span>原始媒体缓存</span><strong>示例：处理完成后已清理</strong></div><p class="report-callout">录音与屏幕采集各自独立。没有采集到内容，不等于这段时间没有发生事情。日记只整理已有依据的记录。</p><p class="preview-notice">以上是界面示例，当前浏览器未启动录音或屏幕采集。</p>`;
}
function highlight(text,q){if(!q)return esc(text);const safe=esc(text),needle=esc(q).replace(/[.*+?^${}()|[\]\\]/g,'\\$&');try{return safe.replace(new RegExp(needle,'gi'),m=>`<mark>${m}</mark>`);}catch{return safe;}}
function filteredResults(){
  const q=state.query.trim().toLowerCase();
  return allEvents().filter(e=>e.source!=='gap'&&(!q||`${e.title} ${e.text} ${e.description}`.toLowerCase().includes(q))&&(state.source==='all'||state.source===e.source)&&(state.range==='all'||(state.range==='today'?e.date===TODAY:e.date>='2026-10-21'))&&(!state.onlyFavorites||isFavorite(e.date))).sort((a,b)=>b.date.localeCompare(a.date)||b.time.localeCompare(a.time));
}
function resultsMarkup(){
  const q=state.query.trim(),results=filteredResults();
  const active=!!q||state.source!=='all'||state.range!=='all'||state.onlyFavorites;
  if(!active)return `<div class="search-prompt"><h2>从一段记忆开始找起</h2><div class="suggestion-list">${['本地 AI','项目','书店','周末'].map(q=>`<button class="chip" data-action="suggest" data-query="${q}">${icon('clock')}${q}</button>`).join('')}</div></div>${state.history.length?`<div class="search-prompt"><h2>最近找过 <button class="text-link" data-action="clear-history">清除</button></h2><div class="suggestion-list">${state.history.slice(0,6).map(q=>`<button class="chip" data-action="suggest" data-query="${esc(q)}">${esc(q)}</button>`).join('')}</div></div>`:''}<div class="empty-state">${icon('search')}<h2>那些想不起的小事</h2><p>用关键词，在自己的日子里找一找。</p></div>`;
  return `<p class="result-count">找到 ${results.length} 条相关记录${q?` · “${esc(q)}”`:''}</p>${results.length?results.map(e=>`<button class="search-result" data-action="event" data-id="${e.id}"><div class="event-meta">${dateLabel(e.date)}<span> · </span>${e.time}<span> · </span>${icon(sourceIcons[e.source])}${sourceLabels[e.source]}</div><h2 class="result-title">${highlight(e.title,q)}</h2><p>${highlight(e.text.slice(0,145),q)}</p></button>`).join(''):emptyState('没有找到这段记忆','换一个关键词，或试着扩大日期和来源范围。')}`;
}
function searchPage(){return `${pageHeader('SOMEWHERE IN YOUR DAYS','搜索','想起一个词，找回一段日常。')}<div class="settings-layout"><label class="field-label" for="search-input">搜索你的文字记录</label><div class="search-input-wrap">${icon('search')}<input id="search-input" class="search-input" type="search" value="${esc(state.query)}" placeholder="例如：项目、书店、那次讨论…" autocomplete="off" /><button class="icon-button search-clear" data-action="clear-search" aria-label="清空搜索">${icon('close')}</button></div><div class="search-filter-row">${[['all','全部来源'],['screen','屏幕文字'],['audio','语音转写'],['note','我的手记']].map(([v,l])=>`<button class="chip ${state.source===v?'active':''}" data-action="search-source" data-source="${v}" aria-pressed="${state.source===v}">${l}</button>`).join('')}<button class="chip ${state.range!=='all'?'active':''}" data-action="search-range">${icon('calendar')}${{all:'不限日期',today:'仅今天',week:'最近一周'}[state.range]}</button><button class="chip ${state.onlyFavorites?'active':''}" data-action="only-favorites" aria-pressed="${state.onlyFavorites}">${icon('star')}收藏</button></div><div id="search-results" aria-live="polite">${resultsMarkup()}</div><p class="preview-notice">当前为关键词检索预览，搜索范围是本机浏览器中的示例记录。</p></div>`;}
function switchMarkup(key,value,action='setting'){return `<button class="switch ${value?'on':''}" data-action="${action}" data-key="${key}" role="switch" aria-checked="${value}" aria-label="${{mic:'麦克风采集',screen:'屏幕采集',lock:'应用密码锁',biometric:'生物识别',mask:'敏感内容遮蔽',quiet:'睡眠时段暂停',power:'低功耗模式'}[key]||key}"></button>`;}
function settingRow(name,description,right,nameIcon=''){return `<div class="setting-row"><div><h3 class="setting-name">${nameIcon?icon(nameIcon):''}${name}</h3><p class="setting-description">${description}</p></div>${right}</div>`;}
function settingsPage(){return `${pageHeader('MAKE ROOM FOR YOURSELF','设置','记录多少，保留多久，都由你决定。')}<div class="settings-layout"><section class="settings-section"><h2>采集与暂停</h2><div class="settings-group">${settingRow('麦克风采集','独立记录环境中的语音片段',switchMarkup('mic',state.mic,'channel'),'mic')}${settingRow('屏幕采集','仅在你明确授权的会话内采集',switchMarkup('screen',state.screen,'channel'),'screen')}${settingRow('低功耗模式','画面变化时采样，减少无效处理',switchMarkup('power',state.settings.power))}${settingRow('睡眠时段暂停','每日 23:00 至次日 07:00',switchMarkup('quiet',state.settings.quiet))}</div></section>
  <section class="settings-section"><h2>日记的样子</h2><div class="settings-group">${settingRow('自动整理时间','空闲或充电时，整理前一天的记录',`<label><span class="sr-only">自动整理时间</span><input class="settings-select" type="time" value="${esc(state.settings.generateAt)}" data-setting="generateAt" aria-label="自动整理时间"></label>`)}${settingRow('日记长度','给每天留一点恰好的篇幅',`<select class="settings-select" data-setting="length" aria-label="日记长度">${['精简','标准','详细'].map(s=>`<option ${state.settings.length===s?'selected':''}>${s}</option>`).join('')}</select>`)}${settingRow('文字语气','事实不变，表达可以更像自己',`<select class="settings-select" data-setting="tone" aria-label="文字语气">${['生活化','客观'].map(s=>`<option ${state.settings.tone===s?'selected':''}>${s}</option>`).join('')}</select>`)}</div></section>
  <section class="settings-section"><h2>隐私与保护</h2><div class="settings-group">${settingRow('应用密码锁','重新打开日记时，验证你的身份',switchMarkup('lock',state.settings.lock),'lock')}${settingRow('生物识别','使用设备支持的指纹或面容解锁',switchMarkup('biometric',state.settings.biometric))}${settingRow('敏感内容遮蔽','优先过滤验证码、密码与身份号码',switchMarkup('mask',state.settings.mask))}${settingRow('本机离线说明','数据流向、模型与保存方式',`<button class="icon-button" data-action="privacy" aria-label="查看本机离线说明">${icon('chevron')}</button>`,'shield')}</div></section>
  <section class="settings-section"><h2>本地空间</h2><div class="settings-group">${settingRow('存储概览','模型 2.48 GB · 示例文字 18.6 MB',`<div><div class="setting-value">示例 · 2.5 / 8 GB</div><div class="storage-track"><span></span></div></div>`,'folder')}${settingRow('处理缓存','已完成的原始片段会自动清除',`<button class="text-link" data-action="clear-cache">查看</button>`)}${settingRow('重置预览数据','清除这次预览添加的手记、编辑和偏好',`<button class="text-link danger" data-action="reset-preview">重置</button>`)}</div></section><p class="about-version">黑匣子日记 · BLACKBOX DIARY<br>前端预览 0.1.0<br>采集、模型推理与设备加密将在 Android 端接入。</p></div>`;}
function emptyState(title,text){return `<div class="empty-state">${icon('book')}<h2>${title}</h2><p>${text}</p></div>`;}
function toast(message){clearTimeout(toastTimer);const el=document.getElementById('toast');el.textContent=message;el.classList.add('visible');toastTimer=setTimeout(()=>el.classList.remove('visible'),3300);}
function openModal(title,body,footer='',wide=false){
  lastFocus=document.activeElement;
  document.getElementById('modal-root').innerHTML=`<div class="modal-overlay" data-action="dismiss-backdrop"><section class="modal ${wide?'wide':''}" role="dialog" aria-modal="true" aria-labelledby="modal-title"><div class="modal-header"><h2 id="modal-title">${title}</h2><button class="icon-button" data-action="close-modal" aria-label="关闭">${icon('close')}</button></div>${body}${footer?`<div class="modal-footer">${footer}</div>`:''}</section></div>`;
  document.body.style.overflow='hidden';requestAnimationFrame(()=>{const input=document.querySelector('.modal textarea')||document.querySelector('.modal input');(input||document.querySelector('.modal button'))?.focus();});
}
function closeModal(){document.getElementById('modal-root').innerHTML='';document.body.style.overflow='';lastFocus?.isConnected&&lastFocus.focus();}
function noteModal(date=state.page==='day'||state.page==='calendar'?state.selectedDate:TODAY){
  openModal('写一笔',`<p class="modal-description">不用写得完整。此刻想记住的，写下来就好。</p><label class="field-label" for="note-date">记在哪一天</label><input class="text-field" id="note-date" type="date" value="${date}" required><label class="field-label" for="note-text">我的手记</label><textarea id="note-text" class="text-field textarea" placeholder="今天，有什么让你想停下来记一笔？" maxlength="3000"></textarea><p class="modal-caption">手记会保存在当前浏览器中。自动整理不会覆盖你的原话。</p>`,`<button class="button ghost" data-action="close-modal">先不写了</button><button class="button primary" data-action="save-note">${icon('check')}留下这一笔</button>`);
}
function pauseModal(){openModal('暂停一会儿',`<p class="modal-description">有些时刻，只留给自己。暂停期间的采集缺口会如实标注。</p>${[[15,'15 分钟','喝杯水，缓一缓'],[60,'1 小时','给自己一段完整的时间'],[0,'直到手动继续','准备好时，再回来开启记录']].map(([n,title,sub])=>`<button class="choice-row" data-action="set-pause" data-minutes="${n}">${icon(n?'clock':'pause')}<span>${title}<small>${sub}</small></span></button>`).join('')}<p class="modal-caption">当前操作用于演示界面状态，不会调用设备的麦克风或屏幕。</p>`);}
function eventModal(id){const e=allEvents().find(e=>e.id===id);if(!e)return;openModal('一段记录',`<div class="source-label">${icon(sourceIcons[e.source])}${sourceLabels[e.source]} · ${dateLabel(e.date)} · ${e.time}–${e.end}</div><h3 class="event-detail-title" style="margin-top:17px">${esc(e.title)}</h3><div class="source-box">${esc(e.text)}</div><div class="source-label">${icon('shield')}可信程度：${e.confidence}</div><p class="modal-caption">${e.source==='gap'?'这段时间没有屏幕证据，日记不会补写具体活动。':'原始识别文字作为日记依据。示例媒体已处理，无原始音视频可播放。'}</p>`,`<button class="button ghost" data-action="close-modal">关闭</button><button class="button primary" data-action="event-to-day" data-date="${e.date}">回到这一天${icon('arrow')}</button>`);}
function editModal(date){const d=dayInfo(date);openModal('让它更像你的日记',`<p class="modal-description">${dateLabel(date)} · 编辑后保留原始版本，重新整理也不会直接覆盖。</p><label class="field-label" for="edit-title">标题</label><input id="edit-title" class="text-field" value="${esc(d.title)}" maxlength="80"><label class="field-label" for="edit-prose">正文</label><textarea id="edit-prose" class="text-field textarea diary-edit" maxlength="12000">${esc(d.prose)}</textarea>`,`${state.diaries[date]?`<button class="button ghost" data-action="restore-diary" data-date="${date}">${icon('undo')}恢复原稿</button>`:''}<button class="button ghost" data-action="close-modal">取消</button><button class="button primary" data-action="save-diary" data-date="${date}">保存修改</button>`,true);}
function toggleFavorite(date){state.favorites=isFavorite(date)?state.favorites.filter(d=>d!==date):[...state.favorites,date];persist();render();toast(isFavorite(date)?'这一天，已经收藏':'已取消收藏');}
function rememberSearch(){const q=state.query.trim();if(q){state.history=[q,...state.history.filter(x=>x!==q)].slice(0,6);persist();}}
function privacyModal(){openModal('关于本机与隐私',`<p class="modal-description">这是黑匣子日记的前端交互预览。</p><div class="report-row"><span>当前数据</span><strong>示例文字与手动输入</strong></div><div class="report-row"><span>保存位置</span><strong>本机浏览器存储</strong></div><div class="report-row"><span>音视频采集</span><strong>未启动</strong></div><div class="report-row"><span>模型推理</span><strong>尚未接入</strong></div><p class="modal-caption">正式 Android 版将按 PRD 实现数据库与媒体加密、系统授权、本地识别及推理。本预览的密码锁与生物识别开关只演示偏好设置，不提供实际加密或身份验证。请使用示例内容体验。</p>`,`<button class="button primary" data-action="close-modal">知道了</button>`);}
function exportDiary(date){const d=dayInfo(date);const text=`# ${d.title}\n\n${dateLabel(date)}\n\n${d.prose}\n\n---\n黑匣子日记 · 前端预览示例\n`;const blob=new Blob([text],{type:'text/markdown;charset=utf-8'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=`日记-${date}.md`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1500);toast('日记已导出为 Markdown');}
function regenerate(date){
  if(state.generating)return;
  state.generating=true;render();toast('正在用示例记录演示整理流程…');
  clearTimeout(generationTimer);generationTimer=setTimeout(()=>{
    state.generating=false;render();
    const info=date===TODAY?defaultDiary:dayInfo(date);
    openModal('新的整理候选',`<p class="modal-description">这是整理流程的演示。原有版本会保留，只有你确认后才替换。</p><h3 class="event-detail-title">${esc(info.title)}</h3><div class="source-box">${esc(info.prose)}</div><p class="modal-caption">当前使用固定示例内容，尚未调用本地语言模型。</p>`,`<button class="button ghost" data-action="close-modal">保留现在的版本</button><button class="button primary" data-action="accept-candidate" data-date="${date}">采用候选版本</button>`,true);
  },1200);
}
document.addEventListener('click',event=>{
  const target=event.target.closest('[data-action]');if(!target)return;
  const {action,date,id,key,tab}=target.dataset;
  if(action==='dismiss-backdrop'){if(event.target===target)closeModal();return;}
  switch(action){
    case 'nav':go(target.dataset.page);break;
    case 'open-day':go('day',date);break;
    case 'open-timeline':go('day',date);state.tab='timeline';render();break;
    case 'select-date':state.selectedDate=date;render();break;
    case 'note':noteModal(date);break;
    case 'close-modal':closeModal();break;
    case 'save-note':{
      const text=document.getElementById('note-text').value.trim(),inputDate=document.getElementById('note-date');
      if(!inputDate.reportValidity())break;
      if(!text){document.getElementById('note-text').focus();toast('先写下一点想记住的事情');break;}
      const now=new Date(),time=now.toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',hour12:false});
      state.notes.push({id:'note-'+Date.now(),date:inputDate.value,time,end:time,source:'note',title:text.length>27?text.slice(0,27)+'…':text,description:'我的手记 · 刚刚留下',text,confidence:'用户手写'});
      persist();closeModal();render();toast('这一笔，已经留好了');break;
    }
    case 'pause':pauseModal();break;
    case 'set-pause':state.paused=true;state.pauseUntil=Number(target.dataset.minutes)?Date.now()+Number(target.dataset.minutes)*60000:0;persist();closeModal();render();toast('记录已暂停，给自己一点时间');break;
    case 'resume':state.paused=false;state.pauseUntil=0;persist();render();toast('示例记录已继续');break;
    case 'channels':go('settings');toast('选择要开启的采集来源');break;
    case 'channel':state[key]=!state[key];persist();render();toast(`${key==='mic'?'麦克风':'屏幕'}示例采集已${state[key]?'开启':'关闭'}`);break;
    case 'setting':state.settings[key]=!state.settings[key];persist();render();toast(['lock','biometric'].includes(key)?'预览偏好已保存，设备验证将在 Android 端接入':'设置已保存');break;
    case 'favorite':toggleFavorite(date);break;
    case 'source-ref':case 'event':rememberSearch();eventModal(id);break;
    case 'event-to-day':closeModal();go('day',date);state.tab='raw';render();break;
    case 'edit-diary':editModal(date);break;
    case 'save-diary':{
      const title=document.getElementById('edit-title').value.trim(),prose=document.getElementById('edit-prose').value.trim();
      if(!title||!prose){toast('标题和正文都要留一点内容');break;}
      const previous=state.diaries[date];state.diaries[date]={title,prose,previous:previous?{title:previous.title,prose:previous.prose}:null};persist();closeModal();render();toast('修改已保存，原稿也为你保留');break;
    }
    case 'restore-diary':delete state.diaries[date];persist();closeModal();render();toast('已恢复原始日记');break;
    case 'day-tab':state.tab=tab;render();break;
    case 'report':go('day',TODAY);state.tab='report';render();break;
    case 'month':{const d=new Date(state.year,state.month+Number(target.dataset.delta),1);state.year=d.getFullYear();state.month=d.getMonth();render();break;}
    case 'mini-month':{const d=new Date(state.miniYear,state.miniMonth+Number(target.dataset.delta),1);state.miniYear=d.getFullYear();state.miniMonth=d.getMonth();render();break;}
    case 'this-month':state.month=9;state.year=2026;state.selectedDate=TODAY;render();break;
    case 'suggest':state.query=target.dataset.query;rememberSearch();render();break;
    case 'search-source':state.source=target.dataset.source;render();break;
    case 'search-range':openModal('找哪段时间',`<p class="modal-description">缩小一点范围，也许就能找到那段记忆。</p>${[['all','不限日期','在所有记录里查找'],['week','最近一周','10 月 21 日至 10 月 27 日'],['today','仅今天','10 月 27 日']].map(([value,title,description])=>`<button class="choice-row" data-action="set-range" data-range="${value}">${icon(state.range===value?'check':'calendar')}<span>${title}<small>${description}</small></span></button>`).join('')}`);break;
    case 'set-range':state.range=target.dataset.range;closeModal();render();break;
    case 'only-favorites':state.onlyFavorites=!state.onlyFavorites;render();break;
    case 'clear-search':state.query='';render();document.getElementById('search-input').focus();break;
    case 'clear-history':state.history=[];persist();render();toast('已清除搜索历史');break;
    case 'export':openModal('导出这一天',`<p class="modal-description">将日记保存为 Markdown 文件。导出的文字包含你的修改，保存到外部位置后，请自行妥善保管。</p>`,`<button class="button ghost" data-action="close-modal">取消</button><button class="button primary" data-action="confirm-export" data-date="${date}">${icon('download')}保存到本机</button>`);break;
    case 'confirm-export':closeModal();exportDiary(date);break;
    case 'regenerate':regenerate(date);break;
    case 'accept-candidate':if(date===TODAY)state.diaries[date]={...defaultDiary,previous:dayInfo(date)};persist();closeModal();render();toast('已采用候选版本');break;
    case 'privacy':privacyModal();break;
    case 'clear-cache':openModal('处理缓存',`<p class="modal-description">本预览未采集音视频，目前没有待清理的媒体缓存。正式版会在确认文字入库后自动清理已处理的片段。</p><div class="report-row"><span>待转换片段</span><strong>0</strong></div><div class="report-row"><span>媒体缓存</span><strong>0 B</strong></div>`,`<button class="button primary" data-action="close-modal">完成</button>`);break;
    case 'reset-preview':openModal('重新翻开这本日记',`<p class="modal-description">将删除你在当前浏览器添加的手记、日记修改、收藏和偏好，恢复最初的示例内容。此操作无法撤回。</p>`,`<button class="button ghost" data-action="close-modal">保留现在的记录</button><button class="button primary" data-action="confirm-reset">重置预览</button>`);break;
    case 'confirm-reset':localStorage.removeItem(STORAGE);closeModal();location.hash='today';location.reload();break;
  }
});
document.addEventListener('input',event=>{
  if(event.target.id==='search-input'){state.query=event.target.value;document.getElementById('search-results').innerHTML=resultsMarkup();}
});
document.addEventListener('change',event=>{if(event.target.dataset.setting){state.settings[event.target.dataset.setting]=event.target.value;persist();toast('设置已保存');}});
document.addEventListener('keydown',event=>{
  const modal=document.querySelector('.modal');
  if(event.key==='Escape'&&modal){closeModal();return;}
  if(event.key==='Enter'&&event.target.id==='search-input')rememberSearch();
  if(event.key==='Tab'&&modal){const focusable=[...modal.querySelectorAll('button:not([disabled]),input,textarea,select,a[href]')];const first=focusable[0],last=focusable.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}}
  if(event.key==='ArrowRight'||event.key==='ArrowLeft'){
    const tab=event.target.closest('[role="tab"]');
    if(tab){event.preventDefault();const tabs=[...document.querySelectorAll('[role="tab"]')],next=(tabs.indexOf(tab)+(event.key==='ArrowRight'?1:-1)+tabs.length)%tabs.length;state.tab=tabs[next].dataset.tab;render();document.querySelectorAll('[role="tab"]')[next].focus();}
  }
});
window.addEventListener('popstate',()=>{state.page=['today','calendar','search','settings','day'].includes(location.hash.slice(1))?location.hash.slice(1):'today';render();});
window.addEventListener('hashchange',()=>{const page=location.hash.slice(1);if(['today','calendar','search','settings','day'].includes(page)&&page!==state.page){state.page=page;render();window.scrollTo(0,0);}});
setInterval(()=>{if(state.paused&&state.pauseUntil&&Date.now()>=state.pauseUntil){state.paused=false;state.pauseUntil=0;persist();render();toast('暂停时间已到，示例记录已继续');}},1000);
render();
