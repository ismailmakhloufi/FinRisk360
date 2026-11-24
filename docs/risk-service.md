# `risk-service` — Guide complet

> **Jalon M3** — le cœur du projet : le moteur qui transforme une transaction
> en score 0-100, en niveau de risque et en décision, avec à chaque fois la
> **liste des règles qui ont joué et pourquoi**.

---

## Sommaire

1. [A quoi sert ce service](#1-a-quoi-sert-ce-service)
2. [Démarrage rapide](#2-démarrage-rapide)
3. [Arborescence commentée](#3-arborescence-commentée)
4. [Les couches et le pipeline du moteur](#4-les-couches-et-le-pipeline-du-moteur)
5. [Les entités et leur correspondance SQL](#5-les-entités-et-leur-correspondance-sql)
6. [Les 5 endpoints REST](#6-les-5-endpoints-rest)
7. [Les règles métier : le moteur en 4 étages](#7-les-règles-métier--le-moteur-en-4-étages)
8. [Le format d'erreur REST](#8-le-format-derreur-rest)
9. [Décisions d'architecture du moteur](#9-décisions-darchitecture-du-moteur)
10. [Swagger](#10-swagger)
11. [Les 49 tests](#11-les-49-tests)
12. [Propriété des tables](#12-propriété-des-tables)
13. [Dépannage](#13-dépannage)
14. [Historique M3](#14-historique-m3)
15. [Ce qui reste (M4+)](#15-ce-qui-reste-m4)
16. [Points de soutenance](#16-points-de-soutenance)

---

## 1. A quoi sert ce service

À chaque évaluation d'une transaction, il répond à trois questions :

| Question | Réponse stockée dans `risk_assessments` |
|---|---|
| **Quel score ?** | `risk_score` de 0.00 à 100.00 |
| **Quel niveau ?** | `risk_level` : LOW / MEDIUM / HIGH / CRITICAL |
| **Que faire ?** | `decision` : APPROVED / MONITORING / PENDING_REVIEW / HOLD |

Et surtout, dans `risk_factors` : **pourquoi** — chaque règle qui a joué ou
qui n'a pas pu jouer (neutralisée) est écrite avec une phrase lisible :

```
Montant 9000.00 TND vs seuil 5000.00 (>) : zone pleine (+0.2500)
Regle neutralisee : Pas d'antecedent : P95 inconnu
```

**Ce que ce service ne fait PAS (M3)** : il ne change pas le statut de la
transaction, il ne crée pas d'alerte, il n'appelle aucun LLM. C'est un
calculateur pur : on lui donne `transactionId`, il rend un verdict. L'orchestration
(transaction → évaluation → alerte) arrive en **M7**.

---

## 2. Démarrage rapide

```powershell
# Prérequis : PostgreSQL (docker compose up -d) + customer/account/transaction
#             services démarrés (le risk-service les interroge PAR API)

cd "backend\risk-service"
mvn spring-boot:run

# vérifications
Invoke-RestMethod http://localhost:8084/actuator/health     # {"status":"UP"}
Start-Process http://localhost:8084/swagger-ui.html         # Swagger
mvn test                                                    # 49 tests
```

### Démonstration complète (effectuée, résultats réels)

```powershell
# 1. évaluer une transaction normale -> 0.00 APPROVED
curl.exe -X POST http://localhost:8084/api/evaluations `
  -H "Content-Type: application/json" -d '{"transactionId":4}'

# 2. évaluer une transaction atypique -> 72.00 PENDING_REVIEW
#    (montant 8500 + pays DE + P95 dépassé + cumul 24h + pays éloigné,
#     5 signaux -> bonus x1,20 : 65 x 1,20 = 78)
curl.exe -X POST http://localhost:8084/api/evaluations `
  -H "Content-Type: application/json" -d '{"transactionId":5}'

# 3. consulter le verdict d'une transaction
curl.exe http://localhost:8084/api/evaluations/transaction/5

# 4. le catalogue : 12 règles, somme des poids = 1.35
curl.exe http://localhost:8084/api/rules

# 5. modifier une règle SANS redemarrer, puis réévaluer
curl.exe -X PUT http://localhost:8084/api/rules/1 `
  -H "Content-Type: application/json" -d '{"thresholdValue":1000}'
```

---

## 3. Arborescence commentée

```
risk-service/src/main/java/tn/finrisk/riskservice/
├── RiskServiceApplication.java        point d'entree Spring Boot
├── client/                            les SEULES passerelles vers les autres services
│   ├── TransactionClient.java         GET /api/transactions/{id} + liste 90j
│   ├── AccountClient.java             GET /api/accounts/{id} + comptes du client
│   ├── MerchantClient.java            GET /api/merchants/{id} (age du marchand)
│   ├── dto/                           TransactionView, AccountView, MerchantView
│   ├── DownstreamUnavailableException   503 : l'amont est tombe
│   └── RemoteResourceNotFoundException  404 : la ressource n'existe pas la-bas
├── domain/                            les 4 entites JPA (tables risque)
│   ├── RiskAssessment.java            un verdict par evaluation (append-only)
│   ├── RiskFactor.java                une ligne par regle jouee/explicable
│   ├── RiskProfile.java               baseline du client (vue materialisee)
│   ├── RiskRule.java                  le catalogue configurable
│   └── enums/                         RiskLevel, RiskCategory, Severity,
│                                      Decision, ComparisonOperator (+converter)
├── repository/                        4 repositories Spring Data
├── engine/                            LE MOTEUR — pur, sans I/O
│   ├── TransactionFeatures.java       ce que les regles lisent (records)
│   ├── FeatureEngine.java             ① historique -> features
│   ├── RuleEngine.java                ② features -> facteurs 0/0.5/1
│   ├── ScoringEngine.java             ③ facteurs -> score 0-100
│   ├── DecisionEngine.java            ④ score -> niveau + decision
│   ├── RuleEvaluation.java            resultat d'une regle (+ reasons)
│   └── NoEnabledRulesException        500 si aucune regle activee
├── service/
│   └── EvaluationService.java         orchestration + persistance + profil
└── web/
    ├── EvaluationController.java      POST/GET /api/evaluations
    ├── RuleController.java            GET/PUT /api/rules
    ├── dto/                           requetes et reponses (records)
    └── error/                         ErrorResponse + GlobalExceptionHandler
```

---

## 4. Les couches et le pipeline du moteur

Une seule requête `POST /api/evaluations {transactionId}` déclenche :

```
 ┌─ 1. DONNEES (jamais de SQL chez les autres, uniquement leurs API) ────────┐
 │ TransactionClient.getById(transactionId)          -> la transaction      │
 │ AccountClient.getById(accountId)                  -> customerId          │
 │ AccountClient.listByCustomer(customerId)          -> TOUS ses comptes    │
 │ TransactionClient.listByAccountSince(compte, -90j) -> baseline 90j       │
 │ MerchantClient.getById(merchantId)                -> age du marchand     │
 └──────────────────────────────────────────────────────────────────────────┘
                                  │
 ┌─ 2. LE MOTEUR (4 etages purs) ───────────────────────────────────────────┐
 │ FeatureEngine   transaction + historique  -> TransactionFeatures         │
 │ RuleEngine      features + 11 regles      -> RuleEvaluation[]           │
 │ ScoringEngine   contributions             -> score x100, bonus x1,20     │
 │ DecisionEngine  score + bandes (yml)       -> (niveau, decision)         │
 └──────────────────────────────────────────────────────────────────────────┘
                                  │
 ┌─ 3. PERSISTANCE (une transaction SQL) ───────────────────────────────────┐
 │ risk_assessments : le verdict (append-only, engine_version='v1')         │
 │ risk_factors      : les facteurs joues + les neutralises (explicabilite) │
 │ risk_profiles     : la baseline recalculee + feature_snapshot JSONB      │
 └──────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Les entités et leur correspondance SQL

### `RiskAssessment` → `risk_assessments`

| Colonne Java | Type Java | Type SQL |
|---|---|---|
| `assessmentId` | `Long` | `BIGSERIAL PRIMARY KEY` |
| `transactionId` | `Long` | `BIGINT NOT NULL` (FK transactions) |
| `riskScore` | `BigDecimal` | `DECIMAL(5,2)` — CHECK 0..100 |
| `riskLevel` | `RiskLevel` (`@Enumerated STRING`) | `VARCHAR(20)` + CHECK |
| `decision` | `Decision` (`@Enumerated STRING`) | `VARCHAR(20)` + CHECK |
| `assessedAt` | `OffsetDateTime` | `TIMESTAMPTZ DEFAULT now()` |
| `engineVersion` | `String` | `VARCHAR(20)` — `'v1'` |

### `RiskFactor` → `risk_factors`

Le journal explicatif : `factorScore` (0 / 0.5 / 1), `weight`, et surtout
`reason` (`TEXT`) — la phrase lue par l'analyste. `ruleId` pointe vers
`risk_rules` (`ON DELETE SET NULL` : supprimer une règle ne casse pas l'historique).

### `RiskRule` → `risk_rules`

Le catalogue. Champs clés : `ruleCode` (`RULE-001`…), `comparisonOperator`
(stocké en symbole `>`/`<=`/`=` via `ComparisonOperatorConverter`), `thresholdValue`,
`weight` (part maximale du score), `enabled`.

### `RiskProfile` → `risk_profiles`

Vue materialisée du client : moyenne, médiane, max, P95, P99, transactions/jour,
pays et heure habituels, + `feature_snapshot` (`JSONB`, mappé
`@JdbcTypeCode(SqlTypes.JSON)`) qui gèle toutes les features de la dernière
évaluation — carburant de l'explication IA en **M6**.

---

## 6. Les 5 endpoints REST

| # | Méthode + URL | Corps / params | Réponse |
|---|---|---|---|
| 1 | `POST /api/evaluations` | `{"transactionId": 42}` | `201` + `Location: /api/evaluations/{id}` + verdict complet |
| 2 | `GET /api/evaluations/{id}` | — | `200` verdict + facteurs · `404` |
| 3 | `GET /api/evaluations/transaction/{transactionId}` | — | `200` dernière évaluation · `404` si jamais évaluée |
| 4 | `GET /api/rules` | — | `200` les 12 règles du catalogue |
| 5 | `PUT /api/rules/{id}` | `{"enabled":false}` / `{"weight":0.30}` / `{"thresholdValue":1000}` | `200` règle mise à jour · `400` poids hors [0;1] · `404` |

Mise à jour **partielle** : seuls les champs fournis sont modifiés (d'où le nom
"PATCH-like" sur une PUT — choix assumé pour rester simple).

### Exemple de réponse (transaction frauduleuse réelle du smoke test)

```json
{
  "assessmentId": 2,
  "transactionId": 5,
  "riskScore": 72.00,
  "riskLevel": "HIGH",
  "decision": "PENDING_REVIEW",
  "assessedAt": "2026-10-05T15:28:04Z",
  "engineVersion": "v1",
  "factors": [
    { "ruleCode": "RULE-001", "factorScore": 1, "weight": 0.25,
      "reason": "Montant 8500.00 TND vs seuil 5000.00 (>) : zone pleine (+0.2500)" },
    { "ruleCode": "RULE-011", "factorScore": 1, "weight": 0.10,
      "reason": "Ecart entre deux pays differents = 0 min vs seuil 180.00 (<=) : zone pleine (+0.1000)" },
    { "ruleCode": "RULE-006", "factorScore": 0, "weight": 0.05,
      "reason": "Regle neutralisee : Ecart-type nul (transactions identiques) : ecart non mesurable" }
  ]
}
```

---

## 7. Les règles métier : le moteur en 4 étages

### ① Feature Engine — `FeatureEngine` (pur, testable seul)

Calcule pour une transaction donnée :

- **Baseline 90 jours** (`risk.stats-window-days`) sur **tous les comptes du
  client**, en **excluant la transaction évaluée** (une fraude ne pollue pas sa
  propre baseline) : moyenne, écart-type (sigma, population), médiane, P95/P99
  (nearest-rank), transactions/jour, pays et heure habituels ;
- **Fenêtres glissantes de 24h ancrées sur `transactionDate`** (pas sur le jour
  calendaire — sinon contournement à minuit) : vélocité, cumul TND, échecs ;
- **Couples suspects** : écart minimal (en minutes) entre transactions dans deux
  pays différents, et entre deux (marchand, montant) identiques ;
- **Heures converties en `risk.timezone`** (`Africa/Tunis`, UTC+1) : la règle
  00h-05h ne juge jamais sur l'heure UTC.

Si aucun antécédent : `stats = null` → mode dégradé (voir neutralisations).

### ② Rule Engine — notation **graduée**

Loi unique, la même pour toutes les règles numériques (→ prévisible, testable) :

| Opérateur (en base) | valeur ≤ seuil | ≤ 1,5 × seuil | au-delà |
|---|---|---|---|
| `>` ou `>=` | **0** | **0,5** | **1** |
| `<=` ou `<` | **1** (en deçà) | **0,5** (> seuil/1,5) | **0** (au-delà du seuil) |
| `=` (RULE-003 pays) | binaire 0 / 1 | | |

`contribution = weight × factor`. Exemple : montant 6000 vs seuil 5000 →
zone demi → `0.25 × 0.5 = 0.125` → 12,5 points.

**Neutralisations explicites** — une règle qu'on ne peut pas juger n'est jamais
ignorée en silence, elle est écrite avec sa raison :

| Cas | Règles neutralisées | Raison stockée |
|---|---|---|
| Pas d'antécédent (1ère tx) | 003, 006, 007, 008 | `Pas d'antecedent : …` |
| Devise ≠ TND | 001, 006, 007, 008 | `Devise EUR : seuils exprimes en TND` |
| Sigma = 0 (montants identiques) | 006 | `Ecart-type nul : ecart non mesurable` |
| Pas de marchand (virement) | 005 | `Pas de marchand sur cette transaction` |
| Code inconnu (règle ajoutée en base sans support moteur) | n'importe laquelle | `Code de regle inconnu du moteur` |

### ③ Scoring Engine

```
score = somme(contributions) × 100
      × 1,20  si ≥ 3 règles ont été déclenchées   (bonus « l'ensemble fait le dossier »)
      plafond 100, arrondi à 2 décimales
```

### ④ Decision Engine — bandes configurables

| Score | Decision | RiskLevel |
|---|---|---|
| 0 – 29 | `APPROVED` | `LOW` |
| 30 – 59 | `MONITORING` | `MEDIUM` |
| 60 – 84 | `PENDING_REVIEW` | `HIGH` |
| 85 – 100 | `HOLD` | `CRITICAL` |

Les bornes (29/59/84) vivent dans `application.yml` sous `risk.decisions.*` :
ajustables sans recompilation.

### Les 12 règles seedées (`postgres/02-schema.sql` §7, somme des poids = 1.35)

| Code | Signe | Poids | Seuil | Catégorie |
|---|---|---|---|---|
| RULE-001 | montant > 5000 TND | **0.25** | 5000 | AMOUNT |
| RULE-002 | vélocité > 5 tx / 24h | 0.15 | 5 | VELOCITY |
| RULE-003 | pays ≠ pays habituel | 0.10 | `=` | GEOGRAPHIC |
| RULE-004 | heure locale ≤ 5h | 0.05 | 5 | TIME |
| RULE-005 | marchand créé ≤ 30 jours | 0.05 | 30 | BEHAVIOR |
| RULE-006 | dérive > 2,5σ | 0.05 | 2.5 | BEHAVIOR |
| RULE-007 | montant > P95 du client | 0.10 | dynamique | AMOUNT |
| RULE-008 | cumul 24h > 3× habituel | **0.10** | 3 | HISTORY |
| RULE-009 | doublon (marchand, montant) ≤ 10 min | 0.05 | 10 | BEHAVIOR |
| RULE-010 | ≥ 3 échecs / 24h | 0.05 | 3 | VELOCITY |
| RULE-011 | 2 pays ≤ 180 min d'écart | 0.10 | 180 | GEOGRAPHIC |
| RULE-012 | structuration : > 3 petites tx / 24h | **0.30** | 3 | HISTORY |
| | | **Σ = 1.35** | | |

L'invariant `SUM(weight) WHERE enabled = 1.35` est **vérifié par un test**
(`catalogueDouzeReglesEtInvariantPoids`).

---

## 8. Le format d'erreur REST

Identique à M1/M2 (même `ErrorResponse`, même `GlobalExceptionHandler`) :

```json
{ "status": 404, "error": "Not Found",
  "message": "transaction introuvable chez le service distant : id = 99999",
  "path": "/api/evaluations", "errors": [] }
```

| Statut | Quand |
|---|---|
| `400` | `transactionId` manquant · JSON illisible · poids hors [0;1] |
| `404` | assessment/règle inconnu ICI, ou ressource inconnue **chez l'amont** (404 retransmis) |
| `500` | `NoEnabledRulesException` — seed absent ; **jamais** un score 0 silencieux |
| `503` | `DownstreamUnavailableException` — transaction/account-service injoignable (indisponibilité d'infrastructure, pas la faute du client) |

---

## 9. Décisions d'architecture du moteur

| Sujet | Choix | Pourquoi |
|---|---|---|
| Déclenchement | **L'appelant orchestre** : créer la tx (8083) puis `POST /api/evaluations` (8084) | le risk-service ne boucle pas sur lui-même ; la chaîne complète s'écrit en 2 appels (le bouton Angular les enchaînera en M7) |
| Lecture d'autrui | **Uniquement par API** (`TransactionClient`, `AccountClient`, `MerchantClient`) | règle de propriété M0 : aucun SQL sur les tables des autres |
| Historique | **Au niveau CLIENT** (tous ses comptes) | la vélocité et la baseline d'un client ne s'arrêtent pas à un compte |
| Baseline | **Recalculée à chaque évaluation** puis persistée dans `risk_profiles` | le doc dit « recalcule a chaque transaction » ; la table est une vue, pas une source |
| Historique des verdicts | `risk_assessments` **append-only** | chaque évaluation laisse une trace, `GET /transaction/{id}` rend la plus récente |
| Statut de transaction | **Immobile en M3** | M7 orchestrera transaction → évaluation → alerte |
| ML | **Aucun en M3** (règles déterministes) | M6 branchera l'IA sur `feature_snapshot` |
| Bandes de décision | Dans `application.yml`, pas en constantes | ajustables sans recompilation (démontré par test) |

**Limites assumées** (évolutions) :

- géo-granularité **pays uniquement** — Paris ↔ Lille est introuvable ; Tunis →
  Paris en 30 min, lui, est attrapé (RULE-011) → **M6** (villes via merchant) ;
- seuils de montant exprimés en **TND** : une tx en devise étrangère neutralise
  les règles 001/006/007/008 (raison écrite) → conversion de devise = évolution ;
- l'heure locale suppose `risk.timezone: Africa/Tunis` (configurable).

---

## 10. Swagger

- UI : `http://localhost:8084/swagger-ui.html`
- OpenAPI : `http://localhost:8084/v3/api-docs`
- Tags : **Evaluations** (POST/GET) et **Rules** (GET/PUT), chaque opération
  documente ses codes de retour (201/400/404/503/500).

---

## 11. Les 49 tests

```
mvn test
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
```

| Classe | Nb | Ce qu'elle couvre |
|---|---|---|
| `FeatureEngineTest` | 11 | baseline hors tx évaluée, fenêtre 24h glissante, percentiles nearest-rank, heure locale Tunis, couples pays/doublon, mode pays, devise |
| `RuleEngineTest` | 11 | les 3 zones de gradation (`>` et `<=`), `=` binaire, les 6 neutralisations, code inconnu, `NoEnabledRulesException`, RULE-009/011 |
| `ScoringEngineTest` | 5 | ×100, bonus ×1,20 à 3 règles, plafond 100, facteur 0 n'active pas le bonus |
| `DecisionEngineTest` | 5 | les 4 bornes exactes (29/30/59/60/84/85) + bandes personnalisées |
| `EvaluationApiTest` | 8 | `@WebMvcTest` : 201 + `Location`, 400 validation/JSON, 404, 503, GET/PUT rules, poids hors bornes |
| `EvaluationIntegrationTest` | 8 | **scénarios golden sur `finrisk_test`** (voir ci-dessous) + invariant + régles à chaud + 404/503 |
| `RiskServiceApplicationTests` | 1 | contexte + `ddl-auto: validate` |

### Les scénarios golden (scores calculés à la main, vérifiés en BDD)

| Scénario | Signaux | Score | Verdict |
|---|---|---|---|
| 1ʳᵉ transaction (aucun antécédent) | 5 neutralisations | **0.00** | LOW · APPROVED |
| Montant seul 8000 (001 pleine + 008 pleine) | 2 → pas de bonus | **30.00** | MEDIUM · MONITORING |
| 3 signaux faibles (001 demi + 003 + 005) | 0.275 × 1,20 | **33.00** | MEDIUM · MONITORING |
| Fraude (7 règles : montant, vélocité, pays, marchand récent, P95, cumul, écart pays) | 0.725 × 1,20 | **87.00** | CRITICAL · **HOLD** |

Le test `regleDesactivee_leScoreChangeSansRedemarrage` désactive RULE-001 par
`PUT`, réévalue (30.00 → 5.00) puis restaure : la démo « règles à chaud » est
intégrée à la suite.

**Règles des tests** : profil `test` → base `finrisk_test` (jamais `finrisk`) ;
les 3 clients distants sont **mockés** (aucun réseau) ; la persistance est réelle ;
`risk.timezone=UTC` dans les tests pour des scores déterministes à toute heure.

---

## 12. Propriété des tables

Le risk-service est le **seul responsable** de :
`risk_assessments`, `risk_factors`, `risk_rules`, `risk_profiles`.

- il **n'écrit jamais** dans `transactions` / `accounts` / `customers` (lecture
  exclusive par API) ;
- `risk_assessments.transaction_id` est une **clé étrangère logique** vers la
  table d'autrui : la ligne de transaction existe car transaction-service l'a
  créée, jamais le risk-service ;
- `risk_profiles` appartient au risk-service depuis l'**amendement M3**
  (voir [architecture-services.md](architecture-services.md) §2) — c'est la
  baseline d'entrée du Feature Engine, `customer-service` ne s'en servait pas.

---

## 13. Dépannage

| Symptôme | Cause | Solution |
|---|---|---|
| `503 Service distant indisponible : transaction-service` | 8083 arrêté | redémarrer transaction-service |
| `500 Aucune regle activee...` | `risk_rules` vide (seed absent) | réappliquer `postgres/02-schema.sql` §7 |
| Score toujours 0 alors que le montant est gros | devise ≠ TND (règles neutralisées) | lire `risk_factors.reason` — il l'explique |
| `Validation failed : risk_profiles.feature_snapshot` | schéma≠ entité | `ddl-auto: validate` a raison : resynchroniser le SQL |
| Le test d'intégration touche la base de dev | profil `test` manquant | `@ActiveProfiles("test")` + `src/test/resources/application-test.yml` |
| `mvn package` échoue « Unable to rename jar » | le jar est en cours d'exécution | arrêter le java du port concerné d'abord |

Voir aussi : les logs `app.log` / `app.err` du lancement, et
`http://localhost:8084/actuator/health`.

---

## 14. Historique M3

| Étape | Contenu | Commit |
|---|---|---|
| 1-2 | squelette Initializr (Boot 4.1.1), `application.yml` 8084 + clients, `application-test.yml` → `finrisk_test`, amendement `risk_profiles`, suppression du code mort customer-service | `3ba0566`, `93afbab` |
| 3-4 | les 4 entités + 6 enums + converter, `ddl-auto: validate` vert | `93afbab` |
| — | catalogue 6 → **11 règles**, Σ poids = 1.00 (SQL + 2 bases + doc) | `c6ace0d` |
| 5 | repositories + clients HTTP (Transaction/Account/Merchant) + DTOs ; filtre `?since=` côté transaction-service (30 tests) | `8fd92e8` |
| 6-8 | **le moteur** (Feature/Rule/Scoring/Decision), `EvaluationService`, contrôleurs, `GlobalExceptionHandler` | `d5cac32` |
| 9 | **49 tests** : unites moteur, WebMvc, intégration golden | `cb9ba75` |
| 10 | ce guide + README + jalons | (ce commit) |

**Smoke test live effectué** (4 services lancés) : transaction 42,50 TN →
`0.00 LOW APPROVED` (5 neutralisations écrites) ; transaction 8500 DE →
`72.00 HIGH PENDING_REVIEW` (7 facteurs) ; `PUT threshold 1000` → réévaluation
`33.00` avec `zone demi (+0.1250)` puis restauration ; BDD vérifiée
(`risk_profiles.feature_snapshot` JSONB rempli, `SUM(weight)=1.00`).

---

## 15. Ce qui reste (M4+)

- **M4** — alert-case-service : score ≥ seuil → `POST /api/alerts` (l'appelant
  décidera), cases, commentaires ;
- **M5** — auth (JWT/RBAC) + gateway : protéger `PUT /api/rules` (rôle ADMIN) ;
- **M6** — IA : lire `feature_snapshot` pour expliquer un verdict (« Explain
  Alert »), villes des marchands, conversion de devise ;
- **M7** — Angular : le bouton *Send Transaction* enchaînera
  `POST /api/transactions` → `POST /api/evaluations` → alerte, et affichera les
  facteurs du score ;
- **M8** — conteneurisation + CI.

---

## 16. Points de soutenance

1. **« Un indice ne prouve rien : leur ensemble fait le dossier »** → le bonus
   ×1,20 à partir de 3 signaux fait basculer un 60 en 72, un 71 en 85 (HOLD).
2. **Explicabilité par construction** : `risk_factors.reason` contient la phrase
   exacte ; les règles non applicables sont neutralisées **à voix haute**.
3. **Déterministe et testé** : scénarios golden dont les scores
   (0 / 35 / 33 / 100 / 66) sont calculés à la main dans les comments du test.
4. **Configurable à chaud** : démonstration `PUT /api/rules/{id}` en direct —
   seuil, poids, activation, le score bouge sans redémarrage.
5. **Propriété stricte** : zéro SQL sur les tables d'autrui, lecture par API
   uniquement ; l'invariant `SUM(weight)=1.35` est garanti par un test.
6. **Honnêteté des limites** : pays ≠ ville, seuils en TND — documenté comme
   évolution, pas dissimulé.
