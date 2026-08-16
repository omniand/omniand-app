const webList = document.querySelector('#web-apps');
const template = document.querySelector('#app-template');
const refresh = document.querySelector('#refresh');
const isAndroidPlatform = navigator.userAgent.includes('OmniAndPlatform/');

function appCard(app) {
  const card = template.content.firstElementChild.cloneNode(true);
  card.querySelector('.app-name').textContent = app.name;
  card.querySelector('.app-origin').textContent = new URL(app.origin).host;
  const mark = card.querySelector('.app-mark');
  if (app.icon) {
    const image = document.createElement('img');
    image.src = app.icon;
    image.alt = '';
    image.addEventListener('error', () => {
      image.remove();
      mark.textContent = app.name.slice(0, 1).toUpperCase();
    });
    mark.append(image);
  } else {
    mark.textContent = app.name.slice(0, 1).toUpperCase();
  }
  card.querySelector('.open').addEventListener('click', () => window.location.assign(`${app.origin}/`));

  const integration = card.querySelector('.integration');
  if (isAndroidPlatform && app.androidIntegration?.supported) {
    integration.hidden = false;
    integration.disabled = app.androidIntegration.installed;
    integration.textContent = app.androidIntegration.installed ? 'Added to Android' : 'Add to Android';
    integration.addEventListener('click', () => addAndroidIntegration(app, integration));
  }
  return card;
}

async function loadApps() {
  refresh.disabled = true;
  try {
    const response = await fetch('/api/apps/web');
    if (!response.ok) throw new Error('Web apps unavailable');
    const apps = await response.json();
    webList.replaceChildren(...apps.map(appCard));
  } catch (_) {
    webList.innerHTML = '<p class="state error">Unable to reach the platform server.</p>';
  } finally {
    refresh.disabled = false;
  }
}

async function addAndroidIntegration(app, button) {
  button.disabled = true;
  try {
    const response = await fetch(`/api/apps/web/${encodeURIComponent(app.id)}/integrate`, {method: 'POST'});
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Android integration unavailable');
    if (result.status === 'permission-required') {
      alert('Allow OmniAnd to install apps, then select “Add to Android” again.');
      button.disabled = false;
    } else {
      button.textContent = 'Installing…';
    }
  } catch (error) {
    alert(error.message);
    button.disabled = false;
  }
}

refresh.addEventListener('click', loadApps);
window.addEventListener('focus', loadApps);
loadApps();
