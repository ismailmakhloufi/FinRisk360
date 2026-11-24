# Tester le relais SMS sur iPhone

Cette première étape vérifie l'envoi depuis la SIM de l'iPhone avec un seul
numéro de test autorisé. L'espace bancaire et le rapport de nuit continuent à
fonctionner normalement. La file contient uniquement des messages de test
mis en file par l'opérateur.

Le texte standard est « Atlas Bank - Une operation inhabituelle a ete
detectee. Consultez vos operations dans votre espace Atlas Bank habituel. ».
Les essais ajoutent le préfixe « TEST - ». Le texte vient de FinRisk360,
via le champ `message` de la réponse au raccourci.

## Préparer FinRisk360

Appliquer `postgres/21-sms-relay-test.sql` à la base destinée au test. Démarrer
`alert-case-service` avec ces variables d'environnement.

| Variable | Valeur |
| --- | --- |
| `SMS_RELAY_TEST_ENABLED` | `true` pour la session de test |
| `SMS_RELAY_TEST_RECIPIENT` | Un mobile de test autorisé, au format `+216XXXXXXXX` |
| `SMS_RELAY_OPERATOR_TOKEN` | Secret aléatoire Base64URL d'au moins 32 caractères, à conserver sur le poste |
| `SMS_RELAY_TOKEN` | Un autre secret aléatoire Base64URL d'au moins 32 caractères, destiné au raccourci |

La route opérateur reste accessible sur le poste. Pour joindre le service depuis
l'iPhone, utiliser une adresse HTTPS de confiance sur un réseau privé et rendre
seules les routes `POST /internal/sms-relay/claim` et
`POST /internal/sms-relay/{messageId}/submitted` accessibles depuis ce réseau.
La passerelle FinRisk360 publique et l'adresse iPhone `localhost` ne desservent
pas ces routes internes.

Sur le PC Windows, `scripts/sms-relay-proxy.py` filtre ces routes sur
`127.0.0.1:8765`. Une fois le script démarré, lancer dans un terminal
administrateur `tailscale serve --bg http://127.0.0.1:8765`. Si Tailscale
demande d'activer Serve, terminer cette étape dans le navigateur, puis vérifier
l'adresse privée avec `tailscale serve status`. Utiliser cette adresse à la
place de `https://ADRESSE-PRIVEE` ci-dessous.

## Créer le raccourci

1. Dans **Raccourcis**, créer un raccourci nommé **Relais SMS FinRisk360**.
2. Ajouter **Obtenir le contenu de l'URL**. Faire un `POST` vers
   `https://ADRESSE-PRIVEE/internal/sms-relay/claim` avec l'en-tête
   `Authorization: Bearer <jeton du relais>`. Une réponse vide indique que la
   file est vide. Dans ce cas, arrêter le raccourci.
3. Récupérer les champs `recipient`, `message` et `messageId` du JSON. Dans
   **Envoyer un message**, passer le texte au champ message et le destinataire au
   champ correspondant. Désactiver l'option **Afficher lors de l'exécution**
   si elle est disponible. Sur un iPhone double SIM, vérifier la ligne d'envoi.
4. Après l'action d'envoi, ajouter un `POST` vers
   `https://ADRESSE-PRIVEE/internal/sms-relay/{messageId}/submitted` avec le même
   en-tête. Cette confirmation signifie que le raccourci est arrivé à cette
   étape. La réception se vérifie sur le téléphone destinataire.

Mettre un message en file depuis le poste en générant un UUID par essai. Si
l'appel opérateur est répété pour le même essai, conserver le même UUID.

```powershell
$id = [guid]::NewGuid()
Invoke-RestMethod http://127.0.0.1:8085/internal/sms-relay/test -Method Post -Headers @{
    Authorization = "Bearer $env:SMS_RELAY_OPERATOR_TOKEN"
    'Idempotency-Key' = $id.ToString()
}
```

Faire un premier essai manuellement depuis Raccourcis. Puis ouvrir
l'automatisation personnelle **E-mail** et remplacer son action d'envoi à un
destinataire fixe par **Exécuter le raccourci** → **Relais SMS FinRisk360**.
Garder le filtre sur un expéditeur connu et un objet réservé, ainsi que
l'exécution immédiate. Le courriel sert de signal sans numéro ni détail
bancaire. Mettre un seul SMS en file et envoyer un nouvel e-mail de test pendant
que l'écran est verrouillé. Vérifier que la messagerie du destinataire indique
un SMS provenant de la SIM souhaitée. L'action iOS peut utiliser iMessage pour
certains destinataires.

Un message déjà récupéré reste `CLAIMED` si le raccourci s'arrête en cours de
route. Le serveur laisse cette demande en l'état pour éviter un double SMS si
l'envoi a réussi avant l'interruption. Vérifier la réception avant de créer un
nouvel essai.

Documentation officielle [Automatisations Raccourcis](https://support.apple.com/guide/shortcuts/add-automations-apdfbdbd7123/ios).

## Pilote de détection automatique

Appliquer aussi `postgres/22-sms-fraud-pilot.sql` avant d'activer le pilote.
Le service examine les nouvelles alertes associées à des transactions en
attente lorsque le numéro enregistré du titulaire du compte émetteur
correspond au numéro de test. Un avis `FRAUDE_SUSPECTE` ajoute un seul SMS de
test par transaction. Le pilote fonctionne jour et nuit et ne change aucun solde ni statut.

Activer la validation en deux étapes du compte Google qui figure dans le
filtre de l'automatisation iPhone. Dans Google, créer un mot de passe
d'application pour FinRisk360, puis le saisir sur le PC Windows dans
`scripts/configure-sms-gmail.ps1`. Ce script l'enregistre sous forme
chiffrée pour l'utilisateur Windows dans
`%LOCALAPPDATA%\FinRisk360\sms-wake-gmail.xml`. Le garder hors du dépôt et
de la conversation. [Guide Google](https://support.google.com/accounts/answer/185833?hl=fr)

Le processus `alert-case-service` reçoit `SMS_WAKE_GMAIL_USER` et
`SMS_WAKE_GMAIL_APP_PASSWORD` depuis cette configuration locale. Activer
`SMS_FRAUD_PILOT_ENABLED=true` et définir `SMS_FRAUD_PILOT_START` sur
l'horodatage de démarrage du pilote avec décalage UTC. Par exemple,
`2026-11-23T22:00:00+01:00`. Le courriel est envoyé à ce même compte
avec l'objet `Test finrisk360`. Les échecs SMTP sont réessayés au plus cinq
fois au total. L'iPhone confirme l'étape d'envoi dans FinRisk360 une fois le
raccourci exécuté.
