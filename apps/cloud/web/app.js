(function(){'use strict';
var status=document.getElementById('status'), tokenPanel=document.getElementById('admin-token-panel');
function message(value){status.textContent=value}
function call(path,payload){return fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}).then(function(r){return r.json().then(function(x){if(!r.ok||!x.ok)throw new Error(x.error&&x.error.message||'请求失败');return x})})}
document.getElementById('user-login').onsubmit=function(e){
 e.preventDefault(); var identity=document.getElementById('login-identity').value.trim(), password=document.getElementById('login-password').value; tokenPanel.hidden=true;
 call('/api/v1/admin/login',{username:identity,password:password}).then(function(){tokenPanel.hidden=false;message('管理员账户验证成功，请输入管理 Token');document.getElementById('token').focus()}).catch(function(){return call('/api/v1/auth/login',{email:identity,password:password}).then(function(r){sessionStorage.setItem('liteshop-user-token',r.data.token);location.href='/account'})}).catch(function(error){message(error.message)});
};
document.getElementById('admin-token').onsubmit=function(e){e.preventDefault();var value=document.getElementById('token').value.trim();if(!value)return;sessionStorage.setItem('liteshop-admin-token',value);location.href='/admin'};
})();
