package org.blog.duelPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent; // ★ 추가
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class DuelPlugin extends JavaPlugin implements Listener {

    // === 메뉴 타이틀 ===
    private static final String MENU_TITLE_SELECT_TARGET = "§a듀얼 상대 선택";
    private static final String MENU_TITLE_CLASS = "§a병과 선택";
    private static final String MENU_TITLE_CONFIRM = "§a듀얼 요청 (승인/거절)";

    // 병과 슬롯(27칸 인벤 중앙)
    private static final int SLOT_SWORD = 11;
    private static final int SLOT_BOW   = 13;
    private static final int SLOT_LANCE = 15;

    // 확인 메뉴 슬롯
    private static final int SLOT_ACCEPT = 11;
    private static final int SLOT_DECLINE = 15;

    // 말/상태 추적
    private final Set<UUID> duelHorseIds = new HashSet<>();
    private final Map<UUID, UUID> pendingTarget = new HashMap<>();
    private final Map<UUID, CombatClass> pendingClass = new HashMap<>();
    private final Map<UUID, UUID> pendingRequestByTarget = new HashMap<>();

    // 진행 중 듀얼
    private final Map<UUID, Duel> activeDuels = new HashMap<>();
    private final Set<UUID> pendingRespawn = new HashSet<>();
    private final Set<UUID> forcedDeath = new HashSet<>();

    private enum CombatClass { SWORD, BOW, LANCE }

    // 시작 아이템
    private static final Material START_ITEM_MATERIAL = Material.NETHER_STAR;
    private static final String START_ITEM_NAME = "§b팀 점령전 시작";

    private ItemStack makeStartItem() {
        ItemStack it = new ItemStack(START_ITEM_MATERIAL);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(START_ITEM_NAME);
        it.setItemMeta(m);
        return it;
    }
    private void giveStartItem(Player p) {
        p.getInventory().setItem(8, makeStartItem());
        p.updateInventory();
    }
    private void removeStartItem(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isStartItem(it)) p.getInventory().setItem(i, null);
        }
        p.updateInventory();
    }
    private boolean isStartItem(ItemStack it) {
        if (it == null || it.getType() != START_ITEM_MATERIAL) return false;
        if (!it.hasItemMeta()) return false;
        return START_ITEM_NAME.equals(it.getItemMeta().getDisplayName());
    }
    private boolean isHoldingStartItem(Player p) { return isStartItem(p.getInventory().getItemInMainHand()); }

    // 아레나 화살 정리 반경
    private static final double ARENA_CLEAR_RADIUS = 40.0;

    // Arena 시스템
    private static final class Spawn {
        final int x, y, z;
        Spawn(int x, int y, int z) { this.x=x; this.y=y; this.z=z; }
        Location to(World w) { return new Location(w, x, y, z); }
    }
    private static final class Arena {
        final Spawn p1Spawn, p2Spawn;
        boolean occupied = false;
        Arena(Spawn a, Spawn b){ this.p1Spawn=a; this.p2Spawn=b; }
    }
    private final List<Arena> arenas = new ArrayList<>();

    private static final class Duel {
        final UUID p1, p2;
        final CombatClass picked;
        final Set<UUID> horses = new HashSet<>();
        int actionbarTaskId = -1;
        boolean ending = false;
        int arenaIndex = -1;

        Duel(UUID p1, UUID p2, CombatClass picked) {
            this.p1 = p1; this.p2 = p2; this.picked = picked;
        }
        boolean involves(UUID u) { return p1.equals(u) || p2.equals(u); }
        UUID other(UUID u) { return p1.equals(u) ? p2 : p1; }
    }

    // 말 힐 상태값
    private final Map<UUID, Long> horseHealCooldown = new HashMap<>();
    private final Map<UUID, Integer> horseHealTaskId = new HashMap<>();

    private static final double HEAL_PER_WHEAT = 9.0;   // 4.5칸
    private static final double HEAL_STEP = 0.5;
    private static final long   HEAL_PERIOD_TICKS = 5L;

    private void clearProjectilesInArena(int arenaIdx, World world) {
        if (arenaIdx < 0 || arenaIdx >= arenas.size() || world == null) return;
        Arena a = arenas.get(arenaIdx);

        Location c1 = a.p1Spawn.to(world);
        Location c2 = a.p2Spawn.to(world);

        for (org.bukkit.entity.Entity ent : world.getEntities()) {
            if (ent instanceof org.bukkit.entity.AbstractArrow arrow) {
                if (arrow.getLocation().distanceSquared(c1) <= ARENA_CLEAR_RADIUS * ARENA_CLEAR_RADIUS
                        || arrow.getLocation().distanceSquared(c2) <= ARENA_CLEAR_RADIUS * ARENA_CLEAR_RADIUS) {
                    arrow.remove();
                }
            }
        }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        arenas.add(new Arena(new Spawn(132, -56, 57),   new Spawn(132, -54, 151)));
        arenas.add(new Arena(new Spawn(-116, -59, 106), new Spawn(-116, -57, 200)));
        arenas.add(new Arena(new Spawn(102, -59, 172),  new Spawn(102, -57, 266)));
        arenas.add(new Arena(new Spawn(86, -59, -326),  new Spawn(86, -57, -232)));

        getLogger().info("DuelPlugin Enabled! Arenas=" + arenas.size());
    }

    public Map<UUID, Duel> getActiveDuels() { return activeDuels; }


    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();

        // 쉬프트(웅크리기) 안 누르면: 정상 F 동작 허용 (방패/오프핸드 교체)
        if (!player.isSneaking()) return;

        // 쉬프트+F일 때만 우리 메뉴 로직 실행
        event.setCancelled(true);

        if (activeDuels.containsKey(player.getUniqueId())) {
            player.sendMessage("§c듀얼 진행 중에는 메뉴를 열 수 없습니다.");
            return;
        }
        openTargetMenu(player);
    }


    // 메뉴
    private void openTargetMenu(Player opener) {
        Inventory menu = Bukkit.createInventory(null, 54, MENU_TITLE_SELECT_TARGET);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(opener)) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(p);
            meta.setDisplayName("§a" + p.getName());
            head.setItemMeta(meta);
            menu.addItem(head);
        }
        opener.openInventory(menu);
    }

    private void openClassMenu(Player opener, Player target) {
        pendingTarget.put(opener.getUniqueId(), target.getUniqueId());
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE_CLASS);
        inv.setItem(SLOT_SWORD, icon(Material.DIAMOND_SWORD, "§a검","§e클릭하여 선택"));
        inv.setItem(SLOT_BOW,   icon(Material.BOW, "§a활","§e클릭하여 선택"));

        ItemStack lance = new ItemStack(Material.IRON_AXE);
        ItemMeta lm = lance.getItemMeta();
        lm.setDisplayName("§a창");
        lm.setLore(Arrays.asList("§e클릭하여 선택"));
        lance.setItemMeta(lm);
        lance.addUnsafeEnchantment(Enchantment.DAMAGE_UNDEAD, 3);
        lance.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 3);
        inv.setItem(SLOT_LANCE, lance);
        opener.openInventory(inv);
    }

    private void openConfirmMenu(Player target, Player opener, CombatClass picked) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE_CONFIRM);
        String cls = switch (picked) { case SWORD -> "검"; case BOW -> "활"; case LANCE -> "창"; };
        ItemStack accept = icon(Material.LIME_CONCRETE, "§a듀얼 승인",
                "§7신청자: §a" + opener.getName(), "§7병과: §f" + cls, "§e클릭하여 듀얼을 시작합니다");
        ItemStack decline = icon(Material.RED_CONCRETE, "§c듀얼 거절",
                "§7신청자: §a" + opener.getName(), "§e클릭하여 거절합니다");
        inv.setItem(SLOT_ACCEPT, accept);
        inv.setItem(SLOT_DECLINE, decline);
        target.openInventory(inv);
    }

    private ItemStack icon(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (lore != null && lore.length > 0) m.setLore(Arrays.asList(lore));
        it.setItemMeta(m);
        return it;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (title == null) return;

        if (title.equals(MENU_TITLE_SELECT_TARGET) || title.equals(MENU_TITLE_CLASS) || title.equals(MENU_TITLE_CONFIRM)) {
            event.setCancelled(true);
        } else return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        if (title.equals(MENU_TITLE_SELECT_TARGET)) {
            String targetName = clicked.getItemMeta().getDisplayName().replace("§a", "");
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null || !target.isOnline()) { player.sendMessage("§c플레이어를 찾을 수 없습니다."); return; }
            if (activeDuels.containsKey(player.getUniqueId()) || activeDuels.containsKey(target.getUniqueId())) {
                player.sendMessage("§c상대 또는 당신이 이미 듀얼 중입니다."); return;
            }
            openClassMenu(player, target);
            return;
        }

        if (title.equals(MENU_TITLE_CLASS)) {
            UUID targetId = pendingTarget.get(player.getUniqueId());
            if (targetId == null) { player.sendMessage("§c상대 정보를 찾을 수 없습니다."); player.closeInventory(); return; }
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) { player.sendMessage("§c상대가 오프라인입니다."); player.closeInventory(); return; }

            int slot = event.getRawSlot();
            CombatClass picked = switch (slot) {
                case SLOT_SWORD -> CombatClass.SWORD;
                case SLOT_BOW   -> CombatClass.BOW;
                case SLOT_LANCE -> CombatClass.LANCE;
                default -> null;
            };
            if (picked == null) return;

            pendingClass.put(player.getUniqueId(), picked);
            pendingRequestByTarget.put(target.getUniqueId(), player.getUniqueId());

            player.closeInventory();
            openConfirmMenu(target, player, picked);
            player.sendMessage("§a" + target.getName() + "§7에게 듀얼 요청을 보냈습니다. (승인/거절 대기)");
            target.sendMessage("§a" + player.getName() + "§7로부터 듀얼 요청이 도착했습니다.");
            return;
        }

        if (title.equals(MENU_TITLE_CONFIRM)) {
            UUID targetId = player.getUniqueId();
            UUID openerId = pendingRequestByTarget.get(targetId);
            if (openerId == null) { player.sendMessage("§c유효하지 않은 요청입니다."); player.closeInventory(); return; }
            Player opener = Bukkit.getPlayer(openerId);
            if (opener == null || !opener.isOnline()) { player.sendMessage("§c신청자가 오프라인입니다. 요청이 취소되었습니다."); cleanupPending(openerId, targetId); player.closeInventory(); return; }
            CombatClass picked = pendingClass.get(openerId);
            if (picked == null) { player.sendMessage("§c요청 정보가 손상되었습니다."); cleanupPending(openerId, targetId); player.closeInventory(); return; }

            int slot = event.getRawSlot();
            if (slot == SLOT_ACCEPT) {
                if (activeDuels.containsKey(openerId) || activeDuels.containsKey(targetId)) {
                    player.sendMessage("§c이미 듀얼 중인 플레이어가 있습니다. 요청이 취소됩니다.");
                    if (opener.isOnline()) opener.sendMessage("§c요청이 취소되었습니다. (이미 듀얼 중)");
                    cleanupPending(openerId, targetId);
                    player.closeInventory();
                    return;
                }
                player.closeInventory();
                cleanupPending(openerId, targetId);
                startDuel(opener, player, picked);
            } else if (slot == SLOT_DECLINE) {
                player.closeInventory();
                cleanupPending(openerId, targetId);
                player.sendMessage("§c듀얼 요청을 거절했습니다.");
                if (opener.isOnline()) opener.sendMessage("§c상대가 듀얼 요청을 거절했습니다.");
            }
        }
    }

    private void cleanupPending(UUID openerId, UUID targetId) {
        pendingRequestByTarget.remove(targetId);
        pendingClass.remove(openerId);
        pendingTarget.remove(openerId);
    }

    // 듀얼 로직
    private void startDuel(Player p1, Player p2, CombatClass picked) {
        int arenaIdx = findFreeArenaIndex();
        if (arenaIdx < 0) {
            Component red = Component.text("사용할 수 있는 듀얼장이 없습니다.", NamedTextColor.RED);
            p1.sendMessage(red); p2.sendMessage(red); return;
        }
        Arena arena = arenas.get(arenaIdx);
        arena.occupied = true;

        World w1 = p1.getWorld(); World w2 = p2.getWorld();
        p1.teleport(arena.p1Spawn.to(w1));
        p2.teleport(arena.p2Spawn.to(w2));

        p1.setGameMode(GameMode.ADVENTURE);
        p2.setGameMode(GameMode.ADVENTURE);

        Horse h1 = null, h2 = null;
        if (picked == CombatClass.SWORD || picked == CombatClass.BOW) {
            h1 = spawnDuelHorse(p1); h2 = spawnDuelHorse(p2);
        }

        setupGearCommon(p1); setupGearCommon(p2);
        giveClassLoadout(p1, picked); giveClassLoadout(p2, picked);

        if (h1 != null) h1.addPassenger(p1);
        if (h2 != null) h2.addPassenger(p2);

        Duel duel = new Duel(p1.getUniqueId(), p2.getUniqueId(), picked);
        duel.arenaIndex = arenaIdx;
        if (h1 != null) { duel.horses.add(h1.getUniqueId()); duelHorseIds.add(h1.getUniqueId()); }
        if (h2 != null) { duel.horses.add(h2.getUniqueId()); duelHorseIds.add(h2.getUniqueId()); }
        activeDuels.put(p1.getUniqueId(), duel);
        activeDuels.put(p2.getUniqueId(), duel);

        String cls = switch (picked) { case SWORD -> "검"; case BOW -> "활"; case LANCE -> "창"; };
        p1.sendMessage("§a" + p2.getName() + "와 듀얼이 시작되었습니다! §7(병과: " + cls + ", 아레나 #" + (arenaIdx+1) + ")");
        p2.sendMessage("§a" + p1.getName() + "와 듀얼이 시작되었습니다! §7(병과: " + cls + ", 아레나 #" + (arenaIdx+1) + ")");

        if (picked == CombatClass.SWORD) {
            int taskId = getServer().getScheduler().runTaskTimer(this, () -> {
                Player a = Bukkit.getPlayer(duel.p1);
                Player b = Bukkit.getPlayer(duel.p2);
                Component c = Component.text("검기마 데미지 10% 증가").color(NamedTextColor.AQUA);
                if (a != null && a.isOnline() && a.getVehicle() instanceof AbstractHorse h && !h.isDead()) a.sendActionBar(c);
                if (b != null && b.isOnline() && b.getVehicle() instanceof AbstractHorse h && !h.isDead()) b.sendActionBar(c);
            }, 0L, 40L).getTaskId();
            duel.actionbarTaskId = taskId;
        }
    }

    private int findFreeArenaIndex() {
        for (int i = 0; i < arenas.size(); i++) if (!arenas.get(i).occupied) return i;
        return -1;
    }

    private void setupGearCommon(Player p) {
        p.getInventory().clear();
        p.getInventory().setHelmet(ench(new ItemStack(Material.DIAMOND_HELMET), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setChestplate(ench(new ItemStack(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setLeggings(ench(new ItemStack(Material.DIAMOND_LEGGINGS), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setBoots(ench(new ItemStack(Material.DIAMOND_BOOTS), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
    }

    private void giveClassLoadout(Player p, CombatClass picked) {
        p.getInventory().setItem(8, new ItemStack(Material.GOLDEN_APPLE, 8));
        p.getInventory().setItem(7, new ItemStack(Material.COOKED_BEEF, 32));

        switch (picked) {
            case SWORD -> {
                ItemStack sword = ench(new ItemStack(Material.DIAMOND_SWORD), Enchantment.DAMAGE_ALL, 2);
                p.getInventory().setItem(0, sword);
                ItemStack axe = (ench(new ItemStack(Material.DIAMOND_AXE), Enchantment.DAMAGE_UNDEAD, 1));
                p.getInventory().setItem(1, axe);
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
                p.getInventory().setItem(2, new ItemStack(Material.HAY_BLOCK, 64));
            }
            case BOW -> {
                ItemStack bow = ench(new ItemStack(Material.BOW), Enchantment.ARROW_DAMAGE, 3);
                bow.addEnchantment(Enchantment.ARROW_INFINITE, 1);
                p.getInventory().setItem(0, bow);
                p.getInventory().setItem(9, new ItemStack(Material.ARROW, 1));
                p.getInventory().setItem(1, new ItemStack(Material.HAY_BLOCK, 64));
            }
            case LANCE -> {
                ItemStack lance = new ItemStack(Material.NETHERITE_AXE);
                ItemMeta meta = lance.getItemMeta();
                meta.setDisplayName("§a네더라이트 창");
                meta.addEnchant(Enchantment.DAMAGE_ALL, 3, true);
                meta.setCustomModelData(3);
                lance.setItemMeta(meta);
                p.getInventory().setItem(0, lance);
                p.getInventory().setItem(7, new ItemStack(Material.COOKED_BEEF, 32));
                p.getInventory().setItem(8, new ItemStack(Material.GOLDEN_APPLE, 8));
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
            }
        }
    }

    private Horse spawnDuelHorse(Player player) {
        Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
        horse.setOwner(player);
        horse.setTamed(true);
        horse.setAdult();
        horse.setMaxHealth(30.0);
        horse.setHealth(30.0);
        AttributeInstance speed = horse.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0.30);
        horse.setJumpStrength(1.0);
        ItemStack armor = new ItemStack(Material.DIAMOND_HORSE_ARMOR);
        ItemMeta meta = armor.getItemMeta();
        meta.addEnchant(Enchantment.PROTECTION_ENVIRONMENTAL, 1, true);
        armor.setItemMeta(meta);
        horse.getInventory().setArmor(armor);
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        return horse;
    }

    // 전투/종료
    @EventHandler(ignoreCancelled = true)
    public void onDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)) return;
        Duel duel = activeDuels.get(attacker.getUniqueId());
        if (duel == null || duel.picked != CombatClass.SWORD) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!duel.involves(victim.getUniqueId())) return;
        if (attacker.getVehicle() instanceof AbstractHorse h && !h.isDead()) {
            e.setDamage(e.getDamage() * 1.10);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        if (forcedDeath.remove(dead.getUniqueId())) {
            e.getDrops().clear(); e.setDroppedExp(0); return;
        }
        Duel duel = activeDuels.get(dead.getUniqueId());
        if (duel == null) return;
        if (duel.ending) { e.getDrops().clear(); e.setDroppedExp(0); return; }
        duel.ending = true;

        e.getDrops().clear(); e.setDroppedExp(0);
        pendingRespawn.add(dead.getUniqueId());

        UUID otherId = duel.other(dead.getUniqueId());
        Player other = Bukkit.getPlayer(otherId);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (other != null && other.isOnline() && other.getHealth() > 0.0) {
                forcedDeath.add(other.getUniqueId());
                other.setHealth(0.0);
                pendingRespawn.add(other.getUniqueId());
            }
            endDuel(duel);
        }, 40L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!pendingRespawn.remove(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(this, () -> {
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.getInventory().setItemInOffHand(null);
            Bukkit.getScheduler().runTaskLater(this, () -> giveStartItem(p), 1L);
        });
    }

    @EventHandler
    public void onHorseDeath(EntityDeathEvent e) {
        if (e.getEntity().getType() == EntityType.HORSE) {
            UUID id = e.getEntity().getUniqueId();
            if (duelHorseIds.remove(id)) {
                e.getDrops().clear(); e.setDroppedExp(0);
            }
        }
    }

    private void endDuel(Duel duel) {
        if (duel.actionbarTaskId != -1) getServer().getScheduler().cancelTask(duel.actionbarTaskId);

        for (UUID uid : List.of(duel.p1, duel.p2)) {
            Integer tid = horseHealTaskId.remove(uid);
            if (tid != null) getServer().getScheduler().cancelTask(tid);
            horseHealCooldown.remove(uid);
        }

        for (UUID hid : duel.horses) {
            for (World w : Bukkit.getWorlds()) {
                var ent = w.getEntity(hid);
                if (ent instanceof Horse h && !h.isDead()) h.remove();
            }
            duelHorseIds.remove(hid);
        }

        Player pWorldRef = Bukkit.getPlayer(duel.p1);
        World world = (pWorldRef != null) ? pWorldRef.getWorld()
                : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
        clearProjectilesInArena(duel.arenaIndex, world);

        if (duel.arenaIndex >= 0 && duel.arenaIndex < arenas.size()) arenas.get(duel.arenaIndex).occupied = false;

        Player p1 = Bukkit.getPlayer(duel.p1);
        Player p2 = Bukkit.getPlayer(duel.p2);
        Component msg = Component.text("듀얼이 종료되었습니다.", NamedTextColor.GRAY);
        if (p1 != null && p1.isOnline()) p1.sendMessage(msg);
        if (p2 != null && p2.isOnline()) p2.sendMessage(msg);

        activeDuels.remove(duel.p1);
        activeDuels.remove(duel.p2);
    }

    private ItemStack ench(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(ench, level, true);
        item.setItemMeta(meta);
        return item;
    }

    // ── ★ 말에게 먹이 주기 차단 로직 ─────────────────────────
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof AbstractHorse)) return;

        Player p = e.getPlayer();
        ItemStack used = (e.getHand() == EquipmentSlot.OFF_HAND)
                ? p.getInventory().getItemInOffHand()
                : p.getInventory().getItemInMainHand();
        if (used == null) return;

        Material t = used.getType();

        // 1) 말에게 황금사과(일반/마법) 절대 못 먹이게
        if (t == Material.GOLDEN_APPLE || t == Material.ENCHANTED_GOLDEN_APPLE) {
            e.setCancelled(true);
            return;
        }

        // 2) 밀짚은 "말을 타고 있을 때"만 (내린 상태면 말에게 먹이는 기본 행위 차단)
        if (t == Material.HAY_BLOCK && !(p.getVehicle() instanceof AbstractHorse)) {
            e.setCancelled(true);
        }
    }
    // ───────────────────────────────────────────────────────

    // 밀짚 힐(게이지만, 텍스트 없음) — 탑승 중일 때만 동작
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onWheatUseWhileRiding(PlayerInteractEvent e) {
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack used = (e.getHand() == EquipmentSlot.OFF_HAND)
                ? p.getInventory().getItemInOffHand()
                : p.getInventory().getItemInMainHand();

        if (used == null || used.getType() != Material.HAY_BLOCK) return;
        if (!(p.getVehicle() instanceof AbstractHorse horse)) return; // ★ 탑승 중일 때만

        Duel duel = activeDuels.get(p.getUniqueId());
        if (duel != null && duel.picked == CombatClass.LANCE) return;

        double max = horse.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        double now = horse.getHealth();
        if (now >= max - 1e-9) return;

        int cdSec = (duel == null) ? 10 : switch (duel.picked) {
            case SWORD -> 10;
            case BOW   -> 20;
            case LANCE -> 10;
        };

        long nowMs = System.currentTimeMillis();
        long until = horseHealCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (nowMs < until) { e.setCancelled(true); return; }

        e.setCancelled(true);

        if (e.getHand() == EquipmentSlot.OFF_HAND) {
            if (used.getAmount() <= 1) p.getInventory().setItemInOffHand(null);
            else used.setAmount(used.getAmount() - 1);
        } else {
            if (used.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
            else used.setAmount(used.getAmount() - 1);
        }
        p.updateInventory();

        horseHealCooldown.put(p.getUniqueId(), nowMs + cdSec * 1000L);
        p.setCooldown(Material.HAY_BLOCK, cdSec * 20);

        if (horseHealTaskId.containsKey(p.getUniqueId())) return;

        final double targetHeal = Math.min(HEAL_PER_WHEAT, max - now);

        BukkitRunnable task = new BukkitRunnable() {
            double healed = 0.0;
            @Override public void run() {
                if (!horse.isValid() || p.getVehicle() != horse) { stop(); return; }
                double step = Math.min(HEAL_STEP, targetHeal - healed);
                if (step <= 0.0) { stop(); return; }
                double cur = horse.getHealth();
                double next = Math.min(cur + step, max);
                if (next <= cur) { stop(); return; }
                horse.setHealth(next);
                healed += (next - cur);
                if (healed >= targetHeal - 1e-6) stop();
            }
            private void stop() {
                Integer tid = horseHealTaskId.remove(p.getUniqueId());
                if (tid != null) { /* no-op */ }
                cancel();
            }
        };
        int id = task.runTaskTimer(this, 0L, HEAL_PERIOD_TICKS).getTaskId();
        horseHealTaskId.put(p.getUniqueId(), id);
    }
}
