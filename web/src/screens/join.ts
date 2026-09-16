// Join screen: a friend opened the host's party link. Enter a name and you're in.
import { joinParty, JoinError, lastName, NAME_MAX, type GuestSession, type PartyLink } from '../party';
import { h, icon } from '../ui/dom';
import { loadingIndicator } from '../ui/feedback';
import { brand, shapeHero } from './pair';

export interface JoinOptions {
  /** Null when there's no usable link, e.g. the party this browser was in ended. */
  link: PartyLink | null;
  /** Shown above the form, e.g. "The host removed you from the party." */
  notice?: string;
  onJoined: (session: GuestSession) => void;
}

export function renderJoin(root: HTMLElement, { link, notice, onJoined }: JoinOptions): void {
  const card = h('section', { class: 'pair-card' }, shapeHero(link ? 'celebration' : 'music_note'));
  if (notice) card.append(h('p', { class: 'notice body-medium' }, notice));

  if (!link) {
    card.append(
      h('h1', { class: 'display-small-emphasized' }, 'Not in a party'),
      h('p', { class: 'body-large pair-lead' }, 'Open the host’s party link to join. If the party ended, ask them for a new one.'),
    );
    root.replaceChildren(h('header', { class: 'top-bar' }, brand()), h('main', { class: 'pair' }, card));
    return;
  }

  const input = h('input', {
    class: 'name-input',
    id: 'guest-name',
    type: 'text',
    maxlength: NAME_MAX,
    autocomplete: 'nickname',
    enterkeyhint: 'go',
    placeholder: 'Your name',
    'aria-describedby': 'join-message',
  });
  input.value = lastName();
  const field = h('label', { class: 'name-field', for: 'guest-name' },
    icon('person'),
    h('span', { class: 'visually-hidden' }, 'Your name'),
    input);

  const submitLabel = h('span', {}, 'Join the party');
  const submit = h('button', { class: 'btn filled medium state', type: 'submit' }, submitLabel);
  const message = h('p', { class: 'form-message body-medium', id: 'join-message', role: 'alert', hidden: true });

  let busy = false;
  const sync = () => {
    submit.disabled = busy || !input.value.trim();
  };
  input.addEventListener('input', () => {
    message.hidden = true;
    field.classList.remove('error');
    sync();
  });

  const form = h('form', { class: 'pair-form join-form', autocomplete: 'off' }, field, message, submit);
  // Explicit, because implicit submission is unreliable with some on-screen keyboards.
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      form.requestSubmit();
    }
  });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = input.value.trim();
    if (!name || busy) return;
    busy = true;
    sync();
    submit.replaceChildren(loadingIndicator(24, 'Joining'));
    try {
      onJoined(await joinParty(link, name));
    } catch (err) {
      message.textContent = err instanceof JoinError ? err.message : 'Something went wrong. Try again.';
      message.hidden = false;
      field.classList.add('error');
      input.focus();
    } finally {
      busy = false;
      submit.replaceChildren(submitLabel);
      sync();
    }
  });

  card.append(
    h('h1', { class: 'display-small-emphasized' }, 'You’re invited'),
    h('p', { class: 'body-large pair-lead' }, 'Add songs to the party queue from here. They play on the host’s speakers, and everyone sees what’s coming up.'),
    form,
  );
  root.replaceChildren(h('header', { class: 'top-bar' }, brand()), h('main', { class: 'pair' }, card));
  sync();
  input.focus();
}
