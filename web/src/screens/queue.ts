// "Up next": the party queue, visible to everyone. Guests can take back their own
// requests; linked remotes can remove any song, move one to play next, or clear.
import { explain, type Queue, type QueueItem, type Relay } from '../relay';
import { h, icon } from '../ui/dom';
import { confirmDialog, snackbar } from '../ui/feedback';

export class QueuePanel {
  readonly el: HTMLElement;
  private readonly list = h('ol', { class: 'queue-list', 'aria-label': 'Up next' });
  private readonly count = h('span', { class: 'queue-count label-large' });
  private readonly clearButton = h('button', { class: 'btn text state', type: 'button' }, icon('delete_sweep'), 'Clear');
  private readonly footer = h('p', { class: 'queue-footer body-small' });

  constructor(private readonly relay: Relay, private readonly canManage: boolean) {
    this.el = h('section', { class: 'queue', 'aria-label': 'Party queue', hidden: true },
      h('div', { class: 'queue-header' },
        h('h2', { class: 'headline-small-emphasized' }, 'Up next'),
        this.count,
        canManage ? this.clearButton : null),
      this.list,
      this.footer,
    );
    this.clearButton.addEventListener('click', async () => {
      const ok = await confirmDialog({
        title: 'Clear the queue?',
        body: 'Every waiting request is removed. The song playing now keeps playing.',
        confirm: 'Clear',
      });
      if (ok) this.relay.command('queue.clear').catch((e) => snackbar(explain(e)));
    });
  }

  /** Hidden when there's no party. */
  render(queue: Queue | null, partyActive: boolean): void {
    this.el.hidden = !partyActive;
    const items = queue?.items ?? [];

    this.count.textContent = items.length ? String(items.length) : '';
    this.clearButton.hidden = items.length === 0;

    if (!items.length) {
      this.list.replaceChildren(h('li', { class: 'queue-empty body-medium' },
        icon('queue_music'),
        h('span', {}, 'Nothing queued yet. Search for a song and add it.')));
    } else {
      this.list.replaceChildren(...items.map((item, index) => this.row(item, index)));
    }

    const limit = queue?.limitPerGuest;
    this.footer.hidden = this.canManage || !limit;
    if (limit) {
      const mine = items.filter((i) => i.requestedBy[0]?.id === this.relay.selfId).length;
      this.footer.textContent = `You have ${mine} of ${limit} songs waiting.`;
    }
  }

  /** "Requested by Sam", or "You", for the song playing now; null when it wasn't requested. */
  requesterLine(item: QueueItem | null): string | null {
    return item ? `Requested by ${this.names(item)}` : null;
  }

  private names(item: QueueItem): string {
    const [first, ...others] = item.requestedBy;
    if (!first) return 'someone';
    const who = first.id === this.relay.selfId ? 'you' : first.name;
    return others.length ? `${who} +${others.length}` : who;
  }

  private row(item: QueueItem, index: number): HTMLElement {
    const mine = item.requestedBy[0]?.id === this.relay.selfId;
    const img = h('img', { alt: '', loading: 'lazy', referrerpolicy: 'no-referrer' });
    if (item.thumbnail) img.src = item.thumbnail.replace(/=w\d+-h\d+/, '=w120-h120');

    const actions = h('div', { class: 'queue-actions' });
    if (this.canManage && index > 0) {
      actions.append(this.action('vertical_align_top', `Play “${item.title}” next`, () =>
        this.relay.command('queue.move', { itemId: item.itemId, toIndex: 0 })));
    }
    if (this.canManage || mine) {
      actions.append(this.action('close', `Remove “${item.title}”`, async () => {
        await this.relay.command('queue.remove', { itemId: item.itemId });
        snackbar(mine ? `Took back “${item.title}”` : `Removed “${item.title}”`);
      }));
    }

    const by = this.names(item);
    return h('li', { class: `list-item queue-item${mine ? ' mine' : ''}` },
      h('span', { class: 'queue-position label-large', 'aria-hidden': 'true' }, String(index + 1)),
      img,
      h('div', { class: 'text' },
        h('div', { class: 'headline body-large' }, item.title),
        h('div', { class: 'supporting body-medium' },
          // Who asked comes first, so a long artist name can't push it out of view.
          h('span', { class: `by-chip label-small${mine ? ' you' : ''}` }, mine ? `You${by.slice(3)}` : by),
          h('span', { class: 'supporting-text' }, item.artist ?? ''))),
      actions,
    );
  }

  private action(glyph: 'close' | 'vertical_align_top', label: string, run: () => Promise<unknown>): HTMLElement {
    const b = h('button', { class: 'icon-btn state', type: 'button', 'aria-label': label, title: label }, icon(glyph));
    b.addEventListener('click', async () => {
      b.disabled = true;
      try {
        await run();
      } catch (e) {
        snackbar(explain(e));
        b.disabled = false;
      }
    });
    return b;
  }
}
