package cc.sighs.JEIEditor.platform.recipe;

/**
 * The two JSON keys one declared recipe value uses: the ingredient id and its
 * amount.
 *
 * <p>An item value is {@code {"item":…,"count":…}} on the ingredient side and
 * {@code {"id":…,"count":…}} on the result side (see
 * {@link ModdedRecipeAdapter.ItemJsonStyle}). A fluid or a chemical is written by
 * the mod's own stack codec, whose keys depend on the mod and, for Create, on
 * whether the value is an ingredient or a result - so those are named constants
 * here and a declaration picks the one its recipe JSON really uses, verified
 * against the mod's shipped files.
 */
public final class ModdedJsonStyle {
    /** {@code {"item":…,"count":…}} - the ingredient side of an item value. */
    public static final ModdedJsonStyle ITEM = new ModdedJsonStyle("item", "count");
    /** {@code {"id":…,"count":…}} - the result side of an item value. */
    public static final ModdedJsonStyle ID = new ModdedJsonStyle("id", "count");
    /**
     * {@code {"amount":…,"fluid":…}} - Immersive Engineering's fluid value and
     * Create's fluid ingredient.
     */
    public static final ModdedJsonStyle FLUID = new ModdedJsonStyle("fluid", "amount");
    /**
     * {@code {"type":"neoforge:single","fluid":…,"amount":…}} - Create's
     * {@code SizedFluidIngredient} codec requires that explicit {@code type}: its
     * primary branch is {@code Codec.either(FluidIngredientType, …)} and its
     * fallback is Create's own legacy form, both of which read a required
     * {@code type} field, so a bare {@code {"fluid":…,"amount":…}} inside an
     * ingredient list is rejected. {@code neoforge:single} is the type name every
     * shipped Create recipe that names one fluid uses.
     */
    public static final ModdedJsonStyle TYPED_FLUID =
            new ModdedJsonStyle("fluid", "amount", "neoforge:single");
    /**
     * {@code {"amount":…,"fluid":…}} written with the result-side id key, for a mod
     * whose fluid ingredient and fluid result are not the same shape.
     */
    public static final ModdedJsonStyle FLUID_ID = new ModdedJsonStyle("id", "amount");
    /** {@code {"amount":…,"chemical":…}} - Mekanism's chemical stack value. */
    public static final ModdedJsonStyle CHEMICAL = new ModdedJsonStyle("chemical", "amount");
    /** {@code {"amount":…,"id":…}} - the result side of a chemical stack value. */
    public static final ModdedJsonStyle CHEMICAL_ID = new ModdedJsonStyle("id", "amount");
    /**
     * The bare amount, no object at all: the field's value is the amount as a JSON
     * number. Immersive Engineering writes its coke oven's fluid by-product that
     * way - {@code "creosote": 250} - because the field name already names the
     * fluid, so the node carries neither an id nor an amount key.
     *
     * <p>Because the value names no ingredient, a declaration that uses this style
     * must also fix the field's id ({@code withFixedFieldIds}): without it a drag of
     * another fluid of the same kind would be written as an amount the recipe reads
     * as the hard-coded fluid. See {@link #amountOnly()}.
     */
    public static final ModdedJsonStyle AMOUNT = new ModdedJsonStyle(null, null);

    private final String idKey;
    private final String amountKey;
    private final String typeId;

    private ModdedJsonStyle(String idKey, String amountKey) {
        this(idKey, amountKey, null);
    }

    private ModdedJsonStyle(String idKey, String amountKey, String typeId) {
        this.idKey = idKey;
        this.amountKey = amountKey;
        this.typeId = typeId;
    }

    /** The key holding the ingredient id, or null when the value has no object. */
    public String idKey() {
        return idKey;
    }

    /** The key holding the amount, or null when the value has no object. */
    public String amountKey() {
        return amountKey;
    }

    /**
     * The value of the {@code type} key a written object has to carry, or null when
     * the shape has no type field. Only a mod whose ingredient codec dispatches on
     * an explicit type needs it ({@link #TYPED_FLUID}).
     */
    public String typeId() {
        return typeId;
    }

    /**
     * Whether the value is the bare amount rather than an object carrying the id
     * and the amount. Such a field implies its ingredient id from its own name, so
     * the declaration has to fix that id and a writer has to refuse any other.
     */
    public boolean amountOnly() {
        return idKey == null;
    }

    /** The style of an item value in the declaration's own item shape. */
    public static ModdedJsonStyle of(ModdedRecipeAdapter.ItemJsonStyle style) {
        return style == ModdedRecipeAdapter.ItemJsonStyle.ID ? ID : ITEM;
    }
}
