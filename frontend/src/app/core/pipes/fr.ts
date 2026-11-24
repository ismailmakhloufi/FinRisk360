import { Pipe, PipeTransform } from '@angular/core';

/**
 * Dictionnaire enum/code → français pour l'affichage UI.
 * Clé absente ou valeur null → retourné tel quel (ou fallback).
 * Les attributs CSS (data-status/data-sev) gardent les valeurs brutes.
 */
const FR: Record<string, string> = {
  // ── statuts alertes ──
  OPEN: 'OUVERT',
  ACKNOWLEDGED: 'PRIS EN CHARGE',
  IN_PROGRESS: 'EN COURS',
  RESOLVED: 'RÉSOLU',
  FALSE_POSITIVE: 'FAUX POSITIF',
  ABSORBED: 'ABSORBÉ',
  // ── statuts transactions ──
  PENDING: 'EN ATTENTE',
  COMPLETED: 'VALIDÉE',
  HELD: 'BLOQUÉE',
  FAILED: 'ÉCHOUÉE',
  CANCELLED: 'ANNULÉE',
  // ── statuts dossiers / comptes / clients ──
  UNDER_REVIEW: 'EN EXAMEN',
  CONFIRMED: 'CONFIRMÉ',
  CLOSED: 'CLÔTURÉ',
  ACTIVE: 'ACTIF',
  INACTIVE: 'INACTIF',
  FROZEN: 'GELÉ',
  SUSPENDED: 'SUSPENDU',
  // ── statut groupe ──
  ACCEPTED: 'ACCEPTÉ',
  REFUSED: 'REFUSÉ',
  // ── sévérité / niveau de risque / priorité ──
  LOW: 'FAIBLE',
  MEDIUM: 'MOYEN',
  HIGH: 'ÉLEVÉ',
  CRITICAL: 'CRITIQUE',
  // ── décisions moteur / évaluations ──
  APPROVED: 'APPROUVÉ',
  MONITORING: 'SURVEILLANCE',
  PENDING_REVIEW: 'À EXAMINER',
  HOLD: 'BLOQUÉ',
  // ── verdict IA ──
  NORMAL: 'NORMAL',
  INCERTAIN: 'INCERTAIN',
  FRAUDE_SUSPECTE: 'FRAUDE SUSPECTE',
  // ── types de transaction ──
  TRANSFER: 'VIREMENT',
  PAYMENT: 'PAIEMENT',
  WITHDRAWAL: 'RETRAIT',
  DEPOSIT: 'VERSEMENT',
  // ── canaux (sigles français) ──
  POS: 'TPE',
  ATM: 'DAB',
  // ── types de compte / client ──
  CHECKING: 'COURANT',
  SAVINGS: 'ÉPARGNE',
  RETAIL: 'PARTICULIER',
  PREMIUM: 'PRIVILÉGIÉ',
  CORPORATE: 'ENTREPRISE',
  SME: 'PME',
  // ── types d'alerte ──
  HIGH_AMOUNT: 'MONTANT ÉLEVÉ',
  VELOCITY: 'VÉLOCITÉ',
  GEOGRAPHIC: 'GÉOGRAPHIQUE',
  BEHAVIOR: 'COMPORTEMENT',
  DUPLICATE: 'DOUBLON',
  OTHER: 'AUTRE',
  // ── divers enums ──
  NOTE: 'NOTE',
  EVIDENCE: 'PREUVE',
  ACCOUNT: 'COMPTE',
  CUSTOMER: 'CLIENT',
  CREDIT: 'CRÉDIT',
  DEBIT: 'DÉBIT',
  AMOUNT: 'MONTANT',
  TIME: 'TEMPS',
  role: 'rôle',
  status: 'statut',
  active: 'actif',
  true: 'vrai',
  false: 'faux',
  close: 'clôturé',
  perimetre: 'périmètre',
  legitime: 'légitime',
  // ── rôles ──
  ADMIN: 'ADMINISTRATEUR',
  MANAGER: 'RESPONSABLE',
  ANALYST: 'ANALYSTE',
  USER: 'UTILISATEUR',
  // ── actions d'audit ──
  ALERT_CREATED: 'ALERTE_CRÉÉE',
  ALERT_STATUS_CHANGED: 'STATUT_ALERTE_MODIFIÉ',
  ALERT_ABSORBED: 'ALERTE_ABSORBÉE',
  ALERTS_DELETED: 'ALERTES_SUPPRIMÉES',
  ALERT_GROUP_CREATED: 'GROUPE_CRÉÉ',
  ALERT_GROUP_ESCALATED: 'GROUPE_ESCALADÉ',
  ALERT_GROUP_ACCEPTED: 'GROUPE_ACCEPTÉ',
  ALERT_GROUP_REFUSED: 'GROUPE_REFUSÉ',
  ALERT_GROUP_TX_CANCELLED: 'TX_GROUPE_ANNULÉS',
  CASE_OPENED: 'DOSSIER_OUVERT',
  CASE_STATUS_CHANGED: 'STATUT_DOSSIER_MODIFIÉ',
  COMMENT_ADDED: 'COMMENTAIRE_AJOUTÉ',
  USER_CREATED: 'UTILISATEUR_CRÉÉ',
  USER_UPDATED: 'UTILISATEUR_MODIFIÉ',
  USER_PASSWORD_RESET: 'MOT_DE_PASSE_RÉINITIALISÉ',
  LOGIN_SUCCESS: 'CONNEXION_RÉUSSIE',
  LOGIN_FAILED: 'CONNEXION_ÉCHOUÉE',
  // ── entités d'audit ──
  ALERT: 'ALERTE',
  CASE: 'DOSSIER',
  ALERT_GROUP: 'GROUPE_ALERTES',
  TRANSACTION: 'TRANSACTION',
  // ── codes insights IA ──
  BENEFICIARY_NETWORK: 'RÉSEAU_BÉNÉFICIAIRE',
  DEVICE_SHARED: 'APPAREIL_PARTAGÉ',
  IP_SHARED: 'IP_PARTAGÉE',
  AMOUNT_DEVIATION: 'ÉCART_DE_MONTANT',
  // ── intentions du chat IA ──
  SUMMARY: 'RÉSUMÉ',
  FACTORS: 'FACTEURS',
  WHY_SCORE: 'POURQUOI_SCORE',
  SIGNALS: 'SIGNAUX',
  VERDICT: 'VERDICT',
  DECISION: 'DÉCISION',
  CONFIDENCE: 'CONFIANCE',
  ACTIONS: 'ACTIONS',
  BENEFICIAIRE: 'BÉNÉFICIAIRE',
  GEO: 'GÉO',
  DEVICE: 'APPAREIL',
  ACCOUNTS: 'COMPTES',
  CONSTELLATION: 'CONSTELLATION',
  EXPLAIN_ALERT: 'EXPLIQUER_ALERT',
  DATA_QUERY: 'REQUÊTE_DONNÉES',
  GREETING: 'SALUT',
  HELP: 'AIDE',
  FALLBACK: 'HORS_SUJET',
  // ── arêtes de constellation ──
  SHARED_WITH: 'PARTAGÉ_AVEC',
  BELONGS_TO: 'APPARTIENT_À',
  USED_DEVICE: 'APPAREIL_UTILISÉ',
  FROM_IP: 'DEPUIS_IP',
  IN_COUNTRY: 'DANS_PAYS',
  // ── résultats d'enquête / arbitrage ──
  RISQUE_ACCEPTE: 'RISQUE ACCEPTÉ',
  FRAUDE_CONFIRMEE: 'FRAUDE CONFIRMÉE',
  CONFIRMED_FRAUD: 'FRAUDE CONFIRMÉE',
  // ── agent nocturne (M4) ──
  VALIDATED: 'VALIDÉE (AGENT)',
  LEFT_PENDING: 'LAISSE EN ATTENTE',
  NIGHT_AGENT_VALIDATED: 'AGENT_NOCTURNE_VALIDÉ',
  NIGHT_AGENT_DEFERRED: 'AGENT_NOCTURNE_LAISSE',
};

/** Traduit une valeur enum/code pour l'affichage (usage TS : flashes, messages).
 * Valeurs composées (« role=ANALYST, active=true », phrases d'audit) : chaque
 * jeton séparé par espace/virgule/= est traduit individuellement, le reste est
 * conservé tel quel. */
export function frLabel(value: unknown, fallback = ''): string {
  if (value == null || value === '') return fallback;
  return String(value)
    .split(/([\s,;:=()]+)/)
    .map((token) => FR[token] ?? token)
    .join('');
}

/** Pipe d'affichage : valeur inconnue/null → fallback (défaut ''). */
@Pipe({ name: 'fr', standalone: true })
export class FrPipe implements PipeTransform {
  transform(value: unknown, fallback: string = ''): string {
    return frLabel(value, fallback);
  }
}
