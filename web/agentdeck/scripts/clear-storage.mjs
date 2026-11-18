// Fresh first visit: clear storage and block writes from this page; the capture then reloads.
export default async function ({ page, frame }) {
  const before = await (frame ?? page).evaluate(() => Object.keys(localStorage));
  await (frame ?? page).evaluate(() => { localStorage.clear(); Storage.prototype.setItem = () => {}; });
  return { cleared: before, after: await (frame ?? page).evaluate(() => Object.keys(localStorage)) };
}
