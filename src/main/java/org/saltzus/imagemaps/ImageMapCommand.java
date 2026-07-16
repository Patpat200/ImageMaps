package org.saltzus.imagemaps;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Commande /imagemap, enregistrée via la Lifecycle API de Paper
 * (voir ImageMapsPlugin#onEnable). BasicCommand est l'équivalent moderne
 * de l'ancien CommandExecutor pour les plugins paper-plugin.yml.
 */
public class ImageMapCommand implements BasicCommand {

    private final ImageMapsPlugin plugin;

    public ImageMapCommand(ImageMapsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();

        if (args.length == 0) {
            sender.sendMessage("§7Usage: /imagemap create <url>"
                    + (sender.hasPermission("imagemaps.admin")
                        ? "  §7(admin: reload|remove <id>|clearcache [id]|list)" : ""));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (!sender.hasPermission("imagemaps.create")) {
                    sender.sendMessage("§cTu n'as pas la permission de créer une carte-image.");
                    return;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cSeul un joueur peut recevoir une carte.");
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /imagemap create <url>");
                    return;
                }
                String url = args[1];

                MapView view = Bukkit.createMap(player.getWorld());
                view.getRenderers().forEach(view::removeRenderer);
                int id = view.getId();

                plugin.getImageConfig().put(id, url);
                view.addRenderer(new ImageMapRenderer(id, url, plugin.getImageCache()));
                view.setLocked(true);

                ItemStack item = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) item.getItemMeta();
                meta.setMapView(view);
                item.setItemMeta(meta);
                player.getInventory().addItem(item);

                sender.sendMessage("§aCarte-image créée avec l'id §f#" + id + "§a : " + url);
            }
            case "reload" -> {
                if (!sender.hasPermission("imagemaps.admin")) {
                    sender.sendMessage("§cTu n'as pas la permission de faire ça.");
                    return;
                }
                plugin.getImageConfig().load();
                sender.sendMessage("§adata.yml rechargé (" + plugin.getImageConfig().all().size() + " image(s)).");
            }
            case "remove" -> {
                if (!sender.hasPermission("imagemaps.admin")) {
                    sender.sendMessage("§cTu n'as pas la permission de supprimer une carte-image.");
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /imagemap remove <id>");
                    return;
                }
                int id = parseIdOrWarn(sender, args[1]);
                if (id < 0) return;
                plugin.getImageConfig().remove(id);
                plugin.getImageCache().clearMemory(id);
                plugin.getImageCache().clearDisk(id);
                sender.sendMessage("§aEntrée #" + id + " supprimée.");
            }
            case "clearcache" -> {
                if (!sender.hasPermission("imagemaps.admin")) {
                    sender.sendMessage("§cTu n'as pas la permission de faire ça.");
                    return;
                }
                if (args.length >= 2) {
                    int id = parseIdOrWarn(sender, args[1]);
                    if (id < 0) return;
                    plugin.getImageCache().clearMemory(id);
                    plugin.getImageCache().clearDisk(id);
                    sender.sendMessage("§aCache vidé pour #" + id + ". Il sera re-téléchargé au prochain affichage.");
                } else {
                    plugin.getImageCache().clearAll();
                    sender.sendMessage("§aCache entièrement vidé. Toutes les images seront re-téléchargées.");
                }
            }
            case "list" -> {
                if (!sender.hasPermission("imagemaps.admin")) {
                    sender.sendMessage("§cTu n'as pas la permission de faire ça.");
                    return;
                }
                sender.sendMessage("§7--- Cartes-images (" + plugin.getImageConfig().all().size() + ") ---");
                for (Map.Entry<Integer, String> e : plugin.getImageConfig().all().entrySet()) {
                    sender.sendMessage("§f#" + e.getKey() + " §7-> " + e.getValue());
                }
            }
            default -> sender.sendMessage("§7Usage: /imagemap create <url>");
        }
    }

    // Pas de permission() surchargée : /imagemap reste visible/exécutable
    // par tout le monde au niveau de la commande elle-même. Chaque
    // sous-commande vérifie sa propre permission ci-dessus (création
    // ouverte à tous via imagemaps.create, gestion réservée aux admins
    // via imagemaps.admin).

    private int parseIdOrWarn(CommandSender sender, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cId invalide : " + raw);
            return -1;
        }
    }
}
