(function(){
  'use strict';
  var token = sessionStorage.getItem('liteshop-admin-token') || '';
  var login = document.getElementById('login'), dashboard = document.getElementById('dashboard');
  var status = document.getElementById('status');
  function authHeaders(){ return {'Authorization':'Bearer '+token}; }
  function esc(value){ return String(value == null ? '' : value).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];}); }
  function showLogin(message){ login.hidden=false; dashboard.hidden=true; status.textContent=message || '未连接'; }
  function showError(message){ status.textContent=message; status.className='error'; }
  function table(headers, rows){ if(!rows.length)return '<p class="muted">暂无数据</p>'; return '<table><thead><tr>'+headers.map(function(h){return '<th>'+h+'</th>';}).join('')+'</tr></thead><tbody>'+rows.join('')+'</tbody></table>'; }
  function render(data){
    login.hidden=true; dashboard.hidden=false; status.className=''; status.textContent='已连接';
    var c=data.counts||{}; document.getElementById('cards').innerHTML=[['shops','门店'],['terminals','终端'],['members','会员'],['cards','会员卡'],['events','同步事件']].map(function(x){return '<div class="card"><b>'+esc(c[x[0]]||0)+'</b><span>'+x[1]+'</span></div>';}).join('');
    document.getElementById('shops').innerHTML=table(['门店 ID','名称','会员数'],(data.shops||[]).map(function(s){return '<tr><td>'+esc(s.shop_id)+'</td><td>'+esc(s.name||'未命名')+'</td><td>'+esc(s.member_count)+'</td></tr>'; }));
    document.getElementById('terminals').innerHTML=table(['设备 ID','序列号','门店','状态','最后同步'],(data.terminals||[]).map(function(t){return '<tr><td>'+esc(t.device_id)+'</td><td>'+esc(t.serial_no||'未上报')+'</td><td>'+esc(t.shop_id)+'</td><td>'+esc(t.status)+'</td><td>'+new Date(t.last_seen).toLocaleString()+'</td></tr>'; }));
    var s=data.settings||{}; document.getElementById('display-name').value=s.display_name||''; document.getElementById('notice').value=s.notice||''; [['smtp-host','smtp_host'],['smtp-port','smtp_port'],['smtp-username','smtp_username'],['smtp-from','smtp_from'],['miniapp-app-id','miniapp_app_id'],['miniapp-template','miniapp_message_template_id'],['wechat-app-id','wechat_app_id'],['wechat-template','wechat_message_template_id']].forEach(function(x){document.getElementById(x[0]).value=s[x[1]]||''}); document.getElementById('smtp-ssl').value=s.smtp_ssl||'0';
  }
  function load(){
    if(!token){showLogin();return;}
    fetch('/api/v1/admin/summary',{headers:authHeaders()}).then(function(r){if(r.status===401)throw new Error('管理员令牌无效');return r.json();}).then(function(r){if(!r.ok)throw new Error(r.error&&r.error.message||'加载失败');render(r.data);}).catch(function(e){sessionStorage.removeItem('liteshop-admin-token');token='';showLogin(e.message);});
  }
  document.getElementById('login-form').addEventListener('submit',function(e){e.preventDefault();token=document.getElementById('token').value.trim();sessionStorage.setItem('liteshop-admin-token',token);load();});
  document.getElementById('refresh').addEventListener('click',load);
  document.getElementById('settings-form').addEventListener('submit',function(e){e.preventDefault();var value={display_name:document.getElementById('display-name').value,notice:document.getElementById('notice').value,smtp_host:document.getElementById('smtp-host').value,smtp_port:document.getElementById('smtp-port').value,smtp_username:document.getElementById('smtp-username').value,smtp_from:document.getElementById('smtp-from').value,smtp_ssl:document.getElementById('smtp-ssl').value,miniapp_app_id:document.getElementById('miniapp-app-id').value,miniapp_message_template_id:document.getElementById('miniapp-template').value,wechat_app_id:document.getElementById('wechat-app-id').value,wechat_message_template_id:document.getElementById('wechat-template').value};[['smtp-password','smtp_password'],['miniapp-app-secret','miniapp_app_secret'],['wechat-app-secret','wechat_app_secret'],['wechat-token','wechat_token']].forEach(function(x){var v=document.getElementById(x[0]).value;if(v)value[x[1]]=v});fetch('/api/v1/admin/settings',{method:'PUT',headers:Object.assign({'Content-Type':'application/json'},authHeaders()),body:JSON.stringify(value)}).then(function(r){return r.json();}).then(function(r){document.getElementById('settings-message').textContent=r.ok?'已保存':'保存失败：'+(r.error&&r.error.message||'未知错误');}).catch(function(){document.getElementById('settings-message').textContent='保存失败，请检查网络';});});
  load();
}());
