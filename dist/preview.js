const phone = document.getElementById('phone-app');
const allowedPages = ['today','calendar','search','settings','day'];
let actualSize = false;
const initialPage = location.hash.slice(1);
phone.src = './app.html#' + (allowedPages.includes(initialPage) ? initialPage : 'today');
function fitDevice(){
  const scale = actualSize ? 1 : Math.max(.3,Math.min(1,(innerWidth-48)/406,(innerHeight-110)/860));
  document.documentElement.style.setProperty('--device-scale',String(scale));
}
document.getElementById('fit-mode').addEventListener('click',()=>setSize(false));
document.getElementById('actual-mode').addEventListener('click',()=>setSize(true));
function setSize(actual){
  actualSize=actual;
  document.getElementById('fit-mode').classList.toggle('selected',!actual);
  document.getElementById('actual-mode').classList.toggle('selected',actual);
  document.getElementById('fit-mode').setAttribute('aria-pressed',String(!actual));
  document.getElementById('actual-mode').setAttribute('aria-pressed',String(actual));
  fitDevice();
}
window.addEventListener('resize',fitDevice);
window.addEventListener('message',event=>{
  if(event.source!==phone.contentWindow||event.origin!==location.origin)return;
  if(event.data?.type!=='blackbox:page'||!allowedPages.includes(event.data.page))return;
  history.replaceState(null,'','#'+event.data.page);
  document.title='黑匣子日记 · 手机 App 预览';
});
window.addEventListener('hashchange',()=>{
  const page=location.hash.slice(1);
  if(allowedPages.includes(page))phone.src='./app.html#'+page;
});
fitDevice();
