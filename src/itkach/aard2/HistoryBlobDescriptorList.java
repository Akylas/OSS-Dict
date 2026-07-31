package itkach.aard2;

import android.net.Uri;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.descriptor.DescriptorStore;

public class HistoryBlobDescriptorList extends BlobDescriptorList {
    HistoryBlobDescriptorList(DescriptorStore<BlobDescriptor> store) {
        this(store, 100);
    }

    HistoryBlobDescriptorList(DescriptorStore<BlobDescriptor> store, int maxSize) {
        super(store, maxSize);
    }

    @Override
    public BlobDescriptor add(Uri contentUrl) {
        BlobDescriptor bd = createDescriptor(contentUrl);
        if (bd == null) {
            return null;
        }
        int index = list.indexOf(bd);
        if (index > -1) {
            BlobDescriptor oldBd = list.get(index);
            list.set(index, bd);
            store.delete(oldBd.id);
            store.save(bd);
            notifyDataSetChanged();
            return bd;
        }
        list.add(bd);
        store.save(bd);
        trimToMaxSize();
        notifyDataSetChanged();
        return bd;
    }
}
