# Product Definition

Volunteer Network is a planned ZeroVPN routing mode that uses public
volunteer-operated network infrastructure.

It should not be described as a commercial VPN, a ZeroVPN server network, or a
guarantee of anonymity. The user should understand that traffic exits through a
network ZeroVPN does not operate.

## User-Facing Positioning

Suggested short description:

> Free routing through a public volunteer-operated network. Slower than a
> private exit. Some sites may block this traffic.

Key product statements:

* ZeroVPN does not operate the volunteer network.
* ZeroVPN does not sell or provide VPN servers.
* Volunteer Network may be slower than Oracle Free Exit or a private node.
* Some sites may block or challenge traffic from public volunteer exits.
* This changes network routing; it does not make the device anonymous by
  itself.

## Provider Model

Volunteer Network should not be forced into the Oracle/WireGuard exit model if
that creates awkward architecture.

Use a provider or route type model conceptually:

* `ORACLE_WIREGUARD_EXIT`
* `VOLUNTEER_NETWORK`
* `IMPORTED_WIREGUARD`
* `PRIVATE_NODE`

Oracle Free Exit provisions infrastructure. Volunteer Network probably does
not. Imported WireGuard and Private Node also have different ownership and setup
models.

## UI Placement Options

### Option A: Normal Exit Tile

Volunteer Network appears beside Oracle Free Exit as another exit tile.

Pros:

* simple mental model
* reuses existing Add Exit surface
* makes the planned feature visible

Cons:

* may imply ZeroVPN owns or provisions the volunteer exit
* may not match Tor/Orbot-style routing where there is no persistent exit
  object

### Option B: Separate Routing Mode

Volunteer Network appears as a separate mode rather than an exit.

Pros:

* avoids forcing it into infrastructure provisioning language
* easier to explain that it is public volunteer-operated routing

Cons:

* may split the home screen and connection state model
* may duplicate connection UI

### Option C: Provider-Specific Exit Type With Shared Connection UI

Volunteer Network is a provider type that uses the same high-level Connect
button and diagnostics surface, but has provider-specific setup, warnings, and
capabilities.

Pros:

* keeps a unified connection UI
* allows provider-specific warnings and diagnostics
* avoids pretending every provider is a VM-backed WireGuard exit

Cons:

* requires a cleaner internal provider abstraction
* diagnostics must be capability-based

## Recommendation

Use Option C. Treat Volunteer Network as a provider-specific routing type with
shared connection UI and provider-specific setup, warnings, lifecycle, and
diagnostics.

Do not reuse Oracle provisioning language. Do not require a persistent "exit"
record unless the selected implementation actually has one.

