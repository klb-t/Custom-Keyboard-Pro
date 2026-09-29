"""Offline browser fixtures; no real chatbot/device acceptance is implied."""
import json
import os
import shutil
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = (ROOT / 'tools/conversation-capture.browser.js').read_text()
HTML = '''<!doctype html><meta charset="utf-8"><style>
main{height:220px;width:600px;overflow:auto} article{padding:20px;min-height:120px}
</style><main>
<article data-message-id="u1" data-message-author-role="user">Yes</article>
<article data-message-id="a1" data-message-author-role="assistant"><p>Yes</p>
<details><summary>Thinking</summary>DIRECT CLOSED TEXT<details><summary>Tools</summary><pre>tool(1)\nresult = 42</pre></details></details>
<button type="button" aria-expanded="false" aria-controls="panel" onclick="this.setAttribute('aria-expanded','true');document.getElementById('panel').hidden=false">Show details</button>
<div id="panel" hidden>EXPOSED REASONING SUMMARY</div>
<button aria-expanded="false" onclick="window.danger++">Delete</button>
<form><button type="submit" aria-expanded="false" onclick="window.danger++">Show more</button><input type="password" value="PASSWORD_SECRET"></form>
<div aria-label="AGGREGATE_SECRET"><input value="DRAFT_SECRET"></div>
<div contenteditable="true">EDITABLE_SECRET</div><div style="display:none">HIDDEN_SECRET</div>
</article><article data-message-id="u2" data-message-author-role="user">Yes</article>
<article data-message-id="a2" data-message-author-role="assistant"><p>Last reply</p><img alt="diagram" src=""></article>
</main><script>window.danger=0</script>'''

def run_case(page, options):
    return page.evaluate('''async options => {
      const h = IOConversationCapture.start({root:document.querySelector('main'), scroller:document.querySelector('main'), settleMs:100, maxSteps:30, ...options});
      return await h.done;
    }''', options)

with sync_playwright() as p:
    binary = os.environ.get('CHROMIUM_EXECUTABLE') or shutil.which('chromium') or shutil.which('chromium-browser')
    launch = dict(headless=True, args=['--no-sandbox'])
    if binary:
        launch['executable_path'] = binary
    browser = p.chromium.launch(**launch)
    page = browser.new_page()
    cases = 0
    def fresh(html=HTML):
        # New document resets the script singleton and ensures no observer survives navigation.
        page.goto('about:blank')
        page.set_content(html)
        page.add_script_tag(content=SCRIPT)
    def check(condition, name):
        global cases
        assert condition, name
        cases += 1
        print('PASS', name)
    fresh()
    check(page.evaluate("document.querySelector('details').open") is False, 'loading script does not start capture')
    result = run_case(page, {'expand':False, 'seekStart':False})
    encoded = json.dumps(result)
    for secret in ['PASSWORD_SECRET','DRAFT_SECRET','EDITABLE_SECRET','HIDDEN_SECRET','AGGREGATE_SECRET','DIRECT CLOSED TEXT','EXPOSED REASONING SUMMARY']:
        check(secret not in encoded, f'excludes non-exposed/private: {secret}')
    check(page.evaluate('window.danger') == 0, 'no destructive or submit clicks')
    fresh()
    result = run_case(page, {})
    encoded = json.dumps(result)
    check(result['expanded'] == 3, 'nested native details and recognized ARIA disclosure expand')
    check('DIRECT CLOSED TEXT' in encoded and 'tool(1)' in encoded and 'EXPOSED REASONING SUMMARY' in encoded, 'exposed reasoning/tool text captured after expansion')
    check(page.evaluate('window.danger') == 0, 'unsafe controls still not clicked with expansion enabled')
    check([t['key'] for t in result['turns']] == ['message:u1','message:a1','message:u2','message:a2'], 'stable message IDs preserve order and identical replies')
    check(result['completeness']=='unverified' and 'not_proof' in result['startCoverage'], 'no false claim of whole-conversation completeness')
    def walk(n):
        if not n: return
        yield n
        for c in n.get('children',[]): yield from walk(c)
    check(any(n.get('tag')=='pre' for f in result['frames'] for n in walk(f['tree'])), 'raw code hierarchy preserved')
    text = page.evaluate('(r)=>IOConversationCapture.text(r)', result)
    check(text.count('message:u')==2 and 'tool(1)' in text, 'selectable text includes repeated user turns and tools')
    output = ROOT/'build/capture-checks/browser-export-fixture.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    fresh()
    cancelled = page.evaluate('''async () => {
      const h=IOConversationCapture.start({root:document.querySelector('main'),autoScroll:false,settleMs:100});
      setTimeout(()=>h.stop(),350); const r=await h.done;
      const n=r.frames.length; await new Promise(r=>setTimeout(r,500));
      return {r,unchanged:r.frames.length===n};
    }''')
    check(cancelled['r']['stopReason']=='user_stopped' and cancelled['unchanged'], 'Stop terminates polling and mutations')
    fresh()
    bounded = run_case(page, {'maxFrames':1})
    check(len(bounded['frames'])<=1 and bounded['stopReason']=='archive_limit', 'frame budget is enforced')
    fresh()
    clipped = run_case(page, {'maxChars':1000,'seekStart':False})
    check(clipped['stopReason'] in ['archive_limit','dom_limit'] and clipped['warnings'], 'size truncation is explicit')
    fresh()
    detached = page.evaluate('''async()=>{const h=IOConversationCapture.start({root:document.querySelector('main'),autoScroll:false,settleMs:100});setTimeout(()=>document.querySelector('main').remove(),150);return await h.done;}''')
    check(detached['stopReason']=='target_changed_or_hidden', 'target removal stops capture')
    fresh(HTML.replace('data-message-id="u2"','data-message-id="u1"'))
    duplicate = run_case(page, {'seekStart':False, 'expand':False})
    check(duplicate['hasUnkeyedTurns'] and any('Duplicate' in w for w in duplicate['warnings']), 'duplicate stable IDs retain raw fallback')
    fresh()
    invalid = page.evaluate('''()=>{try{IOConversationCapture.start({root:document.querySelector('main'),maxFrames:0});return false}catch{return true}}''')
    check(invalid, 'invalid options rejected')
    virtual = """<!doctype html><style>main{height:200px;width:600px;overflow:auto}article{box-sizing:border-box;height:150px;margin:0;padding:10px}</style><main id="chat"></main><script>
    const chat=document.getElementById('chat');
    function render(){const pos=chat.scrollTop;const first=Math.min(7,Math.floor(pos/150));chat.innerHTML='<div style="height:'+first*150+'px"></div>'+Array.from({length:3},(_,i)=>'<article data-message-id="m'+(first+i)+'" data-message-author-role="assistant">Repeated reply</article>').join('')+'<div style="height:'+(10-first-3)*150+'px"></div>';chat.scrollTop=pos;}
    render();chat.addEventListener('scroll',render);chat.scrollTop=1300;
    </script>"""
    fresh(virtual)
    virtual_result = run_case(page, {'maxSteps':50})
    check([t['key'] for t in virtual_result['turns']] == [f'message:m{i}' for i in range(10)], 'virtualized chat collected from beginning with repeated messages intact')
    fresh(HTML.replace('</main>', '<section>STANDALONE TOOL PAYLOAD</section></main>'))
    supplement = run_case(page, {'seekStart':False, 'expand':False})
    supplement_text = page.evaluate('(r)=>IOConversationCapture.text(r)', supplement)
    check('STANDALONE TOOL PAYLOAD' in supplement_text and supplement['hasUnkeyedTurns'], 'tool text outside keyed turns is not dropped from text export')
    fresh()
    inspected = page.evaluate('IOConversationCapture.inspect({readCollapsedDom:true})')
    encoded = json.dumps(inspected)
    check('DIRECT CLOSED TEXT' in encoded and 'EXPOSED REASONING SUMMARY' in encoded and 'result = 42' in encoded, 'read-only inspection reads existing native and recognized ARIA collapsed DOM')
    check(page.evaluate('document.querySelectorAll("details[open]").length===0 && document.querySelector("button[aria-controls]").getAttribute("aria-expanded")==="false" && document.querySelector("main").scrollTop===0 && danger===0'), 'inspection does not expand click or scroll the conversation')
    check('collapsed-disclosure-dom' in encoded and inspected['frames'][0]['diagnostics']['collapsedDomNodes'] > 0, 'non-rendered disclosure text has explicit provenance')
    for secret in ['PASSWORD_SECRET', 'DRAFT_SECRET', 'EDITABLE_SECRET', 'HIDDEN_SECRET', 'AGGREGATE_SECRET']:
        check(secret not in encoded, 'collapsed DOM inspection still excludes ' + secret)
    ordinary = page.evaluate('IOConversationCapture.inspect({readCollapsedDom:false})')
    check('DIRECT CLOSED TEXT' not in json.dumps(ordinary) and 'EXPOSED REASONING SUMMARY' not in json.dumps(ordinary), 'collapsed-DOM access is explicitly optional')
    fresh(HTML.replace('EXPOSED REASONING SUMMARY</div>', 'EXPOSED REASONING SUMMARY<div hidden>INDEPENDENT_HIDDEN_SECRET</div></div>'))
    inspected = page.evaluate('IOConversationCapture.inspect({readCollapsedDom:true})')
    check('EXPOSED REASONING SUMMARY' in json.dumps(inspected) and 'INDEPENDENT_HIDDEN_SECRET' not in json.dumps(inspected), 'reading a declared panel does not expose independently hidden descendants')
    fresh(HTML.replace('</main>', '<button aria-expanded="false" aria-controls="unknown">Account secrets</button><section id="unknown" hidden>UNKNOWN_PANEL_SECRET</section><button aria-expanded="false" aria-controls="foreign">Show details</button></main><div id="foreign" hidden>FOREIGN_PANEL_SECRET</div>'))
    inspected = page.evaluate('IOConversationCapture.inspect({readCollapsedDom:true})')
    check('UNKNOWN_PANEL_SECRET' not in json.dumps(inspected) and 'FOREIGN_PANEL_SECRET' not in json.dumps(inspected), 'unknown or outside-root hidden panels are never read')
    fresh()
    result = run_case(page, {'autoScroll':False, 'expand':False, 'readCollapsedDom':True, 'maxSteps':2})
    check('DIRECT CLOSED TEXT' in json.dumps(result) and result['expanded'] == 0 and page.evaluate('document.querySelectorAll("details[open]").length===0'), 'capture can read existing collapsed DOM without expanding it')
    fresh('<main style="height:100px;overflow:auto"><p>Start</p><section style="margin-top:600px">OFFSCREEN_DOM_TEXT</section></main>')
    inspected = page.evaluate('IOConversationCapture.inspect()')
    check('OFFSCREEN_DOM_TEXT' in json.dumps(inspected) and inspected['frames'][0]['diagnostics']['offscreenLayoutNodes'] > 0 and page.evaluate('document.querySelector("main").scrollTop===0'), 'off-viewport DOM text is read without a rendering or scrolling requirement')
    lazy = '''<main style="height:120px;overflow:auto"><article data-message-id="lazy"><button type="button" aria-expanded="false" aria-controls="late" onclick="this.setAttribute('aria-expanded','true');const p=document.getElementById('late');p.hidden=false;p.setAttribute('aria-busy','true');setTimeout(()=>{p.textContent='LAZY_PANEL_PAYLOAD';p.setAttribute('aria-busy','false')},600)">Show details</button><div id="late" hidden></div></article><div style="height:300px">Tail</div></main>'''
    fresh(lazy)
    before = page.evaluate('IOConversationCapture.inspect({readCollapsedDom:true})')
    check('LAZY_PANEL_PAYLOAD' not in json.dumps(before), 'inspector does not invent or read script-held content absent from DOM')
    result = run_case(page, {'seekStart':False})
    check('LAZY_PANEL_PAYLOAD' in json.dumps(result) and any('loading' in w for w in result['warnings']), 'expansion waits for declared asynchronous detail loading before scrolling onward')
    fresh('''<main><button disabled aria-expanded="false" onclick="window.danger++">Show details</button><div role="button" aria-disabled="true" aria-expanded="false" onclick="window.danger++">Show details</div><div role="button" aria-expanded="false" aria-controls="p" onclick="this.setAttribute('aria-expanded','true');document.getElementById('p').hidden=false">Show details</div><section hidden id="p">ROLE_BUTTON_PAYLOAD</section></main><script>window.danger=0</script>''')
    result = run_case(page, {'seekStart':False})
    check(page.evaluate('danger===0') and result['expanded']==1 and 'ROLE_BUTTON_PAYLOAD' in json.dumps(result), 'recognized ARIA button disclosures work but disabled controls are not clicked')
    fresh()
    blocked = page.evaluate('''async () => { const h=IOConversationCapture.start({autoScroll:false,expand:false,settleMs:100}); let refused=false; try{IOConversationCapture.inspect()}catch{refused=true} h.stop();await h.done;return refused; }''')
    check(blocked, 'inspection refuses to race an active capture')
    fresh('<main><p>' + 'x' * 5000 + '</p></main>')
    clipped = page.evaluate('IOConversationCapture.inspect({maxChars:1000})')
    check(clipped['frames'][0]['clipped'] and clipped['warnings'] and clipped['completeness']=='unverified', 'inspector resource truncation is explicit')
    fresh('<main><div id="shadow-host"></div></main>')
    page.evaluate('document.querySelector("#shadow-host").attachShadow({mode:"open"}).innerHTML="<p>SHADOW_PAYLOAD</p>"')
    inspected = page.evaluate('IOConversationCapture.inspect()')
    check('Open shadow-root content is not traversed' in json.dumps(inspected) and 'SHADOW_PAYLOAD' not in json.dumps(inspected), 'unsupported open shadow-root content is reported rather than fabricated')
    browser.close()
    print(f'{cases}/{cases} offline Chromium fixture checks passed')
