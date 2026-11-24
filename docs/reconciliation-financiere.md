# Traitement d'un mouvement financier incertain

Cette procedure est une **procedure de diagnostic et d'escalade**, pas une
autorisation d'ajuster les soldes ou de debloquer automatiquement le dossier.
Les appels aux comptes et l'enregistrement de la transaction ne forment pas
une transaction distribuee atomique. Un timeout ou une erreur 5xx ne prouve
pas qu'un mouvement a echoue.

## 1. Isoler et documenter

Pour une transaction affichee avec `MOUVEMENT_INCERTAIN_RECONCILIATION_REQUISE`,
identifier l'ID, la cible demandee, l'heure, l'operateur et le contexte dans
un dossier d'incident. Interdire toute decision contraire, compensation,
annulation ou modification manuelle de solde en attendant l'analyse.
Ne pas supprimer le journal, les cles idempotentes, ni modifier le marqueur.

## 2. Collecter les faits en lecture seule

Avec un acces PostgreSQL en lecture seule, remplacer `42` par l'identifiant
numerique du dossier :

```sql
SELECT transaction_id, status, account_id, beneficiary_account_id, amount,
       transaction_type FROM transactions WHERE transaction_id = 42;
SELECT transaction_id, state, target_status, updated_at
  FROM financial_attempt_guard WHERE transaction_id = 42;
SELECT j.step, j.state, j.operation_id, j.account_id, j.amount, j.direction,
       j.created_at, j.updated_at,
       k.operation_id AS account_operation_id, k.account_id AS account_key_account,
       k.amount AS account_key_amount, k.direction AS account_key_direction,
       k.created_at AS account_key_created_at
  FROM financial_operation_journal j
  LEFT JOIN balance_operation_keys k ON k.operation_id = j.operation_id
 WHERE j.transaction_id = 42 ORDER BY j.created_at, j.step;
```

Verifier les traces des services et, si necessaire, les mouvements aupres du
systeme comptable. Une cle de compte presente avec des donnees concordantes
atteste que la cle et le mouvement ont ete engages ensemble chez account-service.
`PREPARED` ne signifie pas que le mouvement est absent : sa reponse ou sa
confirmation peut avoir ete perdue. Une cle absente dans une lecture isolee
ne prouve pas qu'une requete encore en vol ne va pas etre engagee.
Le solde actuel seul ne prouve pas l'historique d'un mouvement.

## 3. Decision et suivi

Transmettre les elements au responsable habilite, faire verifier le bilan par
un second operateur et conserver les preuves, l'avis et l'heure. En cas de
compensation `COMPENSATE_SOURCE` ou `REVERSE_COMPENSATION`, meme confirmee,
le marqueur et le journal restent conserves : ne pas relancer ni liberer la
tentative manuellement. Escalader egalement si les donnees des cles divergent,
si des appels peuvent encore etre en vol ou si une preuve manque.

Il n'existe pas encore de commande de resolution validee et auditee pour
lever ce verrou. La resolution effective exige une procedure metier specifique,
une validation des soldes et un mecanisme atomique/audite a implementer et
tester. Ne jamais utiliser `DELETE` ou `UPDATE` sur les tables financieres
comme raccourci de reconciliation.
