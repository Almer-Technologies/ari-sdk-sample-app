## Purpose

The frozen wire contract between Ari and a tool provider app: the two AIDL
interfaces and `AriToolsContract`.

This module has no dependencies, on purpose. It is the only part of the Ari App
Tools surface both sides must agree on byte for byte, so a partner takes it
without taking anything else.

Partners do not depend on this module directly. `libs/ari-tool-sdk` exposes it
with `api` scope, so the contract arrives with the SDK.

## What may change here

Every value in `AriToolsContract` is public API. Changing one is a breaking
change, so add a new value instead of redefining an existing one.

Adding a method to the end of an AIDL interface keeps the existing transaction
codes, so an older provider stays callable. An incompatible change means a
second interface next to the first one, and each side then picks the interface
it speaks. See `libs/ari-tool-sdk/README.md` for the declaration version.

The order of the methods is therefore part of the contract. Add a new method
last, and never reorder or remove one.

Removing a method is not a compatible change either. Every method after it
moves down one transaction code, so an old caller reaches the wrong method.
That is free today, because nothing is published: these modules ship to no
partner yet. `listTools()` was dropped for that reason, and `onResult` gained
its `PendingIntent` parameter the same way. After the first release, a removal
means a second interface.

A method's parameter list is part of the contract too. Adding a parameter
changes that method's parcel layout, so two sides built against different
versions disagree on what the parcel holds. Extend a published method by adding
a new method instead.

A new optional method needs a capability name in `AriToolsContract`, and that
name needs an entry in `CAPABILITY_SINCE_PROTOCOL_VERSION` holding the
`PROTOCOL_VERSION` that added it. A provider then says which optional methods
it implements, and the host calls no other one. A mandatory method belongs to
`MIN_SUPPORTED_PROTOCOL_VERSION` instead, which is a floor and never a range:
the surface only grows, so a provider newer than the host is safe.

## What crosses out of band

`IAriToolCallback.onResult` carries a `PendingIntent` next to the JSON, not
inside it. A `PendingIntent` is `Parcelable`, so JSON cannot hold it. The
envelope's `kind` is what tells the reader to expect one.
