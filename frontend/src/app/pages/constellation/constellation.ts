import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  ConstellationService,
  Constellation,
  ConstNode,
  ConstEdge,
} from '../../core/api/constellation.service';
import { FrPipe } from '../../core/pipes/fr';

interface PositionedNode extends ConstNode {
  x: number;
  y: number;
}

type GraphDepth = 'operation' | 'clues' | 'network';

const SVG_W = 900;
const CENTER_X = SVG_W / 2;
const FLOW_Y = 218;
const CONTEXT_Y = 390;
const FLOW_TYPES = new Set(['CONCERNE', 'OWNS', 'SENT', 'TO', 'BELONGS_TO']);

/** Points SVG d'un hexagone régulier (gabarit bénéficiaire). */
function hexPoints(cx: number, cy: number, r: number): string {
  return Array.from({ length: 6 }, (_, i) => {
    const a = (Math.PI / 3) * i - Math.PI / 6;
    return `${(cx + r * Math.cos(a)).toFixed(1)},${(cy + r * Math.sin(a)).toFixed(1)}`;
  }).join(' ');
}

@Component({
  selector: 'app-constellation',
  imports: [RouterModule, DatePipe, FrPipe],
  templateUrl: './constellation.html',
  styleUrl: './constellation.scss',
})
export class ConstellationPage implements OnInit {
  private readonly api = inject(ConstellationService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly data = signal<Constellation | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly alertId = signal<number | null>(null);

  readonly depth = signal<GraphDepth>('operation');
  readonly levels: { id: GraphDepth; label: string; description: string }[] = [
    { id: 'operation', label: 'Opération', description: "Suivre le trajet de l’opération depuis le compte émetteur." },
    { id: 'clues', label: 'Indices', description: "Examiner le pays, l’appareil, l’adresse IP et les autres comptes du client." },
    { id: 'network', label: 'Réseau', description: "Remonter aux comptes liés et aux transactions qui établissent chaque rapprochement." },
  ];
  readonly view = signal({ x: 0, y: 0, k: 1 });
  readonly hover = signal<PositionedNode | null>(null);
  readonly selected = signal<PositionedNode | null>(null);
  readonly selectedEdge = signal<ConstEdge | null>(null);

  readonly transaction = computed(() => this.data()?.nodes.find((n) => n.type === 'TRANSACTION'));
  readonly networkLinks = computed(() => (this.data()?.edges ?? []).filter((e) =>
    e.type === 'SHARED_BENEFICIARY' || e.type === 'SHARED_DEVICE' || e.type === 'SHARED_IP'));
  readonly linkedAccounts = computed(() => {
    const all = this.data()?.edges ?? [];
    return new Set([...this.networkLinks().map((e) => e.target),
      ...all.filter((e) => e.type === 'SHARED_WITH').map((e) => e.target)]);
  });
  readonly sender = computed(() => {
    const link = this.data()?.edges.find((e) => e.type === 'SENT' && e.target === this.transaction()?.id);
    return this.data()?.nodes.find((n) => n.id === link?.source);
  });
  readonly beneficiary = computed(() => {
    const link = this.data()?.edges.find((e) => e.type === 'TO' && e.source === this.transaction()?.id);
    return this.data()?.nodes.find((n) => n.id === link?.target);
  });
  readonly otherAccounts = computed(() => {
    const data = this.data();
    const owned = new Set(data?.edges.filter((e) => e.type === 'OWNS').map((e) => e.target));
    return (data?.nodes ?? []).filter((n) => n.type === 'ACCOUNT' && n.id !== this.sender()?.id
      && (owned.has(n.id) || !this.linkedAccounts().has(n.id)));
  });
  readonly networkAccounts = computed(() => {
    const owned = new Set(this.otherAccounts().map((n) => n.id));
    return [...this.linkedAccounts()].filter((id) => !owned.has(id) && id !== this.sender()?.id);
  });

  readonly networkTop = computed(() => 460 + Math.max(1, Math.ceil(this.otherAccounts().length / 2)) * 100);
  readonly graphHeight = computed(() => {
    if (this.depth() === 'operation') return 300;
    if (this.depth() === 'clues' || !this.networkAccounts().length) return this.networkTop() - 60;
    return this.networkTop() + Math.ceil(this.networkAccounts().length / 3) * 105 + 25;
  });
  readonly graphDescription = computed(() => this.levels.find((level) => level.id === this.depth())?.description);

  readonly nodesView = computed(() => {
    const data = this.data();
    if (!data) return [];
    const depth = this.depth();
    const otherIds = new Map(this.otherAccounts().map((n, i) => [n.id, i]));
    const linkedIds = new Map(this.networkAccounts().map((id, i) => [id, i]));
    return data.nodes.flatMap((node): PositionedNode[] => {
      const other = otherIds.get(node.id);
      const linked = linkedIds.get(node.id);
      if (linked !== undefined && node.id !== this.sender()?.id) {
        return depth === 'network'
          ? [{ ...node, x: 155 + (linked % 3) * 295, y: this.networkTop() + 60 + Math.floor(linked / 3) * 105 }]
          : [];
      }
      if (other !== undefined) {
        return depth === 'operation' ? []
          : [{ ...node, x: 91 + (other % 2) * 151, y: CONTEXT_Y + Math.floor(other / 2) * 100 }];
      }
      if (depth === 'operation' && ['DEVICE', 'IP', 'COUNTRY'].includes(node.type)) return [];
      const positions: Record<ConstNode['type'], [number, number]> = {
        ALERT: [460, 85], GROUP: [703, 85],
        CUSTOMER: [100, FLOW_Y], ACCOUNT: [280, FLOW_Y],
        TRANSACTION: [460, FLOW_Y], BENEFICIARY: [703, FLOW_Y],
        DEVICE: [400, CONTEXT_Y], IP: [590, CONTEXT_Y], COUNTRY: [780, CONTEXT_Y],
      };
      const [x, y] = positions[node.type];
      return [{ ...node, x, y }];
    });
  });
  private readonly visible = computed(() => new Map(this.nodesView().map((n) => [n.id, n])));
  readonly edgesView = computed(() => {
    const explicit = new Set(this.networkLinks().map((edge) => edge.target));
    return (this.data()?.edges ?? []).filter((edge) =>
      this.visible().has(edge.source) && this.visible().has(edge.target)
      && (this.depth() !== 'operation' || FLOW_TYPES.has(edge.type))
      && !(edge.type === 'SHARED_WITH' && explicit.has(edge.target)));
  });
  readonly selectedRelations = computed(() => {
    const id = this.selected()?.id;
    return id ? this.edgesView().filter((edge) => edge.source === id || edge.target === id) : [];
  });
  readonly highlighted = computed(() => {
    const edge = this.selectedEdge();
    const node = this.selected();
    if (!edge && !node) return null;
    const edges = edge ? [edge] : this.selectedRelations();
    const nodes = new Set(edges.flatMap((e) => [e.source, e.target]));
    if (node) nodes.add(node.id);
    return { nodes, edges: new Set(edges.map((e) => e.id)) };
  });

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('alertId'));
    if (!Number.isFinite(id) || id <= 0) {
      this.error.set("Identifiant d'alerte invalide.");
      this.loading.set(false);
      return;
    }
    this.alertId.set(id);
    void this.load(id);
  }

  private async load(id: number): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.data.set(await firstValueFrom(this.api.build(id)));
    } catch {
      this.error.set("Impossible de charger la Constellation.");
    } finally {
      this.loading.set(false);
    }
  }

  setDepth(depth: GraphDepth): void {
    this.depth.set(depth);
    this.onBackgroundDblClick();
  }

  openFocus(id: string): void {
    if (!this.pos(id)) this.setDepth('network');
    const node = this.pos(id);
    if (node) this.onNodeClick(node);
  }

  focusLabel(id: string): string {
    return this.data()?.nodes.find((node) => node.id === id)?.label ?? id;
  }

  openEvidence(id: string): void {
    const evidence = this.data()?.edges.find((edge) => edge.id === id);
    if (!evidence) return;
    if (!this.edgesView().some((edge) => edge.id === id)) this.setDepth('network');
    this.selected.set(null);
    this.selectedEdge.set(evidence);
  }

  shortLabel(label: string): string {
    return label.length > 22 ? label.slice(0, 21) + '…' : label;
  }

  onNodeEnter(node: PositionedNode): void {
    this.hover.set(node);
  }

  onNodeLeave(): void {
    this.hover.set(null);
  }

  /** Clic nœud : focus sur son voisinage + recentrage de la vue. */
  onNodeClick(node: PositionedNode): void {
    if (this.selected()?.id === node.id) {
      this.selected.set(null);
      this.view.set({ x: 0, y: 0, k: 1 });
    } else {
      this.selected.set(node);
      this.centerOn(node);
    }
    this.selectedEdge.set(null);
  }

  /** Recadre la vue pour mettre le nœud au centre (zoom légèrement agrandi). */
  private centerOn(node: PositionedNode): void {
    const k = 1.35;
    this.view.set({
      x: CENTER_X - node.x * k,
      y: this.graphHeight() / 2 - node.y * k,
      k,
    });
  }

  /** Clic arête : afficher sa preuve. */
  onEdgeClick(edge: ConstEdge): void {
    if (this.selectedEdge()?.id === edge.id) {
      this.selectedEdge.set(null);
    } else {
      this.selectedEdge.set(edge);
    }
    this.selected.set(null);
  }

  /** Double-clic fond : réinitialiser la vue. */
  onBackgroundDblClick(): void {
    this.selected.set(null);
    this.selectedEdge.set(null);
    this.view.set({ x: 0, y: 0, k: 1 });
  }

  /** Molette : zoom centré sur le pointeur. */
  onWheel(ev: WheelEvent): void {
    ev.preventDefault();
    const point = this.svgPoint(ev);
    this.zoom(ev.deltaY < 0 ? 1.12 : 1 / 1.12, point.x, point.y);
  }

  zoom(factor: number, px = CENTER_X, py = this.graphHeight() / 2): void {
    const v = this.view();
    const k = Math.min(3, Math.max(0.5, v.k * factor));
    this.view.set({ x: px - ((px - v.x) / v.k) * k, y: py - ((py - v.y) / v.k) * k, k });
  }

  private svgPoint(event: MouseEvent): DOMPoint {
    const svg = event.currentTarget as SVGSVGElement;
    const matrix = svg.getScreenCTM();
    const point = new DOMPoint(event.clientX, event.clientY);
    return matrix ? point.matrixTransform(matrix.inverse()) : point;
  }

  private panStart: { point: DOMPoint; x: number; y: number } | null = null;

  onPointerDown(event: PointerEvent): void {
    if (event.button !== 0 || (event.target as Element).closest('.node, .edge')) return;
    const { x, y } = this.view();
    this.panStart = { point: this.svgPoint(event), x, y };
    (event.currentTarget as Element).setPointerCapture(event.pointerId);
  }

  onPointerMove(event: PointerEvent): void {
    if (!this.panStart) return;
    const point = this.svgPoint(event);
    const start = this.panStart;
    this.view.set({ ...this.view(), x: start.x + point.x - start.point.x, y: start.y + point.y - start.point.y });
  }

  onPointerUp(): void {
    this.panStart = null;
  }

  // ------------------------------------------------------------------ rendu

  /** Transform SVG : pan + zoom. */
  transform(): string {
    const v = this.view();
    return `translate(${v.x} ${v.y}) scale(${v.k})`;
  }

  /** Points SVG hexagone (gabarit bénéficiaire) — exposé au template. */
  hexPoints(cx: number, cy: number, r: number): string {
    return hexPoints(cx, cy, r);
  }

  /** Rayon de la FORME (sans label) — sert à raccourcir les arêtes. */
  private shapeRadius(type: string): number {
    const map: Record<string, number> = {
      ALERT: 22,
      CUSTOMER: 16,
      ACCOUNT: 14,
      TRANSACTION: 13,
      BENEFICIARY: 14,
      DEVICE: 14,
      IP: 9,
      COUNTRY: 12,
      GROUP: 17,
    };
    return map[type] ?? 12;
  }

  /**
   * Coordonnées d'une arête raccourcies aux bords des formes — sans cela,
   * les flèches de direction seraient avalées par les nœuds.
   */
  edgeLine(e: ConstEdge): { x1: number; y1: number; x2: number; y2: number } {
    const s = this.pos(e.source);
    const t = this.pos(e.target);
    if (!s || !t) return { x1: 0, y1: 0, x2: 0, y2: 0 };
    const dx = t.x - s.x;
    const dy = t.y - s.y;
    const dist = Math.max(Math.hypot(dx, dy), 0.01);
    const r1 = this.shapeRadius(s.type) + 1;
    const r2 = this.shapeRadius(t.type) + 5;
    return {
      x1: s.x + (dx / dist) * r1,
      y1: s.y + (dy / dist) * r1,
      x2: t.x - (dx / dist) * r2,
      y2: t.y - (dy / dist) * r2,
    };
  }

  /** Flèche de direction sur les arêtes d'ARGENT (OWNS, SENT, TO). */
  arrowFor(type: string): string | null {
    if (type === 'OWNS') return 'url(#arrow-owns)';
    if (type === 'SENT') return 'url(#arrow-sent)';
    if (type === 'TO') return 'url(#arrow-to)';
    return null;
  }

  /** Arête-symétrique de preuve : label permanent (partage, appartenance). */
  isProofEdge(type: string): boolean {
    return FLOW_TYPES.has(type) && type !== 'OWNS' && type !== 'BELONGS_TO';
  }

  /** Bande du score autonome du LLM (pour la couleur du badge). */
  llmBand(score: number | null | undefined): string {
    if (score == null) return '';
    if (score <= 25) return 'low';
    if (score <= 50) return 'med';
    if (score <= 75) return 'high';
    return 'vhigh';
  }

  isDimmed(nodeId: string): boolean {
    const active = this.highlighted();
    return !!active && !active.nodes.has(nodeId);
  }

  isEdgeActive(edgeId: string): boolean {
    return this.highlighted()?.edges.has(edgeId) ?? false;
  }

  isEdgeDimmed(edgeId: string): boolean {
    const active = this.highlighted();
    return !!active && !active.edges.has(edgeId);
  }

  isFocusNode(nodeId: string): boolean {
    return this.data()?.narrative?.focusNodes?.includes(nodeId) ?? false;
  }

  pos(id: string): PositionedNode | undefined {
    return this.visible().get(id);
  }

  /** Retour à la liste des alertes. */
  back(): void {
    void this.router.navigate(['/alertes']);
  }

  /** Libellé français du type d'entité (légende + panneau). */
  typeLabel(t: string): string {
    const map: Record<string, string> = {
      ALERT: 'Alerte',
      CUSTOMER: 'Client',
      ACCOUNT: 'Compte',
      TRANSACTION: 'Transaction',
      BENEFICIARY: 'Bénéficiaire',
      DEVICE: 'Appareil',
      IP: 'Adresse IP',
      COUNTRY: 'Pays',
      GROUP: "Groupe d'alertes",
    };
    return map[t] ?? t;
  }

  /** Libellé français de la qualité des données du narratif. */
  qualityLabel(q: string): string {
    const map: Record<string, string> = {
      COMPLETE: 'COMPLÈTE',
      PARTIAL: 'PARTIELLE',
      DEGRADED: 'DÉGRADÉE',
    };
    return map[q] ?? q;
  }

  /** Formate une valeur de meta pour l'affichage détail. */
  metaEntries(n: PositionedNode): { key: string; value: string }[] {
    const labels: Record<string, string> = {
      customerId: 'Identifiant client',
      country: 'Pays',
      city: 'Ville',
      customerType: 'Type',
      email: 'Email',
      accountId: 'Identifiant compte',
      balance: 'Solde',
      status: 'Statut',
      accountNumber: 'Numéro',
      transactionId: 'Identifiant TX',
      date: 'Date',
      channel: 'Canal',
      transactionType: 'Type d’opération',
      riskScore: 'Score moteur',
      focal: 'Compte focal',
      occurrences: 'Occurrences',
      sharedAccounts: 'Comptes liés',
      sharedAccountCount: 'Nb comptes liés',
      sharedLinkCount: 'Entités partagées',
      sharedScore: 'Score de preuve',
      sharedSources: 'Sources du lien',
      evidenceCount: 'Nb preuves',
      beneficiaryAccountId: 'Compte bénéficiaire (id stable)',
      beneficiaryRIB: 'RIB bénéficiaire',
      geoAnomaly: 'Anomalie géo',
      merchantName: 'Marchand',
      groupId: 'Identifiant groupe',
      relatedCount: 'Nb alertes apparentées',
      totalAmount: 'Cumul du groupe',
      scopeType: 'Périmètre',
      scopeValue: 'Valeur périmètre',
      windowHours: 'Fenêtre (h)',
    };
    return Object.entries(n.meta ?? {})
      .filter(([k]) => labels[k])
      .map(([k, v]) => ({
        key: labels[k],
        value: Array.isArray(v) ? v.join(', ') : String(v),
      }));
  }
}
