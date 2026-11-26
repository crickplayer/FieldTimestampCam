// Live view served by the ESP32 at http://<ip>/ on the home network (any phone or PC browser).
// It reads the same JSON stream as the Android app (WebSocket on port 81).
#pragma once

#include <pgmspace.h>

static const char kWebPage[] PROGMEM = R"HTML(<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LD2450 Radar</title>
<style>
:root{--bg:#0d1117;--card:#161b22;--text:#e6edf3;--muted:#8b949e;--grid:#1f7a4d;--fan:#0e2a1c}
body{margin:0;background:var(--bg);color:var(--text);font:15px system-ui,sans-serif}
main{max-width:640px;margin:0 auto;padding:12px 16px}
header{display:flex;justify-content:space-between;align-items:baseline;gap:8px;flex-wrap:wrap}
h1{font-size:1.2rem;margin:0}#st{color:var(--muted);font-size:.9rem}
canvas{width:100%;height:auto;display:block;margin:10px 0;background:var(--card);border-radius:10px}
.row{display:flex;gap:10px;align-items:center;padding:6px 0;border-top:1px solid #222}
.dot{width:22px;height:22px;border-radius:50%;color:#000;font-weight:700;display:flex;align-items:center;justify-content:center;font-size:.8rem}
.off{opacity:.35}
</style></head><body><main>
<header><h1>LD2450 Radar</h1><span id="st">connecting…</span></header>
<canvas id="c" width="640" height="380"></canvas>
<div id="rows"></div>
<p style="margin-top:20px;font-size:.85rem"><a href="/update" style="color:#8b949e">Firmware update</a></p>
</main><script>
const C=['#00e5ff','#ffeb3b','#ff4dff'],R=6000,FOV=60;
const cv=document.getElementById('c'),g=cv.getContext('2d'),st=document.getElementById('st');
const W=cv.width,H=cv.height,ox=W/2,oy=H-18,rad=H-36,k=rad/R,trails=[[],[],[]];
let last=null,lastAt=0,count=0,hz=0;
function P(a,r){a*=Math.PI/180;return[ox+r*Math.sin(a),oy-r*Math.cos(a)]}
function draw(){
 g.clearRect(0,0,W,H);g.fillStyle=getComputedStyle(document.body).getPropertyValue('--fan');
 g.beginPath();g.moveTo(ox,oy);g.arc(ox,oy,rad,(-90-FOV)*Math.PI/180,(-90+FOV)*Math.PI/180);g.closePath();g.fill();
 g.strokeStyle='#1f7a4d';g.lineWidth=1;g.fillStyle='#8b949e';g.font='12px system-ui';
 for(let m=1;m<=6;m++){g.beginPath();g.arc(ox,oy,m*1000*k,(-90-FOV)*Math.PI/180,(-90+FOV)*Math.PI/180);g.stroke();
  if(m%2==0)g.fillText(m+'m',ox+4,oy-m*1000*k+14)}
 for(let a=-60;a<=60;a+=30){const[x,y]=P(a,rad);g.beginPath();g.moveTo(ox,oy);g.lineTo(x,y);g.stroke()}
 g.fillStyle='#fff';g.beginPath();g.moveTo(ox,oy-8);g.lineTo(ox-8,oy+6);g.lineTo(ox+8,oy+6);g.fill();
 const ts=last?last.targets:[];
 for(let i=0;i<3;i++){const t=ts.find(t=>t.id==i+1);const tr=trails[i];
  if(!t){tr.length=0;continue}tr.push([ox+t.x*k,oy-t.y*k]);if(tr.length>12)tr.shift();
  tr.forEach((p,j)=>{g.globalAlpha=.15+.6*j/tr.length;g.fillStyle=C[i];g.beginPath();g.arc(p[0],p[1],3,0,7);g.fill()});
  g.globalAlpha=1;const[x,y]=tr[tr.length-1];g.fillStyle=C[i];g.beginPath();g.arc(x,y,10,0,7);g.fill();
  g.strokeStyle='#fff';g.lineWidth=2;g.stroke();g.lineWidth=1;g.fillStyle='#000';g.font='bold 12px system-ui';g.textAlign='center';g.fillText(i+1,x,y+4);g.textAlign='left'}
 let h='';for(let i=0;i<3;i++){const t=ts.find(t=>t.id==i+1);
  h+='<div class="row'+(t?'':' off')+'"><span class="dot" style="background:'+C[i]+'">'+(i+1)+'</span>'+
  (t?(Math.hypot(t.x,t.y)/1000).toFixed(2)+' m &nbsp; '+(Math.atan2(t.x,t.y)*180/Math.PI).toFixed(0)+'° &nbsp; '+(t.speed/100).toFixed(2)+' m/s':'—')+'</div>'}
 document.getElementById('rows').innerHTML=h}
function connect(){const ws=new WebSocket('ws://'+location.hostname+':81/');
 ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.type=='status'){st.textContent=m.device+' · radar '+m.fw;return}
  last=m;lastAt=Date.now();count++;draw()};
 ws.onclose=()=>{st.textContent='reconnecting…';setTimeout(connect,2000)}}
setInterval(()=>{hz=count;count=0;if(last&&Date.now()-lastAt>1500){st.textContent='no radar data';last=null;draw()}},1000);
draw();connect();
</script></body></html>)HTML";

// Firmware update page (http://<ip>/update). Opening it shows a one-time code on the display.
static const char kUpdatePage[] PROGMEM = R"HTML(<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Radar Firmware Update</title>
<style>
:root{--bg:#0d1117;--card:#161b22;--text:#e6edf3;--muted:#8b949e;--ok:#2fbf7f;--bad:#ff6b6b;--border:#30363d}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,sans-serif}
main{max-width:560px;margin:0 auto;padding:20px 16px}h1{font-size:1.3rem;margin:0 0 4px}
.muted{color:var(--muted);font-size:.9rem}.card{background:var(--card);border:1px solid var(--border);border-radius:12px;padding:16px;margin:16px 0}
ol{margin:0;padding-left:1.2rem}li{margin:6px 0}label{display:block;font-weight:600;margin:14px 0 6px}
input[type=text]{width:100%;font-size:1.6rem;letter-spacing:.3em;padding:8px 12px;border-radius:8px;border:1px solid var(--border);background:var(--bg);color:var(--text)}
input[type=file]{width:100%;color:var(--text)}
button{margin-top:18px;width:100%;padding:14px;font-size:1.05rem;font-weight:700;border:0;border-radius:10px;background:var(--ok);color:#06210f}
button:disabled{opacity:.5}progress{width:100%;height:18px;margin-top:14px}
#msg{margin-top:12px;font-weight:600}.ok{color:var(--ok)}.bad{color:var(--bad)}a{color:var(--ok)}
</style></head><body><main>
<h1>Firmware update</h1>
<div class="muted" id="cur">Loading current version…</div>
<div class="card"><ol>
<li>On GitHub: <b>Actions → ESP32 radar display firmware</b> → latest green run → download <b>esp32-radar-display-firmware</b> and unzip it.</li>
<li>Enter the <b>6-digit code shown on the radar's screen</b> (valid 10 minutes, one use).</li>
<li>Choose <b>firmware.bin</b> (not radar-display-merged.bin) and tap <b>Update</b>.</li>
</ol></div>
<form id="f">
<label for="code">Code from the radar's screen</label>
<input id="code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" required>
<label for="file">Firmware file</label>
<input id="file" type="file" accept=".bin" required>
<button id="go" type="submit">Update</button>
</form>
<progress id="p" max="100" value="0" hidden></progress>
<div id="msg"></div>
<p class="muted">Keep the radar display powered until it restarts. If anything goes wrong, it keeps running the current version. <a href="/">Back to the live view</a></p>
</main><script>
const $=id=>document.getElementById(id);let before='';
fetch('/status').then(r=>r.json()).then(s=>{before=s.build;$('cur').textContent=s.device+' · firmware build '+s.build+' · radar '+s.fw});
$('f').onsubmit=e=>{e.preventDefault();const file=$('file').files[0];if(!file)return;
 const x=new XMLHttpRequest(),fd=new FormData();fd.append('firmware',file,file.name);
 x.open('POST','/update?code='+encodeURIComponent($('code').value.trim())+'&size='+file.size);
 $('go').disabled=true;$('p').hidden=false;$('msg').className='';$('msg').textContent='Uploading…';
 x.upload.onprogress=ev=>{if(ev.lengthComputable)$('p').value=Math.round(ev.loaded*100/ev.total)};
 x.onload=()=>{if(x.status==200){$('p').value=100;$('msg').className='ok';$('msg').textContent=x.responseText+' Waiting for it to come back…';waitBack()}
  else{$('msg').className='bad';$('msg').textContent=x.responseText||('Failed ('+x.status+')');$('go').disabled=false;$('p').hidden=true}};
 x.onerror=()=>{$('msg').className='bad';$('msg').textContent='Connection lost during upload. Reload the page and try again.';$('go').disabled=false};
 x.send(fd)};
function waitBack(){setTimeout(waitPoll,6000)}
function waitPoll(){let n=0;const t=setInterval(()=>{n++;fetch('/status',{cache:'no-store'}).then(r=>r.json()).then(s=>{clearInterval(t);
 $('msg').className='ok';$('msg').textContent='Done. Now running firmware build '+s.build+(s.build==before&&before!='dev'?' (same build as before)':'')+'.';$('cur').textContent=s.device+' · firmware build '+s.build+' · radar '+s.fw}).catch(()=>{if(n>30){clearInterval(t);$('msg').className='bad';$('msg').textContent='It has not come back yet. Check the radar display.'}})},2000)}
</script></body></html>)HTML";
