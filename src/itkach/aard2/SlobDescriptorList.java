package itkach.aard2;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import itkach.aard2.descriptor.DescriptorStore;
import itkach.aard2.descriptor.SlobDescriptor;
import itkach.aard2.dictionary.Dictionary;
import itkach.aard2.utils.Utils;
import itkach.slob.Slob;

public class SlobDescriptorList extends BaseDescriptorList<SlobDescriptor> {
    SlobDescriptorList(@NonNull DescriptorStore<SlobDescriptor> store) {
        super(SlobDescriptor.class, store);
    }

    public boolean hasId(@Nullable String id) {
        if (id == null) {
            return false;
        }
        for (SlobDescriptor d : this) {
            if (id.equals(d.id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Looks a descriptor up by dictionary id.  Indexed rather than iterated: this runs
     * on the main thread while binding list items, and a background folder scan can add
     * or remove entries at the same time, which would break an iterator.
     */
    @Nullable
    public SlobDescriptor getById(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (int index = 0; index < size(); index++) {
            SlobDescriptor descriptor;
            try {
                descriptor = get(index);
            } catch (IndexOutOfBoundsException e) {
                // The list shrank while we were walking it
                return null;
            }
            if (descriptor != null && id.equals(descriptor.id)) {
                return descriptor;
            }
        }
        return null;
    }

    @Nullable
    public Dictionary resolve(@NonNull SlobDescriptor sd) {
        return SlobHelper.getInstance().getDictionary(sd.id);
    }

    /** @deprecated Use {@link #resolve(SlobDescriptor)} for format-agnostic code. */
    @Deprecated
    @Nullable
    public Slob resolveSlob(@NonNull SlobDescriptor sd) {
        return SlobHelper.getInstance().getSlob(sd.id);
    }

    public void sort() {
        Utils.sort(this, (d1, d2) -> {
            //Dictionaries that are unfavorited
            //go immediately after favorites
            if (d1.priority == 0 && d2.priority == 0) {
                return Long.compare(d2.lastAccess, d1.lastAccess);
            }
            //Favorites are always above other
            if (d1.priority == 0 && d2.priority > 0) {
                return 1;
            }
            if (d1.priority > 0 && d2.priority == 0) {
                return -1;
            }
            //Old favorites are above more recent ones
            return Long.compare(d1.priority, d2.priority);
        });
    }

    @Override
    public void load() {
        beginUpdate();
        super.load();
        sort();
        endUpdate(true);
    }
}
