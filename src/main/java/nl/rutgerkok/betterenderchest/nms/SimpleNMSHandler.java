package nl.rutgerkok.betterenderchest.nms;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.v1_12_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import nl.rutgerkok.betterenderchest.BetterEnderChest;
import nl.rutgerkok.betterenderchest.BetterEnderInventoryHolder;
import nl.rutgerkok.betterenderchest.ChestRestrictions;
import nl.rutgerkok.betterenderchest.WorldGroup;
import nl.rutgerkok.betterenderchest.chestowner.ChestOwner;
import nl.rutgerkok.betterenderchest.io.SaveEntry;

import net.minecraft.server.v1_12_R1.BlockPosition;
import net.minecraft.server.v1_12_R1.Blocks;
import net.minecraft.server.v1_12_R1.MojangsonParseException;
import net.minecraft.server.v1_12_R1.MojangsonParser;
import net.minecraft.server.v1_12_R1.NBTBase;
import net.minecraft.server.v1_12_R1.NBTCompressedStreamTools;
import net.minecraft.server.v1_12_R1.NBTTagByteArray;
import net.minecraft.server.v1_12_R1.NBTTagCompound;
import net.minecraft.server.v1_12_R1.NBTTagDouble;
import net.minecraft.server.v1_12_R1.NBTTagInt;
import net.minecraft.server.v1_12_R1.NBTTagIntArray;
import net.minecraft.server.v1_12_R1.NBTTagList;
import net.minecraft.server.v1_12_R1.NBTTagLong;
import net.minecraft.server.v1_12_R1.NBTTagString;
import net.minecraft.server.v1_12_R1.TileEntity;
import net.minecraft.server.v1_12_R1.TileEntityEnderChest;

public class SimpleNMSHandler extends NMSHandler {
    static class JSONSimpleTypes {
        /**
         * Byte arrays are stored as {{@value #BYTE_ARRAY}: [0,1,3,etc.]}, ints
         * simply as [0,1,3,etc]. Storing byte arrays this way preserves their
         * type. So when reading a map, check for this value to see whether you
         * have a byte[] or a compound tag.
         */
        private static final String BYTE_ARRAY = "byteArray";

        static final NBTBase javaTypeToNBTTag(Object object) throws IOException {
            // Handle compounds
            if (object instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, ?> map = (Map<String, ?>) object;

                Object byteArrayValue = map.get(BYTE_ARRAY);
                if (byteArrayValue instanceof List) {
                    // The map is actually a byte array, not a compound tag
                    @SuppressWarnings("unchecked")
                    List<Number> boxedBytes = (List<Number>) byteArrayValue;
                    return new NBTTagByteArray(unboxBytes(boxedBytes));
                }

                NBTTagCompound tag = new NBTTagCompound();
                for (Entry<String, ?> entry : map.entrySet()) {
                    NBTBase value = javaTypeToNBTTag(entry.getValue());
                    if (value != null) {
                        tag.set(entry.getKey(), value);
                    }
                }
                return tag;
            }
            // Handle numbers
            if (object instanceof Number) {
                Number number = (Number) object;
                if (number instanceof Integer || number instanceof Long) {
                    // Whole number
                    if (number.intValue() == number.longValue()) {
                        // Fits in integer
                        return new NBTTagInt(number.intValue());
                    }
                    return new NBTTagLong(number.longValue());
                } else {
                    return new NBTTagDouble(number.doubleValue());
                }
            }
            // Handle strings
            if (object instanceof String) {
                return new NBTTagString((String) object);
            }
            // Handle lists
            if (object instanceof List) {
                List<?> list = (List<?>) object;
                NBTTagList listTag = new NBTTagList();

                if (list.isEmpty()) {
                    // Don't deserialize empty lists - we have no idea what
                    // type it should be. The methods on NBTTagCompound will
                    // now return empty lists of the appropriate type
                    return null;
                }

                // Handle int arrays
                Object firstElement = list.get(0);
                if (firstElement instanceof Integer || firstElement instanceof Long) {
                    // Ints may be deserialized as longs, even if the numbers
                    // are small enough for ints
                    @SuppressWarnings("unchecked")
                    List<Number> intList = (List<Number>) list;
                    return new NBTTagIntArray(unboxIntegers(intList));
                }

                // Other lists
                for (Object entry : list) {
                    NBTBase javaType = javaTypeToNBTTag(entry);
                    if (javaType != null) {
                        listTag.add(javaType);
                    }
                }
                return listTag;
            }
            if (object == null) {
                return null;
            }
            throw new IOException("Unknown object: (" + object.getClass() + ") " + object + "");
        }

        /**
         * Turns the given json- or Mojangson-formatted string back into a
         * NBTTagCompound.
         *
         * @param jsonString
         *            The json string to parse.
         * @return The parsed json string.
         * @throws IOException
         *             If the string cannot be parsed.
         */
        static final NBTTagCompound toTag(String jsonString) throws IOException {
            try {
                return (NBTTagCompound) javaTypeToNBTTag(new JSONParser().parse(jsonString));
            } catch (ParseException e) {
                // Ignore, retry as Mojangson
            }

            try {
                return MojangsonParser.parse(jsonString);
            } catch (MojangsonParseException e) {
                throw new IOException(e);
            }
        }

        /**
         * Converts from a List<Number>, as found in the JSON, to byte[].
         *
         * @param boxed
         *            List from the JSON. return The byte array.
         * @return The unboxed bytes.
         */
        private static final byte[] unboxBytes(List<Number> boxed) {
            byte[] bytes = new byte[boxed.size()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = boxed.get(i).byteValue();
            }
            return bytes;
        }

        /**
         * Converts from a List<Number>, as found in the JSON, to int[].
         *
         * @param boxed
         *            List from the JSON. return The int array.
         * @return The unboxed ints.
         */
        private static final int[] unboxIntegers(List<Number> boxed) {
            int[] ints = new int[boxed.size()];
            for (int i = 0; i < ints.length; i++) {
                ints[i] = boxed.get(i).intValue();
            }
            return ints;
        }
    }

    /**
     * Constants for some NBT tag types.
     */
    private static class TagType {
        private static final int COMPOUND = 10;
    }

    private BetterEnderChest plugin;

    public SimpleNMSHandler(BetterEnderChest plugin) {
        this.plugin = plugin;
    }

    @Override
    public void closeEnderChest(Location loc) {
        BlockPosition blockPos = toBlockPosition(loc);
        TileEntity tileEntity = ((CraftWorld) loc.getWorld()).getHandle().getTileEntity(blockPos);
        if (tileEntity instanceof TileEntityEnderChest) {
            ((TileEntityEnderChest) tileEntity).f(); // .close()
        }
    }

    private int getDisabledSlots(NBTTagCompound baseTag) {
        if (baseTag.hasKey("DisabledSlots")) {
            // Load the number of disabled slots
            return baseTag.getByte("DisabledSlots");
        } else {
            // Return 0. This value doesn't harm anything and will be
            // corrected when the owner opens his/her own chest
            return 0;
        }
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    private int getRows(ChestOwner chestOwner, NBTTagCompound baseTag, NBTTagList inventoryListTag) {
        if (baseTag.hasKey("Rows")) {
            // Load the number of rows
            return baseTag.getByte("Rows");
        } else {
            // Guess the number of rows
            // Iterates through all the items to find the highest slot number
            int highestSlot = 0;
            for (int i = 0; i < inventoryListTag.size(); i++) {

                // Replace the current highest slot if this slot is higher
                highestSlot = Math.max(inventoryListTag.get(i).getByte("Slot") & 255, highestSlot);
            }

            // Calculate the needed number of rows for the items, and return the
            // required number of rows
            return Math.max((int) Math.ceil(highestSlot / 9.0), plugin.getEmptyInventoryProvider().getInventoryRows(chestOwner));
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            // Test whether nms access works.
            Blocks.WOOL.getName();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isItemInsertionAllowed(NBTTagCompound baseTag) {
        if (baseTag.hasKey("ItemInsertion")) {
            return baseTag.getBoolean("ItemInsertion");
        } else {
            // Return true. This value doesn't harm anything and will be
            // corrected when the owner opens his/her own chest
            return true;
        }
    }

    @Override
    public Inventory loadNBTInventoryFromFile(File file, ChestOwner chestOwner, WorldGroup worldGroup, String inventoryTagName) throws IOException {
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            NBTTagCompound baseTag = NBTCompressedStreamTools.a(inputStream);
            return loadNBTInventoryFromTag(baseTag, chestOwner, worldGroup, inventoryTagName);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    @Override
    public Inventory loadNBTInventoryFromJson(String jsonString, ChestOwner chestOwner, WorldGroup worldGroup) throws IOException {
        return this.loadNBTInventoryFromTag(JSONSimpleTypes.toTag(jsonString), chestOwner, worldGroup, "Inventory");
    }

    private Inventory loadNBTInventoryFromTag(NBTTagCompound baseTag, ChestOwner chestOwner, WorldGroup worldGroup, String inventoryTagName) throws IOException {
        NBTTagList inventoryTag = baseTag.getList(inventoryTagName, TagType.COMPOUND);

        // Create the Bukkit inventory
        int inventoryRows = getRows(chestOwner, baseTag, inventoryTag);
        int disabledSlots = getDisabledSlots(baseTag);
        boolean itemInsertion = isItemInsertionAllowed(baseTag);
        ChestRestrictions chestRestrictions = new ChestRestrictions(inventoryRows, disabledSlots, itemInsertion);
        Inventory inventory = plugin.getEmptyInventoryProvider().loadEmptyInventory(chestOwner, worldGroup, chestRestrictions);

        // Add all the items
        for (int i = 0; i < inventoryTag.size(); i++) {
            NBTTagCompound item = inventoryTag.get(i);
            int slot = item.getByte("Slot") & 255;
            inventory.setItem(slot,
                    CraftItemStack.asCraftMirror(new net.minecraft.server.v1_12_R1.ItemStack(item)));
        }

        // Items currently in the chest are what is in the database
        BetterEnderInventoryHolder.of(inventory).markContentsAsSaved(inventory.getContents());

        // Return the inventory
        return inventory;
    }

    @Override
    public void openEnderChest(Location loc) {
        BlockPosition blockPos = toBlockPosition(loc);
        TileEntity tileEntity = ((CraftWorld) loc.getWorld()).getHandle().getTileEntity(blockPos);
        if (tileEntity instanceof TileEntityEnderChest) {
            ((TileEntityEnderChest) tileEntity).a(); // .open()
        }
    }

    @Override
    public void saveInventoryToFile(File file, SaveEntry saveEntry) throws IOException {
        FileOutputStream stream = null;
        try {
            // Write inventory to it
            file.getAbsoluteFile().getParentFile().mkdirs();
            file.createNewFile();
            stream = new FileOutputStream(file);
            NBTCompressedStreamTools.a(saveInventoryToTag(saveEntry), stream);
        } finally {
            if (stream != null) {
                stream.flush();
                stream.close();
            }
        }
    }

    @Override
    public String saveInventoryToJson(SaveEntry inventory) throws IOException {
        NBTTagCompound tag = saveInventoryToTag(inventory);
        return tag.toString();
    }

    private NBTTagCompound saveInventoryToTag(SaveEntry inventory) {
        NBTTagCompound baseTag = new NBTTagCompound();
        NBTTagList inventoryTag = new NBTTagList();

        // Chest metadata
        ChestRestrictions chestRestrictions = inventory.getChestRestrictions();
        baseTag.setByte("Rows", (byte) chestRestrictions.getChestRows());
        baseTag.setByte("DisabledSlots", (byte) chestRestrictions.getDisabledSlots());
        baseTag.setBoolean("ItemInsertion", chestRestrictions.isItemInsertionAllowed());
        baseTag.setString("OwnerName", inventory.getChestOwner().getDisplayName());

        // Add all items to the inventory tag
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && stack.getType() != Material.AIR) {
                NBTTagCompound item = new NBTTagCompound();
                item.setByte("Slot", (byte) i);
                inventoryTag.add(CraftItemStack.asNMSCopy(stack).save(item));
            }
        }

        // Add the inventory tag to the base tag
        baseTag.set("Inventory", inventoryTag);

        return baseTag;
    }

    private BlockPosition toBlockPosition(Location location) {
        return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

}
