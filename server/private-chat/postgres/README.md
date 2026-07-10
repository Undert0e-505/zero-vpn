# PostgreSQL ownership and backup paths

The installer creates the `zerovpn_synapse` role and database with UTF-8 encoding and `C` locale. The generated role password is stored only in `/etc/zerovpn/private-chat/secrets/postgres_password` (`0600`, root-owned) and is exposed to Synapse at runtime through systemd credentials.

Back up PostgreSQL with a consistent `pg_dump`/`pg_basebackup` procedure. Do not copy live files from `/var/lib/postgresql` as an ad-hoc backup. A recoverable node backup also needs `/etc/zerovpn/private-chat`, `/var/lib/zerovpn/private-chat/synapse/signing.key`, and the ZeroVPN node manifest.

