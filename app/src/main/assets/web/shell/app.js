const webList = document.querySelector('#web-apps');
const template = document.querySelector('#app-template');
const isAndroidPlatform = navigator.userAgent.includes('OmniAndPlatform/');
const backdrop = document.querySelector('#menu-backdrop');
const menuTitle = document.querySelector('#menu-title');
const menuMark = document.querySelector('#menu-mark');
const integrateButton = document.querySelector('#menu-integrate');
const updateButton = document.querySelector('#menu-update');
const uninstallButton = document.querySelector('#menu-uninstall');
const cancelButton = document.querySelector('#menu-cancel');
const menuActions = document.querySelector('#menu-actions');
const uninstallConfirm = document.querySelector('#uninstall-confirm');
const uninstallBack = document.querySelector('#uninstall-back');
const uninstallConfirmButton = document.querySelector('#uninstall-confirm-button');
const menuMessage = document.querySelector('#menu-message');
const menuMessageText = document.querySelector('#menu-message-text');
const menuMessageClose = document.querySelector('#menu-message-close');
const updateConfirm = document.querySelector('#update-confirm');
const updateCapabilities = document.querySelector('#update-capabilities');
const updateBack = document.querySelector('#update-back');
const updateConfirmButton = document.querySelector('#update-confirm-button');
let selectedApp = null;
let selectedUpdate = null;
let updateCheckSequence = 0;

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
  integrateButton.hidden = !app.androidIntegration?.supported;
  integrateButton.textContent = app.androidIntegration.installed ? 'Update Android integration' : 'Add to Android';
  uninstallButton.hidden = app.id === 'store';
  selectedUpdate = null;
  updateButton.hidden = !app.updatable;
  updateButton.disabled = true;
  updateButton.textContent = 'Checking for updates…';
  showActions();
  backdrop.hidden = false;
  if (app.updatable) checkForUpdate(app, ++updateCheckSequence);
}

function showActions() {
  menuActions.hidden = false;
  uninstallConfirm.hidden = true;
  updateConfirm.hidden = true;
  menuMessage.hidden = true;
}

function showUninstallConfirmation() {
  menuActions.hidden = true;
  uninstallConfirm.hidden = false;
  updateConfirm.hidden = true;
  menuMessage.hidden = true;
}

function showMessage(message) {
  menuActions.hidden = true;
  uninstallConfirm.hidden = true;
  updateConfirm.hidden = true;
  menuMessageText.textContent = message;
  menuMessage.hidden = false;
}

function closeMenu() {
  updateCheckSequence++;
  backdrop.hidden = true;
  selectedApp = null;
}

async function checkForUpdate(app, sequence) {
  try {
    const response = await fetch(`/api/apps/web/${encodeURIComponent(app.id)}/update`);
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Update check failed');
    if (sequence !== updateCheckSequence || selectedApp?.id !== app.id) return;
    if (!result.available) {
      updateButton.hidden = true;
      return;
    }
    selectedUpdate = result;
    updateButton.textContent = `Update to ${result.availableVersion}`;
    updateButton.disabled = false;
  } catch (_) {
    if (sequence !== updateCheckSequence || selectedApp?.id !== app.id) return;
    updateButton.hidden = false;
    updateButton.disabled = true;
    updateButton.textContent = 'Couldn’t check for updates';
  }
}

function requestUpdate() {
  if (!selectedApp || !selectedUpdate) return;
  if (!selectedUpdate.addedCapabilities.length) {
    updateApp(selectedApp, selectedUpdate);
    return;
  }
  menuActions.hidden = true;
  uninstallConfirm.hidden = true;
  menuMessage.hidden = true;
  updateCapabilities.replaceChildren(...selectedUpdate.addedCapabilities.map(capability => {
    const item = document.createElement('li');
    item.textContent = capability;
    return item;
  }));
  updateConfirm.hidden = false;
}

function setMenuDisabled(disabled) {
  document.querySelectorAll('.app-menu button').forEach(button => { button.disabled = disabled; });
}

async function updateApp(app, update) {
  setMenuDisabled(true);
  try {
    const response = await fetch(`/api/apps/web/${encodeURIComponent(app.id)}/update`, {
      method: 'POST',
      headers: {'X-OmniAnd-Update-Version': update.availableVersion}
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Unable to update this app');
    await loadApps();
    showMessage(`${app.name} was updated to ${result.newVersion}.`);
  } catch (error) {
    showMessage(`${error.message}. Check the Store connection and try again.`);
  } finally {
    setMenuDisabled(false);
  }
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
updateButton.addEventListener('click', requestUpdate);
uninstallButton.addEventListener('click', showUninstallConfirmation);
uninstallBack.addEventListener('click', showActions);
uninstallConfirmButton.addEventListener('click', () => selectedApp && uninstallApp(selectedApp));
updateBack.addEventListener('click', showActions);
updateConfirmButton.addEventListener('click', () => selectedApp && selectedUpdate && updateApp(selectedApp, selectedUpdate));
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
