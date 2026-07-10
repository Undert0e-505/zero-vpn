# Private Chat firewall boundary

The installer generates idempotent `iptables` scripts from `installer/model.py` and runs them through `zerovpn-private-chat-firewall.service` after `wg-quick@wg0`. It adds dedicated chains and a single jump; it never flushes built-in chains or rewrites `/etc/wireguard/wg0.conf`.

Phase 1 binds TLS only to the WireGuard service address. It also creates unattached `ZEROVPN_CHAT_PEER_INPUT` and `ZEROVPN_CHAT_PEER_FORWARD` policy chains. A later invitation phase must attach future chat-only peer `/32` sources to those chains before ZeroVPN's broad owner-forwarding rule. The forward policy denies Oracle metadata, the WireGuard subnet, and all Internet forwarding.

