import { Component, OnDestroy, OnInit, inject, signal, computed } from '@angular/core';
import { RouterModule } from '@angular/router';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FrPipe } from '../../core/pipes/fr';
import { Observable, firstValueFrom } from 'rxjs';
import { CustomersService, Customer } from '../../core/api/customers.service';
import { AccountsService } from '../../core/api/accounts.service';
import { TransactionsService, Transaction } from '../../core/api/transactions.service';
import { AlertsService, Alert, Severity } from '../../core/api/alerts.service';
import {
  StatsService,
  AlertStats,
  TransactionStats,
  EvaluationStats,
} from '../../core/api/stats.service';

/** Intervalle du dashboard vivant (les chiffres bougent sans F5). */
const REFRESH_MS = 15_000;
/** Circonférence du donut (2πr, r = 54) — pour stroke-dasharray. */
const DONUT_C = 2 * Math.PI * 54;

/** Couleurs plateforme en dur — les SVG restent lisibles même si un
 *  style composant ne s'applique pas (attributs de présentation). */
const SEV_COLOR: Record<Severity, string> = {
  CRITICAL: '#ff4d6d',
  HIGH: '#ff4d6d',
  MEDIUM: '#ffb020',
  LOW: '#3ddc97',
};

interface SevSegment {
  key: Severity;
  value: number;
  pct: number;
  len: number;
  offset: number;
  /** stroke-dasharray prêt à l'emploi : "arc vide". */
  dash: string;
  /** Hex plateforme (attribut stroke de secours). */
  color: string;
}

interface ActBar {
  day: string;
  count: number;
  x: number;
  /** Largeur de barre — réduite quand la fenêtre passe à 30 jours. */
  w: number;
  y: number;
  h: number;
  hot: boolean;
  /** Étiquette de valeur au-dessus (masquée si 30 barres : illisible). */
  showVal: boolean;
  /** Étiquette de jour en bas (une sur N à partir de 10 jours). */
  showDay: boolean;
}

@Component({
  selector: 'app-dashboard',
  imports: [RouterModule, DatePipe, DecimalPipe, FrPipe],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard implements OnInit, OnDestroy {
  private readonly customersApi = inject(CustomersService);
  private readonly accountsApi = inject(AccountsService);
  private readonly txApi = inject(TransactionsService);
  private readonly alertsApi = inject(AlertsService);
  private readonly statsApi = inject(StatsService);

  readonly stats = signal({ clients: 0, comptes: 0, transactions: 0, alertes: 0, alertesOpen: 0, casesOpen: 0 });
  readonly alertStats = signal<AlertStats | null>(null);
  readonly txStats = signal<TransactionStats | null>(null);
  readonly evalStats = signal<EvaluationStats | null>(null);
  readonly latest = signal<Alert[]>([]);
  readonly loading = signal(true);

  /** Filtres de la recherche « combien de transactions ? ». */
  readonly fName = signal('');
  readonly fClient = signal('');
  readonly fFrom = signal('');
  readonly fUntil = signal('');
  readonly clientOptions = signal<Customer[]>([]);
  readonly searchResult = signal<{ total: number; rows: Transaction[] } | null>(null);
  readonly searching = signal(false);

  /** Fenêtre de l'histogramme d'activité : 7 j (défaut) ou 30 j. */
  readonly actDays = signal(7);
  readonly abs = Math.abs;

  /** Première alerte OPEN = cible par défaut de la constellation. */
  readonly firstOpenAlert = computed(() =>
    this.latest().find((a) => a.status === 'OPEN') ?? null,
  );
  readonly firstOpenAlertId = computed(() => this.firstOpenAlert()?.alertId ?? null);
  readonly firstOpenAlertCode = computed(() => this.firstOpenAlert()?.alertCode ?? '');

  /** Segments du donut sévérité (CRITICAL d'abord, ordre décroissant). */
  readonly sevSegments = computed<SevSegment[]>(() => {
    const s = this.alertStats();
    if (!s) return [];
    const order: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
    const total = order.reduce((n, k) => n + (s.bySeverity[k] ?? 0), 0);
    if (total === 0) return [];
    let offset = 0;
    return order
      .map((key) => {
        const value = s.bySeverity[key] ?? 0;
        const len = (value / total) * DONUT_C;
        const segment: SevSegment = {
          key,
          value,
          pct: Math.round((value / total) * 100),
          len,
          offset,
          dash: `${len} ${DONUT_C - len}`,
          color: SEV_COLOR[key],
        };
        offset += len;
        return segment;
      })
      .filter((seg) => seg.value > 0);
  });

  /** Barres de l'activité transactions (7 ou 30 j, hauteur indexée sur le max). */
  readonly activityBars = computed<ActBar[]>(() => {
    const days = this.txStats()?.activity ?? [];
    if (!days.length) return [];
    const n = days.length;
    const max = Math.max(1, ...days.map((d) => d.count));
    const step = 258 / n;
    const w = n <= 7 ? 22 : Math.max(3, Math.min(14, step * 0.7));
    const showVal = n <= 10;
    const labelEvery = n <= 10 ? 1 : Math.ceil(n / 7);
    return days.map((d, i) => {
      const h = d.count === 0 ? 2 : Math.max(4, Math.round((d.count / max) * 76));
      return {
        day: d.day.slice(8),
        count: d.count,
        x: i * step + 8,
        w,
        y: 96 - h,
        h,
        hot: d.count === max && max > 1,
        showVal: showVal && d.count > 0,
        showDay: i % labelEvery === 0,
      };
    });
  });

  readonly watchlist = computed(() => this.evalStats()?.watchlist ?? []);
  readonly topBeneficiaries = computed(() => this.txStats()?.topBeneficiaries ?? []);
  readonly tbMax = computed(() => Math.max(1, ...this.topBeneficiaries().map((b) => b.count)));

  /** Alertes haute/critique encore actives — bannière rouge prioritaire. */
  readonly activeHighCritical = computed(() => this.alertStats()?.activeHighCritical ?? 0);

  /** % d'alertes clôturées (résolues + faux positifs) sur le total. */
  readonly resolutionPct = computed(() => {
    const a = this.alertStats();
    if (!a || a.totalAlerts === 0) return 0;
    return Math.round((a.closedAlerts / a.totalAlerts) * 100);
  });

  /** Tendance du volume vs la fenêtre précédente (null = base vide). */
  readonly trend = computed(() => this.txStats()?.trendPct ?? null);

  /** Répartitions par type / canal, triées et exprimées en %. */
  readonly typeList = computed(() => this.splitList(this.txStats()?.byType));
  readonly channelList = computed(() => this.splitList(this.txStats()?.byChannel));

  private splitList(map?: Record<string, number>): { key: string; count: number; pct: number }[] {
    if (!map) return [];
    const total = Object.values(map).reduce((n, v) => n + v, 0);
    if (total === 0) return [];
    return Object.entries(map)
      .filter(([, count]) => count > 0)
      .sort((a, b) => b[1] - a[1])
      .map(([key, count]) => ({ key, count, pct: (count / total) * 100 }));
  }

  /** Horodatage du dernier rafraîchissement (preuve du temps réel). */
  readonly lastUpdate = signal<Date | null>(null);
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.load();
    this.loadClients();
    // Dashboard vivant : rafraîchit les chiffres toutes les 15 s sans F5.
    this.timer = setInterval(() => this.load(), REFRESH_MS);
  }

  ngOnDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  /** Dropdown des clients — chargé UNE fois (pas dans la boucle 15 s). */
  private async loadClients(): Promise<void> {
    try {
      const page = await firstValueFrom(this.customersApi.page(0, 100));
      this.clientOptions.set(page.content);
    } catch {
      // dropdown laissé vide : la recherche par client reste indisponible
    }
  }

  /** Compte + liste des transactions correspondant aux filtres. */
  async runSearch(): Promise<void> {
    this.searching.set(true);
    try {
      const page = await firstValueFrom(this.txApi.page(
        0,
        25,
        {
          beneficiary: this.fName().trim() || undefined,
          customerId: this.fClient() ? Number(this.fClient()) : undefined,
          since: this.fFrom() || undefined,
          until: this.fUntil() || undefined,
        },
        '-transactionDate',
      ));
      this.searchResult.set({ total: page.totalElements, rows: page.content });
    } finally {
      this.searching.set(false);
    }
  }

  resetSearch(): void {
    this.fName.set('');
    this.fClient.set('');
    this.fFrom.set('');
    this.fUntil.set('');
    this.searchResult.set(null);
  }

  /** Bascule 7 j / 30 j : recharge uniquement les stats transactions. */
  setActDays(days: number): void {
    if (this.actDays() === days) return;
    this.actDays.set(days);
    this.safeStats(() => this.statsApi.transactionStats(days)).then((s) => {
      if (s) this.txStats.set(s);
    });
  }

  /** Appel tolérant : un stats-endpoint en panne ne casse pas le dashboard. */
  private safeStats<T>(call: () => Observable<T>): Promise<T | null> {
    return firstValueFrom(call()).catch(() => null);
  }

  private async load(): Promise<void> {
    try {
      const [clients, comptes, alertes, aStats, tStats, eStats] = await Promise.all([
        firstValueFrom(this.customersApi.page(0, 1)),
        firstValueFrom(this.accountsApi.page(0, 1)),
        firstValueFrom(this.alertsApi.page(0, 5)),
        this.safeStats(() => this.statsApi.alertStats()),
        this.safeStats(() => this.statsApi.transactionStats(this.actDays())),
        this.safeStats(() => this.statsApi.evaluationStats()),
      ]);
      this.alertStats.set(aStats);
      this.txStats.set(tStats);
      this.evalStats.set(eStats);
      this.stats.set({
        clients: clients.totalElements,
        comptes: comptes.totalElements,
        transactions: tStats?.totalTransactions ?? 0,
        alertes: aStats?.totalAlerts ?? 0,
        // Agrégat SQL : exact même si > 50 alertes (contrairement au filtre page).
        alertesOpen: aStats?.byStatus['OPEN'] ?? 0,
        casesOpen: aStats?.openCases ?? 0,
      });
      this.latest.set(alertes.content.slice(0, 5));
    } finally {
      this.lastUpdate.set(new Date());
      this.loading.set(false);
    }
  }
}
