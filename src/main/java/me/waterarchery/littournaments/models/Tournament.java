package me.waterarchery.littournaments.models;


import lombok.Getter;
import me.waterarchery.littournaments.LitTournaments;
import me.waterarchery.littournaments.api.events.TournamentEndEvent;
import me.waterarchery.littournaments.api.events.TournamentStartEvent;
import me.waterarchery.littournaments.database.Database;
import me.waterarchery.littournaments.handlers.FileHandler;
import me.waterarchery.littournaments.handlers.PlayerHandler;
import me.waterarchery.littournaments.handlers.TournamentHandler;
import me.waterarchery.littournaments.handlers.WebhookHandler;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

@Getter
public class Tournament {

    private final String identifier;
    private final YamlConfiguration yamlConfiguration;
    private final boolean shouldRestartAfterFinished;
    private boolean isActive;
    private final String timePeriod;
    private final String coolName;
    private BukkitTask finishTask;
    private JoinChecker joinChecker;
    private ActionChecker actionChecker;
    private TournamentLeaderboard leaderboard;

    public Tournament(String identifier, YamlConfiguration yamlConfiguration) {
        this.identifier = identifier;
        this.yamlConfiguration = yamlConfiguration;

        this.isActive = yamlConfiguration.getBoolean("Active");
        this.timePeriod = yamlConfiguration.getString("TimePeriod");
        this.shouldRestartAfterFinished = yamlConfiguration.getBoolean("RestartAfterFinished", true);

        this.coolName = yamlConfiguration.getString("CoolName", identifier);

        load();
    }

    public void load() {
        joinChecker = new JoinChecker(yamlConfiguration, this);
        actionChecker = new ActionChecker(yamlConfiguration, this);
        leaderboard = new TournamentLeaderboard(this);

        if (isActive)
            startFinishTask();
    }

    public boolean checkWorldEnabled(String worldName) {
        if (actionChecker.getWorldWhitelist().contains(worldName)) return true;
        else {
            if (actionChecker.getWorldWhitelist().contains("*")) {
                return !actionChecker.getWorldBlacklist().contains(worldName);
            }

            return false;
        }
    }

    public boolean checkActionAllowed(String actionName) {
        if (actionChecker.getActionWhitelist().contains(actionName)) return true;
        else {
            if (actionChecker.getActionWhitelist().contains("*")) {
                return !actionChecker.getActionBlacklist().contains(actionName);
            }

            return false;
        }
    }

    public LocalDateTime getFinishTime() {
        LocalDate now = LocalDate.now();

        if (timePeriod.equalsIgnoreCase("daily")) {
            return now.atTime(23,59, 59);
        }
        else if (timePeriod.equalsIgnoreCase("weekly")) {
            LocalDate endOfWeekDate = now.with(DayOfWeek.SUNDAY);
            LocalTime endOfDayTime = LocalTime.of(23, 59, 59);

            return LocalDateTime.of(endOfWeekDate, endOfDayTime);
        }
        else if (timePeriod.equalsIgnoreCase("monthly")) {
            int lastDayOfMonth = now.lengthOfMonth();
            return now.withDayOfMonth(lastDayOfMonth).atTime(23,59, 59);
        }

        return null;
    }

    public Duration getRemainingTime() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime finishTime = getFinishTime();
        return Duration.between(now, finishTime);
    }

    public void startFinishTask() {
        stopFinishTask();

        LocalDateTime finishTime = getFinishTime();
        LocalDateTime now = LocalDateTime.now();
        Duration remaining = Duration.between(now, finishTime);
        long inTicks = remaining.getSeconds() * 20L;

        finishTask = Bukkit.getScheduler().runTaskLater(LitTournaments.getInstance(), this::finishTournament, inTicks);
    }

    public void stopFinishTask() {
        if (finishTask != null) finishTask.cancel();
    }

    /**
     * Define se esta instância é a "mestre" responsável por entregar recompensas e
     * operar mudanças globais no banco (multi-instância).
     * Usa a chave EnableRewards da config como "flag" de master.
     */
    private boolean isRewardMaster() {
        return FileHandler.getConfig().getYml().getBoolean("EnableRewards", true);
    }

    public void finishTournament() {
        Tournament tournament = this;
        Database database = LitTournaments.getDatabase();
        TournamentHandler tournamentHandler = TournamentHandler.getInstance();
        PlayerHandler playerHandler = PlayerHandler.getInstance();
        
        // Executar comandos de finalização do torneio (local, todas as instâncias)
        tournamentHandler.parseConditionalCommand(tournament, "TOURNAMENT_END");

        // Disparar evento de finalização do torneio (local)
        TournamentEndEvent tournamentEndEvent = new TournamentEndEvent(tournament);
        Bukkit.getPluginManager().callEvent(tournamentEndEvent);
        
        // Webhook pode duplicar em multi-instância; enviar apenas na instância mestre
        if (isRewardMaster()) {
            WebhookHandler.sendWebhook(tournament);
        }
        
        int waitTime = FileHandler.getConfig().getYml().getInt("WaitTimeBetweenTournaments");
        boolean enableRewards = isRewardMaster();

        if (enableRewards) {
            // Somente a instância mestre realiza o reload do DB e entrega de recompensas
            LitTournaments.getInstance().getLogger().info("[LitTournaments] Starting database reload for tournament: " + tournament.getIdentifier());

            CompletableFuture.runAsync(database.getReloadTournamentRunnable(tournament))
                    .thenRun(() -> {
                        // Após o reload ser concluído, executar recompensas na thread principal do Bukkit
                        Bukkit.getScheduler().runTask(LitTournaments.getInstance(), () -> {
                            try {
                                LitTournaments.getInstance().getLogger().info("[LitTournaments] Database reload completed for tournament: " + tournament.getIdentifier());

                                LitTournaments.getInstance().getLogger().info("[LitTournaments] Executing rewards for tournament: " + tournament.getIdentifier());
                                tournamentHandler.parseRewards(tournament);

                                // Executar limpeza e finalização após as recompensas
                                finalizeTournamentCleanup(tournament, database, playerHandler, waitTime);

                            } catch (Exception e) {
                                LitTournaments.getInstance().getLogger().severe("[LitTournaments] ERROR executing rewards for tournament " + tournament.getIdentifier() + ": " + e.getMessage());
                                e.printStackTrace();

                                // Mesmo com erro nas recompensas, executar limpeza
                                finalizeTournamentCleanup(tournament, database, playerHandler, waitTime);
                            }
                        });
                    });
        } else {
            // Nas instâncias não-mestras, pular reload/recompensas e apenas finalizar localmente
            LitTournaments.getInstance().getLogger().info("[LitTournaments] Rewards disabled on this instance. Skipping DB reload and rewards for tournament: " + tournament.getIdentifier());
            finalizeTournamentCleanup(tournament, database, playerHandler, waitTime);
        }
    }
    
    /**
     * Executa a limpeza final do torneio após as recompensas
     */
    private void finalizeTournamentCleanup(Tournament tournament, Database database, PlayerHandler playerHandler, int waitTime) {
        // Marcar torneio como inativo
        this.isActive = false;
        
        // Salvar estado no arquivo
        File file = new File(LitTournaments.getInstance().getDataFolder(), "/tournaments/" + this.identifier + ".yml");
        yamlConfiguration.set("Active", false);
        try {
            yamlConfiguration.save(file);
        } catch (IOException e) {
            LitTournaments.getInstance().getLogger().log(Level.WARNING, "Error saving tournament file: " + this.identifier, e);
        }
        
        // Limpar dados do torneio no DB apenas na instância mestre
        if (isRewardMaster()) {
            database.clearTournament(tournament);
        } else {
            LitTournaments.getInstance().getLogger().info("[LitTournaments] Skipping DB clear on non-reward instance for: " + this.identifier);
        }
        playerHandler.clearPlayerValues(tournament);
        getLeaderboard().clear();
        stopFinishTask();
        
        // Se deve reiniciar após finalizar, agendar o reinício
        if (shouldRestartAfterFinished) {
            Bukkit.getScheduler().runTaskLater(LitTournaments.getInstance(), this::startTournament, waitTime * 20L);
        }
    }

    public void startTournament() {
        TournamentHandler tournamentHandler = TournamentHandler.getInstance();
        Database database = LitTournaments.getDatabase();
        PlayerHandler playerHandler = PlayerHandler.getInstance();

        startFinishTask();
        if (isRewardMaster()) {
            database.clearTournament(this);
        } else {
            LitTournaments.getInstance().getLogger().info("[LitTournaments] Skipping DB clear on start (non-reward instance) for: " + this.identifier);
        }
        playerHandler.clearPlayerValues(this);
        getLeaderboard().clear();
        
        // Limpar cache de recompensas para este torneio
        tournamentHandler.clearRewardCache(this.identifier);

        TournamentStartEvent tournamentStartEvent = new TournamentStartEvent(this);
        Bukkit.getPluginManager().callEvent(tournamentStartEvent);
        tournamentHandler.parseConditionalCommand(this, "TOURNAMENT_START");
        this.isActive = true;

        File file = new File(LitTournaments.getInstance().getDataFolder(), "/tournaments/" + this.identifier + ".yml");
        yamlConfiguration.set("Active", true);
        try {
            yamlConfiguration.save(file);
        } catch (IOException e) {
            LitTournaments.getInstance().getLogger().log(Level.WARNING, "Error saving tournament file: " + this.identifier, e);
        }
    }

}
