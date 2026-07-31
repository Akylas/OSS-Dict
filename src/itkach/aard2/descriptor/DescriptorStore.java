package itkach.aard2.descriptor;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class DescriptorStore<T extends BaseDescriptor> {
    static final String TAG = DescriptorStore.class.getSimpleName();

    private final File dir;
    private final ObjectMapper mapper;

    public DescriptorStore(@NonNull ObjectMapper mapper, @NonNull File dir) {
        this.dir = dir;
        this.mapper = mapper;
    }

    public List<T> load(@NonNull Class<T> type) {
        List<T> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                try {
                    T sd = mapper.readValue(f, type);
                    result.add(sd);
                } catch (Exception e) {
                    String path = f.getAbsolutePath();
                    Log.w(TAG, String.format("Loading data from file %s failed", path), e);
                    T migrated = tryLoadLegacy(f, type);
                    if (migrated != null) {
                        result.add(migrated);
                        try {
                            save(migrated);
                            Log.i(TAG, "Recovered legacy descriptor from " + path);
                        } catch (Exception saveEx) {
                            Log.w(TAG, "Failed to rewrite recovered descriptor from " + path, saveEx);
                        }
                    } else {
                        // Don't keep unreadable descriptor files - they cause issues on app restart
                        Log.w(TAG, "Deleting unrecoverable descriptor file: " + path);
                        if (!f.delete()) {
                            Log.e(TAG, "Failed to delete corrupted descriptor file: " + path);
                        }
                    }
                }
            }
        }
        return result;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private T tryLoadLegacy(@NonNull File f, @NonNull Class<T> type) {
        try {
            JsonNode root = mapper.readTree(f);
            if (root == null || !root.isObject()) {
                return null;
            }
            if (type == BlobDescriptor.class) {
                BlobDescriptor d = new BlobDescriptor();
                fillBaseDescriptor(d, root, f);
                d.slobId = getText(root, "slobId");
                d.slobUri = getText(root, "slobUri");
                d.blobId = getText(root, "blobId");
                d.key = getText(root, "key");
                d.fragment = getText(root, "fragment");
                if (d.id == null || d.id.isEmpty()) {
                    d.id = f.getName();
                }
                if (d.slobId == null || d.slobId.isEmpty() || d.key == null || d.key.isEmpty()) {
                    return null;
                }
                return (T) d;
            }
            if (type == SlobDescriptor.class) {
                SlobDescriptor d = new SlobDescriptor();
                fillBaseDescriptor(d, root, f);
                String format = getText(root, "format");
                d.format = (format == null || format.isEmpty()) ? SlobDescriptor.FORMAT_SLOB : format;
                d.path = getText(root, "path");
                d.mddPath = getText(root, "mddPath");
                JsonNode tagsNode = root.get("tags");
                if (tagsNode != null && tagsNode.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = tagsNode.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        d.tags.put(entry.getKey(), entry.getValue().asText(""));
                    }
                }
                d.displayName = getText(root, "displayName");
                d.active = getBoolean(root, "active", true);
                d.priority = getLong(root, "priority", 0L);
                d.blobCount = getLong(root, "blobCount", 0L);
                d.error = getText(root, "error");
                d.expandDetail = getBoolean(root, "expandDetail", false);
                if (d.id == null || d.id.isEmpty()) {
                    d.id = f.getName();
                }
                if (d.path == null || d.path.isEmpty()) {
                    Log.w(TAG, "Skipping legacy SlobDescriptor with no path: " + f.getName());
                    return null;
                }
                return (T) d;
            }
        } catch (Exception e) {
            Log.w(TAG, "Legacy recovery failed for file " + f.getAbsolutePath(), e);
        }
        return null;
    }

    private void fillBaseDescriptor(@NonNull BaseDescriptor d, @NonNull JsonNode root, @NonNull File f) {
        d.id = getText(root, "id");
        if (d.id == null || d.id.isEmpty()) {
            d.id = f.getName();
        }
        d.createdAt = getLong(root, "createdAt", 0L);
        d.lastAccess = getLong(root, "lastAccess", d.createdAt);
    }

    @Nullable
    private static String getText(@NonNull JsonNode node, @NonNull String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText(null);
    }

    private static long getLong(@NonNull JsonNode node, @NonNull String key, long defaultValue) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? defaultValue : value.asLong(defaultValue);
    }

    private static boolean getBoolean(@NonNull JsonNode node, @NonNull String key, boolean defaultValue) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? defaultValue : value.asBoolean(defaultValue);
    }

    public void save(@NonNull List<T> lst) {
        for (T item : lst) {
            save(item);
        }
    }

    public void save(@NonNull T item) {
        if (item.id == null) {
            Log.d(getClass().getName(), "Can't save item without id");
            return;
        }
        try {
            File out = new File(dir, item.id);
            if (item instanceof BlobDescriptor) {
                BlobDescriptor d = (BlobDescriptor) item;
                ObjectNode root = mapper.createObjectNode();
                root.put("id", d.id);
                root.put("createdAt", d.createdAt);
                root.put("lastAccess", d.lastAccess);
                root.put("slobId", d.slobId);
                root.put("slobUri", d.slobUri);
                root.put("blobId", d.blobId);
                root.put("key", d.key);
                root.put("fragment", d.fragment);
                mapper.writeValue(out, root);
            } else if (item instanceof SlobDescriptor) {
                SlobDescriptor d = (SlobDescriptor) item;
                ObjectNode root = mapper.createObjectNode();
                root.put("id", d.id);
                root.put("createdAt", d.createdAt);
                root.put("lastAccess", d.lastAccess);
                root.put("format", d.format);
                root.put("path", d.path);
                root.put("mddPath", d.mddPath);
                root.set("tags", mapper.valueToTree(d.tags));
                root.put("displayName", d.displayName);
                root.put("active", d.active);
                root.put("priority", d.priority);
                root.put("blobCount", d.blobCount);
                root.put("error", d.error);
                root.put("expandDetail", d.expandDetail);
                mapper.writeValue(out, root);
            } else {
                mapper.writeValue(out, item);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean delete(@Nullable String itemId) {
        if (itemId == null) {
            return false;
        }
        return new File(dir, itemId).delete();
    }

}
