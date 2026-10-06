package com.example.itembind;

import com.example.itembind.mixin.HandledScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ItemBindClient implements ClientModInitializer {

    private static final String CATEGORY = "category.itembind";
    private static final long RETURN_DELAY_MS = 50;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int YELLOW = 0xFFFFFF55;

    /** Предмет, привязанный к клавише. */
    private static class Bind {
        final String name;
        int key;
        boolean wasDown = false;

        Bind(String name, int key) {
            this.name = name;
            this.key = key;
        }
    }

    private final List<Bind> binds = new ArrayList<>();

    private KeyBinding selectKey;
    private String pendingItem = null; // предмет выбран, ждём клавишу

    // Данные для возврата предмета на место
    private int returnSlotId = -1;
    private int returnHotbar = -1;
    private long returnAt = 0;

    private Path configFile;
      @Override
    public void onInitializeClient() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("itembind.txt");
        load();

        selectKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.itembind.select", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof InventoryScreen inv)) return;

            // Режим назначения клавиши: перехватываем нажатие до инвентаря
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, mods) -> {
                if (pendingItem == null) return true;
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    pendingItem = null;
                    msg(client, "ItemBind: отменено");
                    return true;
                }
                assignKey(client, key);
                return false;
            });

            // Кнопка выбора на предмете: привязать, а если уже привязан, то убрать бинд
            ScreenKeyboardEvents.afterKeyPress(screen).register((s, key, scancode, mods) -> {
                if (selectKey.matchesKey(key, scancode)) toggleHovered(client, inv);
            });

            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> drawPanel(ctx, client));

            ScreenEvents.remove(screen).register(s -> pendingItem = null);
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.currentScreen == null && !mc.options.hudHidden) drawPanel(ctx, mc);
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }
      // ---------- Тик: бинды ----------

    private void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null) return;
        long now = System.currentTimeMillis();

        // Возвращаем предмет на место через 50 мс
        if (returnSlotId != -1 && now >= returnAt
                && mc.player.currentScreenHandler == mc.player.playerScreenHandler) {
            mc.interactionManager.clickSlot(
                    mc.player.playerScreenHandler.syncId,
                    returnSlotId, returnHotbar, SlotActionType.SWAP, mc.player);
            returnSlotId = -1;
        }

        boolean allowed = mc.currentScreen == null || mc.currentScreen instanceof InventoryScreen;
        long handle = mc.getWindow().getHandle();

        for (Bind b : binds) {
            boolean down = b.key != -1 && InputUtil.isKeyPressed(handle, b.key);
            if (allowed && down && !b.wasDown && returnSlotId == -1) useBound(mc, b);
            b.wasDown = down;
        }
    }

    private void useBound(MinecraftClient mc, Bind b) {
        int index = findInventoryIndex(mc, b.name);
        if (index == -1) {
            msg(mc, "ItemBind: " + b.name + " не найден");
            return;
        }

        int hotbar = mc.player.getInventory().selectedSlot; // выбранный слот (правая рука)

        if (index != hotbar) {
            // Слоты PlayerScreenHandler: хотбар 36-44, инвентарь 9-35
            int slotId = index < 9 ? 36 + index : index;

            // SWAP с кнопкой = номер слота хотбара: обмен предмета с рукой
            mc.interactionManager.clickSlot(
                    mc.player.playerScreenHandler.syncId,
                    slotId, hotbar, SlotActionType.SWAP, mc.player);

            returnSlotId = slotId;
            returnHotbar = hotbar;
            returnAt = System.currentTimeMillis() + RETURN_DELAY_MS;
        }

        useMainHand(mc);
    }

    private void useMainHand(MinecraftClient mc) {
        // Если смотрим на блок, пробуем поставить/применить на него (трапка, пласт)
        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            ActionResult r = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            if (r.isAccepted()) {
                mc.player.swingHand(Hand.MAIN_HAND);
                return;
            }
        }
        // Иначе обычное использование (эндер-перл и т.п.)
        ActionResult r2 = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (r2.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
    }
      // ---------- Выбор предметов в инвентаре ----------

    private void toggleHovered(MinecraftClient mc, InventoryScreen screen) {
        Slot slot = ((HandledScreenAccessor) screen).getFocusedSlot();
        if (slot == null || !slot.hasStack()) return;
        String name = nameOf(slot.getStack());

        boolean removed = binds.removeIf(b -> b.name.equalsIgnoreCase(name));
        if (removed) {
            save();
            msg(mc, "ItemBind: бинд " + name + " удалён");
        } else {
            pendingItem = name;
            msg(mc, "ItemBind: нажми клавишу для " + name + " (Esc - отмена)");
        }
    }

    private void assignKey(MinecraftClient mc, int key) {
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT
                || key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL
                || key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT) {
            msg(mc, "ItemBind: Shift/Ctrl/Alt нельзя, нажми другую клавишу");
            return;
        }
        if (KeyBindingHelper.getBoundKeyOf(selectKey).getCode() == key) {
            msg(mc, "ItemBind: эта клавиша занята под выбор предметов");
            return;
        }
        for (Bind b : binds) {
            if (b.key == key) {
                msg(mc, "ItemBind: клавиша уже занята");
                return;
            }
        }
        Bind nb = new Bind(pendingItem, key);
        nb.wasDown = true; // клавиша ещё зажата: не срабатываем сразу
        binds.add(nb);
        save();
        msg(mc, "ItemBind: [" + keyName(key) + "] = " + pendingItem);
        pendingItem = null;
    }

    // ---------- Интерфейс (справа сверху) ----------

    private void drawPanel(DrawContext ctx, MinecraftClient mc) {
        if (mc.player == null) return;
        TextRenderer tr = mc.textRenderer;

        int width = tr.getWidth("ItemBind");
        for (Bind b : binds) {
            width = Math.max(width, tr.getWidth("[" + keyName(b.key) + "] " + b.name));
        }
        String status = null;
        if (pendingItem != null) status = "Нажми клавишу (Esc - отмена)";
        else if (binds.isEmpty()) status = "В инвентаре: предмет + " + selectKey.getBoundKeyLocalizedText().getString();
        if (status != null) width = Math.max(width, tr.getWidth(status));

        int lines = 1 + binds.size() + (status != null ? 1 : 0);
        int x = mc.getWindow().getScaledWidth() - width - 6;
        int y = 6, lh = 11;
        ctx.fill(x - 3, y - 3, x + width + 3, y + lines * lh + 1, 0x90000000);

        ctx.drawTextWithShadow(tr, "ItemBind", x, y, YELLOW);
        y += lh;

        for (Bind b : binds) {
            String key = "[" + keyName(b.key) + "] ";
            ctx.drawTextWithShadow(tr, key, x, y, GRAY);
            ctx.drawTextWithShadow(tr, b.name, x + tr.getWidth(key), y, WHITE);
            y += lh;
        }

        if (status != null) ctx.drawTextWithShadow(tr, status, x, y, GRAY);
    }
      // ---------- Утилиты ----------

    // Ищем предмет по названию: сначала хотбар (0-8), потом инвентарь (9-35)
    private int findInventoryIndex(MinecraftClient mc, String name) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && nameOf(stack).equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private String keyName(int key) {
        return InputUtil.fromKeyCode(key, -1).getLocalizedText().getString();
    }

    private String nameOf(ItemStack stack) {
        return stack.getName().getString().trim();
    }

    private void msg(MinecraftClient mc, String text) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(text), true);
    }

    // Формат файла: клавиша<TAB>название предмета
    private void load() {
        try {
            if (!Files.exists(configFile)) return;
            for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                if (parts.length == 2) {
                    binds.add(new Bind(parts[1], Integer.parseInt(parts[0])));
                }
            }
        } catch (IOException | NumberFormatException ignored) {}
    }

    private void save() {
        List<String> lines = new ArrayList<>();
        for (Bind b : binds) lines.add(b.key + "\t" + b.name);
        try {
            Files.write(configFile, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }
}
