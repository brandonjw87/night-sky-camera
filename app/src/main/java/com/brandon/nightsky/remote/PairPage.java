package com.brandon.nightsky.remote;

/** The start page for a browser that came without the token: it trades the six-digit code shown on the phone for it. */
final class PairPage {
    private PairPage() {}

    static final String HTML = "<!doctype html><html lang=en><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Night Sky viewer</title>"
            + "<style>"
            + "html,body{background:#000;color:#e53935;font:16px system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0}"
            + "main{max-width:420px;margin:12vh auto;padding:0 20px}"
            + "h1{font-size:26px;margin:0 0 6px}p{color:#9a2a25;line-height:1.45}"
            + "input{width:100%;box-sizing:border-box;font-size:34px;letter-spacing:8px;text-align:center;background:#2a0807;"
            + "color:#ffcdd2;border:1px solid #9a2a25;border-radius:12px;padding:12px;margin:14px 0}"
            + "button{width:100%;font-size:18px;padding:14px;border:0;border-radius:12px;background:#e53935;color:#000;font-weight:600}"
            + ":focus-visible,input:focus{outline:2px solid #e53935;outline-offset:2px}"
            + "#msg{min-height:1.4em;margin-top:12px}"
            + "</style></head><body><main>"
            + "<h1>Night Sky viewer</h1>"
            + "<p>Type the six-digit code shown on the phone, under the viewer address.</p>"
            + "<form id=f><input id=c inputmode=numeric autocomplete=one-time-code maxlength=7 placeholder='000000' autofocus>"
            + "<button>Open the viewer</button></form><p id=msg></p>"
            + "<p>Or scan the QR code on the phone's screen with a phone or tablet camera.</p>"
            + "</main><script>"
            + "document.getElementById('f').onsubmit=function(e){e.preventDefault();"
            + "var m=document.getElementById('msg');m.textContent='Checking\\u2026';"
            + "fetch('/pair',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
            + "body:'code='+encodeURIComponent(document.getElementById('c').value)})"
            + ".then(function(r){return r.json()}).then(function(j){"
            + "if(j.token){location.replace('/?t='+encodeURIComponent(j.token))}else{m.textContent=j.error||'That did not work.'}})"
            + ".catch(function(){m.textContent='Cannot reach the phone. Is it on the same Wi-Fi?'})};"
            + "</script></body></html>";
}
