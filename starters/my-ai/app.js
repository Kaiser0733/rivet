const field = id => document.getElementById(id);
const chat = field('chat'), demo = field('demo'), message = field('message');
const form = document.querySelector('form'), send = form.querySelector('button');
const history = [];
function add(role, text) {
  const row = document.createElement('div'), name = document.createElement('strong'), body = document.createElement('p');
  row.className = role;
  name.textContent = role === 'user' ? 'You' : 'Fieldnote';
  body.textContent = text;
  row.append(name, body);
  chat.append(row);
  chat.scrollTop = chat.scrollHeight;
}
demo.addEventListener('change', () => { field('mode').textContent = demo.checked ? 'Demo mode' : 'Live model'; });
function demoReply(text) {
  if (/^(hi|hello|hey)[!. ]*$/i.test(text)) return 'Hello. Try outlining a small project or planning your next step.';
  if (/plan|build|make|start/i.test(text)) return 'Start with a small version you can finish today. What would you like it to do first?';
  return 'You wrote “' + text.slice(0, 100) + (text.length > 100 ? '…' : '') +
    '”. This is an offline demo. Try asking for a first step, or connect a model in Settings.';
}
function requestSettings() {
  try {
    const url = new URL(field('endpoint').value);
    const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
    if ((url.protocol !== 'https:' && !(local && url.protocol === 'http:')) || url.username || url.password) return null;
    if (!url.pathname.endsWith('/chat/completions')) {
      url.pathname = url.pathname.replace(/\/+$/, '') + '/chat/completions';
    }
    const model = field('model').value.trim(), key = field('key').value.trim();
    return model && key ? {url, model, key} : null;
  } catch { return null; }
}
form.addEventListener('submit', async event => {
  event.preventDefault();
  const text = message.value.trim();
  if (!text || send.disabled) return;
  const offline = demo.checked, settings = offline ? null : requestSettings();
  if (!offline && !settings) {
    field('status').textContent = 'Enter a valid endpoint, model and key, or switch back to demo mode.';
    return;
  }
  add('user', text);
  message.value = '';
  send.disabled = true;
  field('status').textContent = 'Writing…';
  try {
    let reply;
    if (offline) reply = demoReply(text);
    else {
      const response = await fetch(settings.url, {
        method:'POST', redirect:'error', headers:{'Content-Type':'application/json', Authorization:'Bearer ' + settings.key},
        body:JSON.stringify({model:settings.model, messages:[...history.slice(-18), {role:'user',content:text}]}),
      });
      if (!response.ok) throw new Error('Model request failed');
      reply = (await response.json()).choices?.[0]?.message?.content;
      if (typeof reply !== 'string' || !reply.trim()) throw new Error('Model reply was empty');
      history.push({role:'user',content:text}, {role:'assistant',content:reply});
    }
    add('assistant', reply);
    field('status').textContent = '';
  } catch {
    field('status').textContent = 'The model could not reply. Check the endpoint, key and browser access.';
    message.value = text;
  } finally { send.disabled = false; }
});
