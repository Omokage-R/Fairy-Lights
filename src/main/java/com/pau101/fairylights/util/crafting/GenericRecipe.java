package com.pau101.fairylights.util.crafting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.math.IntMath;
import com.pau101.fairylights.util.crafting.ingredient.Ingredient;
import com.pau101.fairylights.util.crafting.ingredient.IngredientAuxiliary;
import com.pau101.fairylights.util.crafting.ingredient.IngredientRegular;
import com.pau101.fairylights.util.crafting.ingredient.IngredientRegularEmpty;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;

public final class GenericRecipe implements IRecipe {
	public static final IngredientRegularEmpty EMPTY = new IngredientRegularEmpty();

	private final ItemStack output;

	private final IngredientRegular[] ingredients;

	private final IngredientAuxiliary<?>[] auxiliaryIngredients;

	private final int width;

	private final int height;

	private ThreadLocal<ItemStack> result = new ThreadLocal<>();

	GenericRecipe(ItemStack output, IngredientRegular[] ingredients, IngredientAuxiliary<?>[] auxiliaryIngredients, int width, int height) {
		Objects.requireNonNull(output, "output");
		Objects.requireNonNull(ingredients, "ingredients");
		Objects.requireNonNull(auxiliaryIngredients, "auxiliaryIngredients");
		checkIngredients(ingredients, auxiliaryIngredients);
		Preconditions.checkArgument(width >= 0, "width must be greater than or equal to zero");
		Preconditions.checkArgument(height >= 0, "height must be greater than or equal to zero");
		this.output = output;
		this.ingredients = ingredients;
		this.auxiliaryIngredients = auxiliaryIngredients;
		this.width = width;
		this.height = height;
	}

	public ItemStack getOutput() {
		return output.copy();
	}

	public IngredientRegular[] getIngredients() {
		return ingredients.clone();
	}

	public IngredientAuxiliary<?>[] getAuxiliaryIngredients() {
		return auxiliaryIngredients.clone();
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	@Override
	public boolean matches(InventoryCrafting inventory, World world) {
		if (width > inventory.getWidth() || height > inventory.getHeight()) {
			return false;
		}
		int scanWidth = inventory.getWidth() + 1 - width;
		int scanHeight = inventory.getHeight() + 1 - height;
		for (int i = 0, end = scanWidth * scanHeight; i < end; i++) {
			int x = i % scanWidth, y = i / scanWidth;
			ItemStack result = getResult(inventory, prepareOutput(), x, y, c -> c);
			this.result.set(result);
			if (result != null) {
				prepareResult();
				return true;
			}
			result = getResult(inventory, prepareOutput(), x, y, c -> width - 1 - c);
			this.result.set(result);
			if (result != null) {
				prepareResult();
				return true;
			}
		}
		result.set(null);
		return false;
	}

	private ItemStack prepareOutput() {
		ItemStack result = output.copy();
		if (!result.hasTagCompound()) {
			result.setTagCompound(new NBTTagCompound());
		}
		return result;
	}

	private void prepareResult() {
		ItemStack result = this.result.get();
		if (result.hasTagCompound() && result.getTagCompound().hasNoTags()) {
			result.setTagCompound(null);
		}
	}

	@Nullable
	private ItemStack getResult(InventoryCrafting inventory, ItemStack output, int originX, int originY, IntUnaryOperator funcX) {
		MatchResultRegular[] match = new MatchResultRegular[ingredients.length];
		Multimap<IngredientAuxiliary<?>, MatchResultAuxiliary> auxMatchResults = LinkedListMultimap.create();
		Map<IngredientAuxiliary<?>, Integer> auxMatchTotals = new HashMap<>();
		Set<Ingredient<?, ?>> presentCalled = new HashSet<>();
		List<MatchResultAuxiliary> auxResults = new ArrayList<>();
		for (int i = 0, w = inventory.getWidth(), size = w * inventory.getHeight(), auxCount = auxiliaryIngredients.length; i < size; i++) {
			int x = i % w, y = i / w;
			int ingX = x - originX;
			int ingY = y - originY;
			ItemStack input = inventory.getStackInRowAndColumn(x, y);
			if (contains(ingX, ingY)) {
				int index = funcX.applyAsInt(ingX) + ingY * width;
				IngredientRegular ingredient = ingredients[index];
				MatchResultRegular result = ingredient.matches(input, output);
				if (!result.doesMatch()) {
					return null;
				}
				match[index] = result;
				result.forMatch(presentCalled, output);
			} else if (!EMPTY.matches(input, output).doesMatch()) {
				boolean nonAuxiliary = true;
				for (int n = 0; n < auxCount; n++) {
					MatchResultAuxiliary result = auxiliaryIngredients[n].matches(input, output);
					if (result.doesMatch()) {
						if (result.isAtLimit(auxMatchTotals.getOrDefault(result.ingredient, 0))) {
							return null;
						}
						result.forMatch(presentCalled, output);
						auxMatchTotals.merge(result.ingredient, 1, IntMath::checkedAdd);
						nonAuxiliary = false;
						result.propagate(auxMatchResults);
					}
					auxResults.add(result);
				}
				if (nonAuxiliary) {
					return null;
				}
			}
		}
		Set<Ingredient<?, ?>> absentCalled = new HashSet<>();
		for (MatchResultRegular result : match) {
			result.notifyAbsence(presentCalled, absentCalled, output);
		}
		for (MatchResultAuxiliary result : auxResults) {
			result.notifyAbsence(presentCalled, absentCalled, output);
		}
		for (IngredientAuxiliary<?> ingredient : auxiliaryIngredients) {
			if (ingredient.process(auxMatchResults, output)) {
				return null;
			}
		}
		return output;
	}

	private boolean contains(int x, int y) {
		return x >= 0 && y >= 0 && x < width && y < height;
	}

	@Override
	public ItemStack getCraftingResult(InventoryCrafting inventory) {
		ItemStack result = this.result.get();
		if (result == null) {
			return null;
		}
		return result.copy();
	}

	@Override
	public int getRecipeSize() {
		return width * height;
	}

	@Override
	public ItemStack getRecipeOutput() {
		return output;
	}

	@Override
	public ItemStack[] getRemainingItems(InventoryCrafting inventory) {
		return ForgeHooks.defaultRecipeGetRemainingItems(inventory);
	}

	public interface MatchResult<I extends Ingredient, M extends MatchResult<I, M>> {
		I getIngredient();

		@Nullable
		ItemStack getInput();

		boolean doesMatch();

		void forMatch(Set<Ingredient<?, ?>> called, ItemStack output);

		void notifyAbsence(Set<Ingredient<?, ?>> presentCalled, Set<Ingredient<?, ?>> absentCalled, ItemStack output);

		M withParent(M parent);
	}

	public static class MatchResultRegular implements MatchResult<IngredientRegular, MatchResultRegular> {
		protected final IngredientRegular ingredient;

		@Nullable
		protected final ItemStack input;

		protected final boolean doesMatch;

		protected final ImmutableList<MatchResultRegular> supplementaryResults;

		public MatchResultRegular(IngredientRegular ingredient, @Nullable ItemStack input, boolean doesMatch, List<MatchResultRegular> supplementaryResults) {
			this.ingredient = Objects.requireNonNull(ingredient, "ingredient");
			this.input = input;
			this.doesMatch = doesMatch;
			this.supplementaryResults = ImmutableList.copyOf(supplementaryResults);
		}

		@Override
		public final IngredientRegular getIngredient() {
			return ingredient;
		}

		@Override
		public final ItemStack getInput() {
			return input;
		}

		@Override
		public final boolean doesMatch() {
			return doesMatch;
		}

		@Override
		public void forMatch(Set<Ingredient<?, ?>> called, ItemStack output) {
			ingredient.matched(input, output);
			if (!called.contains(ingredient)) {
				ingredient.present(output);
				called.add(ingredient);
			}
		}

		@Override
		public void notifyAbsence(Set<Ingredient<?, ?>> presentCalled, Set<Ingredient<?, ?>> absentCalled, ItemStack output) {
			if (!presentCalled.contains(ingredient) && !absentCalled.contains(ingredient)) {
				ingredient.absent(output);
				absentCalled.add(ingredient);
			}
			for (MatchResultRegular result : supplementaryResults) {
				result.notifyAbsence(presentCalled, absentCalled, output);
			}
		}

		@Override
		public MatchResultRegular withParent(MatchResultRegular parent) {
			return new MatchResultParentedRegular(ingredient, input, doesMatch, supplementaryResults, parent);
		}
	}

	public static class MatchResultParentedRegular extends MatchResultRegular {
		protected final MatchResultRegular parent;

		public MatchResultParentedRegular(IngredientRegular ingredient, @Nullable ItemStack input, boolean doesMatch, List<MatchResultRegular> supplementaryResults, MatchResultRegular parent) {
			super(ingredient, input, doesMatch, supplementaryResults);
			this.parent = Objects.requireNonNull(parent, "parent");
		}

		@Override
		public void forMatch(Set<Ingredient<?, ?>> called, ItemStack output) {
			super.forMatch(called, output);
			parent.forMatch(called, output);
		}

		@Override
		public void notifyAbsence(Set<Ingredient<?, ?>> presentCalled, Set<Ingredient<?, ?>> absentCalled, ItemStack output) {
			super.notifyAbsence(presentCalled, absentCalled, output);
			parent.notifyAbsence(presentCalled, absentCalled, output);
		}

		@Override
		public MatchResultRegular withParent(MatchResultRegular parent) {
			return this.parent.withParent(new MatchResultParentedRegular(ingredient, input, doesMatch, supplementaryResults, parent));
		}
	}

	public static class MatchResultAuxiliary implements MatchResult<IngredientAuxiliary<?>, MatchResultAuxiliary> {
		protected final IngredientAuxiliary ingredient;

		@Nullable
		protected final ItemStack input;

		protected final boolean doesMatch;

		protected final ImmutableList<MatchResultAuxiliary> supplementaryResults;

		public MatchResultAuxiliary(IngredientAuxiliary ingredient, @Nullable ItemStack input, boolean doesMatch, List<MatchResultAuxiliary> supplementaryResults) {
			this.ingredient = Objects.requireNonNull(ingredient, "ingredient");
			this.input = input;
			this.doesMatch = doesMatch;
			this.supplementaryResults = ImmutableList.copyOf(supplementaryResults);
		}

		@Override
		public final IngredientAuxiliary getIngredient() {
			return ingredient;
		}

		@Override
		public final ItemStack getInput() {
			return input;
		}

		@Override
		public final boolean doesMatch() {
			return doesMatch;
		}

		@Override
		public void forMatch(Set<Ingredient<?, ?>> called, ItemStack output) {
			if (!called.contains(ingredient)) {
				ingredient.present(output);
				called.add(ingredient);
			}
		}

		@Override
		public void notifyAbsence(Set<Ingredient<?, ?>> presentCalled, Set<Ingredient<?, ?>> absentCalled, ItemStack output) {
			if (!presentCalled.contains(ingredient) && !absentCalled.contains(ingredient)) {
				ingredient.absent(output);
				absentCalled.add(ingredient);
			}
			for (MatchResultAuxiliary result : supplementaryResults) {
				result.notifyAbsence(presentCalled, absentCalled, output);
			}
		}

		@Override
		public MatchResultAuxiliary withParent(MatchResultAuxiliary parent) {
			return new MatchResultParentedAuxiliary(ingredient, input, doesMatch, supplementaryResults, parent);
		}

		public boolean isAtLimit(int count) {
			return count >= ingredient.getLimit();
		}

		public void propagate(Multimap<IngredientAuxiliary<?>, MatchResultAuxiliary> map) {
			map.put(ingredient, this);
		}
	}

	public static class MatchResultParentedAuxiliary extends MatchResultAuxiliary {
		protected final MatchResultAuxiliary parent;

		public MatchResultParentedAuxiliary(IngredientAuxiliary ingredient, @Nullable ItemStack input, boolean doesMatch, List<MatchResultAuxiliary> supplementaryResults, MatchResultAuxiliary parent) {
			super(ingredient, input, doesMatch, supplementaryResults);
			this.parent = Objects.requireNonNull(parent, "parent");
		}

		@Override
		public void forMatch(Set<Ingredient<?, ?>> called, ItemStack output) {
			super.forMatch(called, output);
			parent.forMatch(called, output);
		}

		@Override
		public void notifyAbsence(Set<Ingredient<?, ?>> presentCalled, Set<Ingredient<?, ?>> absentCalled, ItemStack output) {
			super.notifyAbsence(presentCalled, absentCalled, output);
			parent.notifyAbsence(presentCalled, absentCalled, output);
		}

		@Override
		public MatchResultAuxiliary withParent(MatchResultAuxiliary parent) {
			return this.parent.withParent(new MatchResultParentedAuxiliary(ingredient, input, doesMatch, supplementaryResults, parent));
		}

		@Override
		public boolean isAtLimit(int count) {
			return super.isAtLimit(count) || parent.isAtLimit(count);
		}

		@Override
		public void propagate(Multimap<IngredientAuxiliary<?>, MatchResultAuxiliary> map) {
			super.propagate(map);
			parent.propagate(map);
		}
	}

	private static void checkIngredients(IngredientRegular[] ingredients, IngredientAuxiliary<?>[] auxiliaryIngredients) {
		checkForNulls(ingredients);
		checkForNulls(auxiliaryIngredients);
		boolean ingredientDictator = checkDictatorship(false, ingredients);
		checkDictatorship(ingredientDictator, auxiliaryIngredients);
	}

	private static void checkForNulls(Ingredient<?, ?>[] ingredients) {
		for (int i = 0; i < ingredients.length; i++) {
			if (ingredients[i] == null) {
				throw new NullPointerException("Must not have null ingredients, found at index " + i);
			}
		}
	}

	private static boolean checkDictatorship(boolean foundDictator, Ingredient<?, ?>[] ingredients) {
		for (Ingredient ingredient : ingredients) {
			if (ingredient.dictatesOutputType()) {
				if (foundDictator) {
					throw new IllegalRecipeException("Only one ingredient can dictate output type");
				}
				foundDictator = true;
			}
		}
		return foundDictator;
	}
}