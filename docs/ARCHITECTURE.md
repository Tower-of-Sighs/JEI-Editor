# Architecture

The active target is NeoForge 1.21.1. Code is split by ownership rather than
by historical feature growth:

- `common/src/main/java/cc/sighs/JEIEditor/editor`: immutable editor models,
  patch/session data, codecs, and validation. `EditorIngredient` /
  `IngredientKind` / `SlotPatchFields` define what one slot patch looks like for
  every ingredient kind, so the client and the server share one field contract.
- `common/src/main/java/cc/sighs/JEIEditor/recipe`: loader-independent recipe
  rules, fingerprints, creation drafts, policy parsing, and operation
  coordination. `RecipeFieldMapping` is the pure slot-key ↔ recipe-JSON-field
  rule the declared mod layer is built on.
- `targets/neoforge-1.21.1/.../client`: GUI events, JEI introspection, drag
  handling, and client editing state.
- `targets/neoforge-1.21.1/.../platform/recipe`: Minecraft/JEI recipe adapters
  and slot mapping. It holds the vanilla adapters (crafting, cooking, special,
  fuel, JEI generated views), the declared mod layer
  (`ModdedRecipeAdapter` + the per-family declarations `CreateRecipeDeclarations`,
  `ImmersiveEngineeringRecipeDeclarations`, `MekanismRecipeDeclarations`,
  `MiscRecipeDeclarations`, aggregated by `ModdedRecipeAdapters`), the page id
  aliases (`RecipeViewerAliases`, for a page that displays another recipe type's
  recipes under a rewritten id), and the dispatch that routes a model or patch to
  the right one.
- `targets/neoforge-1.21.1/.../platform/network`: NeoForge payload transport.
- `targets/neoforge-1.21.1/.../platform/fuel`: NeoForge fuel event state.
- `targets/neoforge-1.21.1/.../server`: SavedData, permission loading,
  commands, recipe application, reload coordination, recipe parsing, and the
  server-side patch gate (`RecipeEditsApplier.validatePatch`) that a client
  patch has to pass before anything is written.

`common` must not import Minecraft, JEI, or loader APIs. Target packages are
adapters at the boundary and should delegate domain rules to `common` instead
of duplicating them.

## Development-only test module

`targets/neoforge-1.21.1/src/clientTest` is a second source set compiled as the
mod `jeieditortests`. It is loaded only by the `clientTest` Gradle run
(`RUN mods=[jeieditor, jeieditortests]`, its own game directory `run/clientTest`),
never by `sourceSets.main`, so the published jar cannot contain it. Inside the
running client it walks every JEI recipe page, builds a model through the
editor's own path, asserts the slot keys, declared kinds, and patch round trip,
and writes `build/client-test/report.json` for `scripts/jei-client-tests.ps1` to
judge.

## Verification

`scripts/jei-verify.ps1` is the single entry point: it runs the `common` unit
tests, then `scripts/jei-client-tests.ps1` (inside the client, no server), then
`scripts/jei-declared-smoke.ps1` (a dedicated server, RCON driven, asserting the
generated datapack JSON, the per-family refusals and that the writes survive
`/reload`). Both harnesses deliver their RCON commands through
`scripts/jei-rcon.ps1`, which retries a transient socket failure or receive
timeout with bounded exponential backoff on a fresh connection. The mod pack
those two harnesses need is documented in `scripts/jei-mod-env/README.md`.
