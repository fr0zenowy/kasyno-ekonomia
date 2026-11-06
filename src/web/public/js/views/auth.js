// Ekran logowania i rejestracji.
import { h, clear, fmt } from '../ui.js';
import { api } from '../api.js';

export function authView(app, info, onDone) {
  let mode = 'login';
  const err = h('div', { class: 'err', role: 'alert' });
  const user = h('input', { class: 'input', name: 'username', autocomplete: 'username', placeholder: 'np. Janek_99', maxlength: 20, required: true });
  const pass = h('input', { class: 'input', name: 'password', type: 'password', autocomplete: 'current-password', placeholder: '••••••', maxlength: 72, required: true });
  const submit = h('button', { class: 'btn primary big block', type: 'submit' }, 'Zaloguj się');
  const tabs = h('div', { class: 'seg', style: 'align-self:center' });
  const hint = h('p', { class: 'muted small' });

  const paint = () => {
    clear(tabs).append(
      h('button', { type: 'button', class: mode === 'login' ? 'on' : '', onclick: () => ((mode = 'login'), paint()) }, 'Logowanie'),
      h('button', { type: 'button', class: mode === 'register' ? 'on' : '', onclick: () => ((mode = 'register'), paint()) }, 'Nowe konto'),
    );
    submit.textContent = mode === 'login' ? 'Zaloguj się' : 'Załóż konto i graj';
    pass.autocomplete = mode === 'login' ? 'current-password' : 'new-password';
    hint.textContent = mode === 'register' ? 'Nazwa 3-20 znaków (litery, cyfry, _ lub -), hasło min. 6 znaków. Na start dostajesz 500 💰.' : '';
    err.textContent = '';
  };

  const form = h('form', {
    onsubmit: async (e) => {
      e.preventDefault();
      err.textContent = '';
      submit.classList.add('loading');
      try {
        const r = await api('POST', mode === 'login' ? '/api/login' : '/api/register', { username: user.value.trim(), password: pass.value });
        await onDone(r.user);
      } catch (ex) {
        err.textContent = ex.message;
        submit.classList.remove('loading');
      }
    },
  },
  h('div', { class: 'field' }, h('label', {}, 'Nazwa gracza'), user),
  h('div', { class: 'field' }, h('label', {}, 'Hasło'), pass),
  err, submit, hint);

  paint();
  clear(app).append(
    h('div', { class: 'auth' },
      h('div', { class: 'card glow auth-card' },
        h('div', { class: 'big-logo emoji' }, '🎰'),
        h('h1', {}, 'Kasyno Ekonomia'),
        h('p', { class: 'muted' }, 'Gry, sklep, biznesy, farma i ranking. Wszystko na wirtualne monety.'),
        tabs, form,
        h('p', { class: 'muted tiny' }, `Graczy: ${fmt(info.players)} • online: ${fmt(info.online)}`))),
  );
  user.focus();
}
