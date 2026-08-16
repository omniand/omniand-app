const state = document.querySelector('#state');

async function start() {
  try {
    const response = await fetch('/api/store/config');
    if (!response.ok) throw new Error('Configuration du Store indisponible.');
    const config = await response.json();
    const installedApps = new Set(config.installedApps || []);
    const storeOrigin = new URL(config.storeUrl).origin;
    const frame = document.createElement('iframe');
    frame.src = config.storeUrl;
    frame.title = 'OmniAnd Store';
    frame.sandbox = 'allow-scripts allow-same-origin allow-downloads';
    state.replaceWith(frame);

    const sendState = () => frame.contentWindow.postMessage(
      {type: 'omniand:host-ready', installedApps: [...installedApps]}, storeOrigin);
    window.addEventListener('message', async event => {
      if (event.source !== frame.contentWindow || event.origin !== storeOrigin) return;
      if (event.data?.type === 'omniand:store-ready') return sendState();
      const requestId = event.data?.requestId;
      try {
        if (event.data?.type === 'omniand:install') {
          const endpoint = `/api/apps/install/${encodeURIComponent(event.data.packageUrl)}`;
          const installResponse = await fetch(endpoint, {method: 'POST'});
          const result = await installResponse.json();
          if (!installResponse.ok) throw new Error(result.error || 'Installation impossible.');
          installedApps.add(result.id);
          frame.contentWindow.postMessage({type: 'omniand:installed', requestId, app: result}, storeOrigin);
        } else if (event.data?.type === 'omniand:uninstall') {
          const uninstallResponse = await fetch(`/api/apps/uninstall/${encodeURIComponent(event.data.id)}`, {method: 'POST'});
          const result = await uninstallResponse.json();
          if (uninstallResponse.status === 409 && result.code === 'android-integration-phone-required') {
            frame.contentWindow.postMessage({
              type: 'omniand:phone-uninstall-required', requestId, id: result.id,
              name: event.data.name, error: result.error
            }, storeOrigin);
            return;
          }
          if (uninstallResponse.status === 409 && result.code === 'android-integration-installed') {
            frame.contentWindow.postMessage({
              type: 'omniand:android-uninstall-required', requestId, id: result.id,
              name: event.data.name, error: result.error
            }, storeOrigin);
            return;
          }
          if (!uninstallResponse.ok) throw new Error(result.error || 'Désinstallation impossible.');
          installedApps.delete(result.id);
          frame.contentWindow.postMessage({type: 'omniand:uninstalled', requestId, id: result.id}, storeOrigin);
        }
      } catch (error) {
        const type = event.data?.type === 'omniand:install' ? 'omniand:install-error' : 'omniand:uninstall-error';
        frame.contentWindow.postMessage({type, requestId, error: error.message}, storeOrigin);
      }
    });
    frame.addEventListener('load', sendState);
  } catch (error) {
    state.className = 'error';
    state.innerHTML = `<p><strong>Impossible d’ouvrir le Store.</strong><br>${error.message}</p>`;
  }
}

start();
