package itkach.aard2.article;

import android.database.DataSetObserver;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import itkach.aard2.dictionary.DictionaryEntry;
import itkach.aard2.lookup.LookupResult;

class LookupResultWrapper implements BlobListWrapper {
    interface ToEntry<T> {
        @Nullable
        DictionaryEntry convert(T item);
    }

    private final LookupResult lookupResult;
    private final ToEntry<DictionaryEntry> toEntry;
    // The live list is cleared/filled on worker threads: read a copy taken with the adapter notify
    private List<DictionaryEntry> snapshot;

    LookupResultWrapper(@NonNull LookupResult lookupResult, @NonNull ToEntry<DictionaryEntry> toEntry) {
        this.lookupResult = lookupResult;
        this.toEntry = toEntry;
        this.snapshot = new ArrayList<>(lookupResult.getList());
    }

    @Override
    public void registerDataSetObserver(@NonNull DataSetObserver observer) {
        lookupResult.registerDataSetObserver(observer);
    }

    @Override
    public void unregisterDataSetObserver(@NonNull DataSetObserver observer) {
        lookupResult.unregisterDataSetObserver(observer);
    }

    @MainThread
    @Override
    public void refresh() {
        snapshot = new ArrayList<>(lookupResult.getList());
    }

    @Nullable
    @Override
    public DictionaryEntry get(int index) {
        return toEntry.convert(snapshot.get(index));
    }

    @Nullable
    @Override
    public CharSequence getLabel(int index) {
        DictionaryEntry item = snapshot.get(index);
        return item != null ? item.key : null;
    }

    @Override
    public int size() {
        return snapshot.size();
    }
}
