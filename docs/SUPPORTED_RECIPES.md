# Vanilla JEI Coverage

The NeoForge 1.21.1 adapter recognizes every vanilla recipe type exposed by
JEI 19.44:

| JEI type | Adapter | Persistence |
| --- | --- | --- |
| Crafting | `CraftingRecipeEditorAdapter` | Targeted recipe JSON |
| Smelting, blasting, smoking, campfire cooking | `CookingRecipeEditorAdapter` | Targeted recipe JSON |
| Stonecutting | `VanillaSpecialRecipeEditorAdapter` | Targeted recipe JSON |
| Smithing transform and trim | `VanillaSpecialRecipeEditorAdapter` | Targeted recipe JSON |
| Fueling | `FuelRecipeEditorAdapter` | SavedData and fuel override |
| Brewing | `JeiVanillaRecipeEditorAdapter` | SavedData and brewing event override |
| Anvil | `JeiVanillaRecipeEditorAdapter` | SavedData and anvil event override |
| Grindstone | `JeiVanillaRecipeEditorAdapter` | SavedData and grindstone event override |
| Composting | `JeiVanillaRecipeEditorAdapter` | SavedData and `neoforge:data_maps/item/compostables` |
| Ingredient information | `JeiVanillaRecipeEditorAdapter` | Client model and SavedData |

The last five JEI types are generated views rather than RecipeManager JSON
entries. Their patches use the JEI UID as the identity. Brewing, anvil, and
grindstone edits are applied by their NeoForge runtime events; composting edits
are written to NeoForge's compostables data map and applied by the separate
Reload action. Information pages have no gameplay operation to override, so
their edits remain client-side display data.
