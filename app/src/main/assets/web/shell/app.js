const androidList = document.querySelector('#android-apps');
const webList = document.querySelector('#web-apps');
const stage = document.querySelector('#stage');
const template = document.querySelector('#app-template');

function appButton(name, subtitle, action) {
  const button = template.content.firstElementChild.cloneNode(true);
  button.querySelector('.app-icon').textContent = name.slice(0, 1).toUpperCase();
  button.querySelector('.app-name').textContent = name;
  button.querySelector('small').textContent = subtitle;
  button.addEventListener('click', action);
  return button;
}

async function loadApps() {
  try {
    const [androidResponse, webResponse] = await Promise.all([
      fetch('/api/apps/android'), fetch('/api/apps/web')
    ]);
    const [androidApps, webApps] = await Promise.all([androidResponse.json(), webResponse.json()]);
    webList.replaceChildren(...webApps.map(app => appButton(app.name, 'Web app', () => openWebApp(app))));
    androidList.replaceChildren(...androidApps.map(app => appButton(app.name, 'Android · phone only', () => launchAndroid(app))));
  } catch (error) {
    webList.innerHTML = `<p class="error">Could not reach the phone server.</p>`;
    androidList.replaceChildren();
  }
}

function openWebApp(app) {
  const frame = document.createElement('iframe');
  frame.src = app.origin + '/';
  frame.title = app.name;
  frame.sandbox = 'allow-scripts allow-same-origin';
  const bar = document.createElement('div');
  bar.className = 'app-bar';
  const close = document.createElement('button');
  close.textContent = '← Apps';
  close.addEventListener('click', closeApp);
  const title = document.createElement('strong');
  title.textContent = app.name;
  bar.append(close, title);
  stage.replaceChildren(bar, frame);
  stage.classList.add('open');
}

function closeApp() {
  stage.classList.remove('open');
  stage.innerHTML = '<div class="welcome"><span>O</span><h2>Your phone is the platform</h2><p>Choose an app from the launcher.</p></div>';
}

async function launchAndroid(app) {
  try {
    const response = await fetch(`/api/apps/android/${encodeURIComponent(app.package)}/launch`, {method: 'POST'});
    if (!response.ok) throw new Error();
  } catch (_) {
    alert('Android apps can only be launched on the phone.');
  }
}

loadApps();
