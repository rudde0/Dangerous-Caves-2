package me.imdanix.caves.util;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Materials {
    private static final Pattern TEXTURE_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    private static final Set<Material> CAVE = new Builder(
            Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.CALCITE, Material.TUFF, Material.DRIPSTONE_BLOCK,
            Material.STONE, Material.BONE_BLOCK, Material.OBSIDIAN, Material.BEDROCK, Material.SMOOTH_BASALT,
            Material.DIAMOND_ORE, Material.EMERALD_ORE, Material.IRON_ORE, Material.GOLD_ORE,
            Material.LAPIS_ORE, Material.REDSTONE_ORE, Material.COAL_ORE, Material.COPPER_ORE,
            Material.DEEPSLATE_DIAMOND_ORE, Material.DEEPSLATE_EMERALD_ORE, Material.DEEPSLATE_IRON_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.DEEPSLATE_LAPIS_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.DEEPSLATE_COAL_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
            Material.DIRT, Material.GRAVEL, Material.MOSS_BLOCK, Material.ROOTED_DIRT,
            Material.SOUL_SAND, Material.NETHERRACK, Material.GLOWSTONE,
            Material.TORCH, Material.CHEST, Material.OAK_PLANKS, Material.RAIL,
            Material.SPAWNER, Material.END_STONE, Material.NETHER_QUARTZ_ORE,
            Material.SOUL_SOIL, Material.BLACKSTONE, Material.BASALT,
            Material.NETHER_GOLD_ORE, Material.GILDED_BLACKSTONE,
            Material.DEEPSLATE
        ).build(true);

    public static void rotate(Block block, BlockFace face) {
        BlockData data = block.getBlockData();
        if (data instanceof FaceAttachable attachable) {
            attachable.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
        } else if (data instanceof Directional directional) {
            directional.setFacing(face);
        } else if (data instanceof MultipleFacing multifacing) {
            multifacing.setFace(face, true);
        }
        block.setBlockData(data, false);
    }

    @SuppressWarnings("deprecation")
    public static ItemStack getHeadFromURL(String URLTexture) {
        /*UUID id = UUID.nameUUIDFromBytes(value.getBytes());
        // Heck yeah, magic numbers
        long less = id.getLeastSignificantBits();
        int lessA = (int) (less >> 32); int lessB = (int) less;
        long most = id.getMostSignificantBits();
        int mostA = (int) (most >> 32); int mostB = (int) most;*/

        URLTexture = URLTexture.toLowerCase();
        URLTexture = URLTexture.replace("http://textures.minecraft.net/texture/", "");
        URLTexture = URLTexture.replace("https://textures.minecraft.net/texture/", "");


        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        SkullMeta skullMeta = (SkullMeta) meta;

        // One profile id per texture: heads with different skins never share an id
        PlayerProfile pp = Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes(URLTexture.getBytes(StandardCharsets.UTF_8)));
        PlayerTextures pt = pp.getTextures();
        try {
            pt.setSkin(URI.create("http://textures.minecraft.net/texture/" + URLTexture).toURL());
        } catch (MalformedURLException | IllegalArgumentException e) {e.printStackTrace();}
        pp.setTextures(pt);
        skullMeta.setOwnerProfile(pp);
        meta = skullMeta;
        item.setItemMeta(meta);

        return item;
        /*return Bukkit.getUnsafe().modifyItemStack(
                new ItemStack(Material.PLAYER_HEAD),
                "{profile={id:[I;" + lessA + "," + lessB + "," + mostA + "," + mostB + "],properties:[{name:\"textures\",value:\"" + value + "\"}]}}"
        );*/
    }

    /**
     * Head from a minecraft-heads.com "Value" (base64 of the textures JSON)
     * @param value Base64 value
     * @return Head, or null when the value holds no texture URL
     */
    public static ItemStack getHeadFromValue(String value) {
        try {
            String json = new String(Base64.getDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            Matcher matcher = TEXTURE_URL.matcher(json);
            return matcher.find() ? getHeadFromURL(matcher.group(1)) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isCave(Material type) {
        return CAVE.contains(type);
    }

    /**
     * Every pickaxe the server knows, copper included (a hardcoded list missed it)
     */
    public static boolean isPickaxe(Material type) {
        return Tag.ITEMS_PICKAXES.isTagged(type);
    }

    public static Set<Material> getSet(Collection<String> types) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String typeStr : types) {
            Material type = Material.getMaterial(typeStr.toUpperCase(Locale.ROOT));
            if (type != null) materials.add(type);
        }
        return materials;
    }

    public static ItemStack getColored(EquipmentSlot slot, int r, int g, int b) {
        ItemStack item;
        switch (slot) {
            default: return null;

            case HEAD:
                item = new ItemStack(Material.LEATHER_HELMET);
                break;

            case CHEST:
                item = new ItemStack(Material.LEATHER_CHESTPLATE);
                break;

            case LEGS:
                item = new ItemStack(Material.LEATHER_LEGGINGS);
                break;

            case FEET:
                item = new ItemStack(Material.LEATHER_BOOTS);
                break;
        }
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(Color.fromRGB(r, g, b));
        item.setItemMeta(meta);
        return item;
    }

    public static class Builder {
        private final Set<Material> materials;

        public Builder() {
            materials = EnumSet.noneOf(Material.class);
        }

        public Builder(Material... types) {
            this(); with(types);
        }

        public Builder(String... types) {
            this(); with(types);
        }

        public Builder with(String... types) {
            materials.addAll(Materials.getSet(Arrays.asList(types)));
            return this;
        }

        public Builder with(Material... types) {
            for (Material type : types) {
                if (type != null)
                    materials.add(type);
            }
            return this;
        }

        public Set<Material> build(boolean unmod) {
            return unmod ? Collections.unmodifiableSet(materials) : materials;
        }
    }
}
