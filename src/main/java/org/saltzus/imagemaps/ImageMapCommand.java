package org.saltzus.imagemaps;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.Material;
import org.bukkit.Bukkit;

import java.util.Map;

public class ImageMapCommand implements CommandExecutor {

    private final ImageMapsPlugin plugin;

    public ImageMapCommand(ImageMapsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7Usage: /imagemap <create <url>|reload|remove <id>|clearcache [id]|list>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cSeul un joueur peut recevoir une carte.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /imagemap create <url>");
                    return true;
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
                return true;
            }
            case "reload" -> {
                plugin.getImageConfig().load();
                sender.sendMessage("§adata.yml rechargé (" + plugin.getImageConfig().all().size() + " image(s)).");
                return true;
            }
            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /imagemap remove <id>");
                    return true;
                }
                int id = parseIdOrWarn(sender, args[1]);
                if (id < 0) return true;
                plugin.getImageConfig().remove(id);
                plugin.getImageCache().clearMemory(id);
                plugin.getImageCache().clearDisk(id);
                sender.sendMessage("§aEntrée #" + id + " supprimée.");
                return true;
            }
            case "clearcache" -> {
                if (args.length >= 2) {
                    int id = parseIdOrWarn(sender, args[1]);
                    if (id < 0) return true;
                    plugin.getImageCache().clearMemory(id);
                    plugin.getImageCache().clearDisk(id);
                    sender.sendMessage("§aCache vidé pour #" + id + ". Il sera re-téléchargé au prochain affichage.");
                } else {
                    plugin.getImageCache().clearAll();
                    sender.sendMessage("§aCache entièrement vidé. Toutes les images seront re-téléchargées.");
                }
                return true;
            }
            case "list" -> {
                sender.sendMessage("§7--- Cartes-images (" + plugin.getImageConfig().all().size() + ") ---");
                for (Map.Entry<Integer, String> e : plugin.getImageConfig().all().entrySet()) {
                    sender.sendMessage("§f#" + e.getKey() + " §7-> " + e.getValue());
                }
                return true;
            }
            default -> {
                sender.sendMessage("§7Usage: /imagemap <create <url>|reload|remove <id>|clearcache [id]|list>");
                return true;
            }
        }
    }

    private int parseIdOrWarn(CommandSender sender, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cId invalide : " + raw);
            return -1;
        }
    }
}
