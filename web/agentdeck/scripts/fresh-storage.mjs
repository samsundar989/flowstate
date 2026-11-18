// Empty localStorage and block further writes from the old page (it persists every 200 ms, which
// would otherwise restore the state before the reload). The reloaded page writes normally again.
export default async function ({ page, frame }) {
  await (frame ?? page).evaluate(() => { localStorage.clear(); Storage.prototype.setItem = () => {}; });
  return { after: await (frame ?? page).evaluate(() => Object.keys(localStorage)) };
}
