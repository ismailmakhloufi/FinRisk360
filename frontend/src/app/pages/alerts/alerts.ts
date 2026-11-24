import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, HostListener, OnInit, inject, signal, computed } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { defer, of, Observable, switchMap } from 'rxjs';
import { AlertsService, Alert, AlertStatus, AlertGroup, AlertGroupsService } from '../../core/api/alerts.service';
import { Page } from '../../core/api/customers.service';
import { Assessment, EvaluationsService, Factor } from '../../core/api/evaluations.service';
import { AiService, Analysis } from '../../core/api/ai.service';
import { CasesService, CasePriority, CaseFile, CaseStatus } from '../../core/api/cases.service';
import {
  Transaction,
  TransactionStatus,
  TransactionsService,
} from '../../core/api/transactions.service';
import { AuthService } from '../../core/auth/auth.service';
import { FrPipe, frLabel } from '../../core/pipes/fr';
import { AccountsService, Account } from '../../core/api/accounts.service';
import { CustomersService, Customer } from '../../core/api/customers.service';
import { RiskGauge } from '../../core/components/risk-gauge/risk-gauge';
import { ScoreLab } from '../../core/components/score-lab/score-lab';

const FILTERS: (AlertStatus | null)[] = [null, 'OPEN', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'FALSE_POSITIVE', 'ABSORBED'];

/** Ordre des états légaux de la machine à états alerte (backend AlertStatus). */
const LADDER: AlertStatus[] = ['OPEN', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED'];

/** Arbitrage porté par le CASE à la décision de l'analyste (50 car. max). */
const ARBITRAGE = {
  approve: 'RISQUE_ACCEPTE',
  reject: 'FRAUDE_CONFIRMEE',
} as const;

@Component({
  selector: 'app-alerts',
  imports: [RiskGauge, ScoreLab, MatPaginatorModule, DatePipe, DecimalPipe, RouterModule, FrPipe],
  templateUrl: './alerts.html',
  styleUrl: './alerts.scss',
})
export class Alerts implements OnInit {
  private readonly alertsApi = inject(AlertsService);
  private readonly groupsApi = inject(AlertGroupsService);
  private readonly evals = inject(EvaluationsService);
  private readonly ai = inject(AiService);
  private readonly casesApi = inject(CasesService);
  private readonly txs = inject(TransactionsService);
  private readonly accounts = inject(AccountsService);
  private readonly customers = inject(CustomersService);
  private readonly route = inject(ActivatedRoute);
  readonly auth = inject(AuthService);

  readonly filters = FILTERS;
  /** File d'attente par défaut : l'analyste commence par ce qui DOIT être traité. */
  readonly filter = signal<AlertStatus | null>('OPEN');
  readonly alerts = signal<Alert[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly selected = signal<Alert | null>(null);
  readonly assessment = signal<Assessment | null>(null);
  /** Transaction émise par le Bank Simulator, liée à cette alerte. */
  readonly linkedTx = signal<Transaction | null>(null);
  /** Compte porteur de la transaction (chargé après la tx). */
  readonly txAccount = signal<Account | null>(null);
  /** Client titulaire du compte — nom affiché dans le détail de l'alerte. */
  readonly txCustomer = signal<Customer | null>(null);
  /** Nom complet du client (« Prénom Nom ») ou null tant qu'il n'est pas chargé. */
  readonly clientName = computed(() => {
    const c = this.txCustomer();
    if (!c) return null;
    return `${c.firstName ?? ''} ${c.lastName ?? ''}`.trim() || null;
  });
  /** Dossier d'enquête ouvert sur cette alerte (null = aucun case). */
  readonly linkedCase = signal<CaseFile | null>(null);
  /**
   * Groupe d'alertes apparentées rattaché à cette alerte (agregation M5).
   * C'est la où l'analyste voit le PATTERN : 10 virements de 100 TND
   * ouvrent 10 alertes LOW, mais le groupe affiche « 10 alertes apparentées ·
   * cumul 1000 TND · agrégée HIGH » — le verdict individuel reste intact.
   */
  readonly linkedGroup = signal<AlertGroup | null>(null);
  /**
   * Détail des alertes membres du groupe (GET .../alerts) : sévérité, statut
   * et montant de CHAQUE membre — l'analyste voit tout le pattern (montants
   * inclus) avant d'accepter ou de refuser, sans décider « à l'aveugle ».
   */
  readonly groupMemberAlerts = signal<Alert[]>([]);
  readonly deciding = signal(false);
  readonly analysis = signal<Analysis | null>(null);
  readonly analyzing = signal(false);
  readonly flash = signal<string | null>(null);
  /** Score Lab ouvert : la preuve derrière le chiffre (clic sur la jauge). */
  readonly labOpen = signal(false);
  /** Écriture alertes + IA réservée ANALYST/ADMIN par la matrice. */
  readonly canWrite = this.auth.hasRole('ANALYST', 'ADMIN');

  // ── Décision de GROUPE (ACCEPT / REFUSE) ─────────────────────────────
  /** Modal de décision ouverte (null = fermée). */
  readonly groupDecision = signal<'ACCEPT' | 'REFUSE' | null>(null);
  /** Motif facultatif saisi par l'analyste (trace en audit_logs). */
  readonly decisionReason = signal('');
  /** Texte de confirmation tapé (REFUSE exige « ANNULER »). */
  readonly confirmText = signal('');
  readonly decidingGroup = signal(false);

  constructor() {
    // Lien case → alerte : /alertes?alert=42 ouvre directement le dossier.
    // On s'abonne (et pas snapshot) pour réagir aussi aux navigations internes.
    this.route.queryParamMap.subscribe((params) => {
      const wanted = Number(params.get('alert'));
      if (!Number.isFinite(wanted) || wanted <= 0) return;
      this.alertsApi.get(wanted).subscribe({
        next: (a) => this.select(a),
        error: () => this.error.set(`Alerte #${wanted} introuvable`),
      });
    });
  }

  ngOnInit(): void {
    this.load(0);
  }

  setFilter(status: AlertStatus | null): void {
    this.filter.set(status);
    this.load(0);
  }

  onPage(event: PageEvent): void {
    this.load(event.pageIndex);
  }

  select(alert: Alert): void {
    this.selected.set(alert);
    this.assessment.set(null);
    this.linkedTx.set(null);
    this.txAccount.set(null);
    this.txCustomer.set(null);
    this.linkedCase.set(null);
    this.linkedGroup.set(null);
    this.groupMemberAlerts.set([]);
    this.analysis.set(null);
    this.labOpen.set(false);
    this.error.set(null);
    // Dossier d'enquête rattaché (404 = pas encore de case : pas une erreur).
    this.casesApi.getByAlertId(alert.alertId).subscribe({
      next: (c) => this.linkedCase.set(c),
      error: () => this.linkedCase.set(null),
    });
    // Groupe d'alertes apparentées (null = alerte non agrégée ou groupe introuvable).
    if (alert.groupId != null) {
      this.groupsApi.get(alert.groupId).subscribe({
        next: (g) => this.linkedGroup.set(g),
        error: () => this.linkedGroup.set(null),
      });
      // Détail des membres : le pattern complet (code, sévérité, montant…).
      this.refreshGroupMembers(alert.groupId);
    }
    this.evals.get(alert.assessmentId).subscribe({
      next: (a) => {
        this.assessment.set(a);
        // Chaîne tx → compte → client : nom du titulaire pour le panneau de décision.
        this.txs
          .getById(a.transactionId)
          .pipe(
            switchMap((tx) => {
              this.linkedTx.set(tx);
              return this.accounts.getById(tx.accountId);
            }),
            switchMap((acc) => {
              this.txAccount.set(acc);
              return this.customers.getById(acc.customerId);
            }),
          )
          .subscribe({
            next: (cust) => this.txCustomer.set(cust),
            error: () => {}, // compte ou client injoignable : la transaction reste affichée
          });
      },
      error: (err) => this.error.set(err?.error?.message ?? `Évaluation ${err?.status ?? '?'} injoignable`),
    });
  }

  /**
   * DÉCISION DE L'ANALYSTE — impacte la transaction du Bank Simulator :
   *   valider  -> tx COMPLETED (validée avec surveillance) + case clôturé « RISQUE_ACCEPTE »
   *   annuler  -> tx CANCELLED (aucun solde ne bouge) + case clôturé « FRAUDE_CONFIRMEE »
   * L'alerte est clôturée par la chaîne légale OPEN→ACK→IN_PROGRESS→RESOLVED,
   * puis le case (s'il existe) est fermé avec l'arbitrage correspondant —
   * sinon l'alerte resterait « en attente d'un arbitrage » à jamais.
   */
  decide(approve: boolean): void {
    const al = this.selected();
    const tx = this.linkedTx();
    if (!al || !tx) return;
    this.deciding.set(true);
    this.error.set(null);
    this.txs.changeStatus(tx.transactionId, approve ? 'COMPLETED' : 'CANCELLED').subscribe({
      next: (updatedTx) => {
        this.linkedTx.set(updatedTx);
        this.closeAlertChain(al).pipe(
          switchMap((closedAlert) =>
            this.closeCaseChain(this.linkedCase(), approve ? ARBITRAGE.approve : ARBITRAGE.reject)
              .pipe(switchMap((closedCase) => of({ closedAlert, closedCase }))),
          ),
        ).subscribe({
          next: ({ closedAlert, closedCase }) => {
            if (closedCase) this.linkedCase.set(closedCase);
            this.selected.set(closedAlert);
            this.alerts.set(this.alerts().map((a) => (a.alertId === closedAlert.alertId ? closedAlert : a)));
            const arbitrage = approve ? ARBITRAGE.approve : ARBITRAGE.reject;
            const casePart = closedCase ? ` · DOSSIER #${closedCase.caseId} → ${frLabel(closedCase.status)} (${arbitrage})` : '';
            const txPart = approve
              ? `TX #${updatedTx.transactionId} validée`
              : `TX #${updatedTx.transactionId} annulée · aucun débit appliqué`;
            this.flash.set(`${txPart} · ${closedAlert.alertCode} → ${frLabel(closedAlert.status)}${casePart}`);
            setTimeout(() => this.flash.set(null), 4500);
            this.deciding.set(false);
          },
          error: (err) => {
            this.error.set(err?.error?.message ?? `Clôture refusée (${err?.status ?? '?'})`);
            this.deciding.set(false);
          },
        });
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? `Décision refusée (${err?.status ?? '?'})`);
        this.deciding.set(false);
      },
    });
  }

  /**
   * DÉCISION DE GROUPE — ouvre la modal de confirmation.
   *   ACCEPT — pattern légitime : alertes membres FALSE_POSITIVE.
   *   REFUSE — fraude confirmée : TOUTES les transactions du groupe sont
   *            annulées + remboursées (saisie « ANNULER » exigée).
   */
  openGroupDecision(decision: 'ACCEPT' | 'REFUSE'): void {
    if (!this.linkedGroup() || this.linkedGroup()?.status !== 'OPEN') return;
    this.groupDecision.set(decision);
    this.decisionReason.set('');
    this.confirmText.set('');
    this.error.set(null);
  }

  closeGroupDecision(): void {
    if (this.decidingGroup()) return;
    this.groupDecision.set(null);
  }

  /** Bouton de confirmation désactivé tant que « ANNULER » n'est pas tapé (REFUSE). */
  confirmDisabled(): boolean {
    if (this.groupDecision() !== 'REFUSE') return false;
    return this.confirmText().trim().toUpperCase() !== 'ANNULER';
  }

  /**
   * Envoie la décision au backend (orchestration annulation + remboursement
   * côté alert-case-service) puis recharge la file et le groupe.
   */
  submitGroupDecision(): void {
    const g = this.linkedGroup();
    const decision = this.groupDecision();
    if (!g || !decision || this.confirmDisabled()) return;
    this.decidingGroup.set(true);
    this.error.set(null);
    this.groupsApi.decide(g.groupId, decision, this.decisionReason() || undefined).subscribe({
      next: (updated) => {
        this.decidingGroup.set(false);
        this.groupDecision.set(null);
        this.linkedGroup.set(updated);
        // Statuts membres changés (FALSE_POSITIVE / RESOLVED) : on recharge
        // le détail + l'alerte courante pour que les badges ne soient pas périmés.
        this.refreshGroupMembers(updated.groupId);
        const cur = this.selected();
        if (cur) {
          this.alertsApi.get(cur.alertId).subscribe({ next: (a) => this.selected.set(a), error: () => {} });
        }
        this.load(this.page());
        const msg =
          decision === 'REFUSE'
            ? `GROUPE #${g.groupId} REFUSÉ · ${g.relatedCount} transaction(s) annulée(s) + remboursée(s) · cumul ${g.totalAmount} TND`
            : `GROUPE #${g.groupId} ACCEPTÉ · pattern légitime · ${g.relatedCount} alerte(s) close(s) (FALSE_POSITIVE)`;
        this.flash.set(msg);
        setTimeout(() => this.flash.set(null), 6000);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? `Décision refusée (${err?.status ?? '?'}) — rejouable`);
        this.decidingGroup.set(false);
      },
    });
  }

  /**
   * Clôture le case lié avec l'arbitrage, en enchaînant les transitions
   * légales du graphe backend (OPEN→UNDER_REVIEW→CLOSED, CONFIRMED→CLOSED).
   * Sans case ouvert : no-op (of(null)).
   */
  private closeCaseChain(caseFile: CaseFile | null, arbitrage: string): Observable<CaseFile | null> {
    if (!caseFile) return of(null);
    const steps: CaseStatus[] = [];
    if (caseFile.status === 'OPEN') steps.push('UNDER_REVIEW');
    if (caseFile.status === 'OPEN' || caseFile.status === 'UNDER_REVIEW' || caseFile.status === 'CONFIRMED') {
      steps.push('CLOSED');
    }
    if (!steps.length) return of(caseFile); // déjà terminal
    return defer(() =>
      steps.reduce(
        (acc, status, i) =>
          acc.pipe(
            switchMap((cur) =>
              this.casesApi.changeStatus(cur.caseId, status, i === steps.length - 1 ? arbitrage : undefined),
            ),
          ),
        of(caseFile),
      ),
    );
  }

  /** CLÔTURER l'alerte seule (sans décision de transaction). */
  closeAlert(): void {
    const al = this.selected();
    if (!al) return;
    this.error.set(null);
    this.closeAlertChain(al).subscribe({
      next: (closed) => {
        this.selected.set(closed);
        this.alerts.set(this.alerts().map((a) => (a.alertId === closed.alertId ? closed : a)));
        this.flash.set(`${closed.alertCode} → ${closed.status}`);
        setTimeout(() => this.flash.set(null), 3000);
      },
      error: (err) => this.error.set(err?.error?.message ?? `Clôture refusée (${err?.status ?? '?'})`),
    });
  }

  /** Enchaîne les transitions légales jusqu'à RESOLVED (une par requête). */
  private closeAlertChain(al: Alert): Observable<Alert> {
    const start = LADDER.indexOf(al.status);
    if (start < 0 || start >= LADDER.length - 1) {
      return of(al); // état terminal déjà : rien à faire
    }
    const steps = LADDER.slice(start + 1);
    return defer(() =>
      steps.reduce(
        (acc, status) => acc.pipe(switchMap(() => this.alertsApi.changeStatus(al.alertId, status))),
        of(al),
      ),
    );
  }

  /** Navigation dans la file : ← / → au clavier et bouton « alerte suivante ». */
  step(dir: 1 | -1): void {
    const list = this.alerts();
    if (!list.length) return;
    const cur = this.selected();
    const idx = cur ? list.findIndex((a) => a.alertId === cur.alertId) : -1;
    const next = list[(idx + dir + list.length) % list.length];
    if (next && next.alertId !== cur?.alertId) this.select(next);
  }

  /** Raccourci clavier de la file d'attente (ignore les champs de saisie). */
  @HostListener('document:keydown.arrowleft')
  onLeft(): void {
    if (this.canWrite) this.step(-1);
  }

  @HostListener('document:keydown.arrowright')
  onRight(): void {
    if (this.canWrite) this.step(1);
  }

  /** Nombre d'alertes restantes à traiter dans la file courante. */
  remaining(): number {
    return this.alerts().filter((a) => a.status === 'OPEN').length;
  }

  analyze(): void {
    const current = this.selected();
    if (!current) return;
    this.analyzing.set(true);
    this.error.set(null);
    this.ai.analyze(current.alertId).subscribe({
      next: (result) => {
        this.analysis.set(result);
        this.analyzing.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? `Analyse ${err?.status ?? '?'} — service IA injoignable`);
        this.analyzing.set(false);
      },
    });
  }

  /** POST /api/alerts/{id}/cases — ouvre un case MEDIUM et l'affiche en direct. */
  openCase(priority: CasePriority = 'MEDIUM'): void {
    const current = this.selected();
    if (!current) return;
    this.error.set(null);
    this.casesApi.open(current.alertId, priority).subscribe({
      next: (opened) => {
        this.linkedCase.set(opened);
        this.flash.set(`DOSSIER #${opened.caseId} ouvert (${frLabel(opened.priority)}) sur ${current.alertCode}`);
        setTimeout(() => this.flash.set(null), 3500);
      },
      error: (err) => this.error.set(err?.error?.message ?? `Erreur ${err?.status ?? '?'} — dossier possiblement déjà ouvert`),
    });
  }

  /** Contribution d'un facteur en points du score 0–100. */
  points(f: Factor): number {
    return f.weight * f.factorScore * 100;
  }

  /** Raison nettoyée : le backend y append « (+0.050) », redondant avec le chip pts. */
  cleanReason(f: Factor): string {
    return f.reason.replace(/\s*\(\s*[+-]?\d*\.?\d+\s*\)\s*$/, '').trim();
  }

  /** Facteurs qui font monter le score (> 0 pt) — le cœur de l'explication. */
  readonly risingFactors = computed(() =>
    (this.assessment()?.factors ?? []).filter((f) => this.points(f) > 0),
  );

  /** Facteurs neutres (0 pt : règles neutralisées ou non déclenchées). */
  readonly neutralFactors = computed(() =>
    (this.assessment()?.factors ?? []).filter((f) => this.points(f) <= 0),
  );

  /** Points cumulés apportés par les facteurs déclenchés. */
  readonly totalPoints = computed(() =>
    this.risingFactors().reduce((sum, f) => sum + this.points(f), 0),
  );

  /**
   * Membres du groupe rattachés à l'alerte courante, dans l'ordre
   * chronologique, en marquant celle que l'on regarde. Sert à la bannière
   * « N alertes apparentées » : l'analyste voit d'un coup le pattern.
   * Repli tant que le détail (groupMemberAlerts) n'est pas encore chargé.
   */
  groupMembers(): { alertId: number; isCurrent: boolean }[] {
    const g = this.linkedGroup();
    const cur = this.selected()?.alertId;
    if (!g) return [];
    return g.memberAlertIds.map((id) => ({ alertId: id, isCurrent: id === cur }));
  }

  /**
   * Vue détaillée des membres (chargée via GET .../alerts) : chaque ligne
   * porte sévérité, statut, montant et horodatage — c'est le dossier
   * complet du pattern pour trancher sans décider à l'aveugle.
   */
  membersDetailed(): Alert[] {
    return this.groupMemberAlerts();
  }

  /** Charge (ou recharge) le détail des alertes membres d'un groupe. */
  refreshGroupMembers(groupId: number): void {
    this.groupsApi.members(groupId).subscribe({
      next: (page) => this.groupMemberAlerts.set(page.content),
      error: () => this.groupMemberAlerts.set([]),
    });
  }

  /** Clic sur un membre : ouvre SON détail (sévérité, facteurs, tx…). */
  gotoMember(alertId: number): void {
    if (alertId === this.selected()?.alertId) return;
    this.alertsApi.get(alertId).subscribe({
      next: (a) => this.select(a),
      error: (err) => this.error.set(err?.error?.message ?? `Alerte #${alertId} injoignable`),
    });
  }

  private load(index: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.alertsApi.page(index, 12, this.filter() ?? undefined).subscribe({
      next: (data: Page<Alert>) => {
        this.alerts.set(data.content);
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
