package me.waterarchery.littournaments.guis;

import me.waterarchery.litlibs.LitLibs;
import me.waterarchery.litlibs.configuration.ConfigManager;
import me.waterarchery.litlibs.libs.adventure.text.Component;
import me.waterarchery.litlibs.libs.gui.builder.item.ItemBuilder;
import me.waterarchery.litlibs.libs.gui.guis.BaseGui;
import me.waterarchery.litlibs.libs.gui.guis.Gui;
import me.waterarchery.litlibs.libs.gui.guis.GuiItem;
import me.waterarchery.littournaments.LitTournaments;
import me.waterarchery.littournaments.handlers.*;
import me.waterarchery.littournaments.models.Tournament;
import me.waterarchery.littournaments.models.TournamentPlayer;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class TournamentGUI {

    private static final NumberFormat numberFormat = NumberFormat.getInstance(Locale.US);

    public static BaseGui of(Player player) {
        GUIHandler guiHandler = GUIHandler.getInstance();
        ConfigManager manager = FileHandler.getTournamentMenu();
        LitLibs libs = LitTournaments.getLitLibs();
        
        if (libs == null) {
            return null;
        }
        
        FileConfiguration yml = manager.getYml();
        PlayerHandler playerHandler = PlayerHandler.getInstance();
        TournamentHandler tournamentHandler = TournamentHandler.getInstance();
        TournamentPlayer tournamentPlayer = playerHandler.getPlayer(player.getUniqueId());

        String title = guiHandler.getMenuTitle(manager);
        int size = guiHandler.getMenuSize(manager);
        boolean disableRightClick = LitTournaments.getInstance().getConfig().getBoolean("DisableLeaderboardWithRightClick", false);

        Gui gui = Gui.gui()
                .title(Component.text(title))
                .rows(size)
                .disableAllInteractions()
                .create();

        // Filling GUI
        guiHandler.fillGUI(yml, gui, manager);

        // Adding decoration items into GUI
        guiHandler.decorateGUI(yml, gui, manager);

        // Adding tournament items into GUI
        for (String tournamentName : Objects.requireNonNull(yml.getConfigurationSection("Items")).getKeys(false)) {
            Tournament tournament = tournamentHandler.getTournament(tournamentName);
            if (tournament == null) {
                libs.getLogger().error(String.format(
                        "You used %s in the tournament menu but there is no tournament that has its id", tournamentName));
                continue;
            }

            ItemStack itemStack = guiHandler.craftItemStack(manager, tournamentName, "Items", null);
            int slot = yml.getInt("Items." + tournamentName + ".Slot");

            if (!tournament.isActive()) {
                itemStack.setType(Material.BARRIER);
            }

            boolean hideAttributes = yml.getBoolean("Items." + tournamentName + ".HideAttributes");
            parseLore(itemStack, tournamentPlayer, tournament);

            boolean glowItemIfJoined = tournament.getYamlConfiguration().getBoolean("GlowItemIfJoined", true);

            GuiItem guiItem = ItemBuilder.from(itemStack)
                    .flags(ItemFlag.HIDE_ATTRIBUTES)
                    .glow(glowItemIfJoined && tournamentPlayer.isRegistered(tournament))
                    .asGuiItem(event -> {
                        if (event.getClick() == ClickType.RIGHT && !disableRightClick) {
                            player.closeInventory();
                            LeaderboardGUI.openMenu(player, tournament, true);
                        }
                        else  {
                            if (tournamentPlayer.isRegistered(tournament)) {
                                libs.getMessageHandler().sendLangMessage(player, "AlreadyJoined");
                                libs.getSoundHandler().sendSound(player, "Sounds.AlreadyJoined");
                                player.closeInventory();
                            }
                            else {
                                tournamentPlayer.join(tournament);
                                libs.getSoundHandler().sendSound(player, "Sounds.SuccessfullyJoined");
                                libs.getMessageHandler().sendLangMessage(player, "SuccessfullyRegistered");
                                gui.update();
                            }
                        }
                    });

            gui.setItem(slot, guiItem);
        }

        return gui;
    }

    private static void parseLore(ItemStack itemStack, TournamentPlayer tournamentPlayer, Tournament tournament) {
        ValueHandler valueHandler = ValueHandler.getInstance();
        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta != null && itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();

            for (String part : Objects.requireNonNull(itemMeta.getLore())) {
                String position = valueHandler.getPlayerPosition(tournamentPlayer, tournament);
                String score = valueHandler.getPlayerScore(tournamentPlayer, tournament);
                String remainingTime = valueHandler.getRemainingTime(tournament);
                
                part = part.replace("%position%", position != null ? position : "Carregando...")
                        .replace("%stat%", score != null ? score : "0")
                        .replace("%remaining_time%", remainingTime != null ? remainingTime : "Carregando...");

                List<String> otherPlaceholders = getPlaceholders(part);

                for (String placeholder : otherPlaceholders) {
                    if (placeholder.contains("leader_score_formatted_")) {
                        int pos = Integer.parseInt(placeholder
                                .replace("leader_score_formatted_", "")
                                .replace("%", ""));

                        String formattedScore = numberFormat.format(valueHandler.getPlayerScoreWithPosition(pos, tournament));
                        part = part.replace(placeholder, formattedScore != null ? formattedScore : "0");
                    }
                    else if (placeholder.contains("leader_score_")) {
                        int pos = Integer.parseInt(placeholder
                                .replace("leader_score_", "")
                                .replace("%", ""));

                        String leaderScore = String.valueOf(valueHandler.getPlayerScoreWithPosition(pos, tournament));
                        part = part.replace(placeholder, leaderScore != null ? leaderScore : "0");
                    }
                    if (placeholder.contains("leader_name_")) {
                        int pos = Integer.parseInt(placeholder
                                .replace("leader_name_", "")
                                .replace("%", ""));

                        String playerName = valueHandler.getPlayerNameWithPosition(pos, tournament);
                        part = part.replace(placeholder, playerName != null ? playerName : "Carregando...");
                    }
                }

                lore.add(part);
            }

            itemMeta.setLore(lore);
            itemStack.setItemMeta(itemMeta);
        }
    }

    private static List<String> getPlaceholders(String part) {
        List<String> placeholders = new ArrayList<>();
        boolean found = false;
        StringBuilder placeholder = new StringBuilder();

        for (char c : part.toCharArray()) {
            if (c == '%') {
                if (found) {
                    placeholder.append(c);
                    placeholders.add(placeholder.toString());
                    found = false;
                    placeholder = new StringBuilder();
                }
                else {
                    found = true;
                }
            }

            if (!found) continue;
            placeholder.append(c);
        }

        return placeholders;
    }

}
