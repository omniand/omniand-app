const state = document.querySelector('#state');

async function start() {
  try {
    const response = await fetch('/api/store/config');
    if (!response.ok) throw new Error('Configuration du Store indisponible.');
    const config = await response.json();
    const {storeUrl} = config;
    const installedApps = new Set(config.installedApps || []);
    const storeOrigin = new URL(storeUrl).origin;
    const frame = document.createElement('iframe');
    frame.src = storeUrl;
    frame.title = 'OmniAnd Store';
    frame.sandbox = 'allow-scripts allow-same-origin allow-downloads';
    state.replaceWith(frame);

    window.addEventListener('message', async event => {
      if (event.source !== frame.contentWindow || event.origin !== storeOrigin) return;
      if (event.data?.type === 'omniand:store-ready') {
        frame.contentWindow.postMessage({type: 'omniand:host-ready', installedApps: [...installedApps]}, storeOrigin);
        return;
      }
      if (event.data?.type === 'omniand:uninstall') {
        const requestId = event.data.requestId;
        try {
          const uninstallResponse = await fetch('/api/apps/uninstall', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({id: event.data.id})
          });
          const result = await uninstallResponse.json();
          if (!uninstallResponse.ok) throw new Error(result.error || 'Désinstallation impossible.');
          installedApps.delete(result.id);
          frame.contentWindow.postMessage({type: 'omniand:uninstalled', requestId, id: result.id}, storeOrigin);
        } catch (error) {
          frame.contentWindow.postMessage({type: 'omniand:uninstall-error', requestId, error: error.message}, storeOrigin);
        }
        return;
      }
      if (event.data?.type !== 'omniand:install') return;
      const requestId = event.data.requestId;
      try {
        const installResponse = await fetch('/api/apps/install', {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({packageUrl: event.data.packageUrl})
        });
        const result = await installResponse.json();
        if (!installResponse.ok) throw new Error(result.error || 'Installation impossible.');
        installedApps.add(result.id);
        frame.contentWindow.postMessage({type: 'omniand:installed', requestId, app: result}, storeOrigin);
      } catch (error) {
        frame.contentWindow.postMessage({type: 'omniand:install-error', requestId, error: error.message}, storeOrigin);
      }
    });

    frame.addEventListener('load', () => {
      frame.contentWindow.postMessage({type: 'omniand:host-ready', installedApps: [...installedApps]}, storeOrigin);
    });
  } catch (error) {
    state.className = 'error';
    state.innerHTML = `<p><strong>Impossible d’ouvrir le Store.</strong><br>${error.message}</p>`;
  }
}

start();
