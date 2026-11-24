# `alert-case-service` — Guide complet

> **Jalon M4** — Alertes et investigation · port **8085** · Spring Boot 4.1.1
> Critère du jalon : *alerte → case → commentaire → clôture tracée* ✅
> 50 tests verts · commit de démarrage `fb32dc4` → documentation `⚠ voir git log`

## Sommaire

1. [A quoi sert ce service](#1-a-quoi-sert-ce-service)
2. [Démarrage rapide](#2-démarrage-rapide)
3. [Arborescence commentée](#3-arborescence-commentée)
4. [Les couches](#4-les-couches)
5. [Les entités et leur correspondance SQL](#5-les-entités-et-leur-correspondance-sql)
6. [Les 12 endpoints](#6-les-12-endpoints)
7. [Les règles métier](#7-les-règles-métier)
8. [La trace d'audit](#8-la-trace-daudit)
9. [Le format d'erreur](#9-le-format-derreur)
10. [Swagger](#10-swagger)
11. [Les 50 tests](#11-les-50-tests)
12. [Propriété des tables et amendement M4](#12-propriété-des-tables-et-amendement-m4)
13. [Dépannage](#13-dépannage)
14. [Historique M4](#14-historique-m4)
15. [Ce qui reste (M5+)](#15-ce-qui-reste-m5)
16. [Points de soutenance](#16-points-de-soutenance)
17. [Agent nocturne — rapport du matin](#17-agent-nocturne--rapport-du-matin)

---

## 1. A quoi sert ce service

Il donne vie au **volet investigation** de FinRisk360 : le moteur de risque
(`risk-service`, M3) produit un verdict — ici, on transforme ce verdict en
**alerte**, l'alerte en **dossier d'enquete (case)**, le dossier en
**commentaires d'analyste**, et tout cela laisse une **trace inaltérable**
dans `audit_logs` (c'est le critère exact du jalon M4).

```
evaluation (risk-service, lu PAR API)
   │  POST /api/alerts
   ▼
ALERT ──── transitions AlertStatus ────▶ RESOLVED / FALSE_POSITIVE
   │  POST /api/alerts/{id}/cases            (resolvedAt renseigne)
   ▼
CASE ───── transitions CaseStatus ────▶ CLOSED / FALSE_POSITIVE
   │  POST /api/cases/{id}/comments               (closedAt renseigne)
   ▼
COMMENTAIRES (NOTE / EVIDENCE / DECISION)

   ... chaque fleche ci-dessus ecrit UNE ligne dans audit_logs
```

Deux principes structurent tout le service :

- **Jamais de SQL chez les autres** : l'evaluation est lue via
  `GET /api/evaluations/{id}` chez risk-service (`RiskClient`) ;
- **Jamais de `SELECT users`** : `auth-service` n'existe pas encore (M5).
  Les `user_id` (X-User-Id) sont verifies par les **FK PostgreSQL**, dont la
  violation est traduite en **409** propre par le `GlobalExceptionHandler`.

## 2. Démarrage rapide

```powershell
# Prérequis : PostgreSQL (docker compose up -d) + risk-service démarré
#             (alert-case l'interroge PAR API pour verifier l'evaluation)

cd "backend\alert-case-service"
mvn spring-boot:run                       # http://localhost:8085

# vérifications
Invoke-RestMethod http://localhost:8085/actuator/health   # {"status":"UP"}
Start-Process http://localhost:8085/swagger-ui.html       # Swagger
mvn test                                                 # 50 tests
```

### Démonstration complète (smoke test effectué, résultats réels)

```powershell
# 1. une evaluation (n°2, 8500 TND DE, deja notee 72.00 HIGH) devient une alerte
#    severity absente -> derivee du verdict du moteur -> HIGH
curl.exe -X POST http://localhost:8085/api/alerts `
  -H "Content-Type: application/json" -H "X-User-Id: 2" `
  -d '{"assessmentId":2,"alertType":"HIGH_AMOUNT"}'
#    -> 201  {"alertCode":"ALT-2","severity":"HIGH","status":"OPEN", ...}

# 2. UNE SEULE alerte par evaluation
#    -> 409  "Une alerte existe deja pour cette evaluation : assessmentId = 2"

# 3. transitions : hors graphe refuse, grappe respecte
#    OPEN -> IN_PROGRESS  -> 409 "Transition d'alerte illegale : OPEN -> IN_PROGRESS"
#    OPEN -> ACKNOWLEDGED -> 200 ; ACKNOWLEDGED -> IN_PROGRESS -> 200

# 4. ouverture du dossier d'enquete (une seule fois)
curl.exe -X POST http://localhost:8085/api/alerts/1/cases `
  -H "Content-Type: application/json" -H "X-User-Id: 2" -d '{}'
#    -> 201  status=OPEN priority=MEDIUM assignedTo=2   (2e fois -> 409)

# 5. commentaire sans auteur -> 400, avec auteur -> 201 (EVIDENCE, NOTE)

# 6. cycle complet de la case
#    UNDER_REVIEW -> 200 ; CONFIRMED + investigationResult -> 200 ; CLOSED -> 200
#    + closedAt renseigne ; commentaire apres cloture -> 409
#    Alerte : RESOLVED -> 200 (+ resolvedAt) ; re-ouverture -> 409

# 7. LA TRACE
curl.exe "http://localhost:8085/api/audit?entityType=CASE&entityId=1"
#    -> 6 entrees chronologiques :
#       CASE_OPENED -> OPEN | COMMENT_ADDED -> EVIDENCE
#       CASE_STATUS_CHANGED OPEN -> UNDER_REVIEW | COMMENT_ADDED -> NOTE
#       CASE_STATUS_CHANGED UNDER_REVIEW -> CONFIRMED
#       CASE_STATUS_CHANGED CONFIRMED -> CLOSED
curl.exe "http://localhost:8085/api/audit?entityType=ALERT&entityId=1"
#    -> 4 entrees : ALERT_CREATED + 3x ALERT_STATUS_CHANGED (jusqu'a RESOLVED)
```

BDD verifiee apres smoke : `alerts.status=RESOLVED` + `resolved_at`,
`cases.status=CLOSED` + `closed_at` + `investigation_result=CONFIRMED_FRAUD`,
2 commentaires, **10 lignes `audit_logs`**.

## 3. Arborescence commentée

```
alert-case-service/src/main/java/tn/finrisk/alertcaseservice/
├── AlertCaseServiceApplication.java    point d'entree (port 8085)
├── client/
│   ├── RiskClient.java                 GET /api/evaluations/{id} chez risk-service
│   ├── dto/EvaluationView.java         vue reduite (score, level, decision)
│   └── exceptions/
│       ├── RemoteResourceNotFoundException.java   404 distant -> 404
│       └── DownstreamUnavailableException.java    amont tombe  -> 503
├── domain/
│   ├── Alert.java                      -> alerts
│   ├── Case.java                       -> cases
│   ├── CaseComment.java                -> case_comments
│   ├── AuditLog.java                   -> audit_logs
│   └── enums/
│       ├── AlertType / Severity        domaines SQL (CHECK)
│       ├── AlertStatus.java            machine a etats + canMoveTo/isTerminal
│       ├── CaseStatus.java             machine a etats + canMoveTo/isTerminal
│       ├── CasePriority / CommentType  domaines SQL (CHECK)
├── repository/                         4 repositories Spring Data (pages compris)
├── service/
│   ├── AlertService.java               creation (avec RiskClient) + transitions
│   ├── CaseService.java                ouverture, transitions, commentaires
│   └── AuditService.java               ecriture MANDATORY + lectures du journal
└── web/
    ├── AlertController.java            6 operations (alertes + ouverture case)
    ├── CaseController.java             5 operations (cases + commentaires)
    ├── AuditController.java            1 operation (journal / trace par entite)
    ├── dto/                            9 records (requests + responses)
    └── error/
        ├── AlertNotFoundException / CaseNotFoundException   404 metier
        ├── ConflictException / BadRequestException          409 / 400 metier
        ├── ErrorResponse.java          meme forme que M1/M2/M3
        └── GlobalExceptionHandler.java 404/409/503/400/500
```

## 4. Les couches

```
Controller  →  Service  →  Repository → PostgreSQL
 (HTTP/DTO)    (regles      (requetes)
                metier)
                  │
                  ├── RiskClient ──────▶ risk-service (8084)  [lecture seule]
                  └── AuditService ────▶ audit_logs  (MÊME transaction)
```

Points d'implementation a retenir :

- **`AuditService.record` est `@Transactional(propagation = MANDATORY)`** :
  il ne peut s'appeler que *dans* la transaction de la mutation qu'il trace.
  Appele hors transaction, Spring leve une exception plutot que d'ecrire une
  trace orpheline — la trace et le changement partagent le **meme commit** ;
- **`RiskClient`** utilise `RestClient.builder().baseUrl(...)` (fabrique
  statique, aucun bean `RestClient.Builder` en Boot 4.1), et traduit
  404 distant → `RemoteResourceNotFoundException`, coupure → `DownstreamUnavailableException` ;
- les transitions vivent **dans les enums** (`AlertStatus.canMoveTo`) :
  pur, testable sans base ni HTTP, un seul endroit a verifier.

## 5. Les entités et leur correspondance SQL

### `Alert` → `alerts`

| Colonne SQL | Champ Java | Notes |
|---|---|---|
| `alert_id` | `alertId` | IDENTITY |
| `assessment_id` | `assessmentId` | **FK logique** → `risk_assessments` (stockee en `Long`, lue PAR API) ; FK SQL `ON DELETE CASCADE` |
| `alert_code` | `alertCode` | `"ALT-" + assessmentId`, `UNIQUE` (filet de securite) |
| `alert_type` | `AlertType` | CHECK : HIGH_AMOUNT, VELOCITY, GEOGRAPHIC, BEHAVIOR, DUPLICATE, OTHER |
| `severity` | `Severity` | CHECK : LOW..CRITICAL ; **derivee du verdict** si absente en requete |
| `status` | `AlertStatus` | DEFAULT OPEN, machine a etats |
| `assigned_to` | `assignedTo` | FK `users` SET NULL (jamais SELECT users) |
| `created_at` | `createdAt` | `@PrePersist` UTC |
| `resolved_at` | `resolvedAt` | renseigne uniquement en etat terminal |

### `Case` → `cases`

| Colonne SQL | Champ Java | Notes |
|---|---|---|
| `case_id` | `caseId` | IDENTITY |
| `alert_id` | `alertId` | **`UNIQUE`** (uq_cases_alert) = 1 case par alerte ; FK RESTRICT |
| `assigned_to` | `assignedTo` | FK `users` SET NULL ; defaut = porteur du X-User-Id |
| `status` | `CaseStatus` | DEFAULT OPEN, machine a etats |
| `priority` | `CasePriority` | DEFAULT MEDIUM |
| `investigation_result` | `investigationResult` | VARCHAR(50), arbitrage pose au changement d'etat |
| `opened_at` / `closed_at` | `openedAt` / `closedAt` | `closedAt` = etat terminal |

### `CaseComment` → `case_comments`
`caseId` (FK CASCADE), **`userId` NOT NULL (FK RESTRICT)**, `content` (TEXT),
`commentType` (NOTE/EVIDENCE/DECISION), `createdAt`.

### `AuditLog` → `audit_logs`
`userId` (FK SET NULL), `action` (VARCHAR 50), `entityType`, `entityId`,
`oldValue`, `newValue`, `createdAt`, `ipAddress` (header `X-Forwarded-For`).

## 6. Les 12 endpoints

| # | Méthode | Chemin | Corps / headers | Succès |
|---|---|---|---|---|
| 1 | **POST** | `/api/alerts` | `{assessmentId, alertType, severity?, assignedTo?}` | **201** + `Location` |
| 2 | GET | `/api/alerts` | `?status=&page=&size=` | 200 `Page` |
| 3 | GET | `/api/alerts/{id}` | — | 200 |
| 4 | **PATCH** | `/api/alerts/{id}/status` | `{status}` | 200 |
| 5 | **POST** | `/api/alerts/{alertId}/cases` | `{priority?, assignedTo?}` | **201** + `Location` |
| 6 | GET | `/api/alerts/{alertId}/case` | — | 200 (404 si aucune) |
| 7 | GET | `/api/cases` | `?status=&page=&size=` | 200 `Page` |
| 8 | GET | `/api/cases/{id}` | — | 200 |
| 9 | **PATCH** | `/api/cases/{id}/status` | `{status, investigationResult?}` | 200 |
| 10 | **POST** | `/api/cases/{id}/comments` | `{content, commentType?}` + **`X-User-Id` requis** | **201** + `Location` |
| 11 | GET | `/api/cases/{id}/comments` | — | 200 (fil chronologique) |
| 12 | GET | `/api/audit` | `entityType+entityId` **ou** `userId`/`action` + page | 200 liste ou `Page` |

Headers optionnels sur toutes les mutations : `X-User-Id` (auteur de la
trace), `X-Forwarded-For` (ip de la trace). M5 branchera le vrai token —
seul le commentaire **exige** deja l'auteur (`user_id NOT NULL`).

## 7. Les règles métier

| # | Règle | Violation |
|---|---|---|
| 1 | L'evaluation doit **exister chez risk-service** avant d'alerter | 404 distant / 503 si arrete |
| 2 | **Une seule alerte par evaluation** (`ALT-{assessmentId}`) | 409 |
| 3 | **Une seule case par alerte** (`uq_cases_alert`) | 409 |
| 4 | Transitions **uniquement dans le graphe** des enums | 409 avec libelle `AVANT -> APRES` |
| 5 | Etat terminal d'alerte (RESOLVED/FALSE_POSITIVE) → `resolvedAt` + **fin de vie** (pas de case, plus aucune transition) | 409 |
| 6 | Etat terminal de case (CLOSED/FALSE_POSITIVE) → `closedAt` + **plus aucun commentaire ni mutation** | 409 |
| 7 | Pas d'ouverture de case sur une alerte deja terminee | 409 |
| 8 | Commentaire **obligatoirement attribue** (`X-User-Id`) | 400 |
| 9 | Severite absente → **derivee du verdict** du moteur (HIGH→HIGH…) | — |
| 10 | FK `users` violee (user inconnu) ou unicite percutee | 409 (`DataIntegrityViolationException`) |

### Les deux graphes

```
AlertStatus :  OPEN → ACKNOWLEDGED → IN_PROGRESS → RESOLVED
                              │            │
                              └────────────┴──→ FALSE_POSITIVE

CaseStatus  :  OPEN → UNDER_REVIEW → CONFIRMED → CLOSED
                            │
                            ├──→ CLOSED
                            └──→ FALSE_POSITIVE
```

## 8. La trace d'audit

Chaque mutation appelle `AuditService.record(...)` **dans sa transaction** :

| action | entite | quand |
|---|---|---|
| `ALERT_CREATED` | `ALERT` | creation (oldValue null, newValue OPEN) |
| `ALERT_STATUS_CHANGED` | `ALERT` | chaque transition (old→new) |
| `CASE_OPENED` | `CASE` | ouverture du dossier |
| `CASE_STATUS_CHANGED` | `CASE` | chaque transition |
| `COMMENT_ADDED` | **`CASE`** | commentaire — rattache a la CASE (pas a l'entite COMMENT) pour que la vue « trace du dossier » montre le fil **au contexte de l'enquete** |

**Deux lectures** :
- `GET /api/audit?entityType=CASE&entityId=7` → la trace **complete et
  chronologique** d'un dossier (« que s'est-il passe ici ? ») — la reponse
  de la soutenance ;
- `GET /api/audit` → le journal general paginé (filtres `userId` OU `action`).

**Amendement M4** : `audit_logs` est listee chez `auth-service` dans
`architecture-services.md` §2, mais le critère M4 exige la trace *des que
maintenant*. alert-case-service l'ecrit pour **ses** entites (ALERT, CASE) ;
la reconciliation avec les events `AUTH` de M5 se fera en M5 — un seul
ecrivain par domaine reste la regle M0.

## 9. Le format d'erreur

Même enveloppe que M1/M2/M3 :

```json
{ "status": 409, "error": "Conflict",
  "message": "Transition d'alerte illegale : OPEN -> RESOLVED",
  "path": "/api/alerts/1/status", "errors": [] }
```

| Code | Exceptions | Cas typique |
|---|---|---|
| `400` | `BadRequestException` + validations `@Valid` | commentaire sans `X-User-Id`, `assessmentId` manquant |
| `404` | `AlertNotFoundException`, `CaseNotFoundException`, `RemoteResourceNotFoundException` | ressource inconnue ICI ou CHEZ risk-service |
| `409` | `ConflictException`, `DataIntegrityViolationException` | transition illegale, doublon, etat terminal, FK users / unicite |
| `503` | `DownstreamUnavailableException` | risk-service arrete |
| `500` | `Exception` (dernier recours) | bug — jamais de stacktrace au client |

## 10. Swagger

- UI : `http://localhost:8085/swagger-ui.html`
- OpenAPI : `http://localhost:8085/v3/api-docs`
- 3 groupes `@Tag` : **Alertes**, **Cases**, **Audit** — chaque operation
  documente ses codes de retour (201/400/404/409/503).

## 11. Les 50 tests

```
Tests run: 50, Failures: 0, Errors: 0, Skipped: 0  →  BUILD SUCCESS
```

| Classe | Nb | Ce qu'elle couvre |
|---|---|---|
| `AlertStatusTest` | 4 | graphe d'alerte PUR : transitions legales, etats terminaux |
| `CaseStatusTest` | 4 | graphe de case PUR : les 3 issues de UNDER_REVIEW, terminaux |
| `AlertServiceTest` | 9 | unites (mocks) : ALT-*, severite derivee/explicite, 404/503/409, transitions + resolvedAt + trace |
| `CaseServiceTest` | 14 | unites (mocks) : ouverture/defauts, graphe, closedAt, 400 sans auteur, 409 commentaires/cloture |
| `AlertApiTest` | 13 | couche Web (`@WebMvcTest`) : 201+Location, 400 validation, 404/409/503, pages, audit |
| `AlertCaseIntegrationTest` | 5 | **golden sur `finrisk_test`** (voir ci-dessous) + 404 distants, 409 graphe, relectures |
| `AlertCaseServiceApplicationTests` | 1 | contexte + `ddl-auto: validate` |

### Le scénario golden (integration)

`scenarioComplet_alerteCaseCommentaireClotureTracee` — risk-service **mocké**,
persistance **reelle**, purge par test :

1. `POST /api/alerts` (assessment 900014 seedé) → 201, `ALT-900014`, severity
   HIGH **derivee**, Location exacte ;
2. doublon → 409 ; transition hors graphe → 409 libelle exact ;
3. ACK → IN_PROGRESS ; ouverture case → 201 (assignedTo = X-User-Id) ;
   2e ouverture → 409 ;
4. commentaire sans X-User-Id → 400 ; EVIDENCE → 201 ; UNDER_REVIEW ;
   NOTE → 201 ; CONFIRMED + `CONFIRMED_FRAUD` ; CLOSED → `closedAt` ;
5. commentaire ni mutation apres cloture → 409 ; alerte RESOLVED →
   `resolvedAt` ; re-ouverture → 409 ;
6. fil chronologique (2 commentaires) ;
7. **trace CASE = 6 entrees exactement** (ordre + old/new values) ;
   **trace ALERT = 4** ; journal = 10 ;
8. etats finaux + compteurs verifies **en SQL** (`closed_at`, `resolved_at`,
   `COUNT(audit_logs)=10`, `COUNT(commentaires)=2`).

## 12. Propriété des tables et amendement M4

- `alerts`, `cases`, `case_comments` : **alert-case-service** (jamais ecrites
  ailleurs) ;
- `audit_logs` : **amendement M4** — ecrite par alert-case-service pour ses
  propres actions (voir §8) ; `auth-service` (M5) ajoutera les events `AUTH` ;
- `risk_assessments` : **lu PAR API uniquement** (`RiskClient`) ;
- `users` : **jamais lue** — FK verifiees par PostgreSQL. Seed de demo en
  `02-schema.sql` §8 (`admin`, `analyste`, hash placeholder remplacables en M5).

## 13. Dépannage

| Symptôme | Cause | Correction |
|---|---|---|
| `503 Service distant indisponible : risk-service` | risk-service (8084) arrete | demarrer risk-service (requis pour creer une alerte) |
| `404 evaluation introuvable chez le service distant` | assessment inexistant | verifier `GET :8084/api/evaluations/{id}` |
| `409 Violation de contrainte d'integrite` | `X-User-Id` pointant un user absent de `users` | utiliser `1` (admin) ou `2` (analyste) — seed §8 |
| `400 Header X-User-Id requis` | commentaire sans auteur | ajouter `-H "X-User-Id: 2"` |
| `409 Une alerte existe deja` | regle 1 alerte/evaluation | utiliser l'alerte existante (`GET /api/alerts`) |
| `ddl-auto: validate` en echec au demarrage | schema descale | reappliquer `postgres/02-schema.sql` (§4 tables) |
| port 8085 occupe | jar precedent | `Get-NetTCPConnection -LocalPort 8085 -State Listen` puis stop |

## 14. Historique M4

| Étape | Contenu | Commit |
|---|---|---|
| 1 | squelette Boot 4.1.1, `application.yml` 8085 (`clients.risk`), profil test → `finrisk_test`, contexte vert | `fb32dc4` |
| 2-3 | 6 enums (graphes dans les status) + 4 entités, `validate` vert | `23e0ce0` |
| 4-5 | 4 repositories (pages) + `RiskClient` + exceptions distantes + `EvaluationView` | `8699965` |
| 6-7 | `AlertService`/`CaseService`/`AuditService` (MANDATORY), 9 DTOs, 3 contrôleurs, `GlobalExceptionHandler` (404/409/503) | `729e34b` |
| 8 | **50 tests** (unites, WebMvc, integration golden) | `fe93759` |
| 9 | seed `users` demo §8 + **smoke live : 17/17 OK**, traces 6+4=10, BDD verifiee | `33491a7` |
| 10 | ce guide, README, jalons M4 | *(cette documentation)* |

## 15. Ce qui reste (M5+)

- **M5 auth-service** : remplacer les hash placeholder, endpoint `/api/auth/*`,
  JWT + `X-User-Id` verifie **cote metier** (aujourd'hui : FK PostgreSQL),
  roles `ADMIN/ANALYST/MANAGER` (l'assignation d'alerte et les changements de
  statut deviennent `ANALYST+`), envoi automatique des alertes depuis
  risk-service (ou orchestrateur) ;
- `ipAddress` deja lu depuis `X-Forwarded-For` : le **gateway** (M7) le posera
  reellement ;
- filtres avances (severite, plage de dates, affectation) si l'ecran Angular
  le demande (M7) ;
- evenementiel (Kafka) : hors périmètre M0, re-étudié si le volume l'exige.

## 16. Points de soutenance

1. **Ou est la logique de workflow ?** Dans les enums (`AlertStatus.canMoveTo`,
   `CaseStatus.canMoveTo`) — pur, teste unitairement, un seul endroit ;
   les services ne font que l'appliquer et tracer.
2. **Pourquoi `MANDATORY` sur AuditService ?** Pour qu'on ne puisse pas
   tracer hors transaction : la trace et la mutation sont le meme commit —
   une clôture sans trace est structurellement impossible.
3. **Pourquoi ne pas lire `users` ?** Regle M0 : ownership strict. Tant que
   M5 n'existe pas, PostgreSQL verifie les FK et le handler traduit en 409 —
   aucune table d'autrui n'est lue.
4. **D'ou vient la severite de l'alerte ?** De l'analyste s'il la donne, du
   verdict du moteur sinon — pas de double saisie.
5. **Le critère M4, preuve en main** : `GET /api/audit?entityType=CASE&entityId=X`
   rend les 6 entrees du dossier, cloture comprise (smoke + integration).

## 17. Agent nocturne — rapport du matin

> Migration `postgres/12-add-night-agent.sql` (tables `night_agent_decisions`)
> · job `@Scheduled` horaire · page Angular **Rapport de nuit** (`/rapport-nuit`).

**Probleme** : l'analyste n'est pas la la nuit — la file EN ATTENTE grossit
sans que rien ne soit fait. **Solution** : un agent balaie la file de
17h00 a 09h00 (fenetre `night-agent.start-hour/end-hour`) et tranche selon
un **design HYBRIDE** : le verdict du moteur ET l'avis de l'IA doivent
s'entendre pour que l'agent ose valider tout seul.

| Moteur | + avis IA | Action |
|---|---|---|
| CRITICAL (>= 85) | quel qu'il soit | **LAISSE** — reserve a l'analyste |
| LOW / MEDIUM | NORMAL | **VALIDE** (PENDING -> COMPLETED, alerte cloturee) |
| LOW / MEDIUM | INCERTAIN | **LAISSE** (l'IA hesite) |
| LOW / MEDIUM | FRAUDE_SUSPECTE | **LAISSE** (l'IA signale, meme sur un LOW) |
| HIGH | NORMAL | **LAISSE** (divergence : revue humaine prioritaire) |
| HIGH | INCERTAIN / FRAUDE | **LAISSE** |
| quel qu'il soit | IA injoignable | **LAISSE** (sans deuxieme avis, pas d'ecriture) |

**Aucune annulation automatique** : l'agent ne fait que VALIDATED ou
LEFT_PENDING (contrainte `ck_night_agent_action`). Tout est trace :
`night_agent_decisions` (rapport du matin, lisible via `GET
/api/night-agent/decisions` + page `/rapport-nuit`) et `audit_logs`
(`NIGHT_AGENT_VALIDATED` / `NIGHT_AGENT_DEFERRED`).

**Endpoints** (route gateway `/api/night-agent/**` : lecture `*`,
ecriture `ANALYST/ADMIN` pour le declencheur manuel) :

| # | Methode | Chemin | Rôle |
|---|---|---|---|
| 13 | GET | `/api/night-agent/decisions` | rapport pagine (recents d'abord) |
| 14 | GET | `/api/night-agent/status` | activation, fenetre, `inWindowNow`, derniere execution |
| 15 | POST | `/api/night-agent/run` | declencheur manuel (ignore la fenetre : action explicite) |

**Une ligne par transaction, et un acces direct** : une tx deja presente
dans `night_agent_decisions` n'est pas re-jugee (sinon chaque tick
horaire remplit le rapport du meme « LAISSE » — 5 doublons par tx en
demo avant correctif). Chaque ligne porte en plus le lien
**« OUVRIR L'ALERTE → »** (`/alertes?alert={id}`) : la ligne dit
« laissee a l'analyste », encore faut-il pouvoir ouvrir l'alerte pour
la traiter.

**Semantique du balayage** : la file est parcourue **page par page** (100)
et `night-agent.max-transactions` (50) est un budget de transactions
**JUGEES** (evaluation + alerte -> matrice) — une tx sans alerte est
sautee **sans consommer le budget** (sinon, une file remplie de txs sans
alerte bloquerait a jamais l'acces a celles qui en ont). Garde-fou dur :
scan plafonne a 10x le budget. Une erreur de service distant ne tue pas
le balayage : la tx est sautee, la suivante est examinee.

**Configuration** (`application.yml`) :

```yaml
night-agent:
  enabled: true        # false = tick planifie coupe (le POST /run reste ouvert)
  start-hour: 17       # fenetre 17h00 -> 09h00 (heure locale)
  end-hour: 9
  max-transactions: 50 # budget jugees / balayage (scan dur : x10)
```

**Tests** : `NightAgentDecisionRuleTest` (10, la matrice est PUR),
`NightAgentServiceTest` (9, budget + pagination + fenetre + panne + pas
de re-emission), `NightAgentWorkerTest` (5, effets de bord),
`NightAgentApiTest` (4, WebMvc), `NightAgentIntegrationTest` (3,
persistance reelle + GRANT de la migration 12 sur `finrisk_test`).
