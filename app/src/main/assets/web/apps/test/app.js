const result = document.querySelector('#result');
async function run() {
  result.textContent = 'Testing…';
  try {
    const response = await fetch('/api/sms');
    const body = await response.json();
    result.textContent = response.status === 403
      ? `PASS — 403 Forbidden\n${body.error}`
      : `FAIL — expected 403, received ${response.status}`;
    result.className = response.status === 403 ? 'pass' : 'fail';
  } catch (error) { result.textContent = `FAIL — ${error.message}`; result.className = 'fail'; }
}
document.querySelector('#run').addEventListener('click', run);
run();
