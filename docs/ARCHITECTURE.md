# Architecture

The active target is NeoForge 1.21.1. Code is split by ownership rather than
by historical feature growth:

- `common/src/main/java/cc/sighs/JEIEditor/editor`: immutable editor models,
  patch/session data, codecs, and validation.
- `common/src/main/java/cc/sighs/JEIEditor/recipe`: loader-independent recipe
  rules, fingerprints, creation drafts, policy parsing, and operation
  coordination.
- `targets/neoforge-1.21.1/.../client`: GUI events, JEI introspection, drag
  handling, and client editing state.
- `targets/neoforge-1.21.1/.../platform/recipe`: Minecraft/JEI recipe
  adapters and slot mapping.
- `targets/neoforge-1.21.1/.../platform/network`: NeoForge payload transport.
- `targets/neoforge-1.21.1/.../platform/fuel`: NeoForge fuel event state.
- `targets/neoforge-1.21.1/.../server`: SavedData, permission loading,
  commands, recipe application, reload coordination, and recipe parsing.

`common` must not import Minecraft, JEI, or loader APIs. Target packages are
adapters at the boundary and should delegate domain rules to `common` instead
of duplicating them.
