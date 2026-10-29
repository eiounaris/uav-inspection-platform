import {el,field,button,badge,format,empty,pageBar,dialog,bindSubmit,errorText,hasRole} from './common.js';

export function createEvidencePanel({api,actor,notify,onShare,onTask}) {
  const state={page:1,pageSize:12,total:0,taskId:'',id:null,version:null,sequence:0,visible:true};
  const lifetime=new AbortController(); let timer;
  const request=(path,options={})=>api.request(path,{...options,signal:lifetime.signal});
  const list=el('div',{className:'card'}), detail=el('section',{className:'version-detail',hidden:true,testId:'version-detail'});
  const taskFilter=el('input',{placeholder:'任务编号（可选）','aria-label':'按任务编号筛选',testId:'version-task-filter'});
  const direct=el('input',{placeholder:'打开指定版本编号','aria-label':'版本编号',testId:'version-id'});
  const filters=el('form',{className:'toolbar'},taskFilter,el('button',{type:'submit',className:'button secondary'},'筛选'));
  bindSubmit(filters,async()=>{state.taskId=taskFilter.value.trim();state.page=1;await load();},notify);
  const openForm=el('form',{className:'toolbar'},direct,el('button',{type:'submit',className:'button secondary',testId:'version-open'},'打开版本'));
  bindSubmit(openForm,()=>openVersion(direct.value.trim()),notify);
  const element=el('section',{className:'panel'},el('div',{className:'page-heading'},el('div',{},el('div',{className:'eyebrow'},'EVIDENCE LIBRARY'),el('h1',{},'成果资料'),el('p',{},'查看固定版本、复核结论和存证状态，按实际文件核验。')),button('刷新',()=>state.id?openVersion(state.id):load(),{className:'button secondary',testId:'versions-refresh'})),el('div',{className:'card grid two-columns'},filters,openForm),list,detail);
  async function load(){
    if(lifetime.signal.aborted)return;
    const sequence=++state.sequence; state.id=null;clearTimeout(timer);detail.hidden=true;list.hidden=false;list.replaceChildren(el('p',{className:'loading'},'正在读取资料…'));
    try{
      const query=new URLSearchParams({page:state.page,pageSize:state.pageSize});if(state.taskId)query.set('taskId',state.taskId);
      const result=await request(`/evidence-versions?${query}`);if(sequence!==state.sequence)return;state.total=result.total;
      const table=el('table',{className:'table'},el('thead',{},el('tr',{},...['资料名称','版本','复核','存证','归档时间',''].map(text=>el('th',{},text)))));
      const body=el('tbody');for(const row of result.items)body.append(el('tr',{'data-version-id':row.id,testId:'version-row'},el('td',{},el('strong',{},row.filename),el('p',{className:'muted'},format.size(row.size))),el('td',{},`V${row.versionNo}`),el('td',{},badge(row.reviewStatus)),el('td',{},badge(row.anchorStatus)),el('td',{},format.time(row.createdAt)),el('td',{},button('查看',()=>openVersion(row.id),{className:'button secondary small'}))));table.append(body);
      list.replaceChildren(result.items.length?el('div',{className:'table-wrap'},table):empty('暂无可见资料','参与任务或取得指定版本授权后，相应资料会显示在这里。'),pageBar(state,page=>{state.page=page;load();}));
    }catch(error){if(error.name!=='AbortError'&&sequence===state.sequence)list.replaceChildren(el('p',{className:'inline-error'},errorText(error)));}
  }
  async function openVersion(id){
    if(!id)return;const sequence=++state.sequence;state.id=id;clearTimeout(timer);list.hidden=true;detail.hidden=false;detail.replaceChildren(el('p',{className:'loading'},'正在读取固定版本…'));
    try{const version=await request(`/evidence-versions/${encodeURIComponent(id)}`);if(sequence!==state.sequence)return;
      try { const task=await request(`/tasks/${version.taskId}`); version.taskWritable=['RUNNING','WAITING_ACCEPTANCE'].includes(task.status); } catch(error) { if(error.name==='AbortError')throw error; version.taskWritable=false; }
      if(sequence!==state.sequence)return;state.version=version;detail.dataset.versionId=version.id;renderDetail(version);schedule();}
    catch(error){if(error.name!=='AbortError'&&sequence===state.sequence)detail.replaceChildren(button('返回资料列表',load,{className:'button secondary'}),el('div',{className:'notice error',testId:'version-error'},errorText(error)));}
  }
  function renderDetail(version){
    const anchorStatus=el('div',{className:'toolbar',testId:'anchor-status'},badge(version.anchor.status));
    const anchorHint=el('p',{className:'muted'},anchorDescription(version.anchor));
    const anchorProof=el('dl',{className:'detail-grid'});
    const updateAnchor=anchor=>{anchorStatus.replaceChildren(badge(anchor.status));anchorHint.textContent=anchorDescription(anchor);anchorProof.replaceChildren(el('div',{},el('dt',{},'首次有效交易'),el('dd',{className:'mono',testId:'anchor-transaction'},anchor.confirmedTxId||'确认后显示')),el('div',{},el('dt',{},'区块号'),el('dd',{},anchor.blockNumber??'—')));};
    updateAnchor(version.anchor);state.updateAnchor=updateAnchor;
    const metadata=el('dl',{className:'detail-grid'},...[
      ['版本编号',version.id],['资料编号',version.evidenceId],['文件大小',format.size(version.size)],['归档时间',format.time(version.createdAt)],['SHA-256',version.sha256],['执行批次',version.executionId],
    ].map(([key,value])=>el('div',{},el('dt',{},key),el('dd',{className:key.includes('编号')||key==='SHA-256'||key==='执行批次'?'mono':''},value))));
    const actions=el('div',{className:'toolbar'},button('申请共享',()=>onShare(version.id),{className:'button secondary',testId:'version-share'}),button('下载文件',async()=>{
      try{const result=await request(`/evidence-versions/${version.id}/download-url`,{method:'POST'});const url=new URL(result.url);if(!['http:','https:'].includes(url.protocol)||url.username||url.password)throw new Error('下载地址不可用');const link=el('a',{href:url.href,target:'_blank',rel:'noopener noreferrer'});document.body.append(link);link.click();link.remove();notify(`已签发下载地址，有效至 ${format.time(result.expiresAt)}`,'success');}catch(error){if(error.name!=='AbortError')notify(errorText(error),'error');}
    },{testId:'version-download'}));
    const header=el('div',{className:'card'},el('div',{className:'version-heading'},el('div',{},el('div',{className:'eyebrow'},`FIXED VERSION ${version.versionNo}`),el('h2',{testId:'version-filename'},version.filename),el('div',{className:'toolbar'},badge(version.reviewStatus),anchorStatus)),actions),el('div',{className:'subtle-rule'}),metadata);
    const chain=el('div',{className:'card'},el('div',{className:'card-heading'},el('h3',{},'存证状态'),button('刷新状态',()=>refreshVersion(),{className:'button secondary small'})),anchorHint,anchorProof);
    if(hasRole(actor,'OPERATOR_TASK_ADMIN')&&version.anchor.retryable)chain.append(button('重新检查存证',()=>retryDialog(version),{className:'button secondary'}));
    const review=reviewPanel(version),issues=issuePanel(version),verification=verificationPanel(version);
    detail.replaceChildren(el('div',{className:'toolbar'},button('返回资料列表',load,{className:'button secondary'}),button('查看所属任务',()=>onTask(version.taskId),{className:'button quiet'})),header,chain,verification,el('div',{className:'two-columns'},review,issues));
  }
  function anchorDescription(anchor){return anchor.status==='CONFIRMED'?'该版本已取得有效登记，可查看首次有效交易。':anchor.status==='UNKNOWN'?'提交结果暂未确定，系统会继续检查原交易。':'PENDING'===anchor.status||anchor.status==='SUBMITTED'?'系统正在办理存证，页面会自动更新处理状态。':'存证处理尚未完成，请根据当前状态重新检查。';}
  function schedule(){clearTimeout(timer);if(state.visible&&state.id&&!lifetime.signal.aborted&&!['CONFIRMED','FAILED'].includes(state.version?.anchor.status))timer=setTimeout(refreshVersion,5000);}
  async function refreshVersion(){const id=state.id;if(!id)return;try{const version=await request(`/evidence-versions/${id}`);if(id!==state.id||lifetime.signal.aborted)return;state.version=version;state.updateAnchor?.(version.anchor);schedule();}catch(error){if(error.name!=='AbortError')notify(errorText(error),'error');}}
  function reviewPanel(version){
    const conclusion=el('div',{className:'muted'},'正在读取复核记录…');
    const box=el('div',{className:'card'},el('h3',{},'人工复核'),conclusion);
    request(`/evidence-versions/${version.id}/review`).then(data=>{conclusion.replaceChildren(badge(data.reviewStatus),el('p',{},data.review?.reason||'暂无复核说明'));}).catch(error=>{if(error.name!=='AbortError')conclusion.textContent=error.status===403?'复核记录仅对任务成员开放。':errorText(error);});
    if(version.taskWritable&&hasRole(actor,'OWNER_REVIEWER')&&version.reviewStatus==='PENDING'){
      const choice=el('select',{testId:'review-decision'},el('option',{value:'APPROVED'},'通过'),el('option',{value:'RETURNED'},'退回补充'));
      const reason=el('textarea',{maxLength:1000,testId:'review-reason',placeholder:'退回时请填写需要补充的内容'});
      const form=el('form',{},field('复核结论',choice),field('复核说明',reason),el('div',{className:'form-actions'},el('button',{className:'button',type:'submit',testId:'review-submit'},'提交复核')));
      bindSubmit(form,async()=>{if(choice.value==='RETURNED'&&!reason.value.trim()){reason.setCustomValidity('退回时请填写说明');reason.reportValidity();reason.setCustomValidity('');return;}try{await request(`/evidence-versions/${version.id}/review`,{method:'POST',body:{decision:choice.value,expectedReviewStatus:'PENDING',...(reason.value.trim()?{reason:reason.value.trim()}:{})}});notify('复核结论已提交','success');await openVersion(version.id);}catch(error){if(error.status===409){await refreshVersion();notify('该版本已被复核，请刷新查看最新结论；本次说明已保留。','warning');}else throw error;}},notify);box.append(el('div',{className:'subtle-rule'}),form);
    }
    return box;
  }
  function issuePanel(version){
    const records=el('div',{className:'grid'});const box=el('div',{className:'card'},el('div',{className:'card-heading'},el('h3',{},'异常记录')),records);
    async function listIssues(){try{const data=await request(`/evidence-versions/${version.id}/issues?page=1&pageSize=20`);records.replaceChildren(...data.items.map(item=>el('div',{},el('p',{},item.description),el('small',{className:'muted'},format.time(item.createdAt)))));if(!data.total)records.append(el('p',{className:'muted'},'暂无异常记录'));if(data.total>20)records.append(el('p',{className:'muted'},`显示最近 20 条，共 ${data.total} 条。`));}catch(error){if(error.name!=='AbortError')records.replaceChildren(el('p',{className:'muted'},error.status===403?'异常记录仅对任务成员开放。':errorText(error)));}}
    listIssues();
    if(version.taskWritable&&hasRole(actor,'OPERATOR_USER','OWNER_REVIEWER')){const description=el('textarea',{required:true,maxLength:4000,testId:'issue-description'});const form=el('form',{},field('记录发现的异常',description),el('div',{className:'form-actions'},el('button',{type:'submit',className:'button secondary',testId:'issue-submit'},'提交异常')));bindSubmit(form,async()=>{await request('/issues',{method:'POST',body:{versionId:version.id,description:description.value.trim()}});description.value='';notify('异常记录已保存','success');await listIssues();},notify);box.append(el('div',{className:'subtle-rule'}),form);}return box;
  }
  function verificationPanel(version){
    const result=el('div',{hidden:true,testId:'verification-result'}), history=el('div');let busy=false;
    const run=async(source,file)=>{if(busy)return;busy=true;controls.querySelectorAll('button').forEach(n=>n.disabled=true);try{let body;if(source==='COPY'){body=new FormData();body.append('file',file);}const data=await request(`/evidence-versions/${version.id}/verifications?source=${source}`,{method:'POST',body});result.hidden=false;result.className=`verification-result${data.result==='MISMATCH'?' mismatch':''}`;result.replaceChildren(el('strong',{},format.status(data.result)),el('p',{},verificationReason(data.result)),el('small',{className:'muted'},`${format.status(source)} · ${format.time(data.verifiedAt)}`));await loadHistory();}catch(error){if(error.name!=='AbortError')notify(errorText(error),'error');}finally{busy=false;controls.querySelectorAll('button').forEach(n=>n.disabled=false);}};
    const copy=el('input',{type:'file',accept:'.png,.jpg,.jpeg,.pdf,.mp4',testId:'verification-copy-file'});
    const copyForm=el('form',{className:'toolbar'},field('上传副本核验',copy),el('button',{type:'submit',className:'button secondary',testId:'verification-copy'},'核验副本'));
    bindSubmit(copyForm,async()=>{if(!copy.files[0]){notify('请先选择需要核验的副本','warning');return;}await run('COPY',copy.files[0]);},notify);
    const controls=el('div',{className:'grid two-columns'},el('div',{},el('p',{className:'muted'},'重新读取存储文件并与存证摘要比较。'),button('核验存储文件',()=>run('STORED'),{testId:'verification-stored'})),copyForm);
    async function loadHistory(){try{const data=await request(`/evidence-versions/${version.id}/verifications?page=1&pageSize=10`);history.replaceChildren(el('h4',{},'最近核验记录'),...data.items.map(item=>el('div',{className:'card-footer'},el('span',{},badge(item.result),' ',format.status(item.source)),el('small',{className:'muted'},format.time(item.verifiedAt)))));if(!data.total)history.append(el('p',{className:'muted'},'暂无核验记录'));if(data.total>10)history.append(el('small',{className:'muted'},`显示最近 10 条，共 ${data.total} 条。`));}catch(error){if(error.name!=='AbortError')history.replaceChildren(el('p',{className:'muted'},errorText(error)));}}
    loadHistory();return el('div',{className:'card grid'},el('div',{},el('h3',{},'文件核验'),el('p',{className:'muted'},'副本只用于本次比较，不会替换正式文件或创建新版本。')),controls,result,history);
  }
  function verificationReason(result){return {MATCH:'实际文件摘要与链上登记一致。',MISMATCH:'实际文件摘要与链上登记不同，请结合文件来源继续核查。',ANCHOR_UNCONFIRMED:'该版本尚未取得可比较的链上登记。',FILE_READ_FAILED:'当前无法完整读取文件，请稍后重试。',CHAIN_QUERY_FAILED:'当前无法查询存证，尚不能判断文件是否一致。'}[result]||'核验结果已记录。';}
  function retryDialog(version){const reason=el('textarea',{required:true,maxLength:1000});let key=crypto.randomUUID();const form=el('form',{},field('重新检查的原因',reason),el('button',{type:'submit',className:'button'},'提交检查'));const node=dialog('重新检查存证',form);bindSubmit(form,async()=>{await request(`/evidence-versions/${version.id}/anchor-retries`,{method:'POST',body:{clientRequestId:key,reason:reason.value.trim()}});key=crypto.randomUUID();node.close();notify('已安排重新检查，页面会更新结果','success');await refreshVersion();},notify);}
  return {element,load,openVersion,setTask(task){state.taskId=task?.id||'';taskFilter.value=state.taskId;state.page=1;},setVisible(value){state.visible=value;if(value)schedule();else clearTimeout(timer);},destroy(){lifetime.abort();clearTimeout(timer);state.sequence++;}};
}
