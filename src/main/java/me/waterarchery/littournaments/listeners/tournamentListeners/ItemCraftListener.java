package me.waterarchery.littournaments.listeners.tournamentListeners;

import me.waterarchery.littournaments.handlers.PointHandler;
import me.waterarchery.littournaments.handlers.TournamentHandler;
import me.waterarchery.littournaments.models.Tournament;
import me.waterarchery.littournaments.models.tournaments.ItemCraftTournament;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ItemCraftListener implements Listener {

    @EventHandler (priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemCraft(CraftItemEvent event) {
        PointHandler pointHandler = PointHandler.getInstance();
        TournamentHandler tournamentHandler = TournamentHandler.getInstance();

        if (event.getWhoClicked() instanceof Player player) {
            ItemStack itemStack = event.getCurrentItem();
            World world = player.getWorld();
            if (itemStack == null) return;
            
            // Calcula a quantidade real craftada
            int points = calculateCraftedAmount(event);
            
            List<Tournament> tournaments = tournamentHandler.getTournaments(ItemCraftTournament.class);
            for (Tournament tournament : tournaments) {
                pointHandler.addPoint(player.getUniqueId(), tournament, world.getName(), itemStack.getType().name(), points);
            }
        }
    }
    
    /**
     * Calcula a quantidade real de itens craftados, considerando shift+click
     * @param event O evento de craft
     * @return A quantidade real craftada
     */
    private int calculateCraftedAmount(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();
        if (result == null) return 1;
        
        // Quantidade base do item resultante
        int baseAmount = result.getAmount();
        
        // Se não é shift+click, retorna a quantidade normal
        if (!event.isShiftClick()) {
            return baseAmount;
        }
        
        // Para shift+click, calcula a quantidade máxima possível baseada nos ingredientes
        int maxCrafts = calculateMaxCraftsFromIngredients(event);
        
        // Retorna a quantidade máxima possível (limitada pelo inventário)
        return Math.min(maxCrafts * baseAmount, getMaxStackSize(result.getType()));
    }
    
    /**
     * Calcula quantas vezes o jogador pode craftar baseado nos ingredientes disponíveis
     * @param event O evento de craft
     * @return O número máximo de crafts possíveis
     */
    private int calculateMaxCraftsFromIngredients(CraftItemEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null) {
            // Fallback para receitas não reconhecidas
            return calculateSimpleMaxCrafts(event);
        }
        
        // Tenta obter informações detalhadas da receita
        Map<org.bukkit.Material, Integer> requiredIngredients = getRequiredIngredientsFromRecipe(recipe);
        if (requiredIngredients.isEmpty()) {
            // Fallback se não conseguir obter ingredientes da receita
            return calculateSimpleMaxCrafts(event);
        }
        
        // Mapeia os ingredientes disponíveis no crafting table
        Map<org.bukkit.Material, Integer> availableIngredients = getAvailableIngredients(event);
        
        // Calcula o máximo de crafts baseado no ingrediente mais limitante
        int maxCrafts = Integer.MAX_VALUE;
        for (Map.Entry<org.bukkit.Material, Integer> entry : requiredIngredients.entrySet()) {
            org.bukkit.Material material = entry.getKey();
            int required = entry.getValue();
            int available = availableIngredients.getOrDefault(material, 0);
            
            if (required > 0) {
                int possibleCrafts = available / required;
                maxCrafts = Math.min(maxCrafts, possibleCrafts);
            }
        }
        
        return maxCrafts == Integer.MAX_VALUE ? 1 : maxCrafts;
    }
    
    /**
     * Método fallback para calcular crafts quando não há informações detalhadas da receita
     * @param event O evento de craft
     * @return O número máximo de crafts possíveis
     */
    private int calculateSimpleMaxCrafts(CraftItemEvent event) {
        // Mapeia os ingredientes disponíveis no crafting table
        Map<org.bukkit.Material, Integer> availableIngredients = new HashMap<>();
        
        // Verifica cada slot do crafting table
        for (int i = 0; i < 9; i++) {
            ItemStack item = event.getInventory().getItem(i);
            if (item != null && !item.getType().isAir()) {
                org.bukkit.Material material = item.getType();
                availableIngredients.put(material, availableIngredients.getOrDefault(material, 0) + item.getAmount());
            }
        }
        
        // Para receitas simples, assume que cada ingrediente é usado uma vez
        // Esta é uma implementação simplificada que funciona para a maioria das receitas
        int maxCrafts = Integer.MAX_VALUE;
        for (Integer amount : availableIngredients.values()) {
            maxCrafts = Math.min(maxCrafts, amount);
        }
        
        return maxCrafts == Integer.MAX_VALUE ? 1 : maxCrafts;
    }
    
    /**
     * Tenta obter os ingredientes necessários da receita
     * @param recipe A receita
     * @return Mapa de material para quantidade necessária
     */
    private Map<org.bukkit.Material, Integer> getRequiredIngredientsFromRecipe(Recipe recipe) {
        Map<org.bukkit.Material, Integer> ingredients = new HashMap<>();
        
        try {
            // Tenta usar reflection para acessar informações da receita
            // Isso pode não funcionar em todas as versões do Bukkit
            if (recipe.getClass().getSimpleName().contains("Shaped")) {
                // Para receitas shaped, tenta obter ingredientes únicos
                ingredients = getShapedRecipeIngredients(recipe);
            } else if (recipe.getClass().getSimpleName().contains("Shapeless")) {
                // Para receitas shapeless, tenta obter todos os ingredientes
                ingredients = getShapelessRecipeIngredients(recipe);
            }
        } catch (Exception e) {
            // Se falhar, retorna mapa vazio para usar fallback
            return new HashMap<>();
        }
        
        return ingredients;
    }
    
    /**
     * Obtém ingredientes de receitas shaped (pode falhar em algumas versões)
     * @param recipe A receita shaped
     * @return Mapa de ingredientes
     */
    private Map<org.bukkit.Material, Integer> getShapedRecipeIngredients(Recipe recipe) {
        Map<org.bukkit.Material, Integer> ingredients = new HashMap<>();
        
        try {
            // Tenta usar reflection para acessar getIngredientMap()
            java.lang.reflect.Method getIngredientMap = recipe.getClass().getMethod("getIngredientMap");
            @SuppressWarnings("unchecked")
            Map<Character, ItemStack> ingredientMap = (Map<Character, ItemStack>) getIngredientMap.invoke(recipe);
            
            for (ItemStack item : ingredientMap.values()) {
                if (item != null && !item.getType().isAir()) {
                    ingredients.put(item.getType(), ingredients.getOrDefault(item.getType(), 0) + 1);
                }
            }
        } catch (Exception e) {
            // Se reflection falhar, retorna mapa vazio
        }
        
        return ingredients;
    }
    
    /**
     * Obtém ingredientes de receitas shapeless (pode falhar em algumas versões)
     * @param recipe A receita shapeless
     * @return Mapa de ingredientes
     */
    private Map<org.bukkit.Material, Integer> getShapelessRecipeIngredients(Recipe recipe) {
        Map<org.bukkit.Material, Integer> ingredients = new HashMap<>();
        
        try {
            // Tenta usar reflection para acessar getIngredientList()
            java.lang.reflect.Method getIngredientList = recipe.getClass().getMethod("getIngredientList");
            @SuppressWarnings("unchecked")
            List<ItemStack> ingredientList = (List<ItemStack>) getIngredientList.invoke(recipe);
            
            for (ItemStack item : ingredientList) {
                if (item != null && !item.getType().isAir()) {
                    ingredients.put(item.getType(), ingredients.getOrDefault(item.getType(), 0) + 1);
                }
            }
        } catch (Exception e) {
            // Se reflection falhar, retorna mapa vazio
        }
        
        return ingredients;
    }
    
    /**
     * Obtém os ingredientes disponíveis no inventário de crafting
     * @param event O evento de craft
     * @return Mapa de material para quantidade disponível
     */
    private Map<org.bukkit.Material, Integer> getAvailableIngredients(CraftItemEvent event) {
        Map<org.bukkit.Material, Integer> ingredients = new HashMap<>();
        
        // Verifica cada slot do crafting table
        for (int i = 0; i < 9; i++) {
            ItemStack item = event.getInventory().getItem(i);
            if (item != null && !item.getType().isAir()) {
                org.bukkit.Material material = item.getType();
                ingredients.put(material, ingredients.getOrDefault(material, 0) + item.getAmount());
            }
        }
        
        return ingredients;
    }
    
    /**
     * Obtém o tamanho máximo da stack para um material
     * @param material O material
     * @return O tamanho máximo da stack
     */
    private int getMaxStackSize(org.bukkit.Material material) {
        return material.getMaxStackSize();
    }

}
