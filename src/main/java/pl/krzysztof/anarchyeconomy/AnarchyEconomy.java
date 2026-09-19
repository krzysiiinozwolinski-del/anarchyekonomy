package pl.krzysztof.anarchyeconomy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

public final class AnarchyEconomy extends JavaPlugin implements Listener, CommandExecutor {
    private Connection db;
    private final String SHOP_TITLE = "§2Sklep";
    private final String MARKET_TITLE = "§6Rynek graczy";

    @Override public void onEnable() {
        saveDefaultConfig();
        try { openDb(); createTables(); }
        catch (Exception e) { getLogger().severe("Blad bazy: " + e.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }
        for (String c : List.of("bal","pay","baltop","sell","sellall","shop","market"))
            Objects.requireNonNull(getCommand(c)).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AnarchyEconomy wlaczony.");
    }

    @Override public void onDisable() {
        try { if (db != null) db.close(); } catch (SQLException ignored) {}
    }

    private void openDb() throws SQLException {
        File f = new File(getDataFolder(), "economy.db");
        db = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
    }

    private void createTables() throws SQLException {
        try (Statement s = db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS balances(uuid TEXT PRIMARY KEY, name TEXT, balance REAL NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS market(id INTEGER PRIMARY KEY AUTOINCREMENT, seller_uuid TEXT NOT NULL, seller_name TEXT NOT NULL, material TEXT NOT NULL, amount INTEGER NOT NULL, price REAL NOT NULL)");
        }
    }

    private double balance(UUID id, String name) {
        try (PreparedStatement p = db.prepareStatement("SELECT balance FROM balances WHERE uuid=?")) {
            p.setString(1,id.toString());
            try (ResultSet r=p.executeQuery()) { if(r.next()) return r.getDouble(1); }
            double start=getConfig().getDouble("starting-balance",1000);
            setBalance(id,name,start); return start;
        } catch(SQLException e){ throw new RuntimeException(e); }
    }

    private void setBalance(UUID id,String name,double amount) {
        try (PreparedStatement p=db.prepareStatement("INSERT INTO balances(uuid,name,balance) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,balance=excluded.balance")) {
            p.setString(1,id.toString()); p.setString(2,name); p.setDouble(3,Math.max(0,amount)); p.executeUpdate();
        } catch(SQLException e){ throw new RuntimeException(e); }
    }

    private void add(UUID id,String name,double amount){ setBalance(id,name,balance(id,name)+amount); }
    private String money(double n){ return String.format(Locale.US,"$%,.2f",n); }
    private double buy(Material m){ return getConfig().getDouble("shop."+m.name()+".buy",-1); }
    private double sell(Material m){ return getConfig().getDouble("shop."+m.name()+".sell",-1); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd=command.getName().toLowerCase(Locale.ROOT);
        if(cmd.equals("baltop")) { baltop(sender); return true; }
        if(!(sender instanceof Player p)){ sender.sendMessage("Ta komenda jest dla gracza."); return true; }

        switch(cmd){
            case "bal" -> p.sendMessage("§aSaldo: §f"+money(balance(p.getUniqueId(),p.getName())));
            case "pay" -> pay(p,args);
            case "sell" -> sellHand(p,args);
            case "sellall" -> sellAll(p);
            case "shop" -> openShop(p);
            case "market" -> { if(args.length>=2 && args[0].equalsIgnoreCase("sell")) marketSell(p,args[1]); else openMarket(p); }
        }
        return true;
    }

    private void pay(Player p,String[] a){
        if(a.length<2){ p.sendMessage("§cUzycie: /pay <gracz> <kwota>"); return; }
        Player t=Bukkit.getPlayerExact(a[0]); if(t==null||t.equals(p)){ p.sendMessage("§cNieprawidlowy gracz."); return; }
        try{
            double x=Double.parseDouble(a[1]); if(x<=0||!Double.isFinite(x)) throw new Exception();
            double b=balance(p.getUniqueId(),p.getName()); if(b<x){p.sendMessage("§cBrak srodkow.");return;}
            setBalance(p.getUniqueId(),p.getName(),b-x); add(t.getUniqueId(),t.getName(),x);
            p.sendMessage("§aWyslano "+money(x)+" do "+t.getName()+"."); t.sendMessage("§aOtrzymales "+money(x)+" od "+p.getName()+".");
        }catch(Exception e){p.sendMessage("§cPodaj poprawna kwote.");}
    }

    private void baltop(CommandSender s){
        try(PreparedStatement p=db.prepareStatement("SELECT name,balance FROM balances ORDER BY balance DESC LIMIT 10");ResultSet r=p.executeQuery()){
            s.sendMessage("§6§lTOP 10"); int i=1; while(r.next()) s.sendMessage("§e"+i+++". §f"+r.getString(1)+" §7- §a"+money(r.getDouble(2)));
        }catch(SQLException e){s.sendMessage("§cBlad bazy.");}
    }

    private void sellHand(Player p,String[] a){
        ItemStack it=p.getInventory().getItemInMainHand(); if(it.getType().isAir()){p.sendMessage("§cTrzymaj przedmiot.");return;}
        double unit=sell(it.getType()); if(unit<0){p.sendMessage("§cTego przedmiotu nie mozna sprzedac.");return;}
        int n=it.getAmount();
        if(a.length>0&&!a[0].equalsIgnoreCase("all")) try{n=Math.min(n,Integer.parseInt(a[0]));}catch(Exception e){p.sendMessage("§cNieprawidlowa ilosc.");return;}
        if(n<=0)return; it.setAmount(it.getAmount()-n); add(p.getUniqueId(),p.getName(),unit*n);
        p.sendMessage("§aSprzedano §f"+n+"x "+it.getType()+" §aza "+money(unit*n)+".");
    }

    private void sellAll(Player p){
        double total=0; int count=0;
        for(ItemStack it:p.getInventory().getStorageContents()){
            if(it==null||it.getType().isAir())continue; double u=sell(it.getType()); if(u<0)continue;
            total+=u*it.getAmount(); count+=it.getAmount(); it.setAmount(0);
        }
        if(total==0){p.sendMessage("§cNie masz przedmiotow do sprzedazy.");return;}
        add(p.getUniqueId(),p.getName(),total); p.sendMessage("§aSprzedano "+count+" przedmiotow za §f"+money(total)+".");
    }

    private void openShop(Player p){
        Inventory inv=Bukkit.createInventory(null,54,SHOP_TITLE); ConfigurationSection sec=getConfig().getConfigurationSection("shop"); if(sec==null)return;
        for(String key:sec.getKeys(false)){
            Material m=Material.matchMaterial(key); if(m==null)continue; ItemStack it=new ItemStack(m); ItemMeta meta=it.getItemMeta();
            meta.setDisplayName("§a"+m.name()); meta.setLore(List.of("§7Kupno: §a"+money(buy(m)),"§7Sprzedaz: §e"+money(sell(m)),"","§fLPM: kup 1","§fPPM: sprzedaj 1")); it.setItemMeta(meta); inv.addItem(it);
        } p.openInventory(inv);
    }

    private void marketSell(Player p,String raw){
        ItemStack hand=p.getInventory().getItemInMainHand(); if(hand.getType().isAir()){p.sendMessage("§cTrzymaj przedmiot.");return;}
        try{
            double price=Double.parseDouble(raw); if(price<=0||!Double.isFinite(price))throw new Exception();
            try(PreparedStatement q=db.prepareStatement("INSERT INTO market(seller_uuid,seller_name,material,amount,price) VALUES(?,?,?,?,?)")){
                q.setString(1,p.getUniqueId().toString());q.setString(2,p.getName());q.setString(3,hand.getType().name());q.setInt(4,hand.getAmount());q.setDouble(5,price);q.executeUpdate();
            }
            p.getInventory().setItemInMainHand(null); p.sendMessage("§aWystawiono przedmiot za "+money(price)+".");
        }catch(Exception e){p.sendMessage("§cUzycie: /market sell <cena>");}
    }

    private void openMarket(Player p){
        Inventory inv=Bukkit.createInventory(null,54,MARKET_TITLE);
        try(PreparedStatement q=db.prepareStatement("SELECT id,seller_name,material,amount,price FROM market ORDER BY id DESC LIMIT 54");ResultSet r=q.executeQuery()){
            while(r.next()){
                Material m=Material.matchMaterial(r.getString("material")); if(m==null)continue;
                ItemStack it=new ItemStack(m,Math.min(r.getInt("amount"),m.getMaxStackSize())); ItemMeta meta=it.getItemMeta();
                meta.setDisplayName("§e"+m.name()+" §7x"+r.getInt("amount"));
                meta.setLore(List.of("§7Sprzedawca: §f"+r.getString("seller_name"),"§7Cena: §a"+money(r.getDouble("price")),"§8ID:"+r.getInt("id"),"","§fKliknij, aby kupic"));
                it.setItemMeta(meta); inv.addItem(it);
            }
        }catch(SQLException e){p.sendMessage("§cBlad rynku.");}
        p.openInventory(inv);
    }

    @EventHandler public void click(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p))return;
        String title=e.getView().getTitle();
        if(title.equals(SHOP_TITLE)){
            e.setCancelled(true); ItemStack it=e.getCurrentItem(); if(it==null||it.getType().isAir())return; Material m=it.getType();
            if(e.isLeftClick()){
                double price=buy(m), b=balance(p.getUniqueId(),p.getName()); if(price<0||b<price){p.sendMessage("§cBrak srodkow.");return;}
                setBalance(p.getUniqueId(),p.getName(),b-price); p.getInventory().addItem(new ItemStack(m)); p.sendMessage("§aKupiono 1x "+m+" za "+money(price)+".");
            } else if(e.isRightClick()){
                double price=sell(m); if(price<0||!p.getInventory().containsAtLeast(new ItemStack(m),1)){p.sendMessage("§cNie masz tego przedmiotu.");return;}
                p.getInventory().removeItem(new ItemStack(m)); add(p.getUniqueId(),p.getName(),price); p.sendMessage("§aSprzedano 1x "+m+" za "+money(price)+".");
            }
        } else if(title.equals(MARKET_TITLE)){
            e.setCancelled(true); ItemStack it=e.getCurrentItem(); if(it==null||!it.hasItemMeta()||it.getItemMeta().getLore()==null)return;
            int id=-1; for(String line:it.getItemMeta().getLore()) if(line.startsWith("§8ID:")) try{id=Integer.parseInt(line.substring(5));}catch(Exception ignored){}
            if(id>0) buyMarket(p,id);
        }
    }

    private void buyMarket(Player buyer,int id){
        try{
            db.setAutoCommit(false);
            String sellerUuid,sellerName,mat; int amount; double price;
            try(PreparedStatement q=db.prepareStatement("SELECT * FROM market WHERE id=?")){q.setInt(1,id);try(ResultSet r=q.executeQuery()){if(!r.next()){db.rollback();openMarket(buyer);return;}sellerUuid=r.getString("seller_uuid");sellerName=r.getString("seller_name");mat=r.getString("material");amount=r.getInt("amount");price=r.getDouble("price");}}
            if(sellerUuid.equals(buyer.getUniqueId().toString())){db.rollback();buyer.sendMessage("§cNie mozesz kupic swojej oferty.");return;}
            double b=balance(buyer.getUniqueId(),buyer.getName()); if(b<price){db.rollback();buyer.sendMessage("§cBrak srodkow.");return;}
            Material m=Material.matchMaterial(mat); if(m==null){db.rollback();return;}
            HashMap<Integer,ItemStack> left=buyer.getInventory().addItem(new ItemStack(m,amount)); if(!left.isEmpty()){ for(ItemStack x:left.values()) buyer.getInventory().removeItem(x); db.rollback(); buyer.sendMessage("§cBrak miejsca w ekwipunku."); return; }
            setBalance(buyer.getUniqueId(),buyer.getName(),b-price);
            UUID sid=UUID.fromString(sellerUuid); add(sid,sellerName,price);
            try(PreparedStatement d=db.prepareStatement("DELETE FROM market WHERE id=?")){d.setInt(1,id);d.executeUpdate();}
            db.commit(); buyer.sendMessage("§aKupiono "+amount+"x "+m+" za "+money(price)+"."); openMarket(buyer);
        }catch(Exception ex){try{db.rollback();}catch(Exception ignored){} buyer.sendMessage("§cNie udalo sie kupic oferty.");}
        finally{try{db.setAutoCommit(true);}catch(Exception ignored){}}
    }
}
