import { DatePipe } from '@angular/common';
import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { AlertsService, Alert } from '../../core/api/alerts.service';
import { AiService, ChatAnswer } from '../../core/api/ai.service';
import { FrPipe } from '../../core/pipes/fr';

/** Un tour de conversation. */
interface Msg {
  role: 'user' | 'ai';
  text: string;
  intent?: string;
  mode?: string;
  /** La réponse pointe vers la constellation (bouton d'accès direct). */
  constellation?: boolean;
  alertId?: number;
}

/** Suggestions calées sur les mots-clés du ChatRouter (M6 + constellation). */
const SUGGESTIONS = [
  'Résume cette alerte',
  'Explique-moi cette alerte',
  'Montre la constellation',
  'Quels signaux as-tu trouvés ?',
  'Pourquoi ce score ?',
  'Que faire maintenant ?',
];

@Component({
  selector: 'app-ia',
  imports: [DatePipe, RouterModule, FrPipe],
  templateUrl: './ia.html',
  styleUrl: './ia.scss',
})
export class Ia implements OnInit {
  private readonly alertsApi = inject(AlertsService);
  private readonly ai = inject(AiService);

  @ViewChild('scroll') private scroll?: ElementRef<HTMLDivElement>;

  readonly alerts = signal<Alert[]>([]);
  readonly selectedAlertId = signal<number | null>(null);
  readonly messages = signal<Msg[]>([
    {
      role: 'ai',
      text: 'Sélectionne une alerte à gauche, puis pose ta question. Je réponds à partir des données réelles (moteur, transactions, réseau) — mode déterministe par défaut.',
    },
  ]);
  readonly sending = signal(false);

  readonly suggestions = SUGGESTIONS;

  ngOnInit(): void {
    this.alertsApi.page(0, 50).subscribe({
      next: (data) => {
        this.alerts.set(data.content);
        const first = data.content[0];
        if (first) {
          this.selectedAlertId.set(first.alertId);
        }
      },
      error: () => this.alerts.set([]),
    });
  }

  pick(id: number): void {
    this.selectedAlertId.set(id);
  }

  ask(text: string): void {
    const message = text.trim();
    const alertId = this.selectedAlertId();
    if (!message || !alertId || this.sending()) return;

    this.messages.update((m) => [...m, { role: 'user', text: message }]);
    this.sending.set(true);
    this.scrollBottom();

    this.ai.chat(alertId, message).subscribe({
      next: (answer: ChatAnswer) => {
        const toConstellation =
          answer.intent === 'CONSTELLATION' || answer.intent === 'EXPLAIN_ALERT';
        this.messages.update((m) => [
          ...m,
          {
            role: 'ai',
            text: answer.answer,
            intent: answer.intent,
            mode: answer.mode,
            constellation: toConstellation,
            alertId,
          },
        ]);
        this.sending.set(false);
        this.scrollBottom();
      },
      error: (err) => {
        this.messages.update((m) => [
          ...m,
          {
            role: 'ai',
            text: `⚠ ${err?.error?.message ?? `Chat ${err?.status ?? '?'} — service IA injoignable`}`,
          },
        ]);
        this.sending.set(false);
        this.scrollBottom();
      },
    });
  }

  private scrollBottom(): void {
    setTimeout(() => {
      const el = this.scroll?.nativeElement;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    }, 50);
  }
}
