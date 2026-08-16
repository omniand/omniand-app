const webList = document.querySelector('#web-apps');
const template = document.querySelector('#app-template');
const isAndroidPlatform = navigator.userAgent.includes('OmniAndPlatform/');
const backdrop = document.querySelector('#menu-backdrop');
const menuTitle = document.querySelector('#menu-title');
const menuMark = document.querySelector('#menu-mark');
const integrateButton = document.querySelector('#menu-integrate');
const uninstallButton = document.querySelector('#menu-uninstall');
const cancelButton = document.querySelector('#menu-cancel');
const menuActions = document.querySelector('#menu-actions');
const uninstallConfirm = document.querySelector('#uninstall-confirm');
const uninstallBack = document.querySelector('#uninstall-back');
const uninstallConfirmButton = document.querySelector('#uninstall-confirm-button');
const menuMessage = document.querySelector('#menu-message');
const menuMessageText = document.querySelector('#menu-message-text');
const menuMessageClose = document.querySelector('#menu-message-close');
let selectedApp = null;

function fillIcon(container, app) {
  container.replaceChildren();
  if (!app.icon) {
    container.textContent = app.name.slice(0, 1).toUpperCase();
    return;
  }
  const image = document.createElement('img');
  image.src = app.icon;
  image.alt = '';
  image.addEventListener('error', () => {
    image.remove();
    container.textContent = app.name.slice(0, 1).toUpperCase();
  });
  container.append(image);
}

function appTile(app) {
  const tile = template.content.firstElementChild.cloneNode(true);
  tile.querySelector('.app-name').textContent = app.name;
  fillIcon(tile.querySelector('.app-mark'), app);

  let timer;
  let longPressed = false;
  let pressX = 0;
  let pressY = 0;
  const cancelLongPress = () => clearTimeout(timer);
  if (isAndroidPlatform) {
    tile.addEventListener('pointerdown', event => {
      if (event.button !== 0) return;
      longPressed = false;
      pressX = event.clientX;
      pressY = event.clientY;
      timer = setTimeout(() => {
        longPressed = true;
        navigator.vibrate?.(25);
        openMenu(app);
      }, 550);
    });
    tile.addEventListener('pointerup', cancelLongPress);
    tile.addEventListener('pointercancel', cancelLongPress);
    tile.addEventListener('pointermove', event => {
      if (Math.hypot(event.clientX - pressX, event.clientY - pressY) > 8) cancelLongPress();
    });
    tile.addEventListener('contextmenu', event => {
      event.preventDefault();
      openMenu(app);
    });
  }
  tile.addEventListener('click', event => {
    if (longPressed) {
      event.preventDefault();
      longPressed = false;
      return;
    }
    window.location.assign(`${app.origin}/`);
  });
  return tile;
}

function openMenu(app) {
  if (!isAndroidPlatform) return;
  selectedApp = app;
  menuTitle.textContent = app.name;
  fillIcon(menuMark, app);
  integrateButton.hidden = !app.androidIntegration?.supported || app.androidIntegration.installed;
  uninstallButton.hidden = app.id === 'store';
  showActions();
  backdrop.hidden = false;
}

function showActions() {
  menuActions.hidden = false;
  uninstallConfirm.hidden = true;
  menuMessage.hidden = true;
}

function showUninstallConfirmation() {
  menuActions.hidden = true;
  uninstallConfirm.hidden = false;
  menuMessage.hidden = true;
}

function showMessage(message) {
  menuActions.hidden = true;
  uninstallConfirm.hidden = true;
  menuMessageText.textContent = message;
  menuMessage.hidden = false;
}

function closeMenu() {
  backdrop.hidden = true;
  selectedApp = null;
}

async function loadApps() {
  try {
    const response = await fetch('/api/apps/web');
    if (!response.ok) throw new Error('Web apps unavailable');
    const apps = await response.json();
    webList.replaceChildren(...apps.map(appTile));
  } catch (_) {
    webList.innerHTML = '<p class="state error">Unable to reach the platform server.</p>';
  }
}

async function addAndroidIntegration(app) {
  integrateButton.disabled = true;
  try {
    const response = await fetch(`/api/apps/web/${encodeURIComponent(app.id)}/integrate`, {method: 'POST'});
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Android integration unavailable');
    if (result.status === 'permission-required') {
      showMessage('Allow OmniAnd to install apps, then try again.');
    } else closeMenu();
  } catch (error) {
    showMessage(error.message);
  } finally {
    integrateButton.disabled = false;
  }
}

async function uninstallApp(app) {
  uninstallConfirmButton.disabled = true;
  try {
    const response = await fetch(`/api/apps/web/${encodeURIComponent(app.id)}/uninstall`, {method: 'POST'});
    const result = await response.json();
    if (response.status === 409 && result.code === 'android-integration-installed') {
      showMessage('Remove the Android integration in the system dialog, then long-press the app and uninstall it again.');
      return;
    }
    if (!response.ok) throw new Error(result.error || 'Unable to uninstall this app');
    closeMenu();
    await loadApps();
  } catch (error) {
    showMessage(error.message);
  } finally {
    uninstallConfirmButton.disabled = false;
  }
}

integrateButton.addEventListener('click', () => selectedApp && addAndroidIntegration(selectedApp));
uninstallButton.addEventListener('click', showUninstallConfirmation);
uninstallBack.addEventListener('click', showActions);
uninstallConfirmButton.addEventListener('click', () => selectedApp && uninstallApp(selectedApp));
menuMessageClose.addEventListener('click', closeMenu);
cancelButton.addEventListener('click', closeMenu);
backdrop.addEventListener('click', event => event.target === backdrop && closeMenu());
window.addEventListener('focus', loadApps);
window.addEventListener('pageshow', loadApps);
window.addEventListener('omniand:resume', loadApps);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') loadApps();
});
loadApps();
