// Returning user at the start of the night: tutorial done, nothing answered (pacing still).
// Writes from the old page are then blocked so its 200 ms persist cannot overwrite the seed.
export default async function ({ page, frame }) {
  await (frame ?? page).evaluate(() => {
    localStorage.setItem('overhand.standup.v1', JSON.stringify({ position: 0, answers: {}, tutorial: true }));
    Storage.prototype.setItem = () => {};
  });
  return { after: await (frame ?? page).evaluate(() => localStorage.getItem('overhand.standup.v1')) };
}
