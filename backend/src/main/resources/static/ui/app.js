import {createApi} from './api.js';
import {el,dom,format,button,field,dialog,bindSubmit,errorText,roleLabels,organizationName,hasRole} from './common.js';
import {createTasksPanel} from './tasks.js';
import {createUploadPanel} from './upload.js';
import {createEvidencePanel} from './evidence.js';
import {createSharingPanel} from './sharing.js';
import {createDevicesPanel} from './devices.js';
import {createAuditPanel} from './audit.js';

const root=document.getElementById('app'), notices=document.getElementById('notifications');
let session=null, generation=0;
function notify(message,tone='info') {
  const toast=el('div',{className:`toast ${tone}`},button('×',()=>toast.remove(),{'aria-label':'关闭提示'}),message);
  notices.append(toast);setTimeout(()=>toast.remove(),tone==='error'?12000:6500);
}
function brand(){return el('div',{className:'brand'},el('img',{src:'/ui/mark.svg',alt:'',width:37,height:37}),el('div',{},el('div',{className:'brand-title'},'巡证'),el('div',{className:'brand-subtitle'},'INSPECTION DESK')));}
function endSession(note) {
  notices.replaceChildren();
  generation++;if(session){session.api.dispose();for(const panel of Object.values(session.panels))panel.destroy?.();session=null;}
  document.querySelectorAll('dialog').forEach(node=>{node.querySelectorAll('input').forEach(input=>input.value='');node.close();node.remove();});renderLogin(note);
}
function renderLogin(note='') {
  const username=el('input',{name:'username',type:'text',autocomplete:'username',required:true,maxLength:64,testId:'login-username'});
  const password=el('input',{name:'password',type:'password',autocomplete:'current-password',required:true,testId:'login-password'});
  const error=el('p',{className:'inline-error',role:'alert',testId:'login-error'},note);
  const form=el('form',{},field('账号',username),field('密码',password),error,el('button',{type:'submit',className:'button',testId:'login-submit'},'登录工作台'));
  let busy=false;
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy||!form.reportValidity())return;busy=true;const turn=++generation;const submit=form.querySelector('[type=submit]');submit.disabled=true;error.textContent='';const anonymous=createApi();let authenticated;
    try{const login=await anonymous.request('/auth/login',{method:'POST',body:{username:username.value.trim(),password:password.value}});password.value='';authenticated=createApi({token:login.accessToken,onUnauthorized:()=>{if(turn===generation)endSession('登录已失效，请重新登录。');}});const actor=await authenticated.request('/users/me');if(turn!==generation){authenticated.dispose();return;}mount(actor,authenticated);}
    catch(failure){authenticated?.dispose();if(turn===generation&&failure.name!=='AbortError')error.textContent=failure.status===401?'账号或密码不正确，或账号已停用。':errorText(failure);}
    finally{anonymous.dispose();busy=false;submit.disabled=false;}
  });
  root.replaceChildren(el('div',{className:'login-layout'},el('aside',{className:'login-story'},brand(),el('div',{className:'login-story-content'},el('h1',{},'让每一次巡检',el('br'),'有据可查。'),el('p',{},'从多机任务协同，到成果复核、授权共享与文件核验。'),el('div',{className:'story-points'},el('span',{},'任务协同'),el('span',{},'固定版本'),el('span',{},'证据追溯'))),el('p',{className:'story-footer'},'无人机集群巡检 · 数据共享与证据溯源')),el('main',{className:'login-form-side',id:'main-content'},el('section',{className:'login-card'},el('div',{className:'eyebrow'},'WELCOME TO INSPECTION DESK'),el('h2',{},'登录巡检工作台'),el('p',{className:'muted'},'使用所属机构分配的账号继续工作。'),form,el('p',{className:'login-footer'},'仅显示当前账号有权访问的任务和资料。')))));
}
function mount(actor,api) {
  notices.replaceChildren();
  let selectedTask=null, currentPanel=null;
  const content=el('main',{id:'main-content',className:'main-content',tabindex:'-1'}), title=el('strong',{},'巡检工作台');
  const panels={};const nav=el('nav',{className:'navigation','aria-label':'主导航'});const navButtons={};
  function show(name,{load=true}={}){if(!panels[name])return;for(const [key,panel]of Object.entries(panels)){panel.element.hidden=key!==name;panel.setVisible?.(key===name);}for(const [key,node]of Object.entries(navButtons)){if(key===name)node.setAttribute('aria-current','page');else node.removeAttribute('aria-current');}currentPanel=name;title.textContent=navButtons[name].lastElementChild.textContent;if(load)Promise.resolve(panels[name].load?.()).catch(error=>{if(error.name!=='AbortError')notify(errorText(error),'error');});}
  const openVersion=id=>{show('evidence',{load:false});panels.evidence.openVersion(id);};
  panels.tasks=createTasksPanel({api,dom,format,actor,notify,onSelectTask:detail=>{selectedTask=detail;panels.evidence?.setTask(detail);},onOpenVersion:openVersion,onOpenUpload:(task,selection)=>{selectedTask=task;show('upload',{load:false});panels.upload.open({task,...selection});}});
  panels.evidence=createEvidencePanel({api,actor,notify,onShare:id=>{show('sharing',{load:false});panels.sharing.openApply(id);},onTask:id=>{show('tasks',{load:false});panels.tasks.openTask(id);}});
  if(hasRole(actor,'OPERATOR_USER'))panels.upload=createUploadPanel({api,dom,format,actor,notify,onVersionReady:openVersion});
  panels.sharing=createSharingPanel({api,actor,notify,onOpenVersion:openVersion,getSelectedTask:()=>selectedTask});
  if(hasRole(actor,'OPERATOR_TASK_ADMIN'))panels.devices=createDevicesPanel({api,actor,notify});
  if(hasRole(actor,'OWNER_TASK_ADMIN','OWNER_REVIEWER','OWNER_DATA_APPROVER','OPERATOR_TASK_ADMIN'))panels.audit=createAuditPanel({api,notify,getSelectedTask:()=>selectedTask});
  const definitions=[['tasks','◫','巡检任务'],['evidence','▤','成果资料'],['upload','↥','上传与续传'],['sharing','⇄','共享授权'],['devices','⌘','设备管理'],['audit','≡','任务审计']];
  for(const [name,symbol,label]of definitions){if(!panels[name])continue;const node=button('',()=>show(name),{className:'nav-button',testId:`nav-${name}`});node.append(el('span',{className:'nav-symbol','aria-hidden':'true'},symbol),el('span',{},label));navButtons[name]=node;nav.append(node);}
  const profile=button(actor.username,()=>{
    const copy=button('复制用户编号',async()=>{try{await navigator.clipboard.writeText(actor.id);notify('用户编号已复制','success');}catch{notify('复制未获浏览器允许，请选择编号后复制','warning');}},{className:'button secondary'});
    dialog('当前账号',el('p',{},organizationName(actor.orgId)),el('p',{},actor.roles.map(role=>roleLabels[role]||role).join(' / ')),el('div',{},el('p',{className:'muted'},'用户编号，可提供给任务管理员添加成员'),el('p',{className:'mono',testId:'profile-user-id'},actor.id)),copy);
  },{className:'button quiet',testId:'user-profile'});
  const logout=button('退出',async()=>{logout.disabled=true;let completed=false;try{await api.request('/auth/logout',{method:'POST'});completed=true;}catch(error){if(error.name!=='AbortError')notify('已结束本地登录，服务暂未确认退出。','warning');}finally{endSession();if(completed)notify('已退出工作台');}},{className:'button quiet',testId:'logout'});
  root.replaceChildren(el('div',{className:'app-layout'},el('aside',{className:'sidebar'},brand(),el('div',{},el('div',{className:'nav-section-label'},'WORKSPACE'),nav),el('div',{className:'sidebar-bottom'},el('strong',{},organizationName(actor.orgId)),el('p',{},actor.roles.map(role=>roleLabels[role]||role).join(' · ')))),el('div',{className:'workspace'},el('header',{className:'app-header'},el('div',{},title,el('p',{},'无人机集群巡检数据共享与证据溯源')),el('div',{className:'user-controls'},el('div',{className:'user-summary'},el('span',{className:'avatar','aria-hidden':'true'},actor.username.slice(0,1).toUpperCase()),profile),logout)),content)));
  for(const panel of Object.values(panels))content.append(panel.element);
  content.append(el('footer',{className:'app-footnote'},'巡检成果按固定版本归档 · 核验结果请结合资料来源与业务记录判断'));
  session={api,panels};show(hasRole(actor,'OWNER_TASK_ADMIN','OWNER_REVIEWER','OWNER_DATA_APPROVER','OPERATOR_TASK_ADMIN','OPERATOR_USER')?'tasks':'evidence');
}
renderLogin();
