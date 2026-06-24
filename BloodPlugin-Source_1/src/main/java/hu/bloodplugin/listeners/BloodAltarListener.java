package hu.bloodplugin.listeners;

import hu.bloodplugin.BloodPlugin;
import hu.bloodplugin.items.BloodItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class BloodAltarListener implements Listener {

    private final BloodPlugin plugin;
    private final NamespacedKey ALTAR_KEY;

    // altar entity UUID -> floating item + holograms + task
    private final Map<UUID, Item> altarItems       = new HashMap<>();
    private final Map<UUID, List<ArmorStand>> altarHolograms = new HashMap<>();
    private final Map<UUID, BukkitTask> altarTasks = new HashMap<>();
    // altar entity UUID -> location
    private final Map<UUID, Location> altarLocations = new HashMap<>();

    public BloodAltarListener(BloodPlugin plugin) {
        this.plugin    = plugin;
        this.ALTAR_KEY = new NamespacedKey(plugin, "blood_altar");
    }

    // ─── /altarspawn command handled from command class, calls this ──
    public void spawnAltar(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;

        // Spawn an invisible, immovable ArmorStand as the "altar block"
        ArmorStand altar = (ArmorStand) world.spawnEntity(
                loc.clone().add(0.5, 0, 0.5), EntityType.ARMOR_STAND);
        altar.setVisible(false);
        altar.setGravity(false);
        altar.setInvulnerable(true);
        altar.setSmall(false);
        altar.setMarker(true);
        altar.setPersistent(true);
        altar.setAI(false);
        altar.getPersistentDataContainer().set(ALTAR_KEY, PersistentDataType.BYTE, (byte) 1);
        altar.customName(Component.text("Blood Altar", NamedTextColor.DARK_RED));
        altar.setCustomNameVisible(false);

        UUID uuid = altar.getUniqueId();
        altarLocations.put(uuid, loc);

        // Place barrier block so players can't walk through
        loc.getBlock().setType(Material.BARRIER);

        // Spawn display
        spawnDisplay(uuid, loc, BloodItems.createBloodMace(), false);
    }

    public boolean isAltar(Entity e) {
        if (!(e instanceof ArmorStand as)) return false;
        return as.getPersistentDataContainer().has(ALTAR_KEY, PersistentDataType.BYTE);
    }

    // ─── Right click on altar ArmorStand ──────────────────────────
    @EventHandler
    public void onInteractEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity entity = event.getRightClicked();
        if (!isAltar(entity)) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        UUID uuid = entity.getUniqueId();
        Location loc = altarLocations.getOrDefault(uuid, entity.getLocation());

        ItemStack held = player.getInventory().getItemInMainHand();

        // Op holding Blood Mace → set up hologram display
        if (player.isOp() && BloodItems.is(held, BloodItems.BLOOD_MACE_KEY)) {
            removeDisplay(uuid);
            spawnDisplay(uuid, loc, held.clone(), true);
            player.sendMessage(Component.text("✦ Altar recept hologram beállítva!", NamedTextColor.GOLD));
            return;
        }

        // Normal player → attempt craft
        attemptCraft(player, uuid, loc);
    }

    // ─── Craft ────────────────────────────────────────────────────
    private void attemptCraft(Player player, UUID altarUuid, Location loc) {
        if (hasItem(player, BloodItems.BLOOD_MACE_KEY)) {
            player.sendMessage(Component.text("Már van egy Blood Mace-ed!", NamedTextColor.RED));
            return;
        }
        if (!hasIngredients(player)) {
            player.sendMessage(Component.text(
                "§cKell: §78 Netherite Ingot §c+ §74 Vércsepp §c+ §71 Mace", NamedTextColor.RED));
            return;
        }

        consumeIngredients(player);
        player.getInventory().addItem(BloodItems.createBloodMace());

        loc.getWorld().spawnParticle(Particle.DUST, loc.clone().add(0.5, 1.5, 0.5),
                60, 0.6, 0.6, 0.6, new Particle.DustOptions(Color.RED, 2f));
        loc.getWorld().playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 0.7f);
        player.sendMessage(Component.text("☽ Blood Mace megalkotva! ☽", NamedTextColor.DARK_RED));
    }

    // ─── Display: floating item + hologram ───────────────────────
    private void spawnDisplay(UUID altarUuid, Location blockLoc, ItemStack displayItem, boolean showRecipe) {
        World world = blockLoc.getWorld();
        if (world == null) return;

        // Floating item
        Location itemLoc = blockLoc.clone().add(0.5, 1.4, 0.5);
        Item floatingItem = world.dropItem(itemLoc, displayItem);
        floatingItem.setPickupDelay(Integer.MAX_VALUE);
        floatingItem.setVelocity(new Vector(0, 0, 0));
        floatingItem.setGravity(false);
        floatingItem.setInvulnerable(true);
        floatingItem.setPersistent(true);
        altarItems.put(altarUuid, floatingItem);

        // Hologram lines
        List<String> lines = new ArrayList<>();
        lines.add("§4§l✦ Blood Altar ✦");
        if (showRecipe) {
            lines.add("§7Recept:");
            lines.add("§68 §7Netherite Ingot");
            lines.add("§64 §7Vércsepp");
            lines.add("§61 §7Mace");
            lines.add("§c§lJobb klikk a crafthoz");
        } else {
            lines.add("§7Recept:");
            lines.add("§68 §7Netherite Ingot");
            lines.add("§64 §7Vércsepp");
            lines.add("§61 §7Mace");
            lines.add("§c§lJobb klikk a crafthoz");
        }

        List<ArmorStand> stands = new ArrayList<>();
        double startY = 2.6;
        for (int i = 0; i < lines.size(); i++) {
            Location standLoc = blockLoc.clone().add(0.5, startY - (i * 0.28), 0.5);
            ArmorStand stand = (ArmorStand) world.spawnEntity(standLoc, EntityType.ARMOR_STAND);
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSmall(true);
            stand.setMarker(true);
            stand.setPersistent(true);
            stand.customName(Component.text(lines.get(i)));
            stand.setCustomNameVisible(true);
            stands.add(stand);
        }
        altarHolograms.put(altarUuid, stands);

        // Rotation + bob task
        final double[] angle = {0};
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (floatingItem.isDead()) { cancel(); return; }
                angle[0] = (angle[0] + 4) % 360;
                double rad    = Math.toRadians(angle[0]);
                double radius = 0.3;
                double bobY   = 1.4 + Math.sin(Math.toRadians(angle[0] * 2)) * 0.1;
                floatingItem.teleport(blockLoc.clone().add(0.5 + Math.cos(rad) * radius,
                        bobY, 0.5 + Math.sin(rad) * radius));
            }
        }.runTaskTimer(plugin, 0L, 2L);
        altarTasks.put(altarUuid, task);
    }

    private void removeDisplay(UUID uuid) {
        Item item = altarItems.remove(uuid);
        if (item != null) item.remove();
        List<ArmorStand> stands = altarHolograms.remove(uuid);
        if (stands != null) stands.forEach(ArmorStand::remove);
        BukkitTask task = altarTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    // ─── Ingredient helpers ───────────────────────────────────────
    private boolean hasIngredients(Player player) {
        int netherite = 0, bloodDrop = 0, mace = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            if (item.getType() == Material.NETHERITE_INGOT && !BloodItems.is(item, BloodItems.BLOOD_MACE_KEY))
                netherite += item.getAmount();
            if (BloodItems.is(item, BloodItems.BLOOD_DROP_KEY))
                bloodDrop += item.getAmount();
            if (item.getType() == Material.MACE && !BloodItems.is(item, BloodItems.BLOOD_MACE_KEY))
                mace += item.getAmount();
        }
        return netherite >= 8 && bloodDrop >= 4 && mace >= 1;
    }

    private void consumeIngredients(Player player) {
        removeAmount(player, Material.NETHERITE_INGOT, 8, null);
        removeAmount(player, null, 4, BloodItems.BLOOD_DROP_KEY);
        removeAmount(player, Material.MACE, 1, null);
    }

    private void removeAmount(Player player, Material mat, int amount, String bloodKey) {
        int remaining = amount;
        for (ItemStack item : player.getInventory().getContents()) {
            if (remaining <= 0) break;
            if (item == null) continue;
            if (bloodKey != null) {
                if (!BloodItems.is(item, bloodKey)) continue;
            } else {
                if (item.getType() != mat) continue;
                if (BloodItems.is(item, BloodItems.BLOOD_MACE_KEY)) continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - take);
            remaining -= take;
        }
    }

    private boolean hasItem(Player player, String key) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (BloodItems.is(item, key)) return true;
        }
        return false;
    }
}
