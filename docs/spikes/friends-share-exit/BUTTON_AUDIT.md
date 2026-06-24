# Friends Share Exit Button Audit

Branch: `feature/friends-share-exit`

## Bottom Navigation

All bottom navigation items are treated as root-tab escape hatches:

- Home -> Home root
- Add Exit -> Add Exit options root
- Friends -> Friends root
- Diagnostics -> Diagnostics root
- Settings -> Settings root

Bottom navigation does not restore child state. This avoids reopening transient provisioning, success, failure, camera, or import-confirmation routes.

## Screen Actions

### Home

- Connect/Disconnect: guarded by provider switch state and VPN/Volunteer busy state.
- Destroy Oracle Exit: danger confirmation, disconnects first, then starts destroy flow.
- Remove Shared Exit: confirmation, local profile removal only.
- Remove Volunteer Exit: confirmation, stops Volunteer VPN first when active.
- Rename Shared Exit: dialog save/cancel.
- Add Exit: navigates to Add Exit root.

### Add Exit

- Create Oracle Free Exit: starts Oracle provisioning flow.
- Scan QR Invite: opens scanner route.
- Volunteer Exit: visible in the normal Add Exit workflow and labelled experimental.
- Import Config / Create Private Node: disabled placeholders with explanatory copy.

### Oracle Provisioning

- Existing Oracle account / created account: starts provisioning.
- Create Oracle account: launches Oracle signup.
- Retry: restarts provisioning from failure.
- Cleanup: runs cleanup for partial resources.
- Connect from success: connects selected created exit and navigates Home when connected.
- Destroy from success: danger confirmation before destroy.

### Scan QR

- Camera permission retry: relaunches permission request.
- Cancel/back button: returns to Add Exit root and releases camera through disposal.
- Successful scan: stops analyzer/camera and shows import confirmation once.
- Save shared exit: imports once and navigates Home root.
- Import cancel/dismiss: returns to Add Exit root.
- Invalid or duplicate QR: shows dismissible error and allows retry.

### Friends

- Share: available for Unused slots; reads invite material from secure storage.
- Show QR: available for PendingClaim slots; unavailable for Claimed slots.
- Rename: dialog save/cancel.
- Check claim: disabled during check/reset and reports errors visibly.
- Revoke/reset: danger confirmation; disabled during check/reset.
- Revoked slots: disabled placeholder.

### Diagnostics

- Refresh: refreshes user diagnostics.
- Copy diagnostics / SSH command: developer-only, safe metadata only.
- Volunteer debug controls: developer-only and state-gated.

### Settings

- Developer Mode: toggle persists through `ProvisioningViewModel`.

### Volunteer

- Create Volunteer Exit: creates local Volunteer profile.
- Connect: state-gated; requests Android VPN permission if needed.
- Remove: danger confirmation.
- Back/Home paths: route through root navigation or normal back stack.

## Manual Validation Notes

Run the manual checklist from the task after installing the debug build. Pay special attention to Home from Add Exit, Add Exit from scanner, and all bottom-nav items from transient provisioning and scanner states.
