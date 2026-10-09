"""Scan installed JEI addons for JEI API calls that could collide with the editor.

The editor persists deletions/edits by calling IJeiRuntime#getRecipeManager()
hideRecipes / unhideRecipes / addRecipes and the ingredient equivalents. Any
addon that also manipulates visibility can fight the editor over the same state.
"""
import os
import re
import zipfile
from collections import defaultdict

MODS = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                    "targets", "neoforge-1.21.1", "run", "mods"))

# JEI runtime API surface the editor relies on, plus nearby visibility calls.
NEEDLES = {
    "hideRecipes": rb"hideRecipes",
    "unhideRecipes": rb"unhideRecipes",
    "addRecipes": rb"addRecipes",
    "hideIngredients": rb"hideIngredients",
    "unhideIngredients": rb"unhideIngredients",
    "addIngredients": rb"addIngredients",
    "IJeiRuntime": rb"mezz/jei/api/runtime/IJeiRuntime",
    "IRecipeManager": rb"mezz/jei/api/runtime/IRecipeManager",
    "IIngredientManager": rb"mezz/jei/api/runtime/IIngredientManager",
    "IBookmarkOverlay": rb"mezz/jei/api/runtime/IBookmarkOverlay",
    "RecipesGui": rb"mezz/jei/gui/recipes/RecipesGui",
    "IngredientListOverlay": rb"mezz/jei/gui/ingredients/IngredientListOverlay",
    "IModPlugin": rb"mezz/jei/api/IModPlugin",
    "IRecipeCategory": rb"mezz/jei/api/recipe/category/IRecipeCategory",
}

ELDER = ("jeieditor",)


def scan(path, into):
    try:
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if not name.endswith(".class"):
                    continue
                data = z.read(name)
                for label, pat in NEEDLES.items():
                    if re.search(pat, data):
                        into[label].add(basename)
    except Exception as exc:
        print("  !! %s: %s" % (basename, exc))


rows = defaultdict(set)
for f in sorted(os.listdir(MODS)):
    if not f.endswith(".jar"):
        continue
    basename = f.split("__")[0]
    if basename in ELDER:
        continue
    scan(os.path.join(MODS, f), rows)

# Which addons touch recipe visibility - the direct collision candidates.
visibility = {"hideRecipes", "unhideRecipes", "addRecipes",
              "hideIngredients", "unhideIngredients", "addIngredients"}
print("=== 会操作 JEI 可见性/注册状态的附属（与编辑器持久化机制直接竞争）===")
hits = sorted(set().union(*[rows[k] for k in visibility]) if any(rows[k] for k in visibility) else set())
for name in hits:
    acts = sorted(k for k in visibility if name in rows[k])
    print("  %-46s %s" % (name, ", ".join(acts)))

print()
print("=== 通过公开 API 注册内容（IModPlugin / IRecipeCategory）的附属数量 ===")
print("  IModPlugin        :", len(rows["IModPlugin"]))
print("  IRecipeCategory   :", len(rows["IRecipeCategory"]))
print("  IJeiRuntime       :", len(rows["IJeiRuntime"]))
print("  IBookmarkOverlay  :", len(rows["IBookmarkOverlay"]))
print()
print("=== 引用 JEI 内部 GUI 类的附属（与编辑器反射点重叠）===")
for key in ("RecipesGui", "IngredientListOverlay"):
    print("  %-22s %s" % (key, ", ".join(sorted(rows[key])) or "（无）"))
