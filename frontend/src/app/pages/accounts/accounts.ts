import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import { AccountsService, Account } from '../../core/api/accounts.service';
import { CustomersService, Page } from '../../core/api/customers.service';
import { AuthService } from '../../core/auth/auth.service';
import { FrPipe } from '../../core/pipes/fr';

const COLUMNS = ['accountId', 'accountNumber', 'customerId', 'accountType', 'balance', 'openingDate', 'status', 'actions'];

@Component({
  selector: 'app-accounts',
  imports: [MatTableModule, MatPaginatorModule, DatePipe, DecimalPipe, FrPipe],
  templateUrl: './accounts.html',
  styleUrl: './accounts.scss',
})
export class Accounts implements OnInit {
  private readonly api = inject(AccountsService);
  private readonly customers = inject(CustomersService);
  readonly auth = inject(AuthService);

  readonly columns = COLUMNS;
  readonly rows = signal<Account[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly pageSize = 20;
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly flash = signal<string | null>(null);
  readonly busy = signal(false);
  /** Le PATCH /status est réservé MANAGER/ADMIN par la matrice du gateway. */
  readonly canWrite = this.auth.hasRole('MANAGER', 'ADMIN');
  /**
   * Supprimer UN compte = supprimer TOUT le dossier du client (tous ses
   * comptes + transactions + evaluations + alertes + le client). Les 5
   * appels ne sont permis qu'en ADMIN — voir CustomersService.deleteCascade.
   */
  readonly canDelete = this.auth.hasRole('ADMIN');
  /** Suppression = action destructive : exige un 2e clic (CONFIRMER). */
  readonly pendingDelete = signal<number | null>(null);

  ngOnInit(): void {
    this.load(0);
  }

  onPage(event: PageEvent): void {
    this.load(event.pageIndex);
  }

  toggleStatus(row: Account): void {
    const next: Account['status'] = row.status === 'ACTIVE' ? 'FROZEN' : 'ACTIVE';
    this.error.set(null);
    this.api.changeStatus(row.accountId, next).subscribe({
      next: (updated) => {
        this.rows.set(this.rows().map((a) => (a.accountId === updated.accountId ? updated : a)));
        this.flash.set(`#${updated.accountId} → ${updated.status}`);
        setTimeout(() => this.flash.set(null), 2500);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? `Erreur ${err?.status ?? '?'} — ${next} refusé`);
      },
    });
  }

  /**
   * 1er clic = arme la confirmation, 2e clic = execute (4 s pour valider).
   * Le bouton porte l'ID du compte mais efface le DOSSIER complet du
   * client : une fraude se juge au niveau du client, pas d'un compte.
   */
  deleteCustomer(row: Account): void {
    if (this.pendingDelete() === row.accountId) {
      this.pendingDelete.set(null);
      this.runDelete(row);
      return;
    }
    this.pendingDelete.set(row.accountId);
    setTimeout(() => {
      if (this.pendingDelete() === row.accountId) this.pendingDelete.set(null);
    }, 4000);
  }

  private runDelete(row: Account): void {
    this.busy.set(true);
    this.error.set(null);
    this.customers.deleteCascade(row.customerId).subscribe({
      next: (results) => {
        this.busy.set(false);
        const detail = results.map((r) => `${r.deleted} ${r.step}`).join(', ');
        this.flash.set(`Dossier du client #${row.customerId} supprimé : ${detail}`);
        setTimeout(() => this.flash.set(null), 6000);
        this.load(this.page());
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(
          err?.error?.message ?? `Erreur ${err?.status ?? '?'} — suppression interrompue`,
        );
      },
    });
  }

  private load(index: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.page(index, this.pageSize).subscribe({
      next: (data: Page<Account>) => {
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
