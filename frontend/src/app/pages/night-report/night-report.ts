import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import {
  NightDecision,
  NightRunSummary,
  NightStatus,
  NightAgentApi,
} from '../../core/api/night-agent.service';
import { Page } from '../../core/api/customers.service';
import { FrPipe } from '../../core/pipes/fr';

/**
 * Rapport de nuit : ce que l'agent nocturne a valide pendant la fenetre
 * 17h00→09h00, et ce qui attend l'analyste au retour (motif lisible).
 * Bouton « Lancer un balayage » = declencheur manuel (POST /run).
 */
@Component({
  selector: 'app-night-report',
  imports: [MatTableModule, MatPaginatorModule, MatButtonModule, MatIconModule, RouterModule, DatePipe, DecimalPipe, FrPipe],
  templateUrl: './night-report.html',
  styleUrl: './night-report.scss',
})
export class NightReport implements OnInit {
  private readonly api = inject(NightAgentApi);

  readonly rows = signal<NightDecision[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly status = signal<NightStatus | null>(null);
  readonly lastRun = signal<NightRunSummary | null>(null);
  readonly running = signal(false);

  readonly columns = ['decisionId', 'transaction', 'amount', 'moteur', 'ia', 'action', 'reason', 'acces', 'runAt'];

  ngOnInit(): void {
    this.load(0);
  }

  onPage(event: PageEvent): void {
    this.load(event.pageIndex);
  }

  runNow(): void {
    this.running.set(true);
    this.error.set(null);
    this.api.run().subscribe({
      next: (summary) => {
        this.lastRun.set(summary);
        this.running.set(false);
        this.refreshStatus();
        this.load(0);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? `Erreur ${err?.status ?? '?'} — service injoignable`);
        this.running.set(false);
      },
    });
  }

  private refreshStatus(): void {
    this.api.status().subscribe({
      next: (s) => this.status.set(s),
      error: () => this.status.set(null),
    });
  }

  private load(index: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.refreshStatus();
    this.api.decisions(index, 25).subscribe({
      next: (data: Page<NightDecision>) => {
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
