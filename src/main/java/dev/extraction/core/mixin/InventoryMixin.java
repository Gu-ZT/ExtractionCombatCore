package dev.extraction.core.mixin;

import java.util.function.Predicate;
import dev.extraction.core.GameAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
abstract class InventoryMixin {
    @Shadow @Final public Player player;
    @Shadow public int selected;
    @Shadow public abstract ItemStack getItem(int slot);

    @Inject(method = "getSelected", at = @At("HEAD"), cancellable = true)
    private void extractionMainHand(CallbackInfoReturnable<ItemStack> ci) {
        if (!GameAccess.policy(player).enabled(selected)) {
            ci.setReturnValue(ItemStack.EMPTY);
        }
    }

    @Inject(method = "getFreeSlot", at = @At("HEAD"), cancellable = true)
    private void extractionFreeSlot(CallbackInfoReturnable<Integer> ci) {
        if (GameAccess.policy(player).mainEnabled() && GameAccess.policy(player).hotbar() == 9) {
            return;
        }
        for (int i = 0; i < 36; i++) {
            if (GameAccess.policy(player).enabled(i) && getItem(i).isEmpty()) {
                ci.setReturnValue(i);
                return;
            }
        }
        ci.setReturnValue(-1);
    }

    @Inject(method = "getSlotWithRemainingSpace", at = @At("HEAD"), cancellable = true)
    private void extractionPartialStack(ItemStack stack, CallbackInfoReturnable<Integer> ci) {
        if (GameAccess.policy(player).mainEnabled() && GameAccess.policy(player).hotbar() == 9) {
            return;
        }
        for (int order = -2; order < 36; order++) {
            int slot = order == -2 ? selected : order == -1 ? 40 : order;
            ItemStack target = getItem(slot);
            if (GameAccess.policy(player).enabled(slot) && target.isStackable() && target.getCount() < target.getMaxStackSize()
                && ItemStack.isSameItemSameComponents(target, stack)) {
                ci.setReturnValue(slot);
                return;
            }
        }
        ci.setReturnValue(-1);
    }

    @Inject(method = "pickSlot", at = @At("HEAD"), cancellable = true)
    private void extractionPick(int slot, CallbackInfo ci) {
        if (slot < 0 || slot >= 36 || !GameAccess.policy(player).enabled(slot) || !GameAccess.policy(player).enabled(selected)) {
            ci.cancel();
        }
    }

    @Inject(method = "addAndPickItem", at = @At("HEAD"), cancellable = true)
    private void extractionPickWithoutHotbar(ItemStack stack, CallbackInfo ci) {
        if (GameAccess.policy(player).hotbar() == 0) {
            ci.cancel();
        }
    }

    @Inject(method = "findSlotMatchingItem", at = @At("HEAD"), cancellable = true)
    private void extractionFindAvailableStack(ItemStack stack, CallbackInfoReturnable<Integer> ci) {
        if (!GameAccess.policy(player).mainEnabled() || GameAccess.policy(player).hotbar() < 9) {
            ci.setReturnValue(extractionFind(found -> ItemStack.isSameItemSameComponents(stack, found)));
        }
    }

    @Inject(method = "findSlotMatchingCraftingIngredient", at = @At("HEAD"), cancellable = true)
    private void extractionFindIngredient(Holder<Item> ingredient, ItemStack existing, CallbackInfoReturnable<Integer> ci) {
        if (!GameAccess.policy(player).mainEnabled() || GameAccess.policy(player).hotbar() < 9) {
            ci.setReturnValue(extractionFind(found -> found.is(ingredient) && Inventory.isUsableForCrafting(found)
                && (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, found))));
        }
    }

    private int extractionFind(Predicate<ItemStack> matches) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack found = getItem(slot);
            if (GameAccess.policy(player).enabled(slot) && !found.isEmpty() && matches.test(found)) {
                return slot;
            }
        }
        return -1;
    }

    @Inject(method = "fillStackedContents", at = @At("HEAD"), cancellable = true)
    private void extractionCountIngredients(StackedItemContents contents, CallbackInfo ci) {
        if (!GameAccess.policy(player).mainEnabled() || GameAccess.policy(player).hotbar() < 9) {
            for (int slot = 0; slot < 36; slot++) {
                if (GameAccess.policy(player).enabled(slot)) {
                    contents.accountSimpleStack(getItem(slot));
                }
            }
            ci.cancel();
        }
    }

    @Inject(method = "getSuitableHotbarSlot", at = @At("HEAD"), cancellable = true)
    private void extractionPickDestination(CallbackInfoReturnable<Integer> ci) {
        int count = GameAccess.policy(player).hotbar();
        if (count < 1 || count == 9) {
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < count; i++) {
                int slot = Math.floorMod(selected + i, count);
                ItemStack stack = getItem(slot);
                if (pass == 0 ? stack.isEmpty() : !stack.isNotReplaceableByPickAction(player, slot)) {
                    ci.setReturnValue(slot);
                    return;
                }
            }
        }
        ci.setReturnValue(Math.floorMod(selected, count));
    }

    @Inject(method = "dropAll", at = @At("TAIL"))
    private void extractionDropPack(CallbackInfo ci) {
        ItemStack pack = GameAccess.itemAt(player, GameAccess.EQUIPMENT_SLOT);
        if (!pack.isEmpty()) {
            GameAccess.setItemAt(player, GameAccess.EQUIPMENT_SLOT, ItemStack.EMPTY);
            player.drop(pack, true, false);
        }
    }
}
