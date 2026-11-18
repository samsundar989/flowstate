(() => {
  'use strict';
  const board = document.getElementById('board');
  const scoreLabel = document.getElementById('score');
  const bestLabel = document.getElementById('best');
  const bestKey = 'garden-best-score';
  let best = 0;
  try {
    const saved = Number(localStorage.getItem(bestKey));
    if (Number.isSafeInteger(saved) && saved >= 0) best = saved;
  } catch (_) { /* Keep a session best if storage is unavailable. */ }
  bestLabel.textContent = String(best);
  const timeLabel = document.getElementById('time');
  const startButton = document.getElementById('start');
  const status = document.getElementById('garden-status');
  const result = document.getElementById('result');
  const timeNote = document.getElementById('time-note');
  const hint = document.getElementById('hint');
  const slots = [];
  let playing = false;
  let score = 0;
  let deadline = 0;
  let nextSpawn = 0;
  let frame = 0;

  for (let i = 0; i < 9; i++) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'hole';
    button.setAttribute('aria-label', `Empty hole ${i + 1}`);
    button.disabled = true;
    button.innerHTML = '<span class="bug" aria-hidden="true"><span class="antenna"></span><span class="head"></span><span class="shell"></span></span><span class="point" aria-hidden="true">+1</span>';
    const slot = { button, expires: 0, clearAt: 0, live: false };
    button.addEventListener('click', () => squash(slot));
    slots.push(slot);
    board.appendChild(button);
  }
  [0, 4, 8].forEach(i => slots[i].button.classList.add('preview'));

  function clearSlot(slot) {
    slot.live = false;
    slot.clearAt = 0;
    slot.button.className = 'hole';
    slot.button.disabled = true;
    slot.button.setAttribute('aria-label', `Empty hole ${slots.indexOf(slot) + 1}`);
  }

  function spawn(now) {
    const available = slots.filter(slot => !slot.live && !slot.clearAt);
    if (!available.length || slots.filter(slot => slot.live).length >= 3) return;
    const slot = available[Math.floor(Math.random() * available.length)];
    const elapsed = 30000 - (deadline - now);
    slot.live = true;
    slot.expires = now + 1100 - elapsed / 75;
    slot.button.className = 'hole live';
    slot.button.disabled = false;
    slot.button.setAttribute('aria-label', 'Squash bug');
  }

  function squash(slot) {
    const now = performance.now();
    if (!playing) return;
    if (now >= deadline) { finish(); return; }
    if (!slot.live || now >= slot.expires) { clearSlot(slot); return; }
    slot.live = false;
    slot.clearAt = now + 430;
    slot.button.disabled = true;
    slot.button.className = 'hole squashed';
    slot.button.setAttribute('aria-label', 'Bug squashed');
    scoreLabel.textContent = String(++score);
    if (score > best) {
      best = score;
      bestLabel.textContent = String(best);
      try { localStorage.setItem(bestKey, String(best)); } catch (_) {}
    }
  }

  function tick(now) {
    if (!playing) return;
    if (now >= deadline) { finish(); return; }
    const seconds = Math.ceil((deadline - now) / 1000);
    timeLabel.textContent = String(seconds);
    document.querySelector('.time-stat').classList.toggle('urgent', seconds <= 5);
    for (const slot of slots) {
      if ((slot.live && now >= slot.expires) || (slot.clearAt && now >= slot.clearAt)) clearSlot(slot);
    }
    if (now >= nextSpawn) {
      spawn(now);
      nextSpawn = now + 340 + Math.random() * 140;
    }
    frame = requestAnimationFrame(tick);
  }

  function finish() {
    playing = false;
    cancelAnimationFrame(frame);
    slots.forEach(clearSlot);
    timeLabel.textContent = '0';
    timeNote.textContent = 'round complete';
    document.querySelector('.time-stat').classList.remove('urgent');
    status.textContent = 'A lovely little round!';
    hint.textContent = 'Take a breath. Then catch a few more.';
    startButton.disabled = false;
    startButton.innerHTML = 'Play again <span aria-hidden="true">↗</span>';
    document.getElementById('instruction').hidden = true;
    result.hidden = false;
    result.textContent = `Time’s up! ${score} ${score === 1 ? 'bug' : 'bugs'} squashed. A fresh patch is just a tap away.`;
  }

  startButton.addEventListener('click', () => {
    if (playing) return;
    slots.forEach(clearSlot);
    score = 0;
    scoreLabel.textContent = '0';
    timeLabel.textContent = '30';
    result.hidden = true;
    document.getElementById('instruction').hidden = false;
    timeNote.textContent = 'make every tap count';
    status.textContent = 'They’re popping up!';
    hint.textContent = 'See a bug? Give it a tap!';
    startButton.textContent = 'Round in progress…';
    startButton.disabled = true;
    playing = true;
    const now = performance.now();
    deadline = now + 30000;
    nextSpawn = now + 340;
    spawn(now);
    frame = requestAnimationFrame(tick);
  });
})();
