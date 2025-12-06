package me.waterarchery.littournaments.handlers;

import lombok.Getter;
import me.waterarchery.litlibs.LitLibs;
import me.waterarchery.litlibs.logger.Logger;
import me.waterarchery.litlibs.utils.ChatUtils;
import me.waterarchery.littournaments.LitTournaments;
import me.waterarchery.littournaments.models.Tournament;
import me.waterarchery.littournaments.models.TournamentLeaderboard;
import me.waterarchery.littournaments.models.TournamentValue;
import me.waterarchery.littournaments.utils.ReflectionUtils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class TournamentHandler {

    private final List<Tournament> tournaments = new ArrayList<>();
    private final List<Class<Tournament>> tournamentClasses = new ArrayList<>();
    private static TournamentHandler instance;
    
    // Cache para evitar duplicação de recompensas
    private final Set<String> rewardCache = ConcurrentHashMap.newKeySet();

    public static TournamentHandler getInstance() {
        if (instance == null) instance = new TournamentHandler();
        return instance;
    }

    private TournamentHandler() { }

    public void reloadTournaments() {
        LitTournaments instance = LitTournaments.getInstance();
        File tournamentsFolder = new File(instance.getDataFolder(), "/tournaments/");

        File[] contents = tournamentsFolder.listFiles();
        if (contents == null) return;

        tournamentClasses.clear();
        tournamentClasses.addAll(ReflectionUtils.getTournamentClasses());

        for (File file : contents) {
            try {
                FileConfiguration yml = new YamlConfiguration();
                yml.load(file);

                String identifier = file.getName().split("\\.")[0];
                loadTournament(identifier, yml);
            } catch (IOException | InvalidConfigurationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void loadTournament(String identifier, FileConfiguration yml) {
        LitLibs litLibs = LitTournaments.getLitLibs();
        Logger logger = litLibs.getLogger();
        logger.log("Loading tournament: " + identifier);

        String classType = yml.getString("Objective");
        assert classType != null;
        classType = classType.replace("_", "");

        for (Class<Tournament> tournamentClass : tournamentClasses) {
            if (tournamentClass.getSimpleName().equalsIgnoreCase(classType)) {
                loadClass(tournamentClass, identifier, yml);
                return;
            }
        }

        logger.error("There is no tournament called: " + classType);
    }

    private void loadClass(Class<Tournament> tournamentClass, Object... args) {
        LitLibs libs = LitTournaments.getLitLibs();
        Logger logger = libs.getLogger();
        try {
            Class<?>[] argTypes = Arrays.stream(args)
                    .map(Object::getClass)
                    .toArray(Class<?>[]::new);

            Tournament tournament = tournamentClass.getDeclaredConstructor(argTypes).newInstance(args);
            logger.log(String.format("Tournament loaded: %s", tournament.getIdentifier()));
            tournaments.add(tournament);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }

    public <T extends Tournament> List<Tournament> getTournaments(Class<T> className) {
        List<Tournament> applicableTournaments = new ArrayList<>();

        for (Tournament tournament : tournaments) {
            if (tournament.getClass() == className) applicableTournaments.add(tournament);
        }

        return applicableTournaments;
    }

    public @Nullable Tournament getTournament(String tournamentName) {
        for (Tournament tournament : tournaments) {
            if (tournament.getIdentifier().equalsIgnoreCase(tournamentName)) return tournament;
        }

        return null;
    }

    public void parseRewards(Tournament tournament) {
        // Verificar se esta instância deve entregar recompensas
        boolean enableRewards = FileHandler.getConfig().getYml().getBoolean("EnableRewards", true);
        if (!enableRewards) {
            LitTournaments.getInstance().getLogger().info("[LitTournaments] Reward delivery disabled on this instance for tournament: " + tournament.getIdentifier());
            return;
        }
        
        YamlConfiguration yml = tournament.getYamlConfiguration();
        TournamentLeaderboard leaderboard = tournament.getLeaderboard();
        
        // Log TOP 3 do torneio
        logTournamentTop3(tournament, leaderboard);
        
        String tournamentId = tournament.getIdentifier();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

        for (String rawPos : Objects.requireNonNull(yml.getConfigurationSection("Rewards")).getKeys(false)) {
            List<String> rewards = yml.getStringList("Rewards." + rawPos);

            for (String reward : rewards) {
                int pos = Integer.parseInt(rawPos);
                TournamentValue value = leaderboard.getPlayer(pos).orElse(null);
                if (value != null) {
                    String name = value.getName();
                    
                    // Gerar chave única para o cache
                    String cacheKey = tournamentId + "_" + timestamp + "_" + name + "_" + pos + "_" + reward.hashCode();
                    
                    // Verificar se já foi processada
                    if (rewardCache.contains(cacheKey)) {
                        LitTournaments.getInstance().getLogger().info("[LitTournaments] Reward execution skipped (already in cache) - Tournament: " + tournamentId + ", Player: " + name + ", Position: " + pos);
                        continue;
                    }
                    
                    // Adicionar ao cache
                    rewardCache.add(cacheKey);
                    
                    // Executar recompensa
                    parseTournamentReward(reward, name, tournament, pos);
                }
            }
        }
    }
    
    /**
     * Log do TOP 3 do torneio
     */
    private void logTournamentTop3(Tournament tournament, TournamentLeaderboard leaderboard) {
        // Debug: Verificar se leaderboard tem dados
        int totalPlayers = leaderboard.getLeaderboard().size();
        LitTournaments.getInstance().getLogger().info("[LitTournaments] DEBUG: Tournament " + tournament.getIdentifier() + " has " + totalPlayers + " players in leaderboard");
        
        StringBuilder top3Log = new StringBuilder();
        top3Log.append("[LitTournaments] Tournament ").append(tournament.getIdentifier()).append(" finished! TOP 3: ");
        
        for (int i = 1; i <= 3; i++) {
            TournamentValue player = leaderboard.getPlayer(i).orElse(null);
            if (player != null) {
                String playerName = player.getName();
                long playerScore = player.getValue();
                
                // Debug: Log detalhado do jogador
                LitTournaments.getInstance().getLogger().info("[LitTournaments] DEBUG: Position " + i + " - UUID: " + player.getUuid() + ", Name: " + playerName + ", Score: " + playerScore);
                
                top3Log.append(i).append("º ").append(playerName).append(" (").append(playerScore).append(" points)");
                if (i < 3) top3Log.append(", ");
            } else {
                LitTournaments.getInstance().getLogger().info("[LitTournaments] DEBUG: Position " + i + " is empty (no player found)");
                top3Log.append(i).append("º N/A");
                if (i < 3) top3Log.append(", ");
            }
        }
        
        LitTournaments.getInstance().getLogger().info(top3Log.toString());
    }

    public void parseConditionalCommand(Tournament tournament, String condition) {
        YamlConfiguration yml = tournament.getYamlConfiguration();

        List<String> commands = yml.getStringList("ConditionalCommands." + condition);
        commands.forEach(command -> {
            TournamentLeaderboard leaderboard = tournament.getLeaderboard();
            String name = "None";
            if (command.contains("tournament_pos_1")) {
                TournamentValue value = leaderboard.getPlayer(1).orElse(null);
                if (value != null) name = value.getName();
                command = command.replace("%tournament_pos_1%", name);
            }
            else if (command.contains("tournament_pos_2")) {
                TournamentValue value = leaderboard.getPlayer(2).orElse(null);
                if (value != null) name = value.getName();
                command = command.replace("%tournament_pos_2%", name);
            }
            else if (command.contains("tournament_pos_3")) {
                TournamentValue value = leaderboard.getPlayer(3).orElse(null);
                if (value != null) name = value.getName();
                command = command.replace("%tournament_pos_3%", name);
            }
            else if (command.contains("tournament_pos_4")) {
                TournamentValue value = leaderboard.getPlayer(4).orElse(null);
                if (value != null) name = value.getName();
                command = command.replace("%tournament_pos_4%", name);
            }
            else if (command.contains("tournament_pos_5")) {
                TournamentValue value = leaderboard.getPlayer(5).orElse(null);
                if (value != null) name = value.getName();
                command = command.replace("%tournament_pos_5%", name);
            }
            parseTournamentReward(command, null);
        });
    }

    public void parseTournamentReward(String command, @Nullable String targetPlayer) {
        parseTournamentReward(command, targetPlayer, null, -1);
    }
    
    public void parseTournamentReward(String command, @Nullable String targetPlayer, @Nullable Tournament tournament, int position) {
        LitLibs libs = LitTournaments.getLitLibs();
        String originalCommand = command;

        if (command.startsWith("[MESSAGE]") && targetPlayer != null) {
            Player player = Bukkit.getPlayer(targetPlayer);
            if (player != null) {
                libs.getMessageHandler().sendMessage(player, command.replace("[MESSAGE] ", ""));
                logRewardExecution(tournament, targetPlayer, position, originalCommand);
            }
        }
        else if (command.startsWith("[BROADCAST]")) {
            String message = ChatUtils.colorizeLegacy(command.replace("[BROADCAST] ", ""));
            Bukkit.broadcastMessage(message);
            logRewardExecution(tournament, targetPlayer, position, originalCommand);
        }
        else if (command.startsWith("[COMMAND]")) {
            command = command.replace("[COMMAND] ", "");
            if (targetPlayer != null) command = command.replace("%player%", targetPlayer);

            String finalCommand = command;
            Bukkit.getScheduler().runTask(LitTournaments.getInstance(),
                    () -> Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), finalCommand));
            
            // Log com o comando após substituição das placeholders
            logRewardExecution(tournament, targetPlayer, position, "[COMMAND] " + finalCommand);
        }
    }
    
    /**
     * Log da execução de recompensa
     */
    private void logRewardExecution(@Nullable Tournament tournament, @Nullable String playerName, int position, String executedCommand) {
        if (tournament != null && playerName != null && position > 0) {
            LitTournaments.getInstance().getLogger().info(
                String.format("[LitTournaments] Reward executed - Tournament: %s, Player: %s, Position: %d, Command: %s", 
                    tournament.getIdentifier(), playerName, position, executedCommand)
            );
        }
    }
    
    /**
     * Limpa o cache de recompensas para um torneio específico
     */
    public void clearRewardCache(String tournamentId) {
        rewardCache.removeIf(key -> key.startsWith(tournamentId + "_"));
        LitTournaments.getInstance().getLogger().info("[LitTournaments] Reward cache cleared for tournament: " + tournamentId);
    }
    
    /**
     * Limpa todo o cache de recompensas
     */
    public void clearAllRewardCache() {
        int size = rewardCache.size();
        rewardCache.clear();
        LitTournaments.getInstance().getLogger().info("[LitTournaments] All reward cache cleared. Removed " + size + " entries.");
    }

}
