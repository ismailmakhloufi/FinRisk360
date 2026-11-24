import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import { CasesService, CaseFile, CaseStatus } from '../../core/api/cases.service';
import { Page } from '../../core/api/customers.service';
import { AuthService } from '../../core/auth/auth.service';
import { FrPipe, frLabel } from '../../core/pipes/fr';

const COLUMNS = ['caseId', 'alertId', 'assignedTo', 'priority', 'status', 'investigationResult', 'openedAt', 'actions'];
const FILTERS: (CaseStatus | null)[] = [null, 'OPEN', 'UNDER_REVIEW', 'CONFIRMED', 'FALSE_POSITIVE', 'CLOSED'];

/** Transitions autorisées (spéc backend : les états terminaux ne bougent plus). */
const NEXT: Partial<Record<CaseStatus, { to: CaseStatus; label: string; kind: string }[]>> = {
  OPEN: [{ to: 'UNDER_REVIEW', label: 'REPRENDRE', kind: 'go' }],
  UNDER_REVIEW: [
    { to: 'CONFIRMED', label: 'CONFIRMER', kind: 'high' },
    { to: 'FALSE_POSITIVE', label: 'FAUSSE ALARME', kind: 'dim' },
    { to: 'CLOSED', label: 'CLÔTURER', kind: 'done' },
  ],
  CONFIRMED: [{ to: 'CLOSED', label: 'CLÔTURER', kind: 'done' }],
};

/**
 * Transitions qui DOIVENT porter un arbitrage (investigationResult) :
 * ce sont celles qui tranchent le dossier. « REPRENDRE » (prise en charge)
 * n'en demande pas.
 */
const NEEDS_ARBITRATION: CaseStatus[] = ['CONFIRMED', 'FALSE_POSITIVE', 'CLOSED'];

@Component({
  selector: 'app-cases',
  imports: [MatTableModule, MatPaginatorModule, RouterModule, DatePipe, FrPipe],
  templateUrl: './cases.html',
  styleUrl: './cases.scss',
})
export class Cases implements OnInit {
  private readonly api = inject(CasesService);
  readonly auth = inject(AuthService);

  readonly columns = COLUMNS;
  readonly filters = FILTERS;
  readonly filter = signal<CaseStatus | null>(null);
  readonly rows = signal<CaseFile[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly flash = signal<string | null>(null);
  readonly canWrite = this.auth.hasRole('ANALYST', 'ADMIN');

  /** Transition armée en attente d'arbitrage saisi par l'analyste. */
  readonly armed = signal<{ row: CaseFile; to: CaseStatus } | null>(null);
  readonly arbitrage = signal('');

  ngOnInit(): void {
    this.load(0);
  }

  setFilter(status: CaseStatus | null): void {
    this.filter.set(status);
    this.load(0);
  }

  onPage(event: PageEvent): void {
    this.load(event.pageIndex);
  }

  transitions(status: CaseStatus) {
    return NEXT[status] ?? [];
  }

  move(row: CaseFile, to: CaseStatus): void {
    // Toute décision (CONFIRMER / FAUSSE ALARME / CLÔTURER) exige un
    // arbitrage écrit : c'est lui qui remplit investigationResult.
    if (NEEDS_ARBITRATION.includes(to)) {
      this.armed.set({ row, to });
      this.arbitrage.set(row.investigationResult ?? '');
      this.error.set(null);
      return;
    }
    this.commit(row, to);
  }

  /** Valide l'arbitrage saisi et exécute la transition armée. */
  confirmArbitrage(): void {
    const a = this.armed();
    if (!a) return;
    const text = this.arbitrage().trim();
    if (!text) {
      this.error.set('Arbitrage obligatoire : décrivez la conclusion de l\'enquête (50 car. max).');
      return;
    }
    this.commit(a.row, a.to, text.slice(0, 50));
  }

  cancelArbitrage(): void {
    this.armed.set(null);
    this.error.set(null);
  }

  private commit(row: CaseFile, to: CaseStatus, investigationResult?: string): void {
    this.error.set(null);
    this.api.changeStatus(row.caseId, to, investigationResult).subscribe({
      next: (updated) => {
        this.rows.set(this.rows().map((c) => (c.caseId === updated.caseId ? updated : c)));
        this.armed.set(null);
        const arb = updated.investigationResult ? ` · arbitrage ${updated.investigationResult}` : '';
        this.flash.set(`DOSSIER #${updated.caseId} → ${frLabel(updated.status)}${arb}`);
        setTimeout(() => this.flash.set(null), 3000);
      },
      error: (err) => this.error.set(err?.error?.message ?? `Erreur ${err?.status ?? '?'} — transition refusée`),
    });
  }

  private load(index: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.page(index, 20, this.filter() ?? undefined).subscribe({
      next: (data: Page<CaseFile>) => {
        this.rows.set(data.content);
        this.total.set(data.totalElements);
        this.page.set(data.number);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? `Erreur ${err?.status ?? '?'} — service injoignable`);
        this.loading.set(false);
      },
    });
  }
}
