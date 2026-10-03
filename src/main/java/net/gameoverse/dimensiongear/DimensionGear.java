package net.gameoverse.dimensiongear;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.equipment.ArmorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rescales other mods' gear from per-dimension tables (this jar's {@code gameoverse_dimension_gear/tables/*.json}),
 * so each post-End dimension's gear sits where docs/post-end-progression.md puts it. Works on the items' default
 * components (Fabric's {@link DefaultItemComponentEvents}), so every reader (tooltips, Apotheosis, combat) sees the
 * new values; the source mods aren't changed.
 *
 * <p>Tools: durability, mining speed (the rules that mine and drop; swords keep their own), and attack damage
 * (the {@code minecraft:base_attack_damage} modifier moves by {@code bonus - from_bonus}, keeping each tool's own
 * offset). Armor: durability ({@code multiplier x} the slot's unit), armor, toughness and knockback resistance per
 * slot; other modifiers (a material's extras) are kept.
 */
public class DimensionGear implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("gameoverse_dimension_gear");
    private static final Identifier BASE_ATTACK_DAMAGE = Identifier.withDefaultNamespace("base_attack_damage");
    private static final String[] ARMOR_PIECES = {"helmet", "chestplate", "leggings", "boots"};
    private static final ArmorType[] ARMOR_TYPES = {ArmorType.HELMET, ArmorType.CHESTPLATE, ArmorType.LEGGINGS, ArmorType.BOOTS};

    private record ToolStats(double fromBonus, double bonus, int durability, float speed) {
    }

    private record ArmorStats(int durabilityMultiplier, int defense, double toughness, double knockbackResistance, ArmorType type) {
    }

    @Override
    public void onInitialize() {
        List<JsonObject> tables = loadTables();
        DefaultItemComponentEvents.MODIFY.register(context -> {
            int tools = 0;
            int armor = 0;
            for (JsonObject table : tables) {
                String ns = table.get("namespace").getAsString();
                if (!FabricLoader.getInstance().isModLoaded(ns)) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> e : table.getAsJsonObject("tools").entrySet()) {
                    JsonObject m = e.getValue().getAsJsonObject();
                    ToolStats stats = new ToolStats(m.get("from_bonus").getAsDouble(), m.get("bonus").getAsDouble(),
                        m.get("durability").getAsInt(), m.get("speed").getAsFloat());
                    for (Item item : items(ns, e.getKey(), m)) {
                        boolean sword = BuiltInRegistries.ITEM.getKey(item).getPath().endsWith("_sword");
                        context.modify(item, builder -> applyTool(builder, item, stats, sword));
                        tools++;
                    }
                }
                for (Map.Entry<String, JsonElement> e : table.getAsJsonObject("armor").entrySet()) {
                    JsonObject m = e.getValue().getAsJsonObject();
                    JsonArray defense = m.getAsJsonArray("defense");
                    for (int i = 0; i < ARMOR_PIECES.length; i++) {
                        Item item = item(ns + ":" + e.getKey() + "_" + ARMOR_PIECES[i]);
                        if (item == null) {
                            continue;
                        }
                        ArmorStats stats = new ArmorStats(m.get("durability_multiplier").getAsInt(), defense.get(i).getAsInt(),
                            m.get("toughness").getAsDouble(), m.get("knockback_resistance").getAsDouble(), ARMOR_TYPES[i]);
                        context.modify(item, builder -> applyArmor(builder, item, stats));
                        armor++;
                    }
                }
            }
            LOG.info("Rescaled {} tools and {} armor pieces", tools, armor);
        });
    }

    private static void applyTool(DataComponentMap.Builder builder, Item item, ToolStats stats, boolean sword) {
        DataComponentMap current = item.components();
        builder.set(DataComponents.MAX_DAMAGE, stats.durability());
        ItemAttributeModifiers modifiers = current.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers != null) {
            List<ItemAttributeModifiers.Entry> entries = new ArrayList<>();
            boolean found = false;
            for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
                if (entry.matches(Attributes.ATTACK_DAMAGE, BASE_ATTACK_DAMAGE)) {
                    AttributeModifier old = entry.modifier();
                    entry = new ItemAttributeModifiers.Entry(entry.attribute(),
                        new AttributeModifier(old.id(), old.amount() + stats.bonus() - stats.fromBonus(), old.operation()),
                        entry.slot(), entry.display());
                    found = true;
                }
                entries.add(entry);
            }
            if (!found && stats.bonus() != stats.fromBonus()) {
                // Aerial Hell leaves the modifier out when a tool's damage works out to 0 (its weakest hoes)
                entries.add(new ItemAttributeModifiers.Entry(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE,
                    stats.bonus() - stats.fromBonus(), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND));
            }
            builder.set(DataComponents.ATTRIBUTE_MODIFIERS, new ItemAttributeModifiers(entries));
        }
        Tool tool = current.get(DataComponents.TOOL);
        if (tool != null && !sword) {
            List<Tool.Rule> rules = new ArrayList<>();
            for (Tool.Rule rule : tool.rules()) {
                if (rule.speed().isPresent() && rule.correctForDrops().orElse(false)) {
                    rule = new Tool.Rule(rule.blocks(), Optional.of(stats.speed()), rule.correctForDrops());
                }
                rules.add(rule);
            }
            builder.set(DataComponents.TOOL, new Tool(rules, tool.defaultMiningSpeed(), tool.damagePerBlock(), tool.canDestroyBlocksInCreative()));
        }
    }

    private static void applyArmor(DataComponentMap.Builder builder, Item item, ArmorStats stats) {
        DataComponentMap current = item.components();
        builder.set(DataComponents.MAX_DAMAGE, stats.type().getDurability(stats.durabilityMultiplier()));
        ItemAttributeModifiers modifiers = current.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        EquipmentSlotGroup slot = EquipmentSlotGroup.bySlot(stats.type().getSlot());
        Identifier id = Identifier.withDefaultNamespace("armor." + stats.type().getName());
        ItemAttributeModifiers.Builder rebuilt = ItemAttributeModifiers.builder();
        rebuilt.add(Attributes.ARMOR, new AttributeModifier(id, stats.defense(), AttributeModifier.Operation.ADD_VALUE), slot);
        rebuilt.add(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(id, stats.toughness(), AttributeModifier.Operation.ADD_VALUE), slot);
        if (stats.knockbackResistance() > 0) {
            rebuilt.add(Attributes.KNOCKBACK_RESISTANCE, new AttributeModifier(id, stats.knockbackResistance(), AttributeModifier.Operation.ADD_VALUE), slot);
        }
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute() == Attributes.ARMOR || entry.attribute() == Attributes.ARMOR_TOUGHNESS
                || entry.attribute() == Attributes.KNOCKBACK_RESISTANCE) {
                continue; // replaced above
            }
            rebuilt.add(entry.attribute(), entry.modifier(), entry.slot());
        }
        builder.set(DataComponents.ATTRIBUTE_MODIFIERS, rebuilt.build());
    }

    private static List<Item> items(String ns, String material, JsonObject m) {
        List<Item> out = new ArrayList<>();
        if (m.has("item_ids")) {
            m.getAsJsonArray("item_ids").forEach(id -> addIfPresent(out, id.getAsString()));
        }
        if (m.has("items")) {
            m.getAsJsonArray("items").forEach(type -> addIfPresent(out, ns + ":" + material + "_" + type.getAsString()));
        }
        return out;
    }

    private static void addIfPresent(List<Item> out, String id) {
        Item item = item(id);
        if (item != null) {
            out.add(item);
        }
    }

    private static Item item(String id) {
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));
        if (item.isEmpty()) {
            LOG.warn("{} isn't registered; skipped", id);
        }
        return item.orElse(null);
    }

    private static List<JsonObject> loadTables() {
        List<JsonObject> tables = new ArrayList<>();
        Path dir = FabricLoader.getInstance().getModContainer("gameoverse_dimension_gear").orElseThrow()
            .findPath("gameoverse_dimension_gear/tables").orElseThrow();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    tables.add(JsonParser.parseReader(reader).getAsJsonObject());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't read gear tables", e);
        }
        LOG.info("Loaded {} gear table(s)", tables.size());
        return tables;
    }
}
