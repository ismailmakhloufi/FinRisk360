import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import { Page } from '../../core/api/customers.service';
import { Transaction, TransactionsService } from '../../core/api/transactions.service';
import { FrPipe } from '../../core/pipes/fr';

const TX_COLUMNS = ['transactionId', 'accountId', 'transactionType', 'amount', 'country', 'channel', 'transactionDate', 'status'];

@Component({
  selector: 'app-transactions',
  imports: [MatTableModule, MatPaginatorModule, DatePipe, DecimalPipe, RouterModule, FrPipe],
  templateUrl: './transactions.html',
  styleUrl: './transactions.scss',
})
export class Transactions {
  private readonly txs = inject(TransactionsService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly txColumns = TX_COLUMNS;
  readonly rows = signal<Transaction[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  /** Consultation ciblee /transactions?tx=42 (rapport de nuit, alerte...). */
  readonly focused = signal<Transaction | null>(null);

  constructor() {
    // Lien rapport de nuit -> transaction : /transactions?tx=42 n'affiche
    // QUE cette transaction (comme /alertes?alert=42 pour une alerte).
    // On s'abonne pour reagir aussi au « retour au journal » (navigation
    // sans parametre -> liste complete).
    this.route.queryParamMap.subscribe((params) => {
      const wanted = Number(params.get('tx'));
      if (Number.isFinite(wanted) && wanted > 0) {
        this.loadOne(wanted);
      } else {
        this.focused.set(null);
        this.loadTx(0);
      }
    });
  }

  onPage(event: PageEvent): void {
    this.loadTx(event.pageIndex);
  }

  /** Retour au journal complet (retire le parametre ?tx=). */
  clearFocus(): void {
    this.router.navigate(['/transactions']);
  }

  private loadOne(id: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.txs.getById(id).subscribe({
      next: (t) => {
        this.focused.set(t);
        this.rows.set([t]);
        this.total.set(1);
        this.page.set(0);
        this.loading.set(false);
      },
      error: (err) => {
        this.focused.set(null);
        this.rows.set([]);
        this.total.set(0);
        this.error.set(
          err?.error?.message ?? `Transaction #${id} introuvable (Erreur ${err?.status ?? '?'})`,
        );
        this.loading.set(false);
      },
    });
  }

  private loadTx(index: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.txs.page(index, 10).subscribe({
      next: (data: Page<Transaction>) => {
        this.rows.set(data.content);
        this.total.set(data.totalElements);
        this.page.set(data.number);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
