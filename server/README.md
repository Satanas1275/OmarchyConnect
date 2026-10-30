# Relais central OmarchyConnect

Petit serveur Rust (axum) qui ne fait que trois choses :

1. **Appairage** : `POST /api/pair/announce` (PC) → code à 8 caractères,
   `POST /api/pair/join` (téléphone) → clé publique du PC,
   `POST /api/pair/finalize` → paire enregistrée.
2. **Authentification** des WebSockets par nonce signé (ECDSA/SHA-256).
3. **Routage opaque** : `{"t":"relay","to":…,"payload":"…"}` est transmis tel
   quel. Le serveur ne voit jamais de clé privée ni de contenu en clair.

L'état (appareils, paires, nonces) est persisté dans un unique `state.json`.
La clé de session E2E n'y figure **pas** : elle est dérivée par ECDH sur les
deux appareils.

## Développement local

```bash
cd server
cargo run -- --port 6716
# écoute http://127.0.0.1:6716
```

Côté daemon PC :

```bash
./omarchy-connect --relay http://127.0.0.1:6716
```

Côté Android (émulateur), mettre `http://10.0.2.2:6716` dans **Pair → Relay
endpoint**. Le fichier `network_security_config.xml` autorise déjà le cleartext
pour `10.0.2.2`, `127.0.0.1` et `localhost` uniquement.

## Production : `https://omarchy-connect.rocknite-studio.com/`

Le binaire écoute **en loopback** ; le TLS et le domaine sont terminés par un
reverse proxy sur la même machine.

```bash
cargo build --release
sudo install -Dm755 target/release/omarchy-connect-server \
  /usr/local/bin/omarchy-connect-server
sudo useradd --system --home /var/lib/omarchy-connect --shell /usr/sbin/nologin omarchy
sudo install -d -o omarchy -g omarchy -m 700 /var/lib/omarchy-connect
```

`/etc/systemd/system/omarchy-connect-server.service` :

```ini
[Unit]
Description=OmarchyConnect relay
After=network-online.target
Wants=network-online.target

[Service]
User=omarchy
Group=omarchy
StateDirectory=omarchy-connect
ExecStart=/usr/local/bin/omarchy-connect-server --bind 127.0.0.1 --port 6716 --state /var/lib/omarchy-connect/state.json
Environment=RUST_LOG=omarchy_connect_server=info
Restart=on-failure
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now omarchy-connect-server
curl -fsS http://127.0.0.1:6716/healthz
```

### Caddy

```caddyfile
omarchy-connect.rocknite-studio.com {
    reverse_proxy 127.0.0.1:6716
}
```

Caddy obtient et renouvelle le certificat automatiquement, et relaie les
WebSockets sans configuration supplémentaire.

### nginx

```nginx
server {
    listen 443 ssl http2;
    server_name omarchy-connect.rocknite-studio.com;

    ssl_certificate     /etc/letsencrypt/live/omarchy-connect.rocknite-studio.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/omarchy-connect.rocknite-studio.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:6716;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
}
```

### Vérifications

```bash
curl -fsS https://omarchy-connect.rocknite-studio.com/healthz
# {"ok":true,...}

# une seule instance doit écouter le port
ss -ltnp | grep 6716
```

Sauvegarder `/var/lib/omarchy-connect/state.json` suffit à conserver les
appareils appairés (les sessions E2E, elles, se rétablissent au prochain
handshake).
