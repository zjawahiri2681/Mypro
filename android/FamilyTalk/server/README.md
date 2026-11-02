# Family Hub (optional self-hosted relay + TURN)

FamilyTalk works out of the box with a public MQTT relay. The relay only ever sees
sealed, end-to-end encrypted envelopes on random-looking topics — but it does see
*that* phones are online and when they exchange messages, and their IP addresses.
Running your own hub removes that third party.

## What you need
- A small Linux VPS (1 vCPU / 1 GB is plenty for a family) or a home server with ports forwarded.
- A domain name pointing at it, e.g. `hub.example.com`.
- Docker with the compose plugin.

## Steps
1. Get a TLS certificate (e.g. `certbot certonly --standalone -d hub.example.com`) and copy
   `fullchain.pem` and `privkey.pem` into `server/certs/`.
2. Create the relay password file:
   `docker run --rm -v "$PWD:/w" eclipse-mosquitto:2.0.20 mosquitto_passwd -b -c /w/passwd family 'A-LONG-RANDOM-PASSWORD'`
3. Edit `turnserver.conf`: set `realm` to your domain and replace `CHANGE_ME_LONG_RANDOM_PASSWORD`.
4. Open firewall ports: TCP 8883 (relay), TCP/UDP 3478 and 5349 (TURN), UDP 49160-49200 (TURN media).
5. `docker compose up -d`
6. In FamilyTalk (admin phone): **Settings › Relay & TURN servers**
   - Broker: `ssl://hub.example.com:8883`, user `family`, the relay password
   - TURN URL: `turns:hub.example.com:5349,turn:hub.example.com:3478?transport=udp`, user `family`, the TURN password
   - Save. The setting is signed and sent to every family phone.
7. Optional: on each phone, **Settings › Hide my IP address** to force all media through your TURN server.

Never commit `certs/`, `passwd` or a filled-in `turnserver.conf` to git.

## Bridging outside callers into a family conference
Mixing an ordinary phone call (or a SIP call) into an end-to-end encrypted family
conference requires a server that decrypts the audio to mix it (e.g. Asterisk or
FreeSWITCH with a SIP trunk). That breaks end-to-end encryption for that call, so it
is deliberately not part of the default hub. Inside the app you can instead merge
several **SIP** calls into one local conference (Keypad › call › call again › Merge).
