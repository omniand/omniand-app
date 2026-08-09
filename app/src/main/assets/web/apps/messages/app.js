const list = document.querySelector('#messages');
const refresh = document.querySelector('#refresh');

function messageCard(message) {
  const article = document.createElement('article');
  const top = document.createElement('div');
  const address = document.createElement('strong');
  const date = document.createElement('time');
  const body = document.createElement('p');
  address.textContent = message.address || 'Unknown';
  date.dateTime = new Date(message.date).toISOString();
  date.textContent = new Intl.DateTimeFormat(undefined, {dateStyle: 'medium', timeStyle: 'short'}).format(message.date);
  body.textContent = message.body;
  top.append(address, date);
  article.append(top, body);
  return article;
}

async function loadMessages() {
  refresh.disabled = true;
  list.innerHTML = '<p class="state">Loading messages…</p>';
  try {
    const response = await fetch('/api/sms');
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Could not read messages');
    if (!data.length) list.innerHTML = '<p class="state">No SMS messages found.</p>';
    else list.replaceChildren(...data.map(messageCard));
  } catch (error) {
    list.innerHTML = '';
    const state = document.createElement('div');
    state.className = 'state error';
    const title = document.createElement('strong');
    const detail = document.createElement('p');
    title.textContent = 'Messages unavailable';
    detail.textContent = error.message;
    state.append(title, detail);
    list.append(state);
  } finally { refresh.disabled = false; }
}

refresh.addEventListener('click', loadMessages);
loadMessages();
