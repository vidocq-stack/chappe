# Chappe — Registre des bugs

Format : un bug par section, datée, avec id court, symptôme, repro minimal,
hypothèse de cause, statut.

---

## CHAPPE-001 — Réponses tronquées sur gros fichiers (SO_SNDBUF saturé)

- **Date** : 2026-05-09
- **Statut** : FIXED (commit en cours)
- **Sévérité** : critique (corruption silencieuse de réponses)

### Symptôme

Sur `https://staging-doc.vidocq.dev/chappe-fr/index.html` (Antora derrière openresty
qui proxy_pass vers Chappe en HTTP/1.1 cleartext), les images ne se chargent
qu'en partie et le browser reste pendu en attente du reste — le serveur a
arrêté d'envoyer mais a annoncé un `Content-Length` complet, donc le client
attend indéfiniment.

### Repro minimal

```java
// Tests/LargeStaticFileTest.java
// 1. Sert un fichier de 8 MiB via StaticFileHandler
// 2. Drain ralenti côté client (chunks de 4 KiB, sleep 2 ms)
// 3. SO_RCVBUF client = 16 KiB pour saturer rapidement le SO_SNDBUF serveur
// → reçoit 319 020 / 8 388 608 octets puis le serveur cesse d'envoyer
```

Sur macOS Sonoma + Java 25, reproduction systématique en ~250 ms.

### Cause

`HttpResponseWriter.writeBody()` ligne 261 (avant patch) :

```java
while (remaining > 0) {
    long transferred = fc.transferTo(position, remaining, channel);
    if (transferred <= 0) break;        // ← BUG
    position += transferred;
    remaining -= transferred;
}
```

`FileChannel.transferTo(SocketChannel)` peut renvoyer **0** sur un
`SocketChannel` blocking quand le `SO_SNDBUF` kernel est saturé : `sendfile(2)`
sous-jacent gère ce cas via EAGAIN-like sur certaines plateformes (macOS
documenté, Linux sous certaines versions — cf. JDK-8264762, JDK-8230846).

Le `break` sur `transferred <= 0` confond ce retour transitoire avec un EOF
réel (`< 0`) et tronque la réponse silencieusement.

### Correctif

Distinguer EOF (`< 0`) de retry (`== 0`) :

```java
if (transferred < 0) break;        // EOF réel
if (transferred == 0) {
    Thread.yield();                // SO_SNDBUF saturé, on retry
    continue;
}
position += transferred;
remaining -= transferred;
```

### Régression couverte

`chappe-tests/.../LargeStaticFileTest.java` — drain ralenti 8 MiB, vérifie
`Content-Length` consommé entièrement et SHA-256 octet-à-octet.

### Notes connexes (à investiguer séparément)

- `Http2Connection.waitForSendWindow` — race possible : plusieurs streams
  concurrents lisent `connectionSendWindow.get()` en parallèle et peuvent
  dépasser la fenêtre annoncée. Pas reproduit ici (chemin H2 inactif sur le
  staging derrière openresty), mais à corriger côté HTTP/2.

### Validation production (post-fix)

Après déploiement de `8d670fb` (fix + observabilité X-Chappe-Build) sur
staging-doc.vidocq.dev derrière oauth2-proxy + NPM :

- 624 requêtes cumulées sur les 7 logos PNG (877 KB → 2.4 MB)
- Profils : burst parallèle 84-way × 5 cycles, burst 200 random, drain
  ralenti `--limit-rate 200K`
- **0 fail, 0 timeout, 0 troncature** (vs 9/21 = 43% fail pré-fix)

Une variante observée pré-fix (timeout à ~96 % du body, soupçonnée
"2e bug") était en réalité le même bug `transferTo==0` qui se manifestait
sous un profil différent (saturation tardive du SO_SNDBUF) — résolue
par le même commit `ef864e8`.
