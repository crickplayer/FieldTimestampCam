// Web pages served by the round display (http://<ip>/).
#pragma once

#include <Arduino.h>


// Small home page: what this device is, and a link to the firmware update.
static const char kHomePage[] PROGMEM = R"HTML(<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LD2450 Round Display</title>
<style>
body{margin:0;background:#0d1117;color:#e6edf3;font:16px/1.5 system-ui,sans-serif}
main{max-width:560px;margin:0 auto;padding:20px 16px}h1{font-size:1.3rem;margin:0 0 8px}
.card{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:16px;margin:16px 0}
.muted{color:#8b949e}a{color:#2fbf7f;font-weight:600}
</style></head><body><main>
<h1>LD2450 round display</h1>
<div class="card" id="s">Loading…</div>
<p><a href="/update">Firmware update</a></p>
<p class="muted">The live radar is shown on the round screen. For the live view in a browser, open the radar's own address (the big display).</p>
</main><script>
fetch('/status').then(r=>r.json()).then(s=>{document.getElementById('s').innerHTML=
 'Device: <b>'+s.device+'</b><br>Firmware build: <b>'+s.build+'</b><br>Radar: <b>'+(s.radar||'not connected')+'</b>'+
 (s.radar?' (<a href="http://'+s.radar+'/">open the radar\'s live view</a>)':'')});
</script></body></html>)HTML";

// Firmware update page (http://<ip>/update). Opening it shows a one-time code on the display.
static const char kUpdatePage[] PROGMEM = R"HTML(<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Round Display Firmware Update</title>
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
<h1>Round display firmware update</h1>
<div class="muted" id="cur">Loading current version…</div>
<div class="card"><ol>
<li>On GitHub: <b>Actions → ESP32-C3 round display firmware</b> → latest green run → download <b>esp32-c3-round-display-firmware</b> and unzip it.</li>
<li>Enter the <b>6-digit code shown on the round display</b> (valid 10 minutes, one use).</li>
<li>Choose <b>firmware.bin</b> (not round-display-merged.bin) and tap <b>Update</b>.</li>
</ol></div>
<form id="f">
<label for="code">Code from the round display</label>
<input id="code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" required>
<label for="file">Firmware file</label>
<input id="file" type="file" accept=".bin" required>
<button id="go" type="submit">Update</button>
</form>
<progress id="p" max="100" value="0" hidden></progress>
<div id="msg"></div>
<p class="muted">Keep the round display powered until it restarts. If anything goes wrong, it keeps running the current version. <a href="/">Back</a></p>
</main><script>
const $=id=>document.getElementById(id);let before='';
fetch('/status').then(r=>r.json()).then(s=>{before=s.build;$('cur').textContent=s.device+' · firmware build '+s.build});
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
 $('msg').className='ok';$('msg').textContent='Done. Now running firmware build '+s.build+(s.build==before&&before!='dev'?' (same build as before)':'')+'.';$('cur').textContent=s.device+' · firmware build '+s.build}).catch(()=>{if(n>30){clearInterval(t);$('msg').className='bad';$('msg').textContent='It has not come back yet. Check the round display.'}})},2000)}
</script></body></html>)HTML";
