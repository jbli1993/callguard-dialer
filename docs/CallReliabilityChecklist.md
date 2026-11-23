# Call reliability checklist

Run this checklist before a release that changes calling, notifications, permissions, or the
default-dialer flow. Use a physical device where possible; Bluetooth and lock-screen behavior are
not representative on every emulator image.

## Default dialer and permissions

- Set OpenDialer as the default phone app, restart it, and verify that the recents, contacts, and
  dialpad screens are available.
- Decline each runtime permission once. Open the corresponding feature again and verify that the
  app explains the missing capability without crashing.
- Revoke Contacts, Call Log, Phone, and Notifications permissions individually in Android Settings
  while the app is backgrounded. Return to the app and verify that it refreshes its state.
- Remove OpenDialer as the default dialer, return to the app, then set it as default again.

## Incoming and outgoing calls

- Receive a call while the device is unlocked, locked, and while OpenDialer is backgrounded.
- Answer, decline, and allow a call to time out. Verify that an unanswered call creates exactly one
  missed-call notification with working Call back, Message, and app-open actions.
- Start an outgoing call from the dialpad, a contact, a favorite, and call history.
- While a call is active, background OpenDialer, rotate the device, and recreate the activity from
  the system recents screen. Verify that the in-call controls retain the real telecom state.

## Audio and multi-call behavior

- Switch between earpiece, speaker, wired headset, and Bluetooth when the device supports them.
- Use headset hardware controls to answer and hang up.
- Hold and resume a call. Start or receive a second call, then verify swap, merge, and split where
  the carrier supports those capabilities.
- Verify that ending one call leaves another active or held call usable and visible.

## Notifications and cleanup

- Verify that an ongoing-call notification is removed once the final call ends.
- Tap each missed-call notification action after the app has been force-stopped and reopened.
- Verify that an incoming call rejected from OpenDialer does not create a missed-call notification.
- While ringing and during an active call, `dumpsys notification` should show only
  `com.callguard.dialer` id `1`; there should be no `com.android.server.telecom` call notification.
- After a missed call, there should be only `com.callguard.dialer` missed notification(s), never a
  `TelecomMissedCalls` notification from `com.android.server.telecom`.
- A missed-call stack must use id `1002` with tag `missed-calls-summary` plus one
  `missed-call-<CallLog._ID>` child per missed call; no random-id or untagged `1002` leftovers.
- Clear missed calls (a `SHOW_MISSED_CALLS_NOTIFICATION` broadcast with count `0`) and verify that
  the whole group is removed and every new missed call is marked `NEW=0 / IS_READ=1`.
- Dismiss one child and verify only that CallLog row is marked read; dismissing the last child also
  removes the summary.
- Kill the app process after a missed notification appears, then deliver the same Telecom
  `SHOW_MISSED_CALLS_NOTIFICATION` again. The receiver must rebuild the same tags, not add a second
  summary or child.
- Verify that a self-managed VoIP missed call does not produce a missed notification from this app.
- While another app is the default dialer, verify that this app posts no call or missed-call
  notification; the default dialer/Telecom owns them.
- For any self-managed VoIP call, verify that this app posts no call notification: without a
  conflict the VoIP app owns the UI; with another unholdable call Telecom's system notification is
  the only incoming-call notification.
