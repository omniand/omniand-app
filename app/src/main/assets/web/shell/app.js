const androidList = document.querySelector('#android-apps');
const androidSection = document.querySelector('#android-apps-section');
const webList = document.querySelector('#web-apps');
const stage = document.querySelector('#stage');
const home = document.querySelector('#home');
const template = document.querySelector('#app-template');
const time = document.querySelector('#time');
const date = document.querySelector('#date');
const isAndroidLauncher = navigator.userAgent.includes('OmniAndLauncher/');

const webIcons = {
  store: '<svg viewBox="0 0 48 48"><path d="M11 18h26l-2 21H13z"/><path d="M18 20v-5a6 6 0 0 1 12 0v5"/></svg>',
  messages: '<svg viewBox="0 0 48 48"><path d="M10 11h28v21H22l-9 7 2-7h-5z"/><path d="M16 18h16M16 24h11"/></svg>',
  test: '<svg viewBox="0 0 48 48"><path d="M19 9h10M21 9v9L12 35a3 3 0 0 0 3 4h18a3 3 0 0 0 3-4l-9-17V9"/><path d="M17 30h14"/></svg>'
};

function updateClock() {
  const now = new Date();
  time.textContent = new Intl.DateTimeFormat('fr-FR', {hour: '2-digit', minute: '2-digit'}).format(now);
  date.textContent = new Intl.DateTimeFormat('fr-FR', {weekday: 'long', day: 'numeric', month: 'long'}).format(now);
}

function appButton(app, type, action) {
  const button = template.content.firstElementChild.cloneNode(true);
  const icon = button.querySelector('.app-icon');
  button.querySelector('.app-name').textContent = app.name;
  button.title = app.name;
  button.setAttribute('aria-label', `Ouvrir ${app.name}`);
  icon.classList.add(type === 'web' ? `web-${app.id}` : 'android-icon');

  if (type === 'web' && webIcons[app.id]) {
    icon.innerHTML = webIcons[app.id];
  } else if (type === 'android') {
    const image = document.createElement('img');
    image.src = `/api/apps/android/${encodeURIComponent(app.package)}/icon`;
    image.alt = '';
    image.addEventListener('error', () => {
      image.remove();
      icon.textContent = app.name.slice(0, 1).toUpperCase();
    });
    icon.append(image);
  } else {
    icon.textContent = app.name.slice(0, 1).toUpperCase();
  }

  button.addEventListener('click', action);
  return button;
}

async function loadApps() {
  try {
    const webResponse = await fetch('/api/apps/web');
    if (!webResponse.ok) throw new Error('Web apps unavailable');
    const webApps = await webResponse.json();
    webList.replaceChildren(...webApps.map(app => appButton(app, 'web', () => openWebApp(app))));

    if (isAndroidLauncher) {
      const androidResponse = await fetch('/api/apps/android');
      if (!androidResponse.ok) throw new Error('Android apps unavailable');
      const androidApps = await androidResponse.json();
      androidList.replaceChildren(...androidApps.map(app => appButton(app, 'android', () => launchAndroid(app))));
      androidSection.hidden = false;
    }
  } catch (error) {
    webList.innerHTML = '<p class="error">Impossible de joindre le serveur du téléphone.</p>';
    androidList.replaceChildren();
  }
}

function openWebApp(app) {
  const frame = document.createElement('iframe');
  frame.src = `${app.origin}/`;
  frame.title = app.name;
  frame.sandbox = 'allow-scripts allow-same-origin';
  const bar = document.createElement('div');
  bar.className = 'app-bar';
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'back';
  close.setAttribute('aria-label', 'Retour aux applications');
  close.textContent = '‹';
  close.addEventListener('click', closeApp);
  const title = document.createElement('strong');
  title.textContent = app.name;
  bar.append(close, title);
  stage.replaceChildren(bar, frame);
  home.setAttribute('aria-hidden', 'true');
  stage.classList.add('open');
}

function closeApp() {
  stage.classList.remove('open');
  stage.replaceChildren();
  home.removeAttribute('aria-hidden');
  loadApps();
}

async function launchAndroid(app) {
  try {
    const response = await fetch(`/api/apps/android/${encodeURIComponent(app.package)}/launch`, {method: 'POST'});
    if (!response.ok) throw new Error();
  } catch (_) {
    alert('Cette application Android ne peut être lancée que depuis le téléphone.');
  }
}

updateClock();
setInterval(updateClock, 30000);
loadApps();
